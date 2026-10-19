package com.zkjs.aio.swap;

import com.zkjs.aio.swap.config.WebFluxUploadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 响应式文件上传与大小限制端到端功能验证测试
 *
 * @author wuyw
 */
@SpringBootTest
class FileUploadIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebFluxUploadConfig webFluxUploadConfig;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        // 绑定 ApplicationContext 构建 WebTestClient，直接映射至 WebFlux DispatcherHandler
        this.webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .configureClient()
                .build();
    }

    @Test
    @DisplayName("测试 WebFluxUploadConfig 配置属性已按预期加载")
    void testUploadConfigLoaded() {
        assertNotNull(webFluxUploadConfig);
        assertEquals(50, webFluxUploadConfig.getMaxInMemorySize().toMegabytes());
        assertEquals(10, webFluxUploadConfig.getMaxParts());
        assertEquals(100, webFluxUploadConfig.getMaxDiskUsagePerPart().toMegabytes());
        assertEquals(2, webFluxUploadConfig.getPartInMemorySize().toMegabytes());
    }

    @Test
    @DisplayName("测试 GET /demo/file/upload-config 查询当前文件上传配置")
    void testGetUploadConfigEndpoint() {
        webTestClient.get()
                .uri("/demo/file/upload-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.result.maxInMemorySize").isEqualTo("50MB")
                .jsonPath("$.result.maxParts").isEqualTo(10)
                .jsonPath("$.result.maxDiskUsagePerPart").isEqualTo("100MB")
                .jsonPath("$.result.partInMemorySize").isEqualTo("2MB");
    }

    @Test
    @DisplayName("测试单文件流式上传接口 POST /demo/file/upload 成功")
    void testSingleFileUploadSuccess() {
        byte[] fileBytes = "AIO Framework Reactive File Upload Content - 2026".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return "aio_test_file.txt";
            }
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", resource)
                .contentType(MediaType.TEXT_PLAIN);
        builder.part("description", "单元测试文件说明");

        webTestClient.post()
                .uri("/demo/file/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.message").isEqualTo("文件上传成功")
                .jsonPath("$.result.filename").isEqualTo("aio_test_file.txt")
                .jsonPath("$.result.size").isEqualTo(fileBytes.length)
                .jsonPath("$.result.description").isEqualTo("单元测试文件说明");
    }

    @Test
    @DisplayName("测试多文件流式并发上传接口 POST /demo/file/upload-multiple 成功")
    void testMultipleFilesUploadSuccess() {
        byte[] bytes1 = "First file content".getBytes(StandardCharsets.UTF_8);
        byte[] bytes2 = "{\"message\":\"second json file\"}".getBytes(StandardCharsets.UTF_8);

        ByteArrayResource resource1 = new ByteArrayResource(bytes1) {
            @Override
            public String getFilename() {
                return "doc1.txt";
            }
        };

        ByteArrayResource resource2 = new ByteArrayResource(bytes2) {
            @Override
            public String getFilename() {
                return "doc2.json";
            }
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", resource1).contentType(MediaType.TEXT_PLAIN);
        builder.part("files", resource2).contentType(MediaType.APPLICATION_JSON);

        webTestClient.post()
                .uri("/demo/file/upload-multiple")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.message").isEqualTo("多文件上传成功")
                .jsonPath("$.result.length()").isEqualTo(2)
                .jsonPath("$.result[0].filename").isEqualTo("doc1.txt")
                .jsonPath("$.result[0].size").isEqualTo(bytes1.length)
                .jsonPath("$.result[1].filename").isEqualTo("doc2.json")
                .jsonPath("$.result[1].size").isEqualTo(bytes2.length);
    }
}
