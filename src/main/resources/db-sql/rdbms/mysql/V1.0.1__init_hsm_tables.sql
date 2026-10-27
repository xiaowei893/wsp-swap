-- -------------------------------------------------------------
-- 密码机全局配置表与数据迁移记录表初始化
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_hsm_config` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `config_key` VARCHAR(64) NOT NULL COMMENT '配置标识键（如 active_vendor）',
    `config_value` VARCHAR(64) NOT NULL COMMENT '配置值（custom/yuweng/yisuo）',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '配置项描述',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='密码机全局配置表';

CREATE TABLE IF NOT EXISTS `sys_hsm_migration_log` (
    `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法）',
    `source_vendor` VARCHAR(32) NOT NULL COMMENT '原厂商编码',
    `target_vendor` VARCHAR(32) NOT NULL COMMENT '新厂商编码',
    `table_name` VARCHAR(64) NOT NULL COMMENT '表名',
    `column_name` VARCHAR(64) NOT NULL COMMENT '字段名',
    `last_processed_id` BIGINT NOT NULL DEFAULT 0 COMMENT '断点游标ID',
    `total_records` BIGINT NOT NULL DEFAULT 0 COMMENT '总条数',
    `processed_records` BIGINT NOT NULL DEFAULT 0 COMMENT '已处理条数',
    `status` VARCHAR(32) NOT NULL COMMENT '状态: RUNNING / COMPLETED / FAILED',
    `error_msg` TEXT DEFAULT NULL COMMENT '异常详情',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_table_col` (`table_name`, `column_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='密码机数据重密迁移断点记录表';

-- -------------------------------------------------------------
-- 自动对加密试点表 swap_task 的字段进行长度扩容并增加加密标签字段
-- -------------------------------------------------------------
ALTER TABLE `swap_task` MODIFY COLUMN `task_name` VARCHAR(512) NOT NULL COMMENT '交换任务名称（整段加密）';
ALTER TABLE `swap_task` MODIFY COLUMN `source_node` VARCHAR(512) DEFAULT NULL COMMENT '源端交换节点（逐字符拆分加密）';
ALTER TABLE `swap_task` ADD COLUMN `encipher_tag` VARCHAR(255) DEFAULT NULL COMMENT '加密标签（字段名:厂商:方式|...，如 taskName:custom:0|sourceNode:plaintext:0）';
ALTER TABLE `swap_task` ADD COLUMN `sign_value` VARCHAR(128) DEFAULT NULL COMMENT '数据完整性防篡改签名值';
ALTER TABLE `swap_task` ADD COLUMN `data_tampered` TINYINT NOT NULL DEFAULT 0 COMMENT '完整性防篡改状态: 0-未检查, 1-正常, 2-已篡改';
