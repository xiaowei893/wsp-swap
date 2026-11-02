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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 交换部署服务示例控制器（Spring MVC 体系）
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/demo")
@Tag(name = "演示接口", description = "用于展示 Spring MVC 接口、Knife4j 文档标注以及全局响应封装规范")
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
     * 3. 异步数据处理接口示例
     */
    @GetMapping({"/async", "/reactive"})
    @Operation(summary = "数据处理接口 (异步/虚拟线程示例)", description = "演示在 Spring MVC + 虚拟线程下的数据处理")
    public ResultBody<DemoResponse> asyncDemo(
            @Parameter(description = "任务名称", example = "交换同步任务")
            @RequestParam(value = "taskName", defaultValue = "默认任务") String taskName) {
        log.info("收到 demo 请求，taskName: {}", taskName);
        DemoResponse response = DemoResponse.builder()
                .traceId(UUID.randomUUID().toString().replace("-", ""))
                .name(taskName)
                .message("数据处理完毕")
                .timestamp(LocalDateTime.now())
                .build();
        return ResultBody.success(response);
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
