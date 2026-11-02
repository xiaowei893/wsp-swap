package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.dto.SwapKafkaMessage;
import com.zkjs.aio.swap.service.SwapKafkaProducerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 消息队列演示控制器
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo/kafka")
@RequiredArgsConstructor
@Tag(name = "Kafka 消息队列集成演示", description = "支持同步可靠发送、异步高吞吐、分区顺序保障、延迟投递与批量消费演示")
public class KafkaDemoController {

    private final SwapKafkaProducerService kafkaProducerService;

    @PostMapping("/send-sync")
    @Operation(summary = "1. 同步发送消息 (等待 Broker 落盘确认)", description = "同步阻塞直至 Kafka 集群所有 ISR 副本落盘成功，返回 Partition 与 Offset 等元数据")
    public ResultBody<Map<String, Object>> sendSync(
            @Parameter(description = "业务任务编码 (作为 Kafka 分区键 Key)", example = "TASK_001")
            @RequestParam(defaultValue = "TASK_001") String taskCode,
            @Parameter(description = "事件类型", example = "DATA_SYNC_START")
            @RequestParam(defaultValue = "DATA_SYNC_START") String eventType,
            @Parameter(description = "业务消息内容", example = "交换同步触发指令")
            @RequestParam(defaultValue = "测试同步消息内容") String content) {

        SwapKafkaMessage message = SwapKafkaMessage.builder()
                .msgId("MSG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .taskCode(taskCode)
                .eventType(eventType)
                .payload(content)
                .timestamp(LocalDateTime.now())
                .build();

        SendResult<String, String> result = kafkaProducerService.sendSync(message);
        RecordMetadata metadata = result.getRecordMetadata();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("msgId", message.getMsgId());
        data.put("topic", metadata.topic());
        data.put("partition", metadata.partition());
        data.put("offset", metadata.offset());
        data.put("timestamp", metadata.timestamp());

        return ResultBody.success("Kafka 同步发送成功并完成 ISR 副本落盘", data);
    }

    @PostMapping("/send-async")
    @Operation(summary = "2. 异步高吞吐发送 (CompletableFuture 回调)", description = "异步投递消息至生产者内存缓冲区，借助 CompletableFuture 非阻塞返回")
    public CompletableFuture<ResultBody<Map<String, Object>>> sendAsync(
            @Parameter(description = "业务任务编码", example = "TASK_002")
            @RequestParam(defaultValue = "TASK_002") String taskCode,
            @Parameter(description = "事件类型", example = "DATA_STREAM_PUSH")
            @RequestParam(defaultValue = "DATA_STREAM_PUSH") String eventType,
            @Parameter(description = "业务消息内容", example = "高吞吐异步数据负载")
            @RequestParam(defaultValue = "测试异步高吞吐消息") String content) {

        SwapKafkaMessage message = SwapKafkaMessage.builder()
                .msgId("MSG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .taskCode(taskCode)
                .eventType(eventType)
                .payload(content)
                .timestamp(LocalDateTime.now())
                .build();

        return kafkaProducerService.sendAsync(message)
                .thenApply(sendResult -> {
                    RecordMetadata metadata = sendResult.getRecordMetadata();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("msgId", message.getMsgId());
                    data.put("topic", metadata.topic());
                    data.put("partition", metadata.partition());
                    data.put("offset", metadata.offset());
                    return ResultBody.success("Kafka 异步发送成功", data);
                });
    }

    @PostMapping("/send-order")
    @Operation(summary = "3. 顺序发送消息 (相同 Key 保证严格有序)", description = "根据指定业务 Key 计算分区 Hash，保障相同 Key 的消息投递到同一分区，实现有序消费")
    public ResultBody<Map<String, Object>> sendOrder(
            @Parameter(description = "业务任务编码 (顺序 Key)", example = "TASK_ORDER_100")
            @RequestParam(defaultValue = "TASK_ORDER_100") String taskCode,
            @Parameter(description = "步骤序号", example = "1")
            @RequestParam(defaultValue = "1") Integer step,
            @Parameter(description = "操作描述", example = "创建临时交换目录")
            @RequestParam(defaultValue = "执行顺序业务动作") String action) {

        SwapKafkaMessage message = SwapKafkaMessage.builder()
                .msgId("MSG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .taskCode(taskCode)
                .eventType("STEP_" + step)
                .payload(action + " [步骤序号 " + step + "]")
                .timestamp(LocalDateTime.now())
                .build();

        SendResult<String, String> result = kafkaProducerService.sendOrderSync(message);
        RecordMetadata metadata = result.getRecordMetadata();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("msgId", message.getMsgId());
        data.put("partitionKey", taskCode);
        data.put("topic", metadata.topic());
        data.put("partition", metadata.partition());
        data.put("offset", metadata.offset());
        data.put("step", step);

        return ResultBody.success("Kafka 顺序消息已定向投递至指定分区", data);
    }

    @PostMapping("/send-delay")
    @Operation(summary = "4. 延迟发送消息", description = "在指定的延迟秒数后，由底层调度器自动发送至 Kafka")
    public ResultBody<String> sendDelay(
            @Parameter(description = "业务任务编码", example = "TASK_003")
            @RequestParam(defaultValue = "TASK_003") String taskCode,
            @Parameter(description = "延时秒数", example = "5")
            @RequestParam(defaultValue = "5") Long delaySeconds,
            @Parameter(description = "业务消息内容", example = "延时任务心跳状态核验")
            @RequestParam(defaultValue = "测试延时投递内容") String content) {

        SwapKafkaMessage message = SwapKafkaMessage.builder()
                .msgId("MSG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .taskCode(taskCode)
                .eventType("DELAY_CHECK")
                .payload(content)
                .timestamp(LocalDateTime.now())
                .build();

        kafkaProducerService.sendDelay(message, delaySeconds);
        return ResultBody.success("延时消息已提交调度，将在 " + delaySeconds + " 秒后投递至 Kafka");
    }

    @PostMapping("/send-batch")
    @Operation(summary = "5. 模拟批量生产 (触发批量监听工厂聚合消费)", description = "一次性连续发送多条消息至批量队列，观察消费者使用批量拉取工厂进行高效批量消费")
    public ResultBody<Map<String, Object>> sendBatch(
            @Parameter(description = "批量发送条数", example = "10")
            @RequestParam(defaultValue = "10") Integer count) {

        List<String> msgIds = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            SwapKafkaMessage message = SwapKafkaMessage.builder()
                    .msgId("MSG_BATCH_" + i + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                    .taskCode("BATCH_TASK")
                    .eventType("BATCH_ITEM")
                    .payload("批量数据包负载 #" + i)
                    .timestamp(LocalDateTime.now())
                    .build();
            kafkaProducerService.sendToBatchTopic(message);
            msgIds.add(message.getMsgId());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sentCount", count);
        data.put("targetTopic", SwapKafkaProducerService.TOPIC_BATCH);
        data.put("messageIds", msgIds);

        return ResultBody.success("批量消息已成功发送至 " + SwapKafkaProducerService.TOPIC_BATCH + "，消费者将按批次聚合处理", data);
    }
}
