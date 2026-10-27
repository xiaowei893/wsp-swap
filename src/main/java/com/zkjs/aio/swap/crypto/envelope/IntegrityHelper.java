package com.zkjs.aio.swap.crypto.envelope;

import com.zkjs.aio.swap.crypto.interceptor.IntegrityMetadataCache;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 数据完整性待签内容拼接辅助工具类
 * 规范：按照 @IntegrityField 的 order 升序排列，以 |#| 为分隔符进行有序拼接
 *
 * @author wuyw
 */
@Slf4j
public final class IntegrityHelper {

    public static final String FIELD_DELIMITER = "|#|";

    private IntegrityHelper() {
    }

    /**
     * 根据实体的 @IntegrityField 顺序拼接待签名明文字符串
     *
     * @param entity 目标实体
     * @return 拼接好的待签名字符串；若无字段则返回 null
     */
    public static String buildPlainString(Object entity) {
        if (entity == null) {
            return null;
        }
        List<IntegrityMetadataCache.IntegrityFieldMeta> metas = IntegrityMetadataCache.getIntegrityFields(entity.getClass());
        if (metas.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < metas.size(); i++) {
            IntegrityMetadataCache.IntegrityFieldMeta meta = metas.get(i);
            Field field = meta.getField();
            try {
                Object val = field.get(entity);
                String valStr = (val == null) ? meta.getAnnotation().nullValue() : String.valueOf(val);
                sb.append(valStr);
            } catch (Exception e) {
                log.warn("[HSM Integrity] 获取字段 {} 属性值异常: {}", field.getName(), e.getMessage());
                sb.append(meta.getAnnotation().nullValue());
            }
            if (i < metas.size() - 1) {
                sb.append(FIELD_DELIMITER);
            }
        }
        return sb.toString();
    }
}
