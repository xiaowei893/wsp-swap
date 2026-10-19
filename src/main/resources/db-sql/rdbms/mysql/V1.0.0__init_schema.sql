-- -------------------------------------------------------------
-- 一体化交换部署服务 MySQL 业务主表初始化
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `swap_task` (
    `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法）',
    `task_code` VARCHAR(64) NOT NULL COMMENT '任务唯一标识编码',
    `task_name` VARCHAR(128) NOT NULL COMMENT '交换任务名称',
    `task_type` VARCHAR(32) NOT NULL DEFAULT 'SYNC' COMMENT '任务类型（SYNC-同步, BACKUP-备份, FORWARD-转发）',
    `source_node` VARCHAR(64) DEFAULT NULL COMMENT '源端交换节点',
    `target_node` VARCHAR(64) DEFAULT NULL COMMENT '目标端交换节点',
    `status` INT NOT NULL DEFAULT 1 COMMENT '任务状态（0-停用, 1-启用, 2-执行中）',
    `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注说明',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_code` (`task_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交换部署任务主表';
