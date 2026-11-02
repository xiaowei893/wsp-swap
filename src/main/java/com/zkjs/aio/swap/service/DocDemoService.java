package com.zkjs.aio.swap.service;

import com.zkjs.aio.common.enums.RequestStateCode;
import com.zkjs.aio.common.exception.BusinessException;
import com.zkjs.aio.doc.excel.ExcelUtil;
import com.zkjs.aio.doc.html.HtmlUtil;
import com.zkjs.aio.doc.pdf.PdfUtil;
import com.zkjs.aio.doc.word.WordUtil;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.vo.SwapTaskExportVo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
     * Spring MVC 下载 HTML 报表
     */
    public void downloadHtmlReport(HttpServletResponse response, String reportTitle, String department) {
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
        HtmlUtil.writeToResponse(response, "数据交换运行质量月报.html", htmlContent);
    }

    /**
     * 动态渲染纯 HTML 文本片段并返回
     */
    public String renderHtmlString(String templateContent, Map<String, Object> variables) {
        return HtmlUtil.renderString(templateContent, variables);
    }

    // =========================================================================
    // 2. Excel 极速导入与流式导出 (EasyExcel 4.x)
    // =========================================================================

    /**
     * Spring MVC 流式导出 Excel 清单
     */
    public void downloadExcel(HttpServletResponse response) {
        List<SwapTaskExportVo> dataList = queryExportTasks();
        ExcelUtil.writeToResponse(response, "交换任务管理清单.xlsx", SwapTaskExportVo.class, dataList);
    }

    /**
     * 接收上传的 Excel 文件并同步读取解析为模型对象列表
     */
    public List<SwapTaskExportVo> importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Collections.emptyList();
        }

        try (InputStream in = file.getInputStream()) {
            List<SwapTaskExportVo> list = ExcelUtil.readSync(in, SwapTaskExportVo.class);
            log.info("Excel 同步导入成功，解析得到 {} 条任务记录", list.size());
            return list;
        } catch (IOException e) {
            log.error("创建文件解析输入流失败: {}", e.getMessage(), e);
            throw new BusinessException(RequestStateCode.OPERATION_ERROR, "创建文件解析输入流失败: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 3. Word 模板化动态渲染与下载 (Poi-tl)
    // =========================================================================

    /**
     * 基于 Word docx 模板渲染并写回下载
     */
    public void downloadWord(HttpServletResponse response, String projectName, String clientName) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("projectName", (projectName != null && !projectName.isBlank()) ? projectName : "AIO 企业中台微服务交换架构");
        model.put("contractCode", "ZKJS-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-088");
        model.put("clientName", (clientName != null && !clientName.isBlank()) ? clientName : "某大型集团数字化创新中心");
        model.put("signDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
        model.put("totalBudget", "¥ 1,280,000.00");

        InputStream templateIn = getWordTemplateInputStream();
        WordUtil.writeToResponse(response, "项目合作协议.docx", templateIn, model);
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
     * 构建简易文本 PDF 并写回下载
     */
    public void downloadSimplePdf(HttpServletResponse response, String title) {
        String finalTitle = (title != null && !title.isBlank()) ? title : "AIO-Framework 架构设计白皮书";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> paragraphs = List.of(
                finalTitle,
                "==================================================",
                "第一章：企业级微服务底座架构设计",
                "本微服务基于 Spring Boot 4 与 Spring MVC + 虚拟线程打造，兼具高吞吐能力与优秀开发调试体验。",
                "",
                "第二章：统一文档导出套件集成指南",
                "一站式支持 Thymeleaf HTML 自然模板、EasyExcel 极速导出、Poi-tl 复杂 Word 模板渲染与 x-easypdf 防伪水印注入。",
                "",
                "第三章：高可用分布式锁与多账号体系",
                "提供开箱即用的 Lock4j、AioScheduledLock 以及基于 Sa-Token 的 Admin/User/Device 物理隔离三账号认证体系。"
        );

        PdfUtil.createSimplePdf(paragraphs, out);
        PdfUtil.writeToResponse(response, "AIO架构设计白皮书.pdf", out.toByteArray());
    }

    /**
     * 为上传的 PDF 注入 45° 倾斜半透明文字防伪水印并写回响应流
     */
    public void addWatermarkToPdf(MultipartFile file, String watermarkText, HttpServletResponse response) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(RequestStateCode.BAD_REQUEST, "PDF 文件不能为空");
        }
        String finalWatermark = (watermarkText != null && !watermarkText.isBlank()) ? watermarkText : "内部机密 严禁外传";

        try (InputStream in = file.getInputStream()) {
            ByteArrayOutputStream watermarkOut = new ByteArrayOutputStream();
            PdfUtil.addTextWatermark(in, watermarkOut, finalWatermark);
            log.info("PDF 水印注入完成，大小: {} bytes", watermarkOut.size());
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
            PdfUtil.writeToResponse(response, "已添加水印_" + originalFilename, watermarkOut.toByteArray());
        } catch (IOException e) {
            log.error("处理 PDF 水印流失败: {}", e.getMessage(), e);
            throw new BusinessException(RequestStateCode.OPERATION_ERROR, "处理 PDF 水印流失败: " + e.getMessage(), e);
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
            List<SwapTask> list = swapTaskService.list();
            if (list != null && !list.isEmpty()) {
                for (SwapTask task : list) {
                    result.add(SwapTaskExportVo.builder()
                            .id(task.getId())
                            .taskCode(task.getTaskCode())
                            .taskName(task.getTaskName())
                            .taskType(task.getTaskType())
                            .sourceNode(task.getSourceNode())
                            .targetNode(task.getTargetNode())
                            .statusDesc(task.getStatus() != null && task.getStatus() == 1 ? "启用" : "停用")
                            .remark(task.getRemark())
                            .createTime(task.getCreateTime())
                            .build());
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("从数据库查询任务失败，使用样例数据兜底: {}", e.getMessage());
        }

        // 默认样例数据
        result.add(SwapTaskExportVo.builder()
                .id(1001L)
                .taskCode("TASK_DEMO_01")
                .taskName("核心业务增量同步")
                .taskType("INCREMENTAL")
                .sourceNode("NODE_PROD_SH")
                .targetNode("NODE_BACKUP_BJ")
                .statusDesc("启用")
                .remark("每10分钟增量触发")
                .createTime(LocalDateTime.now())
                .build());
        result.add(SwapTaskExportVo.builder()
                .id(1002L)
                .taskCode("TASK_DEMO_02")
                .taskName("历史归档全量同步")
                .taskType("FULL")
                .sourceNode("NODE_PROD_SH")
                .targetNode("NODE_ANALYTICS_SZ")
                .statusDesc("启用")
                .remark("每日凌晨归档")
                .createTime(LocalDateTime.now())
                .build());
        return result;
    }
}
