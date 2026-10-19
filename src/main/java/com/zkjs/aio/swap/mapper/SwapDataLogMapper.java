package com.zkjs.aio.swap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zkjs.aio.swap.entity.SwapDataLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 交换数据流水日志 Mapper (ClickHouse)
 *
 * @author wuyw
 */
@Mapper
public interface SwapDataLogMapper extends BaseMapper<SwapDataLog> {
}
