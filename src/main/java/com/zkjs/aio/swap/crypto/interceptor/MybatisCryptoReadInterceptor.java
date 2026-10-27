package com.zkjs.aio.swap.crypto.interceptor;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.envelope.EncipherTagHelper;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus 查询结果透明解密拦截器
 * 拦截 ResultSetHandler.handleResultSets 方法，对查询返回的所有含有 @CryptoField 的实体属性自动解密
 *
 * @author wuyw
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {Statement.class})
})
public class MybatisCryptoReadInterceptor implements Interceptor {

    private final HsmCryptoDispatcher dispatcher;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        if (result == null) {
            return null;
        }

        if (result instanceof Collection<?> collection) {
            for (Object obj : collection) {
                decryptEntity(obj);
            }
        } else {
            decryptEntity(result);
        }

        return result;
    }

    private void decryptEntity(Object entity) {
        if (entity == null) {
            return;
        }
        List<CryptoMetadataCache.CryptoFieldMeta> metas = CryptoMetadataCache.getCryptoFields(entity.getClass());
        if (metas.isEmpty()) {
            return;
        }

        // 解析实体的 encipherTag 标签
        Map<String, EncipherTagHelper.TagItem> tagMap = null;
        Field tagField = CryptoMetadataCache.getTagField(entity.getClass());
        if (tagField != null) {
            try {
                Object tagVal = tagField.get(entity);
                if (tagVal instanceof String tagStr) {
                    tagMap = EncipherTagHelper.parseTag(tagStr);
                }
            } catch (Exception e) {
                log.warn("[HSM] 读取实体 encipherTag 异常: {}", e.getMessage());
            }
        }

        for (CryptoMetadataCache.CryptoFieldMeta meta : metas) {
            Field field = meta.getField();
            try {
                Object val = field.get(entity);
                if (val instanceof String cipherText && !cipherText.isEmpty()) {
                    // 若存在 encipherTag，按标签内记录的厂商与模式进行解密
                    if (tagMap != null && tagMap.containsKey(field.getName())) {
                        EncipherTagHelper.TagItem tagItem = tagMap.get(field.getName());
                        // 若标记为 plaintext（明文原文），则跳过解密，原样展示
                        if (!tagItem.isEncrypted()) {
                            continue;
                        }
                        HsmVendor vendor = HsmVendor.of(tagItem.vendor());
                        CryptoMode mode = CryptoMode.of(tagItem.mode());
                        String plainText = dispatcher.decrypt(cipherText, vendor, mode);
                        field.set(entity, plainText);
                    } else {
                        // 兜底：无标签时按当前全局厂商与字段注解模式解密
                        String plainText = dispatcher.decrypt(cipherText, meta.getAnnotation().mode());
                        field.set(entity, plainText);
                    }
                }
            } catch (Exception e) {
                log.error("[HSM] 读取解密拦截失败: entity={}, field={}", entity.getClass().getSimpleName(), field.getName(), e);
            }
        }
    }
}
