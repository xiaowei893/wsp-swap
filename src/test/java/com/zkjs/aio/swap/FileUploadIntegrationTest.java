package com.zkjs.aio.swap;

import com.zkjs.aio.swap.config.FileUploadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC 文件上传与大小限制端到端功能验证测试
 *
 * @author wuyw
 */
@SpringBootTest
class FileUploadIntegrationTest {

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("测试 FileUploadConfig 配置属性已按预期加载")
    void testUploadConfigLoaded() {
        assertNotNull(fileUploadConfig);
        assertTrue(fileUploadConfig.isEnabled());
        assertEquals(50, fileUploadConfig.getMaxFileSize().toMegabytes());
        assertEquals(100, fileUploadConfig.getMaxRequestSize().toMegabytes());
        assertEquals(2, fileUploadConfig.getFileSizeThreshold().toMegabytes());
    }

    @Test
    @DisplayName("测试 GET /demo/file/upload-config 查询当前文件上传配置")
    void testGetUploadConfigEndpoint() throws Exception {
        mockMvc.perform(get("/demo/file/upload-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.enabled").value(true))
                .andExpect(jsonPath("$.result.maxFileSize").value("50MB"))
                .andExpect(jsonPath("$.result.maxRequestSize").value("100MB"))
                .andExpect(jsonPath("$.result.fileSizeThreshold").value("2MB"));
    }

    @Test
    @DisplayName("测试单文件上传接口 POST /demo/file/upload 成功")
    void testSingleFileUploadSuccess() throws Exception {
        byte[] fileBytes = "AIO Framework Spring MVC File Upload Content - 2026".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "aio_test_file.txt", "text/plain", fileBytes);

        mockMvc.perform(multipart("/demo/file/upload")
                        .file(file)
                        .param("description", "单元测试文件说明"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件上传成功"))
                .andExpect(jsonPath("$.result.filename").value("aio_test_file.txt"))
                .andExpect(jsonPath("$.result.size").value(fileBytes.length))
                .andExpect(jsonPath("$.result.description").value("单元测试文件说明"));
    }

    @Test
    @DisplayName("测试多文件并发上传接口 POST /demo/file/upload-multiple 成功")
    void testMultipleFilesUploadSuccess() throws Exception {
        byte[] bytes1 = "First file content".getBytes(StandardCharsets.UTF_8);
        byte[] bytes2 = "{\"message\":\"second json file\"}".getBytes(StandardCharsets.UTF_8);

        MockMultipartFile file1 = new MockMultipartFile("files", "doc1.txt", "text/plain", bytes1);
        MockMultipartFile file2 = new MockMultipartFile("files", "doc2.json", "application/json", bytes2);

        mockMvc.perform(multipart("/demo/file/upload-multiple")
                        .file(file1)
                        .file(file2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("多文件上传成功"))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].filename").value("doc1.txt"))
                .andExpect(jsonPath("$.result[0].size").value(bytes1.length))
                .andExpect(jsonPath("$.result[1].filename").value("doc2.json"))
                .andExpect(jsonPath("$.result[1].size").value(bytes2.length));
    }
}
