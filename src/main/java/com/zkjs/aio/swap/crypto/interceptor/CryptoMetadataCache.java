package com.zkjs.aio.swap.crypto.interceptor;

import com.zkjs.aio.swap.crypto.annotation.CryptoField;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加密实体元数据高性能反射缓存
 * 避免在每一次 SQL 增删改查时重复进行高开销的反射查找
 *
 * @author wuyw
 */
public final class CryptoMetadataCache {

    private static final Map<Class<?>, List<CryptoFieldMeta>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> TAG_FIELD_CACHE = new ConcurrentHashMap<>();

    private CryptoMetadataCache() {
    }

    /**
     * 获取指定类中包含 @CryptoField 注解的属性元数据列表
     */
    public static List<CryptoFieldMeta> getCryptoFields(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.") || clazz.getName().startsWith("javax.")) {
            return Collections.emptyList();
        }

        return CACHE.computeIfAbsent(clazz, k -> {
            List<CryptoFieldMeta> metas = new ArrayList<>();
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(CryptoField.class)) {
                        field.setAccessible(true);
                        CryptoField annotation = field.getAnnotation(CryptoField.class);
                        metas.add(new CryptoFieldMeta(field, annotation));
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            return Collections.unmodifiableList(metas);
        });
    }

    /**
     * 获取指定类中的 encipherTag 字段（若存在）
     */
    public static Field getTagField(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.") || clazz.getName().startsWith("javax.")) {
            return null;
        }

        return TAG_FIELD_CACHE.computeIfAbsent(clazz, k -> {
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if ("encipherTag".equals(field.getName()) || "encipher_tag".equalsIgnoreCase(field.getName())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            return null;
        });
    }

    @Getter
    @AllArgsConstructor
    public static class CryptoFieldMeta {
        private final Field field;
        private final CryptoField annotation;
    }
}
