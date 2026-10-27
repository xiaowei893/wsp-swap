package com.zkjs.aio.swap.crypto.annotation;

import java.lang.annotation.*;

/**
 * 数据完整性签名参与字段注解
 * 标记实体中需要参与防篡改签名计算的业务字段，根据 order 属性顺序进行拼接计算签名值
 *
 * @author wuyw
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IntegrityField {

    /**
     * 拼接计算签名的顺序（数值越小越靠前，如 1, 2, 3...）
     */
    int order() default 0;

    /**
     * 当字段为 null 时的拼接替代占位符（默认空字符串）
     */
    String nullValue() default "";
}
