package com.zkjs.aio.swap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zkjs.aio.storage.service.AioFileStorageService;
import com.zkjs.aio.storage.validator.StorageFailFastValidator;
import com.zkjs.storage.autoconfigure.FileStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 官方文件存储组件 (aio-spring-boot-starter-storage) 端到端功能测试
 * <p>
 * 覆盖：
 * 1. 自动装配与 Fail-Fast 校验器验证
 * 2. 单文件上传、存在性查询、流式下载、文件删除完整生命周期
 * 3. 大文件分片断点续传完整生命周期 (Init -> Upload Chunks -> List Chunks -> Complete Merge -> Download Verify -> Clean)
 * 4. 分片任务主动中止清理 (Abort)
 * 5. 存储安全防线校验 (路径穿越、非法注入、空字节拦截)
 * 6. 分片缺失时 Fail-Fast 严格连续性合并校验
 * 7. 空文件上传前置阻断拦截
 *
 * @author wuyw
 */
@SpringBootTest
class FileStorageIntegrationTest {

    @Autowired
    private AioFileStorageService storageService;

    @Autowired(required = false)
    private StorageFailFastValidator storageFailFastValidator;

    @Autowired(required = false)
    private FileStorageProperties fileStorageProperties;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("测试1：验证 aio-spring-boot-starter-storage 组件及 Fail-Fast 校验器装配成功")
    void testStorageAutoConfiguration() {
        assertNotNull(storageService, "AioFileStorageService 门面服务必须由 starter 自动装配");
        assertNotNull(storageFailFastValidator, "StorageFailFastValidator 必须按 HIGHEST_PRECEDENCE 注入");
        assertNotNull(fileStorageProperties, "FileStorageProperties 必须成功加载配置");
        assertEquals("local", fileStorageProperties.getType(), "默认存储类型应为 local");

        // 验证 MinIO 与 Aliyun OSS 预留配置块已成功载入且默认处于关闭状态 (enabled = false)
        assertNotNull(fileStorageProperties.getMinio(), "MinIO 配置块应已正常载入");
        assertFalse(fileStorageProperties.getMinio().isEnabled(), "MinIO 启用状态应默认为关闭 (false)");
        assertNotNull(fileStorageProperties.getAliyun(), "Aliyun OSS 配置块应已正常载入");
        assertFalse(fileStorageProperties.getAliyun().isEnabled(), "Aliyun OSS 启用状态应默认为关闭 (false)");
    }

