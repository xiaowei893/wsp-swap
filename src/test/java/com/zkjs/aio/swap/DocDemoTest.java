package com.zkjs.aio.swap;

import com.zkjs.aio.doc.excel.ExcelUtil;
import com.zkjs.aio.doc.pdf.PdfUtil;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.service.DocDemoService;
import com.zkjs.aio.swap.service.SwapTaskService;
import com.zkjs.aio.swap.vo.SwapTaskExportVo;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 文档导出套件单元测试（Spring MVC 体系）
 * <p>
 * 覆盖 HTML 自然模板渲染与下载写回、Excel 导出与读取解析、Word 模板渲染与 PDF 水印注入。
 *
 * @author wuyw
 */
class DocDemoTest {

    private static DocDemoService docDemoService;
    private static SwapTaskService mockSwapTaskService;

    @BeforeAll
    static void setUp() throws IOException {
        mockSwapTaskService = Mockito.mock(SwapTaskService.class);
        // 模拟数据库返回一条真实任务
        SwapTask task = SwapTask.builder()
                .id(10001L)
                .taskCode("TASK_MOCK_001")
                .taskName("单元测试模拟同步任务")
                .taskType("SYNC")
                .sourceNode("MOCK_SRC")
                .targetNode("MOCK_TARGET")
                .status(1)
                .remark("单元测试专用")
                .createTime(LocalDateTime.now())
                .build();
        when(mockSwapTaskService.list()).thenReturn(List.of(task));

        docDemoService = new DocDemoService(mockSwapTaskService);

        // 如果类路径下的 contract_template.docx 不存在，自动生成一个标准的模板文件
        Path templatePath = Path.of("src/main/resources/templates/contract_template.docx");
        if (!Files.exists(templatePath)) {
            Files.createDirectories(templatePath.getParent());
            try (XWPFDocument doc = new XWPFDocument();
                 OutputStream fos = Files.newOutputStream(templatePath)) {
                XWPFParagraph pTitle = doc.createParagraph();
                pTitle.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun rTitle = pTitle.createRun();
                rTitle.setText("战略合作业务合作协议");
                rTitle.setBold(true);
                rTitle.setFontSize(18);

                doc.createParagraph(); // 空行

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

                doc.write(fos);
            }
        }
    }

    @Test
    @DisplayName("1. 测试基于 Thymeleaf 自然模板动态填充与 Spring MVC 下载写回 HTML 报表")
    void testDownloadHtmlReport() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        docDemoService.downloadHtmlReport(response, "2026年数据交换服务月报", "技术中台架构部");

        // 验证 Content-Type 与 Content-Disposition
        assertTrue(response.getContentType().contains("text/html"));
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("数据交换运行质量月报.html") || disposition.contains("%E6%95%B0%E6%8D%AE"));

        // 验证写入的响应体内容包含注入的参数和任务数据
        String bodyText = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(bodyText.contains("2026年数据交换服务月报"));
        assertTrue(bodyText.contains("技术中台架构部"));
        assertTrue(bodyText.contains("TASK_MOCK_001"));
        assertTrue(bodyText.contains("单元测试模拟同步任务"));
    }

    @Test
    @DisplayName("2. 测试动态纯 HTML 字符串源码片段渲染")
    void testRenderHtmlString() {
        String template = "<div class=\"notice\"><span th:text=\"${author}\">作者</span>：<p th:text=\"${content}\">内容</p></div>";
        Map<String, Object> params = Map.of("author", "架构师小周", "content", "AIO-Doc 文档组件运行正常");

        String rendered = docDemoService.renderHtmlString(template, params);
        assertNotNull(rendered);
        assertTrue(rendered.contains("架构师小周"));
        assertTrue(rendered.contains("AIO-Doc 文档组件运行正常"));
        assertFalse(rendered.contains("th:text"));
    }

    @Test
    @DisplayName("3. 测试基于 EasyExcel 4.x 极速流式导出与数据读回反序列化")
    void testExcelExportAndReadBack() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        docDemoService.downloadExcel(response);

        // 验证响应头
        assertTrue(response.getContentType().contains("spreadsheetml"));
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("交换任务管理清单.xlsx") || disposition.contains("%E4%BA%A4%E6%8D%A2"));

        // 获取响应的字节流并反向用 ExcelUtil 同步读取解析
        byte[] excelBytes = response.getContentAsByteArray();
        assertTrue(excelBytes.length > 0);

        List<SwapTaskExportVo> parsedList = ExcelUtil.readSync(new ByteArrayInputStream(excelBytes), SwapTaskExportVo.class);
        assertNotNull(parsedList);
        assertEquals(1, parsedList.size());
        SwapTaskExportVo vo = parsedList.get(0);
        assertEquals(10001L, vo.getId());
        assertEquals("TASK_MOCK_001", vo.getTaskCode());
        assertEquals("单元测试模拟同步任务", vo.getTaskName());
        assertEquals("启用", vo.getStatusDesc());
    }

    @Test
    @DisplayName("4. 测试基于 Poi-tl 动态绑定 Word docx 模板并下载")
    void testWordRenderAndDownload() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        docDemoService.downloadWord(response, "全国数据协同交换工程", "国家信息中心");

        // 验证响应头
        assertTrue(response.getContentType().contains("wordprocessingml"));
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("项目合作协议.docx") || disposition.contains("%E9%A1%B9%E7%9B%AE"));

        // 解析生成的 docx 并校验内容已替换
        byte[] docxBytes = response.getContentAsByteArray();
        assertTrue(docxBytes.length > 0);

        try (XWPFDocument resultDoc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            StringBuilder sb = new StringBuilder();
            resultDoc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            String text = sb.toString();
            assertTrue(text.contains("全国数据协同交换工程"));
            assertTrue(text.contains("国家信息中心"));
            assertFalse(text.contains("{{projectName}}"));
        }
    }

    @Test
    @DisplayName("5. 测试基于 x-easypdf 动态生成文本 PDF 并注入 45° 倾斜防伪水印")
    void testPdfCreateAndWatermark() throws Exception {
        // 1. 生成简易 PDF
        MockHttpServletResponse response = new MockHttpServletResponse();
        docDemoService.downloadSimplePdf(response, "AIO微服务框架设计方案");

        assertTrue(response.getContentType().startsWith("application/pdf"));
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("AIO架构设计白皮书.pdf") || disposition.contains("AIO%E6%9E%B6%E6%9E%84"));

        byte[] rawPdfBytes = response.getContentAsByteArray();
        assertTrue(rawPdfBytes.length > 0);
        // 校验 PDF 标识头
        String header = new String(rawPdfBytes, 0, Math.min(rawPdfBytes.length, 8), StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("%PDF-"));

        // 2. 注入防伪文字水印
        ByteArrayOutputStream watermarkOut = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(rawPdfBytes)) {
            PdfUtil.addTextWatermark(in, watermarkOut, "ZKJS 内部机密");
        }
        byte[] watermarkedPdf = watermarkOut.toByteArray();
        assertTrue(watermarkedPdf.length > 0);
        String wmHeader = new String(watermarkedPdf, 0, Math.min(watermarkedPdf.length, 8), StandardCharsets.US_ASCII);
        assertTrue(wmHeader.startsWith("%PDF-"));
    }
}
