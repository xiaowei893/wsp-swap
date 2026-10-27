package com.zkjs.aio.swap.crypto.init;

import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import com.zkjs.aio.swap.crypto.migration.HsmDataMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 密码机启动生命周期初始化器
 * 遵循核心规范：
 * 1. 启动检查 application.yml 中 [hsm.vendor]，若为空则直接抛异常阻断服务启动；
 * 2. 比对配置文件与数据库 sys_hsm_config：
 *    - 若数据库为空，将配置文件厂商写入数据库并同步至 Redis 缓存；
 *    - 若一致，同步刷新 Redis 缓存；
 *    - 若不一致，触发全量数据旧解密->新加密平滑重密迁移，成功后更新数据库与 Redis。
 *
 * @author wuyw
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class HsmConfigInitializer implements ApplicationRunner {

    @Value("${hsm.vendor:}")
    private String configVendorStr;

    private final JdbcTemplate jdbcTemplate;
    private final HsmCryptoDispatcher dispatcher;
    private final HsmDataMigrationService migrationService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=================================================================");
        log.info("[HSM] 启动执行商用密码机配置双向校验与初始化流程...");

        // 1. 强校验：配置文件厂商不能为空
        if (!StringUtils.hasText(configVendorStr)) {
            String errorMsg = "[HSM] 严重启动阻断：配置文件中密码机厂家 [hsm.vendor] 不能为空！请在 application.yml 中配置（如: custom / yuweng / yisuo）";
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        HsmVendor configVendor = HsmVendor.of(configVendorStr.trim());
        log.info("[HSM] 当前配置文件声明的密码机厂商: {} ({})", configVendor.getCode(), configVendor.getDesc());

        // 2. 保证配置表存在（防御性检查）
        ensureConfigTableExists();

        // 3. 读取数据库中的激活厂商
        String dbVendorStr = queryDbVendor();

        if (!StringUtils.hasText(dbVendorStr)) {
            // 场景 1：数据库中密码厂家为空 -> 写入数据库并同步至 Redis
            log.info("[HSM] 场景一：数据库中密码机厂商为空，写入配置厂商 [{}] 并同步至 Redis", configVendor.getCode());
            saveDbVendor(configVendor.getCode());
            dispatcher.setActiveVendorCache(configVendor);
        } else {
            HsmVendor dbVendor = HsmVendor.of(dbVendorStr.trim());
            if (dbVendor == configVendor) {
                // 场景 2：配置文件与数据库一致 -> 同步至 Redis
                log.info("[HSM] 场景二：数据库与配置文件厂商一致 [{}]，刷新 Redis 缓存状态", configVendor.getCode());
                dispatcher.setActiveVendorCache(configVendor);
            } else {
                // 场景 3：配置文件与数据库不一致 -> 触发全量数据平滑重密迁移
                log.warn("[HSM] 场景三：检测到密码机厂商切换！DB原厂商=[{}] -> 配置文件新厂商=[{}]，启动数据清洗重密！",
                        dbVendor.getCode(), configVendor.getCode());

                // 执行全量平滑重密迁移
                migrationService.executeFullMigration(dbVendor, configVendor);

                // 迁移成功后更新数据库并同步 Redis
                updateDbVendor(configVendor.getCode());
                dispatcher.setActiveVendorCache(configVendor);
                log.info("[HSM] 密码机厂商切换与平滑重密全部完成！数据库与 Redis 已同步更新为: [{}]", configVendor.getCode());
            }
        }
        log.info("=================================================================");
    }

    private void ensureConfigTableExists() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS sys_hsm_config (" +
                            "id BIGINT NOT NULL, " +
                            "config_key VARCHAR(64) NOT NULL, " +
                            "config_value VARCHAR(64) NOT NULL, " +
                            "description VARCHAR(255) DEFAULT NULL, " +
                            "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                            "PRIMARY KEY (id), " +
                            "UNIQUE KEY uk_config_key (config_key)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
            );
        } catch (Exception e) {
            log.warn("[HSM] 校验/创建 sys_hsm_config 表异常（可忽略）: {}", e.getMessage());
        }

        try {
            // 自动对加密试点表执行安全扩容，彻底避免存量 VARCHAR(64) 触发截断
            jdbcTemplate.execute("ALTER TABLE swap_task MODIFY COLUMN task_name VARCHAR(512) NOT NULL COMMENT '交换任务名称'");
            jdbcTemplate.execute("ALTER TABLE swap_task MODIFY COLUMN source_node VARCHAR(512) DEFAULT NULL COMMENT '源端交换节点'");
            try {
                jdbcTemplate.execute("ALTER TABLE swap_task ADD COLUMN encipher_tag VARCHAR(255) DEFAULT NULL COMMENT '加密标签'");
            } catch (Exception ignored) {
                // 已存在时忽略
            }
            try {
                jdbcTemplate.execute("ALTER TABLE swap_task ADD COLUMN sign_value VARCHAR(128) DEFAULT NULL COMMENT '数据完整性签名值'");
            } catch (Exception ignored) {
            }
            try {
                jdbcTemplate.execute("ALTER TABLE swap_task ADD COLUMN data_tampered TINYINT NOT NULL DEFAULT 0 COMMENT '防篡改状态: 0-未检查, 1-正常, 2-篡改'");
            } catch (Exception ignored) {
            }
            log.info("[HSM] 检查并扩容 swap_task 加密列、标签列与防篡改签名列完成");
        } catch (Exception e) {
            log.debug("[HSM] 尝试扩容 swap_task 表列异常（可能表暂未创建或已扩容）: {}", e.getMessage());
        }
    }

    private String queryDbVendor() {
        try {
            List<String> list = jdbcTemplate.query(
                    "SELECT config_value FROM sys_hsm_config WHERE config_key = 'active_vendor'",
                    (rs, rowNum) -> rs.getString("config_value")
            );
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.warn("[HSM] 查询 sys_hsm_config 配置表异常: {}", e.getMessage());
            return null;
        }
    }

    private void saveDbVendor(String vendorCode) {
        jdbcTemplate.update(
                "INSERT INTO sys_hsm_config (id, config_key, config_value, description) VALUES (1, 'active_vendor', ?, '当前密码机激活厂商') " +
                        "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)",
                vendorCode
        );
    }

    private void updateDbVendor(String vendorCode) {
        jdbcTemplate.update(
                "UPDATE sys_hsm_config SET config_value = ? WHERE config_key = 'active_vendor'",
                vendorCode
        );
    }
}
