package com.zkjs.aio.swap.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 文件上传与大小限制配置（Spring MVC 体系）
 * <p>
 * 遵循 Spring MVC 原生 Multipart 规约与磁盘缓冲阈值配置。
 *
 * @author wuyw
 */
@Slf4j
@Getter
@Configuration
public class FileUploadConfig {

    @Value("${spring.servlet.multipart.enabled:true}")
    private boolean enabled;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    @Value("${spring.servlet.multipart.file-size-threshold:2MB}")
    private DataSize fileSizeThreshold;

    /**
     * 兼容原有调用：获取单文件最大大小
     */
    @Deprecated
    public DataSize getMaxInMemorySize() {
        return maxFileSize != null ? maxFileSize : DataSize.ofMegabytes(50);
    }

    /**
     * 兼容原有调用：获取内存缓冲阈值
     */
    @Deprecated
    public DataSize getPartInMemorySize() {
        return fileSizeThreshold != null ? fileSizeThreshold : DataSize.ofMegabytes(2);
    }
}
