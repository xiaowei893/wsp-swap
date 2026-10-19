package com.zkjs.aio.swap;

import com.zkjs.aio.swap.dto.SwapKafkaMessage;
import com.zkjs.aio.swap.service.SwapKafkaProducerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.UUID;

@SpringBootTest
class WspSwapApplicationTests {

    @Autowired(required = false)
    private SwapKafkaProducerService kafkaProducerService;

    @Autowired(required = false)
    private org.springframework.web.cors.reactive.CorsWebFilter corsWebFilter;

    @Autowired(required = false)
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private com.zkjs.aio.cache.util.RedisUtil redisUtil;

    @Test
    void contextLoads() {
    }

    @Test
    void testRedisStarterAutoConfiguration() {
        Assertions.assertNotNull(redisTemplate, "RedisTemplate<String, Object> 必须由 aio-spring-boot-starter-cache 1.0.9 自动配置原生成功装配（验证 BUG-CACHE-001 已修复）");
        Assertions.assertNotNull(redisUtil, "RedisUtil 必须由 aio-spring-boot-starter-cache 1.0.9 自动配置原生成功装配");
    }

    @Test
    void testCorsWebFilterLoaded() {
        Assertions.assertNotNull(corsWebFilter, "AIO 跨域过滤器 CorsWebFilter 应当由 aio-spring-boot-starter-common 自动配置成功装配");
    }

    @Test
    void testKafkaProducer() {
        if (kafkaProducerService != null) {
            SwapKafkaMessage message = SwapKafkaMessage.builder()
                    .msgId("TEST_" + UUID.randomUUID().toString().substring(0, 8))
                    .taskCode("TEST_TASK")
                    .eventType("TEST_EVENT")
                    .payload("Kafka 连通性自动化单元测试")
                    .timestamp(LocalDateTime.now())
                    .build();

            SendResult<String, String> result = kafkaProducerService.sendSync(message);
            Assertions.assertNotNull(result);
            Assertions.assertNotNull(result.getRecordMetadata());
            Assertions.assertEquals(SwapKafkaProducerService.TOPIC_SINGLE, result.getRecordMetadata().topic());
        }
    }

}
