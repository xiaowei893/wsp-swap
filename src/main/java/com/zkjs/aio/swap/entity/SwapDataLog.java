package com.zkjs.aio.swap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交换数据流水日志实体 (ClickHouse 列式存储)
 * 遵循 AIO Framework 规约：
 * 1. 主键严禁使用 IdType.AUTO，必须使用雪花算法 ASSIGN_ID
 * 2. 仅用于大批量写入与高吞吐统计分析
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("swap_data_log")
@Schema(description = "交换数据流水日志实体 (ClickHouse)")
public class SwapDataLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "分布式雪花算法ID")
    private Long id;

    @Schema(description = "关联的交换任务ID")
    private Long taskId;

    @Schema(description = "交换任务编码", example = "TASK_20261013_001")
    private String taskCode;

    @Schema(description = "交换批次流水号", example = "BATCH_20261013_A01")
    private String batchNo;

    @Schema(description = "本次交换数据条数", example = "5000")
    private Long recordsCount;

    @Schema(description = "传输字节大小", example = "1048576")
    private Long bytesSize;

    @Schema(description = "耗时（毫秒）", example = "350")
    private Long costTimeMs;

    @Schema(description = "交换状态（1-成功, 2-部分失败, 3-异常）", example = "1")
    private Integer status;

    @Schema(description = "异常描述信息")
    private String errorMsg;

    @Schema(description = "日志产生时间")
    private LocalDateTime logTime;

    @Schema(description = "记录入库时间")
    private LocalDateTime createTime;
}
