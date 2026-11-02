package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.config.FileUploadConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 文件上传控制器示例（Spring MVC 体系）
 * <p>
 * 演示在 Spring MVC 体系下使用 MultipartFile 处理客户端上传文件，
 * 配合 Java 21 虚拟线程与磁盘缓冲，兼顾性能与低内存占用。
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/file")
@RequiredArgsConstructor
@Tag(name = "文件上传功能演示", description = "基于 Spring MVC 文件上传与大小限制验证")
public class DemoFileUploadController {

    private final FileUploadConfig uploadConfig;

    @GetMapping("/upload-config")
    @Operation(summary = "查询当前上传大小限制配置", description = "获取当前 Spring MVC Multipart 上传限制参数")
    public ResultBody<Map<String, Object>> getUploadConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", uploadConfig.isEnabled());
        config.put("maxFileSize", uploadConfig.getMaxFileSize().toMegabytes() + "MB");
        config.put("maxRequestSize", uploadConfig.getMaxRequestSize().toMegabytes() + "MB");
        config.put("fileSizeThreshold", uploadConfig.getFileSizeThreshold().toMegabytes() + "MB");
        return ResultBody.success("获取上传配置成功", config);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "单文件流式上传接口", description = "接收 MultipartFile 对象并保存，返回上传元数据")
    public ResultBody<Map<String, Object>> uploadSingleFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {

        if (file == null || file.isEmpty()) {
            return ResultBody.failure("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType() != null
                ? file.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        log.info("收到文件上传请求: filename=[{}], contentType=[{}], description=[{}]",
                originalFilename, contentType, description);

        try {
            Path tempFile = Files.createTempFile("aio_upload_", "_" + originalFilename);
            try {
                file.transferTo(tempFile);
                long fileSize = Files.size(tempFile);
                log.info("文件上传完成: filename=[{}], size=[{} bytes], tempPath=[{}]",
                        originalFilename, fileSize, tempFile);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("filename", originalFilename);
                data.put("contentType", contentType);
                data.put("size", fileSize);
                data.put("description", description != null ? description : "");
                return ResultBody.success("文件上传成功", data);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            log.error("文件上传转存异常: {}", e.getMessage(), e);
            return ResultBody.failure("文件保存失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件流式上传接口", description = "接收 List<MultipartFile> 保存并汇总返回")
    public ResultBody<List<Map<String, Object>>> uploadMultipleFiles(
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResultBody.failure("上传文件列表不能为空");
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String originalFilename = file.getOriginalFilename();
            try {
                Path tempFile = Files.createTempFile("aio_multi_upload_", "_" + originalFilename);
                try {
                    file.transferTo(tempFile);
                    long size = Files.size(tempFile);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("filename", originalFilename);
                    item.put("size", size);
                    item.put("contentType", Objects.toString(file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE));
                    list.add(item);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException e) {
                log.error("多文件上传处理失败: {}", e.getMessage(), e);
                return ResultBody.failure("文件保存失败: " + e.getMessage());
            }
        }

        return ResultBody.success("多文件上传成功", list);
    }
}
