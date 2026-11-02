package com.zkjs.aio.swap.task;

import com.zkjs.aio.cache.annotation.AioScheduledLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 高可用集群定时任务演示
 * 演示基于 aio-spring-boot-starter-cache 的 @AioScheduledLock 注解：
 * 1. 静默放弃 (Fail-Silent)：未抢到锁的节点静默跳过，杜绝报警风暴
 * 2. 防死锁兜底 (lockAtMostFor)：Pod 宕机时超时自动失效
 * 3. 防时钟漂移 (lockAtLeastFor)：设定最小锁定时间，抵御微秒/毫秒级 NTP 时钟漂移导致的重复执行
 *
 * @author wuyw
 */
@Slf4j
@Component
public class SwapScheduledTaskDemo {

    /**
     * 每 30 秒执行一次的心跳与状态检查任务
     * lockAtMostFor = "25s": 最多持有 25 秒
     * lockAtLeastFor = "5s": 最小保持 5 秒，防止任务耗时极短（如 20ms）释放后被时钟微漂移的节点误触发
     */
    @Scheduled(fixedRate = 30000)
    @AioScheduledLock(name = "swapClusterHeartbeatJob", lockAtMostFor = "25s", lockAtLeastFor = "5s")
    public void executeHeartbeatCheck() {
        log.info("【集群定时任务】执行心跳巡检成功（由 @AioScheduledLock 保障多副本仅单节点执行）");
    }
}
