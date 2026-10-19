package com.zkjs.aio.swap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Kafka 交换消息传输对象
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Kafka 交换消息载体")
public class SwapKafkaMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "消息全局唯一ID", example = "MSG_1710000000000")
    private String msgId;

    @Schema(description = "业务任务编码 (作为 Kafka 分区 Key)", example = "TASK_001")
    private String taskCode;

    @Schema(description = "业务事件类型", example = "DATA_SYNC")
    private String eventType;

    @Schema(description = "业务负载内容", example = "内外网交换数据同步指令")
    private String payload;

    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "消息生成时间")
    private LocalDateTime timestamp;
}
