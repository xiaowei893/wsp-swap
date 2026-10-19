package com.zkjs.aio.swap.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zkjs.aio.swap.entity.SwapDataLog;
import com.zkjs.aio.swap.mapper.SwapDataLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 交换数据流水日志服务 (ClickHouse 列式分析型数据源)
 * 遵循 AIO Framework 规约：
 * 1. 切换数据源 @DS("clickhouse_db")
 * 2. 严禁添加 @Transactional 注解（ClickHouse 不支持多语句 ACID 事务）
 * 3. 严禁单条高频调用 insert()，必须使用批量写入 saveBatch(list, batchSize)
 *
 * @author wuyw
 */
@Slf4j
@Service
@DS("clickhouse_db")
public class SwapDataLogService extends ServiceImpl<SwapDataLogMapper, SwapDataLog> {

    /**
     * 批量持久化到 ClickHouse
     * 建议单次批次大小在 1,000 ~ 5,000 条左右
     */
    public boolean saveBatchToClickHouse(List<SwapDataLog> logs) {
        if (CollectionUtils.isEmpty(logs)) {
            return false;
        }
        log.info("开始批量写入 ClickHouse 交换日志，记录数: {}", logs.size());
        return this.saveBatch(logs, 1000);
    }
}
