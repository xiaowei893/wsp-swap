package com.zkjs.aio.swap.service;

import com.baomidou.lock.annotation.Lock4j;
import com.zkjs.aio.cache.util.ReactiveRedisUtil;
import com.zkjs.aio.cache.util.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 缓存与分布式锁示例服务 (Redis 单机模式)
 * 遵循 AIO Framework 规约：
 * 1. aio.redis.key-prefix 全局前缀隔离 (wsp-swap)
 * 2. 支持 ReactiveRedisUtil 响应式操作与 RedisUtil 同步操作
 * 3. 支持 @Lock4j 分布式锁
 *
 * @author wuyw
 */
@Slf4j
@Service
public class SwapCacheDemoService {

    /**
     * 响应式存储缓存（底层自动加上 wsp-swap: 前缀）
     */
    public Mono<Boolean> setReactiveCache(String key, String value, long expireSeconds) {
        log.info("响应式写入 Redis 缓存: key={}, value={}, expire={}s", key, value, expireSeconds);
        return ReactiveRedisUtil.set(key, value, Duration.ofSeconds(expireSeconds));
    }

    /**
     * 响应式读取缓存
     */
    public Mono<String> getReactiveCache(String key) {
        return ReactiveRedisUtil.get(key, String.class);
    }

    /**
     * 同步模式写入与读取缓存 (RedisUtil)
     */
    public void setSyncCache(String key, Object value, long expireSeconds) {
        RedisUtil.set(key, value, Duration.ofSeconds(expireSeconds));
    }

    public <T> T getSyncCache(String key, Class<T> clazz) {
        return RedisUtil.get(key, clazz);
    }

    /**
     * Lock4j 注解式分布式锁演示
     * 根据 taskCode 动态加锁，防止并发执行同一任务
     */
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
}
