package com.zkjs.aio.swap.crypto.interceptor;

import com.baomidou.mybatisplus.annotation.TableId;
import com.zkjs.aio.swap.crypto.annotation.IntegrityField;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 完整性防篡改元数据高性能反射缓存
 *
 * @author wuyw
 */
public final class IntegrityMetadataCache {

    private static final Map<Class<?>, List<IntegrityFieldMeta>> INTEGRITY_FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> SIGN_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> STATUS_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    private IntegrityMetadataCache() {
    }

    /**
     * 获取指定类中按 order 升序排序的 @IntegrityField 字段元数据列表
     */
    public static List<IntegrityFieldMeta> getIntegrityFields(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.") || clazz.getName().startsWith("javax.")) {
            return Collections.emptyList();
        }

        return INTEGRITY_FIELDS_CACHE.computeIfAbsent(clazz, k -> {
            List<IntegrityFieldMeta> metas = new ArrayList<>();
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(IntegrityField.class)) {
                        field.setAccessible(true);
                        IntegrityField annotation = field.getAnnotation(IntegrityField.class);
                        metas.add(new IntegrityFieldMeta(field, annotation));
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            // 按 order 升序排序
            metas.sort(Comparator.comparingInt(m -> m.getAnnotation().order()));
            return Collections.unmodifiableList(metas);
        });
    }

    /**
     * 获取签名值存放字段 (signValue 或 sign_value)
     */
    public static Field getSignField(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.")) {
            return null;
        }

        return SIGN_FIELD_CACHE.computeIfAbsent(clazz, k -> {
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if ("signValue".equals(field.getName()) || "sign_value".equalsIgnoreCase(field.getName())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            return null;
        });
    }

    /**
     * 获取防篡改状态存放字段 (dataTampered 或 data_tampered)
     */
    public static Field getStatusField(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.")) {
            return null;
        }

        return STATUS_FIELD_CACHE.computeIfAbsent(clazz, k -> {
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if ("dataTampered".equals(field.getName()) || "data_tampered".equalsIgnoreCase(field.getName())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
            return null;
        });
    }

    /**
     * 获取实体主键 ID 字段
     */
    public static Field getIdField(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.getName().startsWith("java.")) {
            return null;
        }

        return ID_FIELD_CACHE.computeIfAbsent(clazz, k -> {
            Class<?> currentClass = k;
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(TableId.class) || "id".equalsIgnoreCase(field.getName())) {
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
    public static class IntegrityFieldMeta {
        private final Field field;
        private final IntegrityField annotation;
    }
}
