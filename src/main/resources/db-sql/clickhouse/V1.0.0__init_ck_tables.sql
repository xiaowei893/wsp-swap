-- -------------------------------------------------------------
-- 一体化交换部署服务 ClickHouse 日志分析表初始化
-- 遵循 AIO Framework 规约：
-- 1. 无自增主键，采用 ASSIGN_ID 雪花算法
-- 2. 采用 MergeTree 引擎，按 task_id, log_time, id 排序
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `swap_data_log` (
    `id` UInt64 COMMENT '分布式雪花算法ID',
    `task_id` UInt64 COMMENT '关联的交换任务ID',
    `task_code` String COMMENT '交换任务编码',
    `batch_no` String COMMENT '交换批次流水号',
    `records_count` UInt32 DEFAULT 0 COMMENT '本次交换数据条数',
    `bytes_size` UInt64 DEFAULT 0 COMMENT '传输字节大小',
    `cost_time_ms` UInt32 DEFAULT 0 COMMENT '耗时（毫秒）',
    `status` UInt8 DEFAULT 1 COMMENT '交换状态（1-成功, 2-部分失败, 3-异常）',
    `error_msg` String DEFAULT '' COMMENT '异常描述信息',
    `log_time` DateTime DEFAULT now() COMMENT '日志产生时间',
    `create_time` DateTime DEFAULT now() COMMENT '记录入库时间'
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(log_time)
ORDER BY (task_id, log_time, id)
SETTINGS index_granularity = 8192;
