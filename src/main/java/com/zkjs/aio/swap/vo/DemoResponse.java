package com.zkjs.aio.swap.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演示响应出参 VO
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "演示响应数据")
public class DemoResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "处理流水号", example = "REQ-20261012-001")
    private String traceId;

    @Schema(description = "处理后的业务名称", example = "交换任务A")
    private String name;

    @Schema(description = "回显消息内容", example = "测试数据推送")
    private String message;

    @Schema(description = "处理时间戳", example = "2026-10-12T10:50:00")
    private LocalDateTime timestamp;
}
