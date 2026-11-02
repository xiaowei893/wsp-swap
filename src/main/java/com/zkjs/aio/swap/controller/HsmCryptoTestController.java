package com.zkjs.aio.swap.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zkjs.aio.cache.util.RedisUtil;
import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.crypto.core.CryptoDispatcher;
import com.zkjs.aio.crypto.core.VendorMapper;
import com.zkjs.aio.crypto.enums.CryptoMode;
import com.zkjs.aio.crypto.migration.CryptoDataMigrationService;
import com.zkjs.aio.crypto.task.GenericIntegrityCheckTask;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.aio.swap.service.SwapTaskService;
import com.zkjs.gm.pool.enums.PwdFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 国密加解密、防篡改签名与平滑重密功能测试控制器
 * 基于 aio-spring-boot-starter-crypto 官方组件
 *
 * @author wuyw
 */
@Slf4j
@RestController
@RequestMapping("/crypto/test")
@RequiredArgsConstructor
@Tag(name = "国密加解密与防篡改测试接口", description = "用于在线验证透明加解密、DB物理存储密文比对与厂商热切换平滑重密")
public class HsmCryptoTestController {

    @Value("${gm.default-pwd-factory:custom}")
    private String configVendor;

    private final CryptoDispatcher cryptoDispatcher;
    private final SwapTaskService swapTaskService;
    private final JdbcTemplate jdbcTemplate;
    private final CryptoDataMigrationService migrationService;
    private final GenericIntegrityCheckTask integrityCheckTask;

    /**
     * 1. 查询当前系统密码机配置状态
     */
    @GetMapping("/info")
    @Operation(summary = "查询当前密码机系统状态", description = "返回 application.yml 配置厂商、数据库官方配置表 sys_gm_config 厂商及 Redis 激活厂商")
    public ResultBody<Map<String, Object>> getHsmInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("configVendorInYml", configVendor);
        info.put("defaultFactory", cryptoDispatcher.getDefaultFactory().name());
        info.put("activeFactoryInRuntime", cryptoDispatcher.getActiveFactory().name());

