package com.zkjs.aio.swap.service;

import com.zkjs.aio.doc.excel.ExcelUtil;
import com.zkjs.aio.doc.html.HtmlUtil;
import com.zkjs.aio.doc.pdf.PdfUtil;
import com.zkjs.aio.doc.word.WordUtil;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.vo.SwapTaskExportVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 文档导出套件演示服务层（支持 HTML、Excel、Word、PDF）
 *
 * @author wuyw
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocDemoService {

    private final SwapTaskService swapTaskService;

    // =========================================================================
    // 1. HTML 自然模板与字符串模板导出
    // =========================================================================

    /**
     * 响应式 WebFlux 下载 HTML 报表
     */
    public Mono<Void> downloadHtmlReport(ServerHttpResponse response, String reportTitle, String department) {
        String finalTitle = (reportTitle != null && !reportTitle.isBlank())
                ? reportTitle : "2026年数据交换部署服务运行质量分析报告";
        String finalDept = (department != null && !department.isBlank())
                ? department : "智能数据中台架构部";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportTitle", finalTitle);
        data.put("systemName", "AIO 一体化交换部署服务 (wsp-swap)");
        data.put("department", finalDept);
        data.put("createTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        data.put("totalTasks", 128);
        data.put("totalRecords", "12,450,890");
        data.put("avgLatency", "18.5 ms");

        // 优先从真实数据库加载，若无数据则构造样例数据
        List<SwapTaskExportVo> taskList = queryExportTasks();
        data.put("taskList", taskList);

        // HtmlUtil.render 解析 classpath:templates/report.html
        String htmlContent = HtmlUtil.render("report", data);
        return HtmlUtil.writeToResponse(response, "数据交换运行质量月报.html", htmlContent);
    }

    /**
     * 动态渲染纯 HTML 文本片段并返回
     */
    public String renderHtmlString(String templateContent, Map<String, Object> variables) {
        return HtmlUtil.renderString(templateContent, variables);
    }

    // =========================================================================
    // 2. Excel 极速导入与响应式流式导出 (EasyExcel 4.x)
    // =========================================================================

    /**
     * 响应式 WebFlux 流式导出 Excel 清单
     */
    public Mono<Void> downloadExcel(ServerHttpResponse response) {
        List<SwapTaskExportVo> dataList = queryExportTasks();
        return ExcelUtil.writeToResponse(response, "交换任务管理清单.xlsx", SwapTaskExportVo.class, dataList);
    }

    /**
     * 流式接收上传的 Excel 文件并同步读取解析为模型对象列表
     */
    public Mono<List<SwapTaskExportVo>> importExcel(FilePart filePart) {
        if (filePart == null || filePart.filename().isBlank()) {
            return Mono.just(Collections.emptyList());
        }

        try {
            Path tempFile = Files.createTempFile("aio_excel_import_", "_" + filePart.filename());
            return filePart.transferTo(tempFile)
                    .then(Mono.fromCallable(() -> {
                        try {
                            List<SwapTaskExportVo> list = ExcelUtil.readSync(tempFile.toFile(), SwapTaskExportVo.class);
                            log.info("Excel 同步导入成功，解析得到 {} 条任务记录", list.size());
                            return list;
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    }));
        } catch (IOException e) {
            log.error("创建临时解析文件失败: {}", e.getMessage(), e);
            return Mono.error(new IllegalStateException("创建临时解析文件失败: " + e.getMessage(), e));
        }
    }

    // =========================================================================
    // 3. Word 模板化动态渲染与响应式下载 (Poi-tl)
    // =========================================================================

    /**
     * 基于 Word docx 模板渲染并响应式写回下载
     */
    public Mono<Void> downloadWord(ServerHttpResponse response, String projectName, String clientName) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("projectName", (projectName != null && !projectName.isBlank()) ? projectName : "AIO 企业中台微服务交换架构");
        model.put("contractCode", "ZKJS-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-088");
        model.put("clientName", (clientName != null && !clientName.isBlank()) ? clientName : "某大型集团数字化创新中心");
        model.put("signDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
        model.put("totalBudget", "¥ 1,280,000.00");

        InputStream templateIn = getWordTemplateInputStream();
        return WordUtil.writeToResponse(response, "项目合作协议.docx", templateIn, model);
    }

    /**
     * 获取 Word 模板输入流（优先从 classpath 读取，不存在则动态生成合法的最小模板流兜底）
     */
    public InputStream getWordTemplateInputStream() {
        try {
            ClassPathResource resource = new ClassPathResource("templates/contract_template.docx");
            if (resource.exists()) {
                return resource.getInputStream();
            }
        } catch (IOException e) {
            log.warn("未能直接加载 contract_template.docx 资源: {}", e.getMessage());
        }

        // 动态兜底构建包含 Poi-tl 语法标签的合法 docx 模板
        log.info("动态生成内存 Word 模板进行兜底渲染");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph pTitle = doc.createParagraph();
            pTitle.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun rTitle = pTitle.createRun();
            rTitle.setText("战略合作业务合作协议");
            rTitle.setBold(true);
            rTitle.setFontSize(18);

            doc.createParagraph().createRun().setText(""); // 空行

            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("项目名称：{{projectName}}");

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("合同编号：{{contractCode}}");

            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("甲方机构：{{clientName}}");

            XWPFParagraph p4 = doc.createParagraph();
            p4.createRun().setText("签约日期：{{signDate}}");

            XWPFParagraph p5 = doc.createParagraph();
            p5.createRun().setText("预算总额：{{totalBudget}}");

            doc.write(bos);
            return new ByteArrayInputStream(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("动态创建 Word 模板异常: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 4. PDF 文档生成与防伪水印 (x-easypdf)
    // =========================================================================

    /**
     * 构建简易文本 PDF 并响应式写回下载
     */
    public Mono<Void> downloadSimplePdf(ServerHttpResponse response, String title) {
        String finalTitle = (title != null && !title.isBlank()) ? title : "AIO-Framework 架构设计白皮书";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> paragraphs = List.of(
                finalTitle,
                "==================================================",
                "第一章：企业级响应式微服务底座架构设计",
                "本微服务基于 Spring Boot 4 与 WebFlux 全响应式体系打造，消除传统 Servlet 阻塞，极大提升吞吐能力与并发性能。",
                "",
                "第二章：统一文档导出套件集成指南",
                "一站式支持 Thymeleaf HTML 自然模板、EasyExcel 极速导出、Poi-tl 复杂 Word 模板渲染与 x-easypdf 防伪水印注入。",
                "",
                "第三章：高可用分布式锁与多账号体系",
                "提供开箱即用的 Lock4j、AioScheduledLock 以及基于 Sa-Token 的 Admin/User/Device 物理隔离三账号认证体系。"
        );

        PdfUtil.createSimplePdf(paragraphs, out);
        return PdfUtil.writeToResponse(response, "AIO架构设计白皮书.pdf", out.toByteArray());
    }

    /**
     * 为上传的 PDF 注入 45° 倾斜半透明文字防伪水印并写回响应流
     */
    public Mono<Void> addWatermarkToPdf(FilePart filePart, String watermarkText, ServerHttpResponse response) {
        String finalWatermark = (watermarkText != null && !watermarkText.isBlank()) ? watermarkText : "内部机密 严禁外传";

        try {
            Path tempPdf = Files.createTempFile("aio_pdf_watermark_", "_" + filePart.filename());
            return filePart.transferTo(tempPdf)
                    .then(Mono.defer(() -> {
                        try (InputStream in = new FileInputStream(tempPdf.toFile())) {
                            ByteArrayOutputStream watermarkOut = new ByteArrayOutputStream();
                            PdfUtil.addTextWatermark(in, watermarkOut, finalWatermark);
                            log.info("PDF 水印注入完成，大小: {} bytes", watermarkOut.size());
                            return PdfUtil.writeToResponse(response, "已添加水印_" + filePart.filename(), watermarkOut.toByteArray());
                        } catch (IOException e) {
                            return Mono.error(new IllegalStateException("处理 PDF 水印流失败: " + e.getMessage(), e));
                        } finally {
                            try {
                                Files.deleteIfExists(tempPdf);
                            } catch (IOException ignored) {
                            }
                        }
                    }));
        } catch (IOException e) {
            log.error("创建临时 PDF 文件失败: {}", e.getMessage(), e);
            return Mono.error(new IllegalStateException("创建临时 PDF 文件失败: " + e.getMessage(), e));
        }
    }

    // =========================================================================
    // 内部通用辅助方法
    // =========================================================================

    /**
     * 查询或构造演示导出数据
     */
    public List<SwapTaskExportVo> queryExportTasks() {
        List<SwapTaskExportVo> result = new ArrayList<>();
        try {
            List<SwapTask> dbList = swapTaskService.list();
            if (dbList != null && !dbList.isEmpty()) {
                for (SwapTask task : dbList) {
                    result.add(SwapTaskExportVo.builder()
                            .id(task.getId())
                            .taskCode(task.getTaskCode())
                            .taskName(task.getTaskName())
                            .taskType(task.getTaskType() != null ? task.getTaskType() : "SYNC")
                            .sourceNode(task.getSourceNode())
                            .targetNode(task.getTargetNode())
                            .statusDesc(getStatusDesc(task.getStatus()))
                            .successRate("99.99%")
                            .remark(task.getRemark())
                            .createTime(task.getCreateTime() != null ? task.getCreateTime() : LocalDateTime.now())
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("从数据库读取任务列表异常或暂未建表，回退至内置演示数据: {}", e.getMessage());
        }

        // 若数据库为空或未启动，填充优雅的默认演示数据
        if (result.isEmpty()) {
            result.add(SwapTaskExportVo.builder()
                    .id(1978234872938472931L)
                    .taskCode("TASK_20261016_001")
                    .taskName("内外网业务数据准实时同步")
                    .taskType("SYNC")
                    .sourceNode("NODE_INTERNAL_01")
                    .targetNode("NODE_EXTERNAL_01")
                    .statusDesc("启用")
                    .successRate("99.98%")
                    .remark("每 5 分钟定时执行增量拉取")
                    .createTime(LocalDateTime.now().minusHours(24))
                    .build());
            result.add(SwapTaskExportVo.builder()
                    .id(1978234872938472932L)
                    .taskCode("TASK_20261016_002")
                    .taskName("财务凭证历史明细大批量归档")
                    .taskType("ARCHIVE")
                    .sourceNode("FINANCE_MYSQL_MASTER")
                    .targetNode("CLICKHOUSE_OLAP_WAREHOUSE")
                    .statusDesc("启用")
                    .successRate("100.00%")
                    .remark("夜间闲时大批量批量落盘")
                    .createTime(LocalDateTime.now().minusHours(12))
                    .build());
            result.add(SwapTaskExportVo.builder()
                    .id(1978234872938472933L)
                    .taskCode("TASK_20261016_003")
                    .taskName("边缘设备安全遥测实时流转")
                    .taskType("STREAM")
                    .sourceNode("IOT_GATEWAY_NODE_A")
                    .targetNode("KAFKA_CLUSTER_TOPIC")
                    .statusDesc("执行中")
                    .successRate("99.95%")
                    .remark("毫秒级微批汇聚压缩上报")
                    .createTime(LocalDateTime.now().minusHours(2))
                    .build());
        }
        return result;
    }

    private String getStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "停用";
            case 1 -> "启用";
            case 2 -> "执行中";
            default -> "未知(" + status + ")";
        };
    }
}
