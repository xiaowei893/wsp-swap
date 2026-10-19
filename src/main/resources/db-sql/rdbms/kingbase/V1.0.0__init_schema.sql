-- -------------------------------------------------------------
-- 一体化交换部署服务 人大金仓数据库 (KingbaseES) 业务主表初始化
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS swap_task (
    id BIGINT NOT NULL,
    task_code VARCHAR(64) NOT NULL,
    task_name VARCHAR(128) NOT NULL,
    task_type VARCHAR(32) DEFAULT 'SYNC' NOT NULL,
    source_node VARCHAR(64) DEFAULT NULL,
    target_node VARCHAR(64) DEFAULT NULL,
    status INT DEFAULT 1 NOT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_kingbase_task_code UNIQUE (task_code)
);

COMMENT ON TABLE swap_task IS '交换部署任务主表';
COMMENT ON COLUMN swap_task.id IS '主键ID（雪花算法）';
COMMENT ON COLUMN swap_task.task_code IS '任务唯一标识编码';
COMMENT ON COLUMN swap_task.task_name IS '交换任务名称';
COMMENT ON COLUMN swap_task.task_type IS '任务类型（SYNC-同步, BACKUP-备份, FORWARD-转发）';
COMMENT ON COLUMN swap_task.source_node IS '源端交换节点';
COMMENT ON COLUMN swap_task.target_node IS '目标端交换节点';
COMMENT ON COLUMN swap_task.status IS '任务状态（0-停用, 1-启用, 2-执行中）';
COMMENT ON COLUMN swap_task.remark IS '备注说明';
COMMENT ON COLUMN swap_task.create_time IS '创建时间';
COMMENT ON COLUMN swap_task.update_time IS '更新时间';
