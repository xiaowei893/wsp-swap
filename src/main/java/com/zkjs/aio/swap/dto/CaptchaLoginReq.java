package com.zkjs.aio.swap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 带有行为验证码二次凭证的登录请求 DTO
 *
 * @author wuyw
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "行为验证码登录请求体")
public class CaptchaLoginReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "登录用户名", example = "admin")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码", example = "Admin@123456")
    private String password;

    @Schema(description = "行为验证码二次核销凭证 (亦可在 HTTP Header captcha-token 中携带)", example = "c4ca4238a0b923820dcc509a6f75849b")
    private String captchaToken;
}
