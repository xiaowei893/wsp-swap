package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.dto.HtmlTemplateReq;
import com.zkjs.aio.swap.service.DocDemoService;
import com.zkjs.aio.swap.vo.SwapTaskExportVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 文档导出套件集成演示控制器
 * <p>
 * 演示基于 aio-spring-boot-starter-doc 模块实现的 HTML 前端自然模板渲染、
 * EasyExcel 4.x 极速导入导出、Poi-tl Word 模板化绑定及 x-easypdf 防伪水印注入。
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/doc")
@RequiredArgsConstructor
@Tag(name = "文档导出套件集成演示 (HTML / Excel / Word / PDF)",
     description = "全面演示 HTML 自然模板报表、EasyExcel 极速导入导出、Word 占位符精准渲染及 PDF 防伪水印追加")
public class DocDemoController {

    private final DocDemoService docDemoService;

    // =========================================================================
    // 1. HTML 自然模板与动态片段渲染
    // =========================================================================

    @GetMapping("/html/download")
    @Operation(summary = "1. 响应式下载 HTML 报表 (自然模板)",
               description = "基于 classpath:templates/report.html 前端自然模板，后端动态填充真实数据后流式写回浏览器下载")
    public Mono<Void> downloadHtmlReport(
            @Parameter(description = "报表主标题", example = "2026年数据交换部署服务运行质量分析报告")
            @RequestParam(required = false, defaultValue = "2026年数据交换部署服务运行质量分析报告") String title,
            @Parameter(description = "制表部门", example = "智能数据中台架构部")
            @RequestParam(required = false, defaultValue = "智能数据中台架构部") String department,
            ServerHttpResponse response) {
        log.info("收到下载 HTML 报表请求: title=[{}], department=[{}]", title, department);
        return docDemoService.downloadHtmlReport(response, title, department);
    }

    @PostMapping("/html/render-string")
    @Operation(summary = "2. 渲染动态 HTML 字符串模板",
               description = "接收客户端传入的 HTML 源码片段与动态变量 Map，直接在内存中解析并返回渲染后的 HTML 内容")
    public Mono<ResultBody<String>> renderHtmlString(@Valid @RequestBody HtmlTemplateReq req) {
        log.info("收到渲染动态 HTML 字符串请求，变量数: {}", req.getVariables() != null ? req.getVariables().size() : 0);
        String renderedHtml = docDemoService.renderHtmlString(req.getTemplateContent(), req.getVariables());
        return Mono.just(ResultBody.success("HTML 渲染成功", renderedHtml));
    }

    // =========================================================================
    // 2. Excel 极速导入与响应式流式导出 (EasyExcel 4.x)
    // =========================================================================

    @GetMapping("/excel/download")
    @Operation(summary = "3. 响应式流式导出任务清单 Excel",
               description = "基于 EasyExcel 4.x 低内存流式写出任务清单，遵循 RFC 5987 / RFC 6266 标准规避中文乱码")
    public Mono<Void> downloadExcel(ServerHttpResponse response) {
        log.info("收到导出任务清单 Excel 请求");
        return docDemoService.downloadExcel(response);
    }

    @PostMapping(value = "/excel/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "4. 上传并同步解析 Excel 文件",
               description = "流式接收客户端上传的 .xlsx 文件，通过 EasyExcel 同步读取解析为 SwapTaskExportVo 集合返回")
    public Mono<ResultBody<List<SwapTaskExportVo>>> importExcel(
            @Parameter(description = "待导入的 Excel 文件 (.xlsx)")
            @RequestPart("file") FilePart filePart) {
        log.info("收到上传 Excel 文件请求: filename=[{}]", filePart.filename());
        return docDemoService.importExcel(filePart)
                .map(list -> ResultBody.success("Excel 解析成功，共读取 " + list.size() + " 条记录", list));
    }

    // =========================================================================
    // 3. Word 模板化精准渲染与下载 (Poi-tl)
    // =========================================================================

    @GetMapping("/word/download")
    @Operation(summary = "5. 响应式下载 Word 模板文档",
               description = "基于 Word .docx 模板中的 {{placeholder}} 语法标签动态绑定项目与合同参数，写回响应流供浏览器直接下载")
    public Mono<Void> downloadWord(
            @Parameter(description = "项目名称", example = "AIO 企业中台微服务交换架构")
            @RequestParam(required = false, defaultValue = "AIO 企业中台微服务交换架构") String projectName,
            @Parameter(description = "甲方单位名称", example = "某大型集团数字化创新中心")
            @RequestParam(required = false, defaultValue = "某大型集团数字化创新中心") String clientName,
            ServerHttpResponse response) {
        log.info("收到下载 Word 文档请求: projectName=[{}], clientName=[{}]", projectName, clientName);
        return docDemoService.downloadWord(response, projectName, clientName);
    }

    // =========================================================================
    // 4. PDF 文档生成与防伪水印 (x-easypdf)
    // =========================================================================

    @GetMapping("/pdf/download")
    @Operation(summary = "6. 响应式下载构建的 PDF 文档",
               description = "使用 x-easypdf 链式构建包含章节排版与多段落的 PDF 文档，以 application/pdf 流式写回响应对象")
    public Mono<Void> downloadSimplePdf(
            @Parameter(description = "PDF 文档主标题", example = "AIO-Framework 架构设计白皮书")
            @RequestParam(required = false, defaultValue = "AIO-Framework 架构设计白皮书") String title,
            ServerHttpResponse response) {
        log.info("收到下载简易 PDF 请求: title=[{}]", title);
        return docDemoService.downloadSimplePdf(response, title);
    }

    @PostMapping(value = "/pdf/watermark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "7. 上传 PDF 并注入倾斜防伪文字水印",
               description = "接收上传的已有 PDF 文件，为其注入全页覆盖、45度倾斜、25%透明度的浅色防伪文字水印并直接以附件写回下载")
    public Mono<Void> addWatermarkToPdf(
            @Parameter(description = "原始 PDF 文件 (.pdf)")
            @RequestPart("file") FilePart filePart,
            @Parameter(description = "水印文本", example = "内部机密 严禁外传")
            @RequestParam(required = false, defaultValue = "内部机密 严禁外传") String watermarkText,
            ServerHttpResponse response) {
        log.info("收到 PDF 注入水印请求: filename=[{}], watermarkText=[{}]", filePart.filename(), watermarkText);
        return docDemoService.addWatermarkToPdf(filePart, watermarkText, response);
    }
}
