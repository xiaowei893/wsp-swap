package com.zkjs.aio.swap.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * WebFlux 响应式文件上传与编解码器限制配置
 * <p>
 * 在 Spring WebFlux (Netty) 体系下，传统 Servlet 的 spring.servlet.multipart.* 不生效。
 * 本配置通过定制 ServerCodecConfigurer 与 MultipartHttpMessageReader，
 * 设定全局内存解码缓冲区、单 Part 磁盘临时文件存储上限及内存防 OOM 阈值。
 *
 * @author wuyw
 */
@Slf4j
@Getter
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebFluxUploadConfig implements WebFluxConfigurer {

    @Value("${spring.codec.max-in-memory-size:50MB}")
    private DataSize maxInMemorySize;

    @Value("${aio.upload.max-parts:10}")
    private int maxParts;

    @Value("${aio.upload.max-disk-usage-per-part:100MB}")
    private DataSize maxDiskUsagePerPart;

    @Value("${aio.upload.max-in-memory-size:2MB}")
    private DataSize partInMemorySize;

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        int maxMemoryBytes = (int) maxInMemorySize.toBytes();
        log.info("【AIO-WebFlux】配置文件上传全局 Codec 内存限制: {}, 单Part磁盘上限: {}, 内存转存阈值: {}",
                maxInMemorySize, maxDiskUsagePerPart, partInMemorySize);

        // 1. 设置 WebFlux 全局通用编解码器最大内存缓冲大小（解决默认 256KB 报 DataBufferLimitException 问题）
        configurer.defaultCodecs().maxInMemorySize(maxMemoryBytes);

        // 2. 定制基于流式 NIO 的 Multipart 请求读取器，并使用 MultipartHttpMessageReader 封装以支持 MultiValueMap<String, Part>
        DefaultPartHttpMessageReader partReader = new DefaultPartHttpMessageReader();
        partReader.setMaxParts(maxParts);
        partReader.setMaxDiskUsagePerPart(maxDiskUsagePerPart.toBytes());
        partReader.setMaxInMemorySize((int) partInMemorySize.toBytes());
        partReader.setEnableLoggingRequestDetails(true);

        configurer.defaultCodecs().multipartReader(new MultipartHttpMessageReader(partReader));
    }
}
