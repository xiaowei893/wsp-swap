package com.zkjs.aio.swap.controller;

import com.zkjs.aio.cache.util.RedisUtil;
import com.zkjs.aio.common.vo.ResultBody;
import com.zkjs.aio.crypto.core.CryptoDispatcher;
import com.zkjs.aio.crypto.core.VendorMapper;
import com.zkjs.aio.crypto.migration.CryptoDataMigrationService;
import com.zkjs.aio.crypto.task.GenericIntegrityCheckTask;
import com.zkjs.gm.pool.enums.PwdFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 国密加解密与防篡改统一运维控制台
 * 提供全库巡检表查询、手动巡检核验、全库/单表平滑重密清洗、密码厂商在线热切换等管理接口
 *
 * @author wuyw
 */
@Slf4j
@Tag(name = "国密安全运维中心", description = "数据完整性巡检、密码厂商切换与数据平滑重密迁移")
@RestController
@RequestMapping("/admin/crypto")
@RequiredArgsConstructor
public class CryptoAdminController {

    private final GenericIntegrityCheckTask integrityCheckTask;
    private final CryptoDataMigrationService migrationService;
    private final CryptoDispatcher cryptoDispatcher;

    // =========================================================================
    // 1. 数据完整性与防篡改巡检运维
    // =========================================================================

    @Operation(summary = "查询全库参与防篡改巡检的数据表元数据清单")
    @GetMapping("/integrity/tables")
    public ResultBody<List<GenericIntegrityCheckTask.IntegrityTableInfo>> getIntegrityTables() {
        // 自动反射扫描 MyBatis-Plus 中所有标注 @IntegrityField 或 @IntegrityAssociation 的数据表
        List<GenericIntegrityCheckTask.IntegrityTableInfo> tables = integrityCheckTask.scanIntegrityTables();
        return ResultBody.success(tables);
    }

    @Operation(summary = "手动触发全库防篡改数据完整性巡检")
    @PostMapping("/integrity/execute-all")
    public ResultBody<List<GenericIntegrityCheckTask.IntegrityReport>> executeAllIntegrityCheck() {
        log.info("[安全运维] 管理员手动触发全库防篡改巡检核验任务...");
        List<GenericIntegrityCheckTask.IntegrityReport> reports = integrityCheckTask.executeAll();
        return ResultBody.success(reports);
    }

    // =========================================================================
    // 2. 更换密码厂商与数据平滑重密清洗
    // =========================================================================

    @Operation(summary = "查询全库包含国密敏感加密字段的数据表元数据清单")
    @GetMapping("/migration/tables")
    public ResultBody<List<CryptoDataMigrationService.CryptoTableInfo>> getCryptoTables() {
        // 自动反射扫描全库所有标注了 @CryptoField 的实体表及其加密字段、模式
        List<CryptoDataMigrationService.CryptoTableInfo> tables = migrationService.scanCryptoTables();
        return ResultBody.success(tables);
    }

    @Operation(summary = "一键全量迁移全库所有含密表至目标密码厂商")
    @PostMapping("/migration/execute-all")
    public ResultBody<Map<String, Integer>> executeAllMigration(
            @Parameter(description = "目标密码厂商代码：custom (软国密), fisec (渔翁), gays (公安一所)", example = "fisec")
            @RequestParam String targetVendor) {
        PwdFactory target = VendorMapper.toPwdFactory(targetVendor);
        log.info("[安全运维] 管理员触发全库平滑重密迁移清洗任务，目标厂商: [{}]", target.name());
        // 自动遍历所有表逐表游标分批解密并使用新厂商算法重密，支持断点续跑（已迁移行自动跳过）
        Map<String, Integer> summary = migrationService.migrateAll(target);
        return ResultBody.success(summary);
    }

    @Operation(summary = "指定单张数据表执行平滑重密清洗")
    @PostMapping("/migration/execute-table")
    public ResultBody<Integer> executeTableMigration(
            @Parameter(description = "实体类全限定名，如 com.zkjs.aio.swap.entity.SwapTask")
            @RequestParam String entityClassName,
            @Parameter(description = "目标密码厂商代码：custom, fisec, gays", example = "gays")
            @RequestParam String targetVendor) throws ClassNotFoundException {
        Class<?> entityClass = Class.forName(entityClassName.trim());
        PwdFactory target = VendorMapper.toPwdFactory(targetVendor);
        log.info("[安全运维] 管理员触发单表平滑重密，实体: [{}], 目标厂商: [{}]", entityClassName, target.name());
        int count = migrationService.migrateTable(entityClass, target);
        return ResultBody.success(count);
    }

    // =========================================================================
    // 3. 密码厂商在线热切换与状态查询
    // =========================================================================

    @Operation(summary = "查询当前系统默认与在线生效的密码厂商")
    @GetMapping("/vendor/current")
    public ResultBody<Map<String, Object>> getCurrentVendor() {
        return ResultBody.success(Map.of(
                "defaultFactoryInConfig", cryptoDispatcher.getDefaultFactory().name(),
                "activeFactoryInRuntime", cryptoDispatcher.getActiveFactory().name()
        ));
    }

    @Operation(summary = "在线热切换全局生效密码厂商（秒级全集群同步生效）")
    @PostMapping("/vendor/switch")
    public ResultBody<String> switchActiveVendor(
            @Parameter(description = "目标厂商代码：custom, fisec, gays", example = "fisec")
            @RequestParam String targetVendor) {
        PwdFactory target = VendorMapper.toPwdFactory(targetVendor);
        String code = VendorMapper.toStandardCode(target);
        // 写入 Redis 全局热切换键，所有微服务节点读写拦截器立即切换为新厂商
        RedisUtil.set(CryptoDispatcher.ACTIVE_VENDOR_REDIS_KEY, code);
        log.warn("【重要安全操作】密码厂商已在线热切换为: [{}]，全集群节点即刻生效！", code);
        return ResultBody.success("密码厂商已成功在线热切换为: " + code);
    }
}
