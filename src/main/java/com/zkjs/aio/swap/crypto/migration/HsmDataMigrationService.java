package com.zkjs.aio.swap.crypto.migration;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zkjs.aio.swap.crypto.annotation.CryptoField;
import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.envelope.EncipherTagHelper;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import com.zkjs.aio.swap.crypto.service.HsmService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产级密码机全量数据平滑重密清洗引擎
 * 当系统更换密码机厂家时：
 * 1. 采用旧厂家解密历史密文；
 * 2. 采用新厂家加密并重新组装密文信封；
 * 3. 基于主键雪花 ID/自增 ID 游标分页（Keyset Pagination），单批次独立事务提交，杜绝 OOM 与大事务锁表；
 * 4. 具备幂等性（遇断电或中断可安全重跑）。
 *
 * @author wuyw
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HsmDataMigrationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final HsmCryptoDispatcher dispatcher;

    private static final int BATCH_SIZE = 500;
    private static final String ENTITY_PACKAGE = "com.zkjs.aio.swap.entity";

    /**
     * 执行全量重密迁移
     */
    public void executeFullMigration(HsmVendor oldVendor, HsmVendor newVendor) {
        log.warn("===> [HSM Migration] 触发全量数据重密任务: {} -> {}", oldVendor.getCode(), newVendor.getCode());

        HsmService oldService = dispatcher.getService(oldVendor);
        HsmService newService = dispatcher.getService(newVendor);

        if (oldService == null || newService == null) {
            throw new IllegalStateException("缺少迁移所需的服务实现: old=" + oldService + ", new=" + newService);
        }

        List<TableCryptoMeta> tableMetas = scanCryptoTables();
        if (tableMetas.isEmpty()) {
            log.info("===> [HSM Migration] 系统未检测到包含 @CryptoField 的实体表，迁移结束。");
            return;
        }

        for (TableCryptoMeta meta : tableMetas) {
            migrateTable(meta, oldService, newService, newVendor);
        }

        log.info("===> [HSM Migration] 全量数据重密迁移全部顺利完成！");
    }

    private void migrateTable(TableCryptoMeta meta, HsmService oldService, HsmService newService, HsmVendor newVendor) {
        String tableName = meta.getTableName();
        String idCol = meta.getIdColumnName();
        log.info("===> [HSM Migration] 开始处理表 [{}]，加密字段: {}", tableName, meta.getFieldToColumnMap().values());

        // 记录或初始化迁移任务日志
        long migrationLogId = System.currentTimeMillis();
        initMigrationLog(migrationLogId, oldService.getVendor().getCode(), newVendor.getCode(), tableName);

        long lastId = 0L;
        long totalUpdated = 0L;

        // 检查表中是否存在 encipher_tag 列
        boolean hasTagCol = false;
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList("SHOW COLUMNS FROM " + tableName + " LIKE 'encipher_tag'");
            hasTagCol = !cols.isEmpty();
        } catch (Exception ignored) {
        }

        try {
            while (true) {
                // 基于主键游标分页
                String selectCols = String.join(", ", meta.getFieldToColumnMap().values());
                if (hasTagCol) {
                    selectCols += ", encipher_tag";
                }
                String selectSql = String.format("SELECT %s, %s FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT %d",
                        idCol, selectCols, tableName, idCol, idCol, BATCH_SIZE);

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, lastId);
                if (rows.isEmpty()) {
                    break;
                }

                final boolean hasTag = hasTagCol;
                // 单批次独立事务提交
                final long currentLastId = Long.parseLong(rows.get(rows.size() - 1).get(idCol).toString());
                transactionTemplate.execute(status -> {
                    for (Map<String, Object> row : rows) {
                        Object idVal = row.get(idCol);
                        Map<String, Object> updateColumns = new HashMap<>();

                        String tagStr = hasTag && row.get("encipher_tag") != null ? row.get("encipher_tag").toString() : null;
                        Map<String, EncipherTagHelper.TagItem> tagMap = EncipherTagHelper.parseTag(tagStr);
                        List<EncipherTagHelper.TagItem> newTagItems = new ArrayList<>();

                        for (Map.Entry<String, String> entry : meta.getFieldToColumnMap().entrySet()) {
                            String fieldName = entry.getKey();
                            String colName = entry.getValue();
                            CryptoMode mode = meta.getFieldModeMap().get(fieldName);
                            Object rawVal = row.get(colName);

                            EncipherTagHelper.TagItem tagItem = tagMap.get(fieldName);
                            if (tagItem != null && !tagItem.isEncrypted()) {
                                // 存量明文 plaintext，跳过重密
                                newTagItems.add(tagItem);
                                continue;
                            }

                            if (rawVal instanceof String cipherText && StringUtils.hasText(cipherText)) {
                                HsmVendor oldItemVendor = (tagItem != null && StringUtils.hasText(tagItem.vendor()))
                                        ? HsmVendor.of(tagItem.vendor())
                                        : oldService.getVendor();

                                // 幂等：如果该行已经是新厂商加密，无需重复处理
                                if (oldItemVendor == newVendor) {
                                    newTagItems.add(new EncipherTagHelper.TagItem(fieldName, newVendor.getCode(), mode.getMode()));
                                    continue;
                                }

                                HsmService actualOldService = dispatcher.getService(oldItemVendor);
                                String plainText = actualOldService.decrypt(cipherText, mode);

                                // 采用新厂商加密生成纯密文
                                String newRawCipher = newService.encrypt(plainText, mode);
                                updateColumns.put(colName, newRawCipher);
                                newTagItems.add(new EncipherTagHelper.TagItem(fieldName, newVendor.getCode(), mode.getMode()));
                            } else {
                                newTagItems.add(new EncipherTagHelper.TagItem(fieldName, EncipherTagHelper.VENDOR_PLAINTEXT, mode.getMode()));
                            }
                        }

                        if (hasTag && !newTagItems.isEmpty()) {
                            updateColumns.put("encipher_tag", EncipherTagHelper.buildTag(newTagItems));
                        }

                        if (!updateColumns.isEmpty()) {
                            StringBuilder updateSql = new StringBuilder("UPDATE " + tableName + " SET ");
                            List<Object> params = new ArrayList<>();
                            updateColumns.forEach((col, val) -> {
                                updateSql.append(col).append(" = ?, ");
                                params.add(val);
                            });
                            updateSql.setLength(updateSql.length() - 2);
                            updateSql.append(" WHERE ").append(idCol).append(" = ?");
                            params.add(idVal);
                            jdbcTemplate.update(updateSql.toString(), params.toArray());
                        }
                    }
                    return true;
                });

                totalUpdated += rows.size();
                lastId = currentLastId;

                // 更新迁移日志断点
                updateMigrationLogProgress(migrationLogId, lastId, totalUpdated);
                log.info("===> [HSM Migration] 表 [{}] 推进至游标 ID: {}, 已处理: {} 条", tableName, lastId, totalUpdated);
            }

            markMigrationLogSuccess(migrationLogId, totalUpdated);
            log.info("===> [HSM Migration] 表 [{}] 重密完成，总计处理 {} 条", tableName, totalUpdated);

        } catch (Exception e) {
            log.error("===> [HSM Migration] 表 [{}] 重密迁移发生异常！", tableName, e);
            markMigrationLogFailed(migrationLogId, e.getMessage());
            throw new RuntimeException("表 " + tableName + " 密码机重密迁移失败: " + e.getMessage(), e);
        }
    }

    private List<TableCryptoMeta> scanCryptoTables() {
        List<TableCryptoMeta> list = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + ENTITY_PACKAGE.replace('.', '/') + "/**/*.class");
            for (Resource resource : resources) {
                String className = resolveClassName(resource);
                if (className == null) continue;
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(TableName.class)) {
                    TableCryptoMeta meta = extractMeta(clazz);
                    if (meta != null && !meta.getFieldToColumnMap().isEmpty()) {
                        list.add(meta);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[HSM Migration] 扫描加密实体类异常", e);
        }
        return list;
    }

    private TableCryptoMeta extractMeta(Class<?> clazz) {
        TableName tn = clazz.getAnnotation(TableName.class);
        String tableName = tn.value();
        String idCol = "id";

        Map<String, String> fieldToCol = new HashMap<>();
        Map<String, CryptoMode> modeMap = new HashMap<>();

        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(TableId.class)) {
                TableId tid = f.getAnnotation(TableId.class);
                if (StringUtils.hasText(tid.value())) {
                    idCol = tid.value();
                } else {
                    idCol = camelToSnake(f.getName());
                }
            }
            if (f.isAnnotationPresent(CryptoField.class)) {
                CryptoField cf = f.getAnnotation(CryptoField.class);
                String colName = camelToSnake(f.getName());
                if (f.isAnnotationPresent(TableField.class)) {
                    TableField tf = f.getAnnotation(TableField.class);
                    if (StringUtils.hasText(tf.value()) && tf.exist()) {
                        colName = tf.value();
                    }
                }
                fieldToCol.put(f.getName(), colName);
                modeMap.put(f.getName(), cf.mode());
            }
        }
        return new TableCryptoMeta(tableName, idCol, fieldToCol, modeMap);
    }

    private String resolveClassName(Resource resource) {
        try {
            String uri = resource.getURI().toString();
            int idx = uri.indexOf("com/zkjs/aio/swap/entity");
            if (idx == -1) return null;
            return uri.substring(idx).replace('/', '.').replace(".class", "");
        } catch (Exception e) {
            return null;
        }
    }

    private String camelToSnake(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    private void initMigrationLog(long id, String oldVendor, String newVendor, String tableName) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO sys_hsm_migration_log (id, source_vendor, target_vendor, table_name, column_name, status) " +
                            "VALUES (?, ?, ?, ?, 'ALL_CRYPTO_COLS', 'RUNNING')",
                    id, oldVendor, newVendor, tableName
            );
        } catch (Exception ignored) {
        }
    }

    private void updateMigrationLogProgress(long id, long lastId, long processed) {
        try {
            jdbcTemplate.update(
                    "UPDATE sys_hsm_migration_log SET last_processed_id = ?, processed_records = ? WHERE id = ?",
                    lastId, processed, id
            );
        } catch (Exception ignored) {
        }
    }

    private void markMigrationLogSuccess(long id, long total) {
        try {
            jdbcTemplate.update(
                    "UPDATE sys_hsm_migration_log SET status = 'COMPLETED', total_records = ?, processed_records = ? WHERE id = ?",
                    total, total, id
            );
        } catch (Exception ignored) {
        }
    }

    private void markMigrationLogFailed(long id, String error) {
        try {
            jdbcTemplate.update(
                    "UPDATE sys_hsm_migration_log SET status = 'FAILED', error_msg = ? WHERE id = ?",
                    error, id
            );
        } catch (Exception ignored) {
        }
    }

    @Data
    @AllArgsConstructor
    private static class TableCryptoMeta {
        private String tableName;
        private String idColumnName;
        private Map<String, String> fieldToColumnMap;
        private Map<String, CryptoMode> fieldModeMap;
    }
}
