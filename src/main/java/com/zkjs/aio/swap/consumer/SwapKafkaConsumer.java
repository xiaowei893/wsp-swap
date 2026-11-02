package com.zkjs.aio.swap.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zkjs.aio.swap.dto.SwapKafkaMessage;
import com.zkjs.aio.swap.service.SwapKafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka 消息消费者演示服务
 *
 * @author wuyw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapKafkaConsumer {

    private final ObjectMapper objectMapper;

    /**
     * 1. 单条消息消费监听器
     * 工厂模式: singleKafkaListenerContainerFactory
     * 提交模式: MANUAL_IMMEDIATE（单条处理完成后手动立即提交 ACK）
     */
    @KafkaListener(
            topics = SwapKafkaProducerService.TOPIC_SINGLE,
            containerFactory = "singleKafkaListenerContainerFactory",
            groupId = "wsp-swap-single-group"
    )
    public void handleSingleMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            log.info("===> 【Kafka 收到单条消息】Topic: [{}], Partition: [{}], Offset: [{}], Key: [{}]",
                    record.topic(), record.partition(), record.offset(), record.key());

            // 反序列化消息内容
            SwapKafkaMessage message = objectMapper.readValue(record.value(), SwapKafkaMessage.class);
            log.info("     [单条业务内容] MsgId: {}, TaskCode: {}, Event: {}, Payload: {}",
                    message.getMsgId(), message.getTaskCode(), message.getEventType(), message.getPayload());

            // 业务处理成功后手动确认提交偏移量
            ack.acknowledge();
        } catch (Exception e) {
            log.error("【Kafka 单条消费异常】处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("单条消费异常触发重试机制", e);
        }
    }

    /**
     * 2. 批量消息消费监听器
     * 工厂模式: kafkaListenerContainerFactory
     * 提交模式: MANUAL（批次拉取处理完成后批量提交 ACK）
     */
    @KafkaListener(
            topics = SwapKafkaProducerService.TOPIC_BATCH,
            containerFactory = "kafkaListenerContainerFactory",
            groupId = "wsp-swap-batch-group"
    )
    public void handleBatchMessage(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            log.info("===> 【Kafka 收到批量消息】本批次消息数量: [{}]", records.size());
            for (ConsumerRecord<String, String> record : records) {
                log.info("     -> [批次项] Partition: [{}], Offset: [{}], Key: [{}], Value: [{}]",
                        record.partition(), record.offset(), record.key(), record.value());
            }

            // 批次整体处理完成后确认提交
            ack.acknowledge();
            log.info("===> 【Kafka 批量消费完成】本批次 {} 条消息已成功提交确认", records.size());
        } catch (Exception e) {
            log.error("【Kafka 批量消费异常】批次处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("批量消费异常触发重试机制", e);
        }
    }
}