        String dbVendor = "未配置";
        try {
            List<String> list = jdbcTemplate.query(
                    "SELECT config_value FROM sys_gm_config WHERE config_key = 'DEFAULT_PWD_FACTORY'",
                    (rs, rowNum) -> rs.getString("config_value")
            );
            if (!list.isEmpty()) {
                dbVendor = list.get(0);
            }
        } catch (Exception e) {
            dbVendor = "查询失败: " + e.getMessage();
        }
        info.put("dbVendorInSysGmConfig", dbVendor);
        return ResultBody.success("获取密码机状态成功", info);
    }

    /**
     * 2. 模拟插入加密实体数据
     * 架构规范：Controller 层严禁执行任何明文还原或解密操作！
     * 写入拦截器在底层自动完成：构建 SQL 时加密落库 -> 数据库执行完毕后在 finally 中立即将内存实体还原为明文。
     */
    @PostMapping("/task")
    @Operation(summary = "创建交换任务（测试写入加密与底表物理密文比对）", description = "传入明文入库，MyBatis 写入拦截器自动透明加密落盘并还原内存实体为明文，Controller 零还原代码")
    public ResultBody<Map<String, Object>> createEncryptedTask(
            @Parameter(description = "任务名称（整段 SM4 加密测试）", example = "核心涉密同步任务")
            @RequestParam(defaultValue = "核心涉密同步任务") String taskName,
            @Parameter(description = "源端节点（逐字符拆分 SM4 加密测试）", example = "NODE_01")
            @RequestParam(defaultValue = "NODE_01") String sourceNode) {

        try {
            String taskCode = "TASK_" + System.currentTimeMillis();
            // 1. 直接通过 MyBatis-Plus 保存实体
            // 【拦截器保障】：写入拦截器在落库时透明加密并注入 encipher_tag；
            // 入库完成后，拦截器在 finally 块中立即将实体属性还原为明文原文，Controller 拿到的天然就是明文对象！
            SwapTask createdTask = swapTaskService.createTask(taskCode, taskName, sourceNode, "TARGET_99");

            // 2. 原生 JDBC 直读底表物理密文（仅用于测试验证数据库确实物理存储了密文，而非明文）
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, task_code, task_name, source_node, encipher_tag, sign_value, data_tampered FROM swap_task WHERE id = ?",
                    createdTask.getId()
            );
            Map<String, Object> rawDbRow = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);

            Map<String, Object> result = new HashMap<>();
            result.put("insertedId", String.valueOf(createdTask.getId()));
            result.put("originalInputTaskName", taskName);
            result.put("originalInputSourceNode", sourceNode);
            // createdTask 此时已被写入拦截器自动恢复为明文，无需在 Controller 中再次从数据库读取还原
            result.put("createdEntity(写入拦截器已自动还原为明文)", createdTask);
            if (!rawDbRow.isEmpty()) {
                Map<String, Object> safeDbRow = new HashMap<>(rawDbRow);
                safeDbRow.put("id", String.valueOf(safeDbRow.get("id")));
                result.put("rawDbStoredRow(数据库物理真实密文)", safeDbRow);
            } else {
                result.put("rawDbStoredRow(数据库物理真实密文)", Collections.emptyMap());
            }

            return ResultBody.success("数据写入并加密成功（写入拦截器已自动完成实体明文还原）", result);
        } catch (Exception e) {
            log.error("[HSM Test] 创建加密任务失败", e);
            return ResultBody.failure("写入加密失败: " + e.getMessage());
        }
    }

    /**
     * 3. 根据 ID 查询实体，比对数据库物理密文与自动解密明文
     */
    @GetMapping("/task/{id}")
    @Operation(summary = "按 ID 查询任务并比对密文与解密明文", description = "同时展示数据库原始存储密文和 MyBatis 拦截器解密后的明文")
    public ResultBody<Map<String, Object>> getTaskById(@PathVariable("id") Long id) {
        Long targetId = id;
        // 1. 原生 JDBC 直读真实存储
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, task_code, task_name, source_node, encipher_tag, sign_value, data_tampered FROM swap_task WHERE id = ?", targetId
        );

        // 智能纠偏：前端 JavaScript 对超过 2^53-1 (9007199254740991) 的雪花 ID 会发生精度截断（末尾变 00）
        if (rows.isEmpty() && targetId != null && targetId > 9007199254740991L) {
            long lower = (targetId / 100) * 100;
            long upper = lower + 99;
            rows = jdbcTemplate.queryForList(
                    "SELECT id, task_code, task_name, source_node, encipher_tag, sign_value, data_tampered FROM swap_task WHERE id BETWEEN ? AND ? ORDER BY id DESC LIMIT 1",
                    lower, upper
            );
            if (!rows.isEmpty()) {
                targetId = ((Number) rows.get(0).get("id")).longValue();
                log.info("[HSM Test] 检测到前端 JS 精度丢失传入 ID={}, 自动纠偏为真实雪花 ID={}", id, targetId);
            }
        }

        if (rows.isEmpty()) {
            return ResultBody.failure("未找到 ID 为 " + id + " 的任务记录");
        }

        // 2. MyBatis-Plus 查询（由读取拦截器 MybatisCryptoReadInterceptor 自动透明解密还原原文）
        SwapTask task = swapTaskService.getById(targetId);

        Map<String, Object> safeDbRow = new HashMap<>(rows.get(0));
        safeDbRow.put("id", String.valueOf(safeDbRow.get("id")));

        Map<String, Object> result = new HashMap<>();
        result.put("queryId", String.valueOf(targetId));
        if (!targetId.equals(id)) {
            result.put("originalInputId(已修正前端精度截断)", String.valueOf(id));
        }
        result.put("rawDbCipherRow(数据库底表物理密文)", safeDbRow);
        result.put("decryptedEntity(读取拦截器已自动透明解密还原明文)", task);

        return ResultBody.success("查询成功（读取拦截器已自动解密还原明文）", result);
    }

    /**
     * 4. 在线模拟厂商平滑重密迁移
     */
    @PostMapping("/switch-vendor")
    @Operation(summary = "模拟热切换密码机厂商并执行数据平滑重密", description = "在线将历史数据重密为新厂商密文，并同步更新 Redis 全局热切换")
    public ResultBody<Map<String, Object>> switchVendor(
            @Parameter(description = "目标新厂商编码: custom / fisec / gays", example = "fisec")
            @RequestParam String targetVendor) {

        PwdFactory newVendor = VendorMapper.toPwdFactory(targetVendor);
        PwdFactory oldVendor = cryptoDispatcher.getActiveFactory();

        if (newVendor == oldVendor) {
            return ResultBody.success("当前系统已激活厂商 [" + targetVendor + "]，无需执行重密迁移。", Collections.emptyMap());
        }

        log.info("[HSM Test] 收到在线切换厂商请求: {} -> {}", oldVendor.name(), newVendor.name());

        try {
            // 1. 触发平滑数据重密清洗引擎
            int migratedCount = migrationService.migrateTable(SwapTask.class, newVendor);

            // 2. 写入全局 Redis 热切换键
            String code = VendorMapper.toStandardCode(newVendor);
            RedisUtil.set(CryptoDispatcher.ACTIVE_VENDOR_REDIS_KEY, code);

            // 3. 统计并抽样一条数据展示重密后的真实密文
            List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                    "SELECT id, task_name, source_node, encipher_tag, sign_value FROM swap_task ORDER BY id DESC LIMIT 1"
            );

            Map<String, Object> res = new HashMap<>();
            res.put("oldVendor", oldVendor.name());
            res.put("newVendor", newVendor.name());
            res.put("migratedRowsCount", migratedCount);
            res.put("message", "单表平滑重密完成");
            res.put("latestRowRawDbCipherAfterMigration(重密后最新行底表密文与标签)", sample.isEmpty() ? null : sample.get(0));

            return ResultBody.success("厂商平滑重密执行成功", res);
        } catch (Exception e) {
            log.error("[HSM Test] 厂商切换迁移失败", e);
            return ResultBody.failure("厂商切换重密迁移异常: " + e.getMessage());
        }
    }

    /**
     * 5. 快速测试加解密单条文本
     */
    @GetMapping("/codec")
    @Operation(summary = "单文本加解密快速调试", description = "测试指定文本在特定厂商与加密方式下的加解密纯密文结果")
    public ResultBody<Map<String, Object>> testCodec(
            @RequestParam(defaultValue = "机密数据_ABC_123") String text,
            @RequestParam(defaultValue = "custom") String vendor,
            @RequestParam(defaultValue = "0") Integer mode) {

        PwdFactory v = VendorMapper.toPwdFactory(vendor);
        CryptoMode m = mode == 1 ? CryptoMode.CHAR_BY_CHAR : CryptoMode.FULL;

        String cipher = cryptoDispatcher.encrypt(text, v, m);
        String decrypted = cryptoDispatcher.decrypt(cipher, v, m);

        Map<String, Object> map = new HashMap<>();
        map.put("plainText", text);
        map.put("vendor", v.name());
        map.put("mode", m == CryptoMode.FULL ? "0-整段加密 (FULL)" : "1-逐字符拆分加密 (CHAR_BY_CHAR)");
        map.put("rawCipher", cipher);
        map.put("decryptedText", decrypted);
        map.put("isMatch", text.equals(decrypted));

        return ResultBody.success("加解密测试完成", map);
    }

    /**
     * 6. 测试逐字符加密字段（模式1）的密文模糊检索
     */
    @GetMapping("/task/fuzzy-search")
    @Operation(summary = "测试逐字符加密（模式1）密文模糊检索", description = "演示将用户输入的明文关键字逐字符加密后，直接利用 SQL LIKE %密文% 命中数据库，并自动解密返回")
    public ResultBody<Map<String, Object>> fuzzySearchBySourceNode(
            @Parameter(description = "明文检索关键字（如: NODE 或 01）", example = "NODE")
            @RequestParam(defaultValue = "NODE") String keyword) {

        // 1. 将明文关键字按当前激活厂商的模式 1（逐字符拆分加密）加密为纯 Hex 密文片段
        String keywordCipher = cryptoDispatcher.encrypt(keyword, CryptoMode.CHAR_BY_CHAR);

        // 2. 原生 SQL 执行密文模糊检索
        List<Map<String, Object>> rawRows = jdbcTemplate.queryForList(
                "SELECT id, task_code, task_name, source_node, encipher_tag FROM swap_task WHERE source_node LIKE ? ORDER BY id DESC",
                "%" + keywordCipher + "%"
        );

        // 3. 经过 MyBatis-Plus 查询自动还原明文
        List<SwapTask> decryptedTasks = swapTaskService.list(
                new LambdaQueryWrapper<SwapTask>()
                        .like(SwapTask::getSourceNode, keywordCipher)
                        .orderByDesc(SwapTask::getId)
        );

        Map<String, Object> res = new HashMap<>();
        res.put("inputKeyword", keyword);
        res.put("keywordCipherFragment(关键字逐字符加密后的密文片段)", keywordCipher);
        res.put("sqlExecuted", "SELECT * FROM swap_task WHERE source_node LIKE '%" + keywordCipher + "%'");
        res.put("matchedCount", rawRows.size());
        res.put("matchedRawDbRows(底表匹配的原始物理密文记录)", rawRows);
        res.put("decryptedEntities(MyBatis自动解密后的明文对象)", decryptedTasks);

        return ResultBody.success("密文模糊检索成功", res);
    }

    /**
     * 7. 标准业务透明加解密接口示范（Controller 零加解密代码，明文还原 100% 交由拦截器处理）
     */
    @GetMapping("/standard/{id}")
    @Operation(summary = "标准业务按 ID 查询（Controller 零解密代码）", description = "演示常规业务 Controller 写法：直接调用 Service 查询并返回，读取拦截器全自动将密文解密还原为原文")
    public ResultBody<SwapTask> getStandardTaskById(@PathVariable("id") Long id) {
        SwapTask task = swapTaskService.getById(id);
        if (task == null) {
            return ResultBody.failure("未找到 ID 为 " + id + " 的任务");
        }
        return ResultBody.success("查询成功", task);
    }

    /**
     * 8. 修改任务（测试更新时签名重算与防篡改阻断）
     * 若当前数据已被篡改 (data_tampered=2)，拦截器直接抛出 DataTamperedException 阻断更新！
     */
    @PutMapping("/task/{id}")
    @Operation(summary = "修改交换任务（测试防篡改阻断与签名重算）", description = "若记录已被篡改 (data_tampered=2) 则阻断更新并提示用户；正常记录则自动重新按当前厂商算法计算签名")
    public ResultBody<SwapTask> updateTask(
            @PathVariable("id") Long id,
            @RequestParam String taskName,
            @RequestParam String sourceNode) {

        SwapTask task = swapTaskService.getById(id);
        if (task == null) {
            return ResultBody.failure("任务记录不存在: ID=" + id);
        }

        task.setTaskName(taskName);
        task.setSourceNode(sourceNode);

        // 执行更新：触发写入拦截器
        // 写入拦截器自动检查当前 DB 中的 data_tampered 状态，若等于 2 则直接抛出 DataTamperedException 强行阻断！
        swapTaskService.updateById(task);
        return ResultBody.success("任务更新成功（已重新生成防篡改签名）", task);
    }

    /**
     * 9. 手动触发完整性防篡改巡检引擎
     */
    @PostMapping("/integrity/check")
    @Operation(summary = "手动触发后台数据完整性巡检", description = "使用当前激活密码厂商的算法全量校验数据签名，匹配更新为 1(正常)，篡改更新为 2(已篡改)")
    public ResultBody<List<GenericIntegrityCheckTask.IntegrityReport>> triggerIntegrityCheck() {
        List<GenericIntegrityCheckTask.IntegrityReport> result = integrityCheckTask.executeAll();
        return ResultBody.success("完整性巡检执行完毕", result);
    }

    /**
     * 10. 模拟黑客底层篡改数据（用于测试验证防篡改拦截）
     */
    @PostMapping("/integrity/tamper-simulate/{id}")
    @Operation(summary = "模拟底层数据篡改测试", description = "绕过业务逻辑直接在 MySQL 数据库修改数据内容，制造数据与签名不匹配或直接置 data_tampered=2")
    public ResultBody<Map<String, Object>> simulateTamper(
            @PathVariable("id") Long id,
            @Parameter(description = "篡改模式: 1-直接篡改数据库字段(触发下次巡检捕获), 2-直接标记为已篡改(立即触发更新阻断)")
            @RequestParam(defaultValue = "1") Integer tamperType) {

        if (tamperType == 2) {
            jdbcTemplate.update("UPDATE swap_task SET data_tampered = 2 WHERE id = ?", id);
        } else {
            // 直接在数据库篡改 source_node（绕过 MyBatis 写入拦截器，不重新计算签名）
            jdbcTemplate.update("UPDATE swap_task SET source_node = 'HACKED_DATA_007' WHERE id = ?", id);
        }

        Map<String, Object> currentDb = jdbcTemplate.queryForMap(
                "SELECT id, task_name, source_node, sign_value, data_tampered FROM swap_task WHERE id = ?", id
        );

        Map<String, Object> res = new HashMap<>();
        res.put("tamperType", tamperType == 2 ? "直接标记 data_tampered=2" : "直接篡改数据内容（签名失效）");
        res.put("currentDbRow", currentDb);
        res.put("tip", tamperType == 2 ? "现在可立即调用 PUT /crypto/test/task/" + id + " 验证阻断！" : "现在可调用 POST /crypto/test/integrity/check 巡检，该数据将被识别为 2！");

        return ResultBody.success("模拟篡改完成", res);
    }
}
