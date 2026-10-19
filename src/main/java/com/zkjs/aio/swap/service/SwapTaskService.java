package com.zkjs.aio.swap.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.mapper.SwapTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 交换任务服务 (MySQL 主数据源)
 *
 * @author wuyw
 */
@Slf4j
@Service
@DS("mysql_master")
public class SwapTaskService extends ServiceImpl<SwapTaskMapper, SwapTask> {

    /**
     * 创建交换任务（MySQL 支持标准事务）
     */
    @Transactional(rollbackFor = Exception.class)
    public SwapTask createTask(String taskCode, String taskName, String sourceNode, String targetNode) {
        SwapTask task = SwapTask.builder()
                .taskCode(taskCode)
                .taskName(taskName)
                .taskType("SYNC")
                .sourceNode(sourceNode)
                .targetNode(targetNode)
                .status(1)
                .remark("初始化任务")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        this.save(task);
        log.info("MySQL 成功创建交换任务: id={}, code={}", task.getId(), task.getTaskCode());
        return task;
    }
}
