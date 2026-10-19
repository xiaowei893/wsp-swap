package com.zkjs.aio.swap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 演示请求入参 DTO
 *
 * @author wuyw
 */
@Data
@Schema(description = "演示请求参数")
public class DemoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务名称/标识", example = "交换任务A", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "业务名称不能为空")
    private String name;

    @Schema(description = "请求内容/消息", example = "测试数据推送", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String message;
}
