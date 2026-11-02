package com.zkjs.aio.swap.controller;

import com.zkjs.aio.captcha.annotation.CheckCaptcha;
import com.zkjs.aio.captcha.service.AioCaptchaService;
import com.zkjs.aio.common.enums.RequestStateCode;
import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.satoken.util.StpKit;
import com.zkjs.aio.swap.dto.CaptchaLoginReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为验证码套件集成演示控制器（Spring MVC 体系）
 * <p>
 * 基于 aio-spring-boot-starter-captcha（天爱验证码 Tianai Captcha）Spring MVC 组件构建，
 * 演示声明式二次验证注解 @CheckCaptcha、原子防重放核销以及与 Sa-Token 登录鉴权闭环。
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/captcha")
@RequiredArgsConstructor
@Tag(name = "行为验证码集成演示 (Tianai Captcha)",
     description = "演示滑块/点选/旋转行为验证码挑战、二次校验凭证 validToken、@CheckCaptcha 声明式核销与防重放闭环")
public class CaptchaDemoController {

    private final AioCaptchaService aioCaptchaService;

    // =========================================================================
    // 1. 声明式 @CheckCaptcha 登录核销
    // =========================================================================

    @PostMapping("/login")
    @CheckCaptcha(headerName = "captcha-token")
    @Operation(summary = "1. 用户登录（带行为验证码 @CheckCaptcha 声明式核销）",
               description = "切面自动从 HTTP 请求头 (captcha-token)、查询参数或 DTO 中提取 validToken 执行原子核销；核销通过后完成 Sa-Token 登录")
    public ResultBody<Map<String, Object>> loginWithCaptcha(
            @Valid @RequestBody CaptchaLoginReq req) {
        log.info("【验证码登录】验证码二次核销通过，正在为用户 [{}] 建立登录会话", req.getUsername());

        // 1. 业务用户名密码核验逻辑（演示略过直接登录）
        StpKit.USER.login(req.getUsername());
        String token = StpKit.USER.getTokenValue();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", req.getUsername());
        data.put("tokenName", StpKit.USER.getTokenName());
        data.put("tokenValue", token);
        data.put("timeout", StpKit.USER.getTokenTimeout());
        data.put("tip", "验证码已成功原子核销（单次使用即作废，杜绝暴力重放攻击）");

        return ResultBody.success("人机验证通过，登录成功！", data);
    }

    // =========================================================================
    // 2. 声明式 @CheckCaptcha 敏感操作安全保护
    // =========================================================================

    @PostMapping("/sensitive-action")
    @CheckCaptcha(headerName = "captcha-token")
    @Operation(summary = "2. 敏感操作（带行为验证码二次挑战核销）",
               description = "针对资金变更、配置修改或高危销毁指令，要求必须携带有效的行为验证码凭证")
    public ResultBody<String> sensitiveAction(
            @Parameter(description = "目标任务ID", example = "1001")
            @RequestParam Long taskId,
            @Parameter(description = "执行的高危指令", example = "FORCE_RESET")
            @RequestParam(defaultValue = "FORCE_RESET") String action) {
        log.info("【敏感操作】验证码核销成功，正在对任务 [{}] 执行指令 [{}]", taskId, action);
        return ResultBody.success("任务 [" + taskId + "] 指令 [" + action + "] 在行为验证码保护下安全执行成功");
    }

    // =========================================================================
    // 3. 编程式手动核销
    // =========================================================================

    @PostMapping("/verify-token-manual")
    @Operation(summary = "3. 编程式手动验证并核销凭证",
               description = "通过注入 AioCaptchaService 编程式执行 checkAndConsumeToken，返回是否核销成功")
    public ResultBody<Boolean> manualVerifyToken(
            @Parameter(description = "行为验证码二次校验凭证 validToken")
            @RequestParam String validToken) {
        boolean consumed = aioCaptchaService.checkAndConsumeToken(validToken);
        return consumed
                ? ResultBody.success("凭证有效且已成功核销（该凭证已失效）", true)
                : ResultBody.failure(RequestStateCode.OPERATION_ERROR, "凭证无效、已过期或已被核销");
    }

    // =========================================================================
    // 4. 前端调用全流程规范说明
    // =========================================================================

    @GetMapping("/workflow-guide")
    @Operation(summary = "4. 前端与验证码交互完整规范指南",
               description = "返回天爱验证码在 Spring MVC 底座下的三步式端到端交互流程")
    public ResultBody<List<Map<String, String>>> workflowGuide() {
        List<Map<String, String>> guide = List.of(
                Map.of(
                        "step", "1. 生成挑战",
                        "endpoint", "GET /api/v1/captcha/generate?type=SLIDER",
                        "description", "由 aio-spring-boot-starter-captcha 自动注册的通用接口生成滑块挑战（包含混淆背景图、模板图与挑战ID id）"
                ),
                Map.of(
                        "step", "2. 提交滑动轨迹校验",
                        "endpoint", "POST /api/v1/captcha/verify",
                        "description", "用户在前端完成滑块拖拽后，将挑战ID、拖拽百分比轨迹数据发送给服务端核验；校验通过后服务端签发二次校验凭证 validToken"
                ),
                Map.of(
                        "step", "3. 携带凭证发起核心业务请求",
                        "endpoint", "POST /demo/captcha/login 或 POST /demo/captcha/sensitive-action",
                        "description", "前端在请求头中携带 'captcha-token: <validToken>' 或请求体中传入 captchaToken。切面 @CheckCaptcha 自动原子核销，确保单次有效！"
                )
        );
        return ResultBody.success("获取天爱验证码交互流程指南成功", guide);
    }
}
