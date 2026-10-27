package com.zkjs.aio.swap.crypto.annotation;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;

import java.lang.annotation.*;

/**
 * 实体字段落盘加解密注解
 * 配合 MyBatis-Plus 增删改查拦截器实现透明加解密
 * 加密厂商由系统全局（application.yml / Redis / sys_hsm_config）统一纳管
 *
 * @author wuyw
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CryptoField {

    /**
     * 加密方式
     * 0 - 整段字符加密（CryptoMode.FULL）
     * 1 - 拆分成单个字符加密（CryptoMode.CHAR_BY_CHAR）
     */
    CryptoMode mode() default CryptoMode.FULL;
}
