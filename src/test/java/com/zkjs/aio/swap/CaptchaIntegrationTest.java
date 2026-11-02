package com.zkjs.aio.swap;

import com.zkjs.aio.captcha.model.CaptchaGenerateVO;
import com.zkjs.aio.captcha.service.AioCaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行为验证码套件集成验证测试
 * 针对 aio-spring-boot-starter-captcha 官方组件进行挑战生成、存储与 @CheckCaptcha 校验拦截测试
 *
 * @author wuyw
 */
@SpringBootTest
class CaptchaIntegrationTest {

    @Autowired
    private AioCaptchaService aioCaptchaService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("测试1：验证 AioCaptchaService 正确装配并成功生成滑块挑战")
    void testGenerateCaptcha() {
        assertNotNull(aioCaptchaService, "AioCaptchaService 必须由 starter 自动装配");

        CaptchaGenerateVO vo = aioCaptchaService.generateCaptcha("SLIDER");
        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals("SLIDER", vo.getType());
        assertNotNull(vo.getBackgroundImage());
        assertNotNull(vo.getTemplateImage());
    }

    @Test
    @DisplayName("测试2：测试未携带 captcha-token 请求受保护接口时被拦截")
    void testCheckCaptchaInterceptorBlocksMissingToken() throws Exception {
        // 未携带 captcha-token 请求登录接口，切面应当拦截并返回校验失败
        mockMvc.perform(post("/demo/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(not(200)));
    }
}
