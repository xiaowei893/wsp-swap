package com.zkjs.aio.swap.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.DateTimeFormat;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交换部署任务 Excel 导出模型（基于 EasyExcel 4.x 注解）
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交换任务 Excel 导出模型")
public class SwapTaskExportVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "任务主键ID", index = 0)
    @ColumnWidth(22)
    @Schema(description = "任务ID", example = "1978234872938472938")
    private Long id;

    @ExcelProperty(value = "任务唯一编码", index = 1)
    @ColumnWidth(24)
    @Schema(description = "任务编码", example = "TASK_20261016_001")
    private String taskCode;

    @ExcelProperty(value = "任务名称", index = 2)
    @ColumnWidth(28)
    @Schema(description = "任务名称", example = "跨网闸文件同步任务")
    private String taskName;

    @ExcelProperty(value = "任务类型", index = 3)
    @ColumnWidth(16)
    @Schema(description = "任务类型", example = "SYNC")
    private String taskType;

    @ExcelProperty(value = "源端节点", index = 4)
    @ColumnWidth(20)
    @Schema(description = "源端节点", example = "NODE_INTERNAL_01")
    private String sourceNode;

    @ExcelProperty(value = "目标端节点", index = 5)
    @ColumnWidth(20)
    @Schema(description = "目标端节点", example = "NODE_EXTERNAL_01")
    private String targetNode;

    @ExcelProperty(value = "运行状态", index = 6)
    @ColumnWidth(14)
    @Schema(description = "运行状态", example = "启用")
    private String statusDesc;

    @ExcelProperty(value = "同步成功率", index = 7)
    @ColumnWidth(16)
    @Schema(description = "成功率", example = "99.98%")
    @Builder.Default
    private String successRate = "100.00%";

    @ExcelProperty(value = "备注说明", index = 8)
    @ColumnWidth(30)
    @Schema(description = "备注说明", example = "每日定时同步")
    private String remark;

    @ExcelProperty(value = "创建时间", index = 9)
    @ColumnWidth(22)
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
