package com.zkjs.aio.swap.controller;

import com.zkjs.aio.common.exception.BusinessException;
import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.swap.dto.DemoRequest;
import com.zkjs.aio.swap.vo.DemoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 交换部署服务示例控制器
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo")
@Tag(name = "演示接口", description = "用于展示 WebFlux 响应式接口、Knife4j 文档标注以及全局响应封装规范")
public class DemoController {

    /**
     * 1. 基础 GET 接口示例
     */
    @GetMapping("/hello")
    @Operation(summary = "基础问候与连通性测试 (GET)", description = "接收名称参数并返回问候字符串，测试服务运行状态")
    public ResultBody<String> hello(
            @Parameter(description = "访问者名称", example = "开发人员")
            @RequestParam(value = "name", defaultValue = "开发者") String name) {
        log.info("收到 hello 请求，name: {}", name);
        return ResultBody.success("你好，" + name + "！wsp-swap 交换部署服务运行正常。");
    }

    /**
     * 2. 接收 JSON 参数与参数校验接口 (POST)
     */
    @PostMapping("/echo")
    @Operation(summary = "数据回显与校验接口 (POST)", description = "接收 JSON 请求体并进行数据校验，返回封装对象")
    public ResultBody<DemoResponse> echo(@Valid @RequestBody DemoRequest request) {
        log.info("收到 echo 请求: {}", request);
        DemoResponse response = DemoResponse.builder()
                .traceId(UUID.randomUUID().toString().replace("-", ""))
                .name(request.getName())
                .message(request.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResultBody.success("数据处理成功", response);
    }

    /**
     * 3. WebFlux 响应式异步接口示例 (Mono)
     */
    @GetMapping("/reactive")
    @Operation(summary = "响应式异步接口 (WebFlux Mono 示例)", description = "演示返回 Reactor 的 Mono 响应式数据流")
    public Mono<ResultBody<DemoResponse>> reactiveDemo(
            @Parameter(description = "任务名称", example = "交换同步任务")
            @RequestParam(value = "taskName", defaultValue = "默认任务") String taskName) {
        log.info("收到 reactive 请求，taskName: {}", taskName);
        return Mono.fromCallable(() -> {
            DemoResponse response = DemoResponse.builder()
                    .traceId(UUID.randomUUID().toString().replace("-", ""))
                    .name(taskName)
                    .message("响应式异步流处理完毕")
                    .timestamp(LocalDateTime.now())
                    .build();
            return ResultBody.success(response);
        });
    }

    /**
     * 4. 统一全局异常处理测试接口
     */
    @GetMapping("/test-exception")
    @Operation(summary = "全局异常捕获测试接口", description = "主动抛出业务异常，测试 aio-spring-boot-starter-common 全局异常处理器的捕获与统一返回格式")
    public ResultBody<Void> testException() {
        log.warn("准备主动抛出业务异常以测试 GlobalExceptionHandler");
        throw BusinessException.of("测试抛出自定义业务异常：当前交换通道不可用！");
    }
}
