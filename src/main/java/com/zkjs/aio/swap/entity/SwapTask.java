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
 * 交换部署任务实体 (MySQL)
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("swap_task")
@Schema(description = "交换部署任务实体 (MySQL)")
public class SwapTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID（雪花算法）")
    private Long id;

    @Schema(description = "任务唯一标识编码", example = "TASK_20261013_001")
    private String taskCode;

    @Schema(description = "交换任务名称", example = "内外网业务同步任务")
    private String taskName;

    @Schema(description = "任务类型", example = "SYNC")
    private String taskType;

    @Schema(description = "源端交换节点", example = "NODE_INTERNAL_01")
    private String sourceNode;

    @Schema(description = "目标端交换节点", example = "NODE_EXTERNAL_01")
    private String targetNode;

    @Schema(description = "任务状态（0-停用, 1-启用, 2-执行中）", example = "1")
    private Integer status;

    @Schema(description = "备注说明", example = "日常同步")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
