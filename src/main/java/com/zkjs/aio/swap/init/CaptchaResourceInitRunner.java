package com.zkjs.aio.swap.init;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.constant.CommonConstant;
import cloud.tianai.captcha.resource.CrudResourceStore;
import cloud.tianai.captcha.resource.FontCache;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 行为验证码切图底图与资源自动化预热初始化器
 * <p>
 * Tianai-Captcha 默认模板只包含滑块凹槽和缺口模板，底图（background image）由业务方自由扩充。
 * 为保障开箱即用，在此自检并注册 starter 内置的精美底图至当前 ResourceStore（支持 LocalMemory/Redis）。
 * </p>
 *
 * @author wuyw
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class CaptchaResourceInitRunner implements ApplicationRunner {

    private final ResourceStore resourceStore;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[CaptchaResourceInit] 正在自检并预热行为验证码底图资源...");
        try {
            ResourceStore targetStore = resourceStore;
            if (targetStore instanceof FontCache fontCache && fontCache.getTarget() != null) {
                targetStore = fontCache.getTarget();
            }

            if (targetStore instanceof CrudResourceStore crudStore) {
                List<Resource> sliderResources = crudStore.listResourcesByTypeAndTag(CaptchaTypeConstant.SLIDER, CommonConstant.DEFAULT_TAG);
                if (sliderResources == null || sliderResources.isEmpty()) {
                    log.info("[CaptchaResourceInit] 发现底图资源为空，自动预热内置精美切图资源至当前 ResourceStore ({})...", crudStore.getClass().getSimpleName());
                    Resource defaultBg = new Resource("classpath", "META-INF/cut-image/resource/1.jpg", CommonConstant.DEFAULT_TAG);
                    crudStore.addResource(CaptchaTypeConstant.SLIDER, defaultBg);
                    crudStore.addResource(CaptchaTypeConstant.ROTATE, defaultBg);
                    crudStore.addResource(CaptchaTypeConstant.CONCAT, defaultBg);
                    crudStore.addResource(CaptchaTypeConstant.WORD_IMAGE_CLICK, defaultBg);
                    log.info("[CaptchaResourceInit] 行为验证码切图底图注册完成，验证码生成服务已就绪！");
                } else {
                    log.info("[CaptchaResourceInit] 验证码切图底图已存在 ({} 张)，跳过注入。", sliderResources.size());
                }
            } else {
                log.warn("[CaptchaResourceInit] 当前 ResourceStore ({}) 不支持动态写入，跳过注入。", targetStore.getClass().getName());
            }
        } catch (Exception e) {
            log.warn("[CaptchaResourceInit] 验证码资源初始化异常: {}", e.getMessage(), e);
        }
    }
}
