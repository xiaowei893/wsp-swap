package com.zkjs.aio.swap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 动态 HTML 字符串模板渲染请求 DTO
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "动态 HTML 字符串模板渲染请求")
public class HtmlTemplateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "模板内容不能为空")
    @Schema(description = "HTML 模板内容源码（支持 Thymeleaf 标签语法）", example = "<html><body><h1 th:text=\"${title}\">标题</h1><p th:text=\"${desc}\">描述</p></body></html>")
    private String templateContent;

    @Schema(description = "动态变量数据模型")
    private Map<String, Object> variables;
}
