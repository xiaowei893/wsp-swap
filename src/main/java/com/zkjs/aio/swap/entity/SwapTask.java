package com.zkjs.aio.swap.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zkjs.aio.crypto.annotation.*;
import com.zkjs.aio.crypto.enums.CryptoMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交换部署任务实体 (MySQL)
 * 集成 aio-spring-boot-starter-crypto 国密透明加解密与防篡改统一组件
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
    @Schema(description = "主键ID（雪花算法，前端接收为 String 防精度丢失）", example = "2112736289972080641")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @IntegrityField(order = 1)
    @Schema(description = "任务唯一标识编码", example = "TASK_20261013_001")
    private String taskCode;

    @IntegrityField(order = 2)
    @CryptoField(mode = CryptoMode.FULL)
    @Schema(description = "交换任务名称（整段 SM4 加密）", example = "内外网业务同步任务")
    private String taskName;

    @Schema(description = "任务类型", example = "SYNC")
    private String taskType;

    @IntegrityField(order = 3)
    @CryptoField(mode = CryptoMode.CHAR_BY_CHAR)
    @Schema(description = "源端交换节点（逐字符拆分 SM4 加密，支持密文模糊检索）", example = "NODE_INTERNAL_01")
    private String sourceNode;

    @IntegrityField(order = 4)
    @Schema(description = "目标端交换节点", example = "NODE_EXTERNAL_01")
    private String targetNode;

    @IntegrityField(order = 5)
    @Schema(description = "任务状态（0-停用, 1-启用, 2-执行中）", example = "1")
    private Integer status;

    @Schema(description = "备注说明", example = "日常同步")
    private String remark;

    @EncipherTag
    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "加密元数据标签（记录字段:厂商:方式，如 taskName:custom:0|sourceNode:custom:1）", example = "taskName:custom:0|sourceNode:custom:1")
    private String encipherTag;

    @IntegritySign
    @TableField(value = "sign_value", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "数据完整性防篡改签名值（国密 SM3-HMAC 计算）", example = "a1b2c3d4...")
    private String signValue;

    @IntegrityStatus
    @TableField(value = "data_tampered", insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "数据完整性防篡改状态：0-未检查，1-正常，2-篡改锁定", example = "0")
    private Integer dataTampered;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
