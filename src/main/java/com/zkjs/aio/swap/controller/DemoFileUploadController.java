package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.config.WebFluxUploadConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 响应式文件上传控制器示例
 * <p>
 * 演示在 Spring Boot WebFlux 体系下如何使用响应式 FilePart
 * 流式处理客户端上传的大文件，避免将整个文件加载到 JVM 堆内存导致 OOM。
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/file")
@RequiredArgsConstructor
@Tag(name = "文件上传功能演示", description = "基于 Spring WebFlux 响应式流式上传与大小限制验证")
public class DemoFileUploadController {

    private final WebFluxUploadConfig uploadConfig;

    @GetMapping("/upload-config")
    @Operation(summary = "查询当前上传大小限制配置", description = "获取当前 WebFlux Codec 内存限制与 Multipart 限制参数")
    public ResultBody<Map<String, Object>> getUploadConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxInMemorySize", uploadConfig.getMaxInMemorySize().toMegabytes() + "MB");
        config.put("maxParts", uploadConfig.getMaxParts());
        config.put("maxDiskUsagePerPart", uploadConfig.getMaxDiskUsagePerPart().toMegabytes() + "MB");
        config.put("partInMemorySize", uploadConfig.getPartInMemorySize().toMegabytes() + "MB");
        return ResultBody.success("获取上传配置成功", config);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "单文件流式上传接口", description = "接收 FilePart 响应式对象并流式落盘，返回上传元数据")
    public Mono<ResultBody<Map<String, Object>>> uploadSingleFile(
            @RequestPart("file") FilePart filePart,
            @RequestPart(value = "description", required = false) String description) {

        if (filePart == null || filePart.filename().isBlank()) {
            return Mono.just(ResultBody.failure("上传文件不能为空"));
        }

        String originalFilename = filePart.filename();
        String contentType = filePart.headers().getContentType() != null
                ? filePart.headers().getContentType().toString()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        log.info("收到文件上传请求: filename=[{}], contentType=[{}], description=[{}]",
                originalFilename, contentType, description);

        try {
            // 创建安全临时文件进行流式写入（避免驻留 JVM 堆内存）
            Path tempFile = Files.createTempFile("aio_upload_", "_" + originalFilename);

            return filePart.transferTo(tempFile)
                    .then(Mono.fromCallable(() -> {
                        long fileSize = Files.size(tempFile);
                        log.info("文件上传完成: filename=[{}], size=[{} bytes], tempPath=[{}]",
                                originalFilename, fileSize, tempFile);

                        // 模拟业务处理完成后清理临时文件
                        Files.deleteIfExists(tempFile);

                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("filename", originalFilename);
                        data.put("contentType", contentType);
                        data.put("size", fileSize);
                        data.put("description", description != null ? description : "");
                        return ResultBody.success("文件上传成功", data);
                    }))
                    .onErrorResume(e -> {
                        log.error("文件上传转存异常: {}", e.getMessage(), e);
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException ignored) {
                        }
                        return Mono.just(ResultBody.failure("文件保存失败: " + e.getMessage()));
                    });
        } catch (IOException e) {
            log.error("创建临时转存文件失败: {}", e.getMessage(), e);
            return Mono.just(ResultBody.failure("系统临时文件创建失败: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件流式上传接口", description = "接收 Flux<FilePart> 并发流式保存并汇总返回")
    public Mono<ResultBody<List<Map<String, Object>>>> uploadMultipleFiles(
            @RequestPart("files") Flux<FilePart> fileParts) {

        return fileParts
                .flatMap(filePart -> {
                    String originalFilename = filePart.filename();
                    try {
                        Path tempFile = Files.createTempFile("aio_multi_upload_", "_" + originalFilename);
                        return filePart.transferTo(tempFile)
                                .then(Mono.fromCallable(() -> {
                                    long size = Files.size(tempFile);
                                    Files.deleteIfExists(tempFile);
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("filename", originalFilename);
                                    item.put("size", size);
                                    item.put("contentType", Objects.toString(filePart.headers().getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE));
                                    return item;
                                }));
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                })
                .collectList()
                .map(list -> ResultBody.success("多文件上传成功", list));
    }
}
