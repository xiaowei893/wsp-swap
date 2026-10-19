package com.zkjs.aio.swap.service;

import com.zkjs.aio.kafka.util.KafkaProducerUtil;
import com.zkjs.aio.swap.dto.SwapKafkaMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 消息生产服务
 *
 * @author wuyw
 */
@Slf4j
@Service
public class SwapKafkaProducerService {

    /**
     * 单条消费演示主题
     */
    public static final String TOPIC_SINGLE = "wsp-swap-demo-single";

    /**
     * 批量消费演示主题
     */
    public static final String TOPIC_BATCH = "wsp-swap-demo-batch";

    /**
     * 1. 同步可靠发送（等待 Broker 所有 ISR 副本落盘确认）
     *
     * @param message 消息体
     * @return 包含 Partition、Offset 等元数据的发送结果
     */
    public SendResult<String, String> sendSync(SwapKafkaMessage message) {
        log.info("【Kafka 同步发送】Topic: {}, Key: {}, MsgId: {}", TOPIC_SINGLE, message.getTaskCode(), message.getMsgId());
        return KafkaProducerUtil.sendSync(TOPIC_SINGLE, message.getTaskCode(), message);
    }

    /**
     * 2. 异步高吞吐发送（通过 CompletableFuture 进行非阻塞回调）
     *
     * @param message 消息体
     * @return 异步回调 Future
     */
    public CompletableFuture<SendResult<String, String>> sendAsync(SwapKafkaMessage message) {
        log.info("【Kafka 异步发送】Topic: {}, Key: {}, MsgId: {}", TOPIC_SINGLE, message.getTaskCode(), message.getMsgId());
        return KafkaProducerUtil.sendAsync(TOPIC_SINGLE, message.getTaskCode(), message);
    }

    /**
     * 3. 顺序发送（保证相同 Key 投递至同一分区且有序消费）
     *
     * @param message 消息体
     * @return 发送结果
     */
    public SendResult<String, String> sendOrderSync(SwapKafkaMessage message) {
        log.info("【Kafka 顺序发送】Topic: {}, Key: {}, MsgId: {}", TOPIC_SINGLE, message.getTaskCode(), message.getMsgId());
        return KafkaProducerUtil.sendOrderSync(TOPIC_SINGLE, message.getTaskCode(), message);
    }

    /**
     * 4. 延迟发送（基于内存调度延时投递）
     *
     * @param message      消息体
     * @param delaySeconds 延时时长（秒）
     */
    public void sendDelay(SwapKafkaMessage message, long delaySeconds) {
        log.info("【Kafka 延时发送】Topic: {}, Key: {}, 延时: {} 秒", TOPIC_SINGLE, message.getTaskCode(), delaySeconds);
        KafkaProducerUtil.sendDelay(TOPIC_SINGLE, message.getTaskCode(), message, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 5. 发送至批量消费队列
     *
     * @param message 消息体
     * @return 发送结果
     */
    public SendResult<String, String> sendToBatchTopic(SwapKafkaMessage message) {
        log.info("【Kafka 批量队列发送】Topic: {}, Key: {}, MsgId: {}", TOPIC_BATCH, message.getTaskCode(), message.getMsgId());
        return KafkaProducerUtil.sendSync(TOPIC_BATCH, message.getTaskCode(), message);
    }
}
