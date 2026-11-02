package com.zkjs.aio.swap.service;

import com.baomidou.lock.annotation.Lock4j;
import com.zkjs.aio.cache.annotation.AioCache;
import com.zkjs.aio.cache.annotation.AioCacheEvict;
import com.zkjs.aio.cache.annotation.AioCachePut;
import com.zkjs.aio.cache.enums.CacheType;
import com.zkjs.aio.cache.util.RedisUtil;
import com.zkjs.aio.swap.entity.SwapTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存与分布式锁示例服务
 * 遵循 AIO Framework 规约：
 * 1. aio.redis.key-prefix 全局前缀隔离 (wsp-swap)
 * 2. 支持 RedisUtil 同步操作
 * 3. 支持 @Lock4j 分布式锁
 * 4. 支持 @AioCache, @AioCachePut, @AioCacheEvict 统一多级缓存 (L1 Caffeine + L2 Redis)
 *
 * @author wuyw
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwapCacheDemoService {

    @Lazy
    private final SwapTaskService swapTaskService;

    // =========================================================================
    // 1. RedisUtil 同步操作
    // =========================================================================

    public void setSyncCache(String key, Object value, long expireSeconds) {
        RedisUtil.set(key, value, Duration.ofSeconds(expireSeconds));
    }

    public <T> T getSyncCache(String key, Class<T> clazz) {
        return RedisUtil.get(key, clazz);
    }

    // =========================================================================
    // 3. Lock4j 分布式锁
    // =========================================================================

    @Lock4j(keys = {"#taskCode"}, expire = 30000, acquireTimeout = 3000)
    public String executeTaskWithLock(String taskCode) {
        log.info("成功获取 Lock4j 分布式锁，开始执行交换任务: {}", taskCode);
        try {
            // 模拟业务处理耗时
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("交换任务执行完毕，即将自动释放锁: {}", taskCode);
        return "任务 [" + taskCode + "] 在分布式锁保护下执行成功";
    }

    // =========================================================================
    // 4. @AioCache 统一多级缓存 (L1 Caffeine + L2 Redis + 集群广播驱逐)
    // =========================================================================

    /**
     * 多级缓存读取与回源填充 (Cache-Aside)
     * 优先查 L1 Caffeine；未命中查 L2 Redis 并回填 L1；均未命中查 DB 并同步写入 L1 和 L2
     */
    @AioCache(name = "swap_task", key = "#id", type = CacheType.BOTH, localExpire = 300, remoteExpire = 3600)
    public Optional<SwapTask> getTaskMultiLevelCache(Long id) {
        log.info("【多级缓存未命中，执行回源】从 MySQL 数据库查询任务: id={}", id);
        return Optional.ofNullable(swapTaskService.getById(id));
    }

    /**
     * 强制执行目标方法并回写刷新多级缓存 (@AioCachePut)
     */
    @AioCachePut(name = "swap_task", key = "#result.id", type = CacheType.BOTH)
    public SwapTask updateTaskMultiLevelCache(SwapTask task) {
        log.info("【多级缓存刷新】更新任务并强制同步回写 L1+L2 缓存: id={}", task.getId());
        swapTaskService.updateById(task);
        return task;
    }

    /**
     * 驱逐多级缓存 (@AioCacheEvict)
     * 当 type 为 BOTH 时，自动通过 Redis Pub/Sub 广播通知集群所有节点驱逐本地 Caffeine 缓存
     */
    @AioCacheEvict(name = "swap_task", key = "#id", type = CacheType.BOTH)
    public void evictTaskMultiLevelCache(Long id) {
        log.info("【多级缓存驱逐】清理任务 L1+L2 缓存并广播集群同步驱逐: id={}", id);
    }
}
