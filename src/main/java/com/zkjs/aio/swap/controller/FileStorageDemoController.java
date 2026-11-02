package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.storage.service.AioFileStorageService;
import com.zkjs.storage.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 统一文件存储组件演示控制器（Spring MVC 体系）
 * <p>
 * 基于 aio-spring-boot-starter-storage 官方组件构建，
 * 演示普通文件上传、无状态大文件分片断点续传、分片合并、文件存在性检查、删除及流式下载。
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/storage/file")
@RequiredArgsConstructor
@Tag(name = "统一文件存储组件演示 (AioStorage)", description = "基于 AIO 官方存储门面 AioFileStorageService 的单文件上传、大文件分片合并与下载")
public class FileStorageDemoController {

    private final AioFileStorageService storageService;

    /**
     * 1. 普通文件单文件上传 (Spring MVC MultipartFile)
     */
    @Operation(summary = "1. 普通单文件上传")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultBody<FileUploadResult> upload(
            @Parameter(description = "待上传文件")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "业务存储相对路径", example = "swap/task")
            @RequestParam(value = "bizPath", defaultValue = "swap/task") String bizPath,
            @Parameter(description = "指定存储提供者类型（可选：LOCAL, MINIO, ALIYUN_OSS）")
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到文件上传请求: filename=[{}], size=[{} bytes], bizPath=[{}]",
                file.getOriginalFilename(), file.getSize(), bizPath);
        FileUploadResult result = storageService.upload(file, bizPath, storageType);
        return ResultBody.success("文件上传成功", result);
    }

    /**
     * 2. 分片上传步骤 1：初始化分片任务
     */
    @Operation(summary = "2. 初始化分片上传任务")
    @PostMapping("/multipart/init")
    public ResultBody<MultipartInitResult> initMultipart(
            @RequestBody MultipartInitRequest request,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到初始化分片上传请求: filename=[{}], totalSize=[{} bytes]",
                request.getOriginalFilename(), request.getTotalSize());
        MultipartInitResult result = storageService.initMultipartUpload(request, storageType);
        return ResultBody.success("分片任务初始化成功", result);
    }

    /**
     * 3. 分片上传步骤 2：上传单个分片
     */
    @Operation(summary = "3. 上传单个分片文件")
    @PostMapping(value = "/multipart/upload-chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultBody<ChunkUploadResult> uploadChunk(
            @Parameter(description = "分片上传任务ID", example = "upload-uuid-123")
            @RequestParam("uploadId") String uploadId,
            @Parameter(description = "分片序号 (从 0 开始)", example = "0")
            @RequestParam("chunkIndex") int chunkIndex,
            @Parameter(description = "当前分片文件数据")
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到上传分片请求: uploadId=[{}], chunkIndex=[{}], size=[{} bytes]",
                uploadId, chunkIndex, file.getSize());
        ChunkUploadResult result = storageService.uploadChunk(uploadId, chunkIndex, file, storageType);
        return ResultBody.success("分片上传成功", result);
    }

    /**
     * 4. 分片上传步骤 3：完成分片合并 (Fail-Fast 严格核验连续性)
     */
    @Operation(summary = "4. 完成分片合并")
    @PostMapping("/multipart/complete")
    public ResultBody<MultipartCompleteResult> completeMultipart(
            @RequestBody MultipartCompleteRequest request,
            @Parameter(description = "客户端预期分片总数", example = "3")
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到分片合并请求: uploadId=[{}], totalChunks=[{}]", request.getUploadId(), totalChunks);
        MultipartCompleteResult result = storageService.completeMultipartUpload(request, totalChunks, storageType);
        return ResultBody.success("分片合并成功", result);
    }

    /**
     * 5. 断点续传辅助：查询已上传分片列表
     */
    @Operation(summary = "5. 查询已上传分片索引列表")
    @GetMapping("/multipart/uploaded-chunks")
    public ResultBody<List<Integer>> listUploadedChunks(
            @RequestParam("uploadId") String uploadId,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        List<Integer> chunks = storageService.listUploadedChunks(uploadId, storageType);
        return ResultBody.success("查询已上传分片成功", chunks);
    }

    /**
     * 6. 中止并清理放弃的分片任务
     */
    @Operation(summary = "6. 中止分片上传任务并清理临时文件")
    @DeleteMapping("/multipart/abort")
    public ResultBody<Void> abortMultipart(
            @RequestParam("uploadId") String uploadId,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到中止分片任务请求: uploadId=[{}]", uploadId);
        storageService.abortMultipartUpload(uploadId, storageType);
        return ResultBody.success("分片任务已中止并清理", null);
    }

    /**
     * 7. 检查文件是否存在
     */
    @Operation(summary = "7. 检查文件是否存在")
    @GetMapping("/exists")
    public ResultBody<Boolean> exists(
            @RequestParam("fileKey") String fileKey,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        boolean exists = storageService.exists(fileKey, storageType);
        return ResultBody.success(exists);
    }

    /**
     * 8. 删除文件
     */
    @Operation(summary = "8. 删除已存储文件")
    @DeleteMapping("/delete")
    public ResultBody<Void> delete(
            @RequestParam("fileKey") String fileKey,
            @RequestParam(value = "storageType", required = false) StorageType storageType) {

        log.info("收到删除文件请求: fileKey=[{}]", fileKey);
        storageService.delete(fileKey, storageType);
        return ResultBody.success("文件删除成功", null);
    }

    /**
     * 9. 直接下载文件至 HttpServletResponse
     */
    @Operation(summary = "9. 直接下载文件")
    @GetMapping("/download")
    public void download(
            @RequestParam("fileKey") String fileKey,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "storageType", required = false) StorageType storageType,
            HttpServletResponse response) {

        log.info("收到文件下载请求: fileKey=[{}], filename=[{}]", fileKey, filename);
        storageService.downloadToResponse(fileKey, storageType, response, filename);
    }
}