    @Test
    @DisplayName("测试2：单文件上传、存在性查询、流式下载与删除完整生命周期")
    void testSingleFileUploadAndDownloadLifecycle() throws Exception {
        byte[] fileBytes = "Hello AIO Framework Storage - Spring MVC 2026 Test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "hello_storage.txt", "text/plain", fileBytes);

        // 1. 上传文件
        MvcResult uploadResult = mockMvc.perform(multipart("/demo/storage/file/upload")
                        .file(file)
                        .param("bizPath", "swap/lifecycle-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.originalFilename").value("hello_storage.txt"))
                .andExpect(jsonPath("$.result.size").value(fileBytes.length))
                .andReturn();

        JsonNode uploadJson = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        String fileKey = uploadJson.path("result").path("fileKey").asText();
        assertNotNull(fileKey, "上传成功后必须返回合法的 fileKey");
        assertFalse(fileKey.isBlank());

        // 2. 检查文件存在性
        mockMvc.perform(get("/demo/storage/file/exists")
                        .param("fileKey", fileKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result").value(true));

        // 3. 下载文件并验证内容及响应头
        MvcResult downloadResult = mockMvc.perform(get("/demo/storage/file/download")
                        .param("fileKey", fileKey)
                        .param("filename", "downloaded_hello.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("downloaded_hello.txt")))
                .andReturn();

        byte[] downloadedBytes = downloadResult.getResponse().getContentAsByteArray();
        assertArrayEquals(fileBytes, downloadedBytes, "下载文件内容必须与上传内容严格一致");

        // 4. 删除文件
        mockMvc.perform(delete("/demo/storage/file/delete")
                        .param("fileKey", fileKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 验证删除后文件不再存在
        mockMvc.perform(get("/demo/storage/file/exists")
                        .param("fileKey", fileKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result").value(false));
    }

    @Test
    @DisplayName("测试3：大文件分片断点续传、列表查询、顺序合并与下载校验完整流程")
    void testMultipartUploadAndMergeWorkflow() throws Exception {
        byte[] chunk0Bytes = new byte[1024];
        byte[] chunk1Bytes = new byte[512];
        Arrays.fill(chunk0Bytes, (byte) 'A');
        Arrays.fill(chunk1Bytes, (byte) 'B');
        int totalSize = chunk0Bytes.length + chunk1Bytes.length;

        // 1. 初始化分片任务
        String initPayload = String.format("""
                {
                    "originalFilename": "merged_sample.dat",
                    "totalSize": %d,
                    "totalChunks": 2,
                    "bizPath": "swap/multipart-test"
                }
                """, totalSize);

        MvcResult initResult = mockMvc.perform(post("/demo/storage/file/multipart/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode initJson = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String uploadId = initJson.path("result").path("uploadId").asText();
        assertNotNull(uploadId);
        assertFalse(uploadId.isBlank());

        // 2. 上传分片 0
        MockMultipartFile chunk0File = new MockMultipartFile("file", "part-0.dat", "application/octet-stream", chunk0Bytes);
        mockMvc.perform(multipart("/demo/storage/file/multipart/upload-chunk")
                        .file(chunk0File)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.chunkIndex").value(0));

        // 3. 上传分片 1
        MockMultipartFile chunk1File = new MockMultipartFile("file", "part-1.dat", "application/octet-stream", chunk1Bytes);
        mockMvc.perform(multipart("/demo/storage/file/multipart/upload-chunk")
                        .file(chunk1File)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.chunkIndex").value(1));

        // 4. 查询已上传分片列表
        mockMvc.perform(get("/demo/storage/file/multipart/uploaded-chunks")
                        .param("uploadId", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result[0]").value(0))
                .andExpect(jsonPath("$.result[1]").value(1));

        // 5. 完成分片合并
        String completePayload = String.format("""
                {
                    "uploadId": "%s",
                    "bizPath": "swap/multipart-test"
                }
                """, uploadId);

        MvcResult completeResult = mockMvc.perform(post("/demo/storage/file/multipart/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completePayload)
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode completeJson = objectMapper.readTree(completeResult.getResponse().getContentAsString());
        String mergedFileKey = completeJson.path("result").path("fileKey").asText();
        assertNotNull(mergedFileKey);

        // 6. 下载合并文件并核对内容
        MvcResult downloadMerged = mockMvc.perform(get("/demo/storage/file/download")
                        .param("fileKey", mergedFileKey))
                .andExpect(status().isOk())
                .andReturn();

        byte[] mergedBytes = downloadMerged.getResponse().getContentAsByteArray();
        assertEquals(totalSize, mergedBytes.length, "合并后文件大小必须等于各分片大小之和");

        byte[] expectedCombined = new byte[totalSize];
        System.arraycopy(chunk0Bytes, 0, expectedCombined, 0, chunk0Bytes.length);
        System.arraycopy(chunk1Bytes, 0, expectedCombined, chunk0Bytes.length, chunk1Bytes.length);
        assertArrayEquals(expectedCombined, mergedBytes, "合并文件内容必须与两分片拼接内容严格一致");

        // 7. 清理测试文件
        mockMvc.perform(delete("/demo/storage/file/delete")
                        .param("fileKey", mergedFileKey))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("测试4：分片任务主动中止清理 (Abort)")
    void testMultipartAbortLifecycle() throws Exception {
        // 1. 初始化分片任务
        String initPayload = """
                {
                    "originalFilename": "abort_test.dat",
                    "totalSize": 1024,
                    "totalChunks": 2,
                    "bizPath": "swap/abort-test"
                }
                """;

        MvcResult initResult = mockMvc.perform(post("/demo/storage/file/multipart/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isOk())
                .andReturn();

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .path("result").path("uploadId").asText();

        // 2. 上传一个分片
        MockMultipartFile chunk0File = new MockMultipartFile("file", "part-0.dat", "application/octet-stream", new byte[512]);
        mockMvc.perform(multipart("/demo/storage/file/multipart/upload-chunk")
                        .file(chunk0File)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "0"))
                .andExpect(status().isOk());

        // 3. 中止分片任务
        mockMvc.perform(delete("/demo/storage/file/multipart/abort")
                        .param("uploadId", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试5：StorageSecurityValidator 严格安全防线校验 (路径穿越、非法根路径、非法字符)")
    void testSecurityValidationPathTraversal() throws Exception {
        byte[] bytes = "Security Test Content".getBytes(StandardCharsets.UTF_8);

        // 5.1 业务路径包含 .. 目录穿越
        MockMultipartFile file1 = new MockMultipartFile("file", "sec_test.txt", "text/plain", bytes);
        mockMvc.perform(multipart("/demo/storage/file/upload")
                        .file(file1)
                        .param("bizPath", "../../etc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("不得包含路径遍历符号'..'")));

        // 5.2 业务路径以绝对路径 / 开头
        mockMvc.perform(multipart("/demo/storage/file/upload")
                        .file(file1)
                        .param("bizPath", "/usr/local/bin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("必须为相对路径")));

        // 5.3 上传文件名携带目录穿越符号
        MockMultipartFile fileWithTraversal = new MockMultipartFile("file", "../../../evil.txt", "text/plain", bytes);
        mockMvc.perform(multipart("/demo/storage/file/upload")
                        .file(fileWithTraversal)
                        .param("bizPath", "swap/safe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("不得包含路径分隔符")));

        // 5.4 fileKey 存在性查询携带路径穿越符号
        mockMvc.perform(get("/demo/storage/file/exists")
                        .param("fileKey", "../../etc/shadow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("包含非法字符或遍历符")));
    }

    @Test
    @DisplayName("测试6：分片缺失时 Fail-Fast 严格连续性合并校验 (禁止缺失拼装)")
    void testMultipartCompleteMissingChunkFailFast() throws Exception {
        // 1. 初始化需要 2 个分片的任务
        String initPayload = """
                {
                    "originalFilename": "missing_chunk_test.dat",
                    "totalSize": 2048,
                    "totalChunks": 2,
                    "bizPath": "swap/fail-fast"
                }
                """;

        MvcResult initResult = mockMvc.perform(post("/demo/storage/file/multipart/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isOk())
                .andReturn();

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .path("result").path("uploadId").asText();

        // 2. 仅上传第 0 片，故意漏掉第 1 片
        MockMultipartFile chunk0 = new MockMultipartFile("file", "part-0.dat", "application/octet-stream", new byte[1024]);
        mockMvc.perform(multipart("/demo/storage/file/multipart/upload-chunk")
                        .file(chunk0)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "0"))
                .andExpect(status().isOk());

        // 3. 尝试以 totalChunks=2 执行合并，预期被 Fail-Fast 校验坚决阻断
        String completePayload = String.format("""
                {
                    "uploadId": "%s",
                    "bizPath": "swap/fail-fast"
                }
                """, uploadId);

        mockMvc.perform(post("/demo/storage/file/multipart/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completePayload)
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("分片不完整，禁止合并")));

        // 4. 清理未完成任务
        mockMvc.perform(delete("/demo/storage/file/multipart/abort")
                        .param("uploadId", uploadId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("测试7：上传空文件 (0 字节) 前置阻断校验")
    void testUploadEmptyFileRejected() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/demo/storage/file/upload")
                        .file(emptyFile)
                        .param("bizPath", "swap/empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("上传文件内容为空 (0 字节)")));
    }
}
