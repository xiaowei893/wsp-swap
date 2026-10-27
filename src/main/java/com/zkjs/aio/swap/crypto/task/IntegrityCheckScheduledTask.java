package com.zkjs.aio.swap.crypto.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zkjs.aio.swap.crypto.envelope.IntegrityHelper;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.mapper.SwapTaskMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据库完整性防篡改定时巡检任务
 * 定期全量/增量扫描检查数据完整性签名：
 * 1. 签名匹配一致 -> 更新 data_tampered = 1 (正常)
 * 2. 签名不匹配或被修改 -> 更新 data_tampered = 2 (已篡改)，并记录安全审计告警
 *
 * @author wuyw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegrityCheckScheduledTask {

    private final SwapTaskMapper swapTaskMapper;
    private final HsmCryptoDispatcher dispatcher;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 默认每 30 分钟定时执行一次完整性防篡改巡检
     */
    @Scheduled(cron = "${hsm.integrity.cron:0 */30 * * * ?}")
    public void scheduledIntegrityCheck() {
        log.info("===> [完整性防篡改巡检] 触发定时调度任务...");
        executeCheck();
    }

    /**
     * 执行完整性巡检（可供定时任务触发或在线管理接口手动触发）
     */
    public IntegrityCheckResult executeCheck() {
        long startTime = System.currentTimeMillis();
        String activeVendor = dispatcher.getActiveVendor().getCode();
        log.info("===> [完整性防篡改巡检] 开始巡检表 [swap_task]，使用当前激活密码厂商: {}", activeVendor);

        long lastId = 0L;
        int pageSize = 200;
        int totalChecked = 0;
        int normalCount = 0;
        int tamperedCount = 0;

        while (true) {
            // 通过 MyBatis-Plus 分批查询（读取拦截器会自动将密文字段透明解密还原为明文）
            List<SwapTask> list = swapTaskMapper.selectList(
                    new QueryWrapper<SwapTask>()
                            .gt("id", lastId)
                            .orderByAsc("id")
                            .last("LIMIT " + pageSize)
            );

            if (list == null || list.isEmpty()) {
                break;
            }

            for (SwapTask task : list) {
                totalChecked++;
                lastId = task.getId();

                // 1. 按 @IntegrityField 的 order 顺序拼接待签明文
                String plainContent = IntegrityHelper.buildPlainString(task);

                // 2. 调用当前激活密码厂商的密码算法进行验签
                boolean isValid = false;
                if (plainContent != null && StringUtils.hasText(task.getSignValue())) {
                    isValid = dispatcher.verify(plainContent, task.getSignValue());
                }

                if (!isValid) {
                    tamperedCount++;
                    log.error("【重大安全告警】检测到表 swap_task 记录 ID={} 数据已被非法篡改！当前厂商={}, 数据库原签名={}",
                            task.getId(), activeVendor, task.getSignValue());

                    // 将状态标记为 2（已篡改）
                    jdbcTemplate.update("UPDATE swap_task SET data_tampered = 2 WHERE id = ?", task.getId());
                } else {
                    normalCount++;
                    // 若状态尚未标记为 1（正常），则更新为 1
                    if (task.getDataTampered() == null || task.getDataTampered() != 1) {
                        jdbcTemplate.update("UPDATE swap_task SET data_tampered = 1 WHERE id = ?", task.getId());
                    }
                }
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        log.info("===> [完整性防篡改巡检] 巡检完成！共核查记录 {} 条，正常 {} 条，发现篡改 {} 条，耗时 {} ms",
                totalChecked, normalCount, tamperedCount, cost);

        return IntegrityCheckResult.builder()
                .activeVendor(activeVendor)
                .totalChecked(totalChecked)
                .normalCount(normalCount)
                .tamperedCount(tamperedCount)
                .costMs(cost)
                .build();
    }

    @Data
    @Builder
    public static class IntegrityCheckResult {
        private String activeVendor;
        private int totalChecked;
        private int normalCount;
        private int tamperedCount;
        private long costMs;
    }
}
