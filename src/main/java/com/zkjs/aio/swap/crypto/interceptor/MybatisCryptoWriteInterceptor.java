package com.zkjs.aio.swap.crypto.interceptor;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zkjs.aio.common.exception.BusinessException;
import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.envelope.EncipherTagHelper;
import com.zkjs.aio.swap.crypto.envelope.IntegrityHelper;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;

/**
 * MyBatis-Plus 写入/更新透明加密与数据完整性防篡改拦截器
 * 拦截 Executor.update 方法，在构建 BoundSql 与执行入库前：
 * 1. 【防篡改拦截】：更新操作前强校验数据库真实 data_tampered，若为 2（已篡改）则严禁更新并抛出 BusinessException 提示用户；
 * 2. 【完整性签名】：按 @IntegrityField 的 order 顺序拼接明文字段，调用当前激活密码厂商的算法计算签名存入 sign_value；
 * 3. 【透明加密】：对含有 @CryptoField 的属性自动加密，自动计算并注入 encipher_tag；
 * 4. 【自动还原】：在 finally 块中立即将内存实体还原为明文，保证上层调用方对象纯净。
 *
 * @author wuyw
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class MybatisCryptoWriteInterceptor implements Interceptor {

    private final HsmCryptoDispatcher dispatcher;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameterObject = invocation.getArgs()[1];
        SqlCommandType commandType = ms.getSqlCommandType();

        List<RestoreAction> restoreActions = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            if (parameterObject != null) {
                processObject(parameterObject, commandType, restoreActions, visited);
            }
            return invocation.proceed();
        } finally {
            // 确保在入库完成后将内存中的实体属性还原为明文
            for (int i = restoreActions.size() - 1; i >= 0; i--) {
                restoreActions.get(i).restore();
            }
        }
    }

    private void processObject(Object param, SqlCommandType commandType, List<RestoreAction> restoreActions, Set<Object> visited) {
        if (param == null || !visited.add(param)) {
            return;
        }
        if (param instanceof Map<?, ?> map) {
            for (Object val : map.values()) {
                processObject(val, commandType, restoreActions, visited);
            }
        } else if (param instanceof Collection<?> col) {
            for (Object item : col) {
                processObject(item, commandType, restoreActions, visited);
            }
        } else {
            handleEntity(param, commandType, restoreActions);
        }
    }

    private void handleEntity(Object entity, SqlCommandType commandType, List<RestoreAction> restoreActions) {
        if (entity == null) {
            return;
        }

        // =========================================================================
        // 1. 完整性处理：防篡改检查与签名计算
        // =========================================================================
        handleIntegrity(entity, commandType);

        // =========================================================================
        // 2. 敏感数据加密处理
        // =========================================================================
        handleEncryption(entity, restoreActions);
    }

    /**
     * 数据完整性防篡改检查与签名计算
     */
    private void handleIntegrity(Object entity, SqlCommandType commandType) {
        List<IntegrityMetadataCache.IntegrityFieldMeta> integrityFields = IntegrityMetadataCache.getIntegrityFields(entity.getClass());
        if (integrityFields.isEmpty()) {
            return;
        }

        // 1. 若为 UPDATE 操作，前置强校验数据库当前 data_tampered 状态
        if (commandType == SqlCommandType.UPDATE) {
            Field idField = IntegrityMetadataCache.getIdField(entity.getClass());
            if (idField != null) {
                try {
                    Object idVal = idField.get(entity);
                    if (idVal != null) {
                        checkTamperStatus(entity.getClass(), idVal);
                    }
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    log.warn("[HSM Integrity] 获取实体主键校验篡改状态异常: {}", e.getMessage());
                }
            }
        }

        // 2. 根据当前明文实体属性，按 @IntegrityField 的 order 顺序拼接待签字符串
        String plainContent = IntegrityHelper.buildPlainString(entity);
        if (plainContent != null) {
            // 调用当前激活密码厂商的密码算法计算签名
            String sign = dispatcher.sign(plainContent);

            // 写入 sign_value
            Field signField = IntegrityMetadataCache.getSignField(entity.getClass());
            if (signField != null) {
                try {
                    signField.set(entity, sign);
                } catch (Exception e) {
                    log.error("[HSM Integrity] 注入 signValue 失败: {}", e.getMessage());
                }
            }

            // 初始化或重置 data_tampered 为 0（未检查）
            Field statusField = IntegrityMetadataCache.getStatusField(entity.getClass());
            if (statusField != null) {
                try {
                    statusField.set(entity, 0);
                } catch (Exception e) {
                    log.error("[HSM Integrity] 注入 dataTampered 失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 校验数据库中当前记录是否已被篡改
     */
    private void checkTamperStatus(Class<?> entityClass, Object idVal) {
        String tableName = getTableName(entityClass);
        String sql = "SELECT data_tampered FROM " + tableName + " WHERE id = ?";
        try {
            List<Integer> list = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt("data_tampered"), idVal);
            if (!list.isEmpty() && Integer.valueOf(2).equals(list.get(0))) {
                String msg = "【操作禁止】当前记录 [ID=" + idVal + "] 数据已被篡改（data_tampered=2），系统已锁定，严禁更新！请联系安全管理员核查。";
                log.error("[HSM Integrity] 拦截到对已篡改数据的修改请求: table={}, id={}", tableName, idVal);
                throw BusinessException.of(msg);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("[HSM Integrity] 查询表 {} 记录 ID={} 防篡改状态异常: {}", tableName, idVal, e.getMessage());
        }
    }

    private String getTableName(Class<?> clazz) {
        TableName tableNameAnno = clazz.getAnnotation(TableName.class);
        if (tableNameAnno != null && !tableNameAnno.value().isEmpty()) {
            return tableNameAnno.value();
        }
        return clazz.getSimpleName().toLowerCase();
    }

    /**
     * 实体属性透明加密处理
     */
    private void handleEncryption(Object entity, List<RestoreAction> restoreActions) {
        List<CryptoMetadataCache.CryptoFieldMeta> metas = CryptoMetadataCache.getCryptoFields(entity.getClass());
        if (metas.isEmpty()) {
            return;
        }

        List<EncipherTagHelper.TagItem> tagItems = new ArrayList<>();
        String currentVendor = dispatcher.getActiveVendor().getCode();

        for (CryptoMetadataCache.CryptoFieldMeta meta : metas) {
            Field field = meta.getField();
            CryptoMode mode = meta.getAnnotation().mode();
            try {
                Object val = field.get(entity);
                if (val instanceof String plainText && !plainText.isEmpty()) {
                    String cipher = dispatcher.encrypt(plainText, mode);
                    restoreActions.add(new RestoreAction(field, entity, plainText));
                    field.set(entity, cipher);
                    tagItems.add(new EncipherTagHelper.TagItem(field.getName(), currentVendor, mode.getMode()));
                } else {
                    tagItems.add(new EncipherTagHelper.TagItem(field.getName(), EncipherTagHelper.VENDOR_PLAINTEXT, mode.getMode()));
                }
            } catch (Exception e) {
                log.error("[HSM] 写入加密拦截失败: entity={}, field={}", entity.getClass().getSimpleName(), field.getName(), e);
                tagItems.add(new EncipherTagHelper.TagItem(field.getName(), EncipherTagHelper.VENDOR_PLAINTEXT, mode.getMode()));
            }
        }

        // 自动注入 encipherTag
        Field tagField = CryptoMetadataCache.getTagField(entity.getClass());
        if (tagField != null) {
            try {
                String newTag = EncipherTagHelper.buildTag(tagItems);
                tagField.set(entity, newTag);
            } catch (Exception e) {
                log.error("[HSM] 写入更新 encipherTag 失败: entity={}", entity.getClass().getSimpleName(), e);
            }
        }
    }

    private record RestoreAction(Field field, Object target, String originalPlainText) {
        public void restore() {
            try {
                field.set(target, originalPlainText);
            } catch (Exception ignored) {
            }
        }
    }
}
