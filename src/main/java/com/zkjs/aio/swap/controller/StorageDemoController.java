package com.zkjs.aio.swap.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.entity.SwapDataLog;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.service.SwapCacheDemoService;
import com.zkjs.aio.swap.service.SwapDataLogService;
import com.zkjs.aio.swap.service.SwapTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多存储数据源与缓存集成演示控制器
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/storage")
@RequiredArgsConstructor
@Tag(name = "存储与缓存集成演示", description = "包含 MySQL 业务增删查改、ClickHouse 海量分析日志批量写入及 Redis 缓存与分布式锁")
public class StorageDemoController {

    private final SwapTaskService swapTaskService;
    private final SwapDataLogService swapDataLogService;
    private final SwapCacheDemoService swapCacheDemoService;

    // =========================================================================
    // 1. MySQL 关系型数据库演示 (OLTP)
    // =========================================================================

    @PostMapping("/mysql/task")
    @Operation(summary = "MySQL: 创建交换任务", description = "演示往 MySQL 默认主数据源中插入一条交换任务记录")
    public ResultBody<SwapTask> createMysqlTask(
            @Parameter(description = "任务编码", example = "TASK_20261013_001")
            @RequestParam(defaultValue = "TASK_001") String taskCode,
            @Parameter(description = "任务名称", example = "交换同步任务A")
            @RequestParam(defaultValue = "测试任务") String taskName,
            @Parameter(description = "源节点", example = "NODE_A")
            @RequestParam(defaultValue = "NODE_A") String sourceNode,
            @Parameter(description = "目标节点", example = "NODE_B")
            @RequestParam(defaultValue = "NODE_B") String targetNode) {
        SwapTask task = swapTaskService.createTask(taskCode, taskName, sourceNode, targetNode);
        return ResultBody.success("MySQL 任务创建成功", task);
    }

    @GetMapping("/mysql/task/{id}")
    @Operation(summary = "MySQL: 根据主键查询任务", description = "从 MySQL 中按雪花算法 ID 查询任务详情")
    public ResultBody<SwapTask> getMysqlTask(
            @Parameter(description = "任务主键ID")
            @PathVariable Long id) {
        SwapTask task = swapTaskService.getById(id);
        return ResultBody.success(task);
    }

    @GetMapping("/mysql/tasks")
    @Operation(summary = "MySQL: 查询所有交换任务列表")
    public ResultBody<List<SwapTask>> listMysqlTasks() {
        return ResultBody.success(swapTaskService.list());
    }

    // =========================================================================
    // 2. ClickHouse 分析型列式存储演示 (OLAP)
    // =========================================================================

    @PostMapping("/clickhouse/logs/batch")
    @Operation(summary = "ClickHouse: 批量写入海量交换日志", description = "遵循 AIO 规范：无事务、雪花主键、严格批量写入")
    public ResultBody<String> batchInsertClickHouseLogs(
            @Parameter(description = "关联的任务ID", example = "1001")
            @RequestParam(defaultValue = "1001") Long taskId,
            @Parameter(description = "模拟插入条数", example = "1000")
            @RequestParam(defaultValue = "1000") int count) {

        List<SwapDataLog> logs = new ArrayList<>(count);
        String batchNo = "BATCH_" + System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < count; i++) {
            logs.add(SwapDataLog.builder()
                    .taskId(taskId)
                    .taskCode("TASK_" + taskId)
                    .batchNo(batchNo)
                    .recordsCount(500L)
                    .bytesSize(102400L)
                    .costTimeMs((long) (Math.random() * 500 + 50))
                    .status(1)
                    .errorMsg("")
                    .logTime(now)
                    .createTime(now)
                    .build());
        }

        boolean success = swapDataLogService.saveBatchToClickHouse(logs);
        return ResultBody.success("ClickHouse 批量写入 " + count + " 条日志完成，结果: " + success);
    }

    @GetMapping("/clickhouse/logs")
    @Operation(summary = "ClickHouse: 查询分析日志记录（最新20条）")
    public ResultBody<List<SwapDataLog>> listClickHouseLogs(
            @Parameter(description = "关联任务ID", example = "1001")
            @RequestParam(required = false) Long taskId) {
        var query = Wrappers.<SwapDataLog>query()
                .eq(taskId != null, "task_id", taskId)
                .orderByDesc("log_time")
                .last("LIMIT 20");
        return ResultBody.success(swapDataLogService.list(query));
    }

    // =========================================================================
    // 3. Redis 缓存与分布式锁演示
    // =========================================================================

    @PostMapping("/redis/set")
    @Operation(summary = "Redis: 写入缓存数据 (响应式)", description = "写入 Redis 缓存，由 aio.redis.key-prefix 自动追加 wsp-swap: 前缀")
    public Mono<ResultBody<Boolean>> setRedisCache(
            @Parameter(description = "缓存Key", example = "status:task:1001")
            @RequestParam String key,
            @Parameter(description = "缓存值", example = "RUNNING")
            @RequestParam String value,
            @Parameter(description = "有效时长(秒)", example = "300")
            @RequestParam(defaultValue = "300") long expireSeconds) {
        return swapCacheDemoService.setReactiveCache(key, value, expireSeconds)
                .map(success -> ResultBody.success("缓存写入结果: " + success, success));
    }

    @GetMapping("/redis/get")
    @Operation(summary = "Redis: 获取缓存数据 (响应式)", description = "从 Redis 读取缓存，自动剥离前缀")
    public Mono<ResultBody<String>> getRedisCache(
            @Parameter(description = "缓存Key", example = "status:task:1001")
            @RequestParam String key) {
        return swapCacheDemoService.getReactiveCache(key)
                .map(ResultBody::success)
                .defaultIfEmpty(ResultBody.success("缓存不存在或已过期", null));
    }

    @PostMapping("/redis/lock-test")
    @Operation(summary = "Redis: 测试 Lock4j 分布式锁", description = "验证基于注解的 Lock4j 分布式锁加锁与释放")
    public ResultBody<String> testLock(
            @Parameter(description = "任务编码加锁Key", example = "TASK_SYNC_001")
            @RequestParam(defaultValue = "TASK_SYNC_001") String taskCode) {
        String msg = swapCacheDemoService.executeTaskWithLock(taskCode);
        return ResultBody.success(msg);
    }
}
