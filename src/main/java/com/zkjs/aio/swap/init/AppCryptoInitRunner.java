package com.zkjs.aio.swap.init;

import com.zkjs.aio.crypto.core.CryptoVoucherManager;
import com.zkjs.gm.pool.PwdPoolClient;
import com.zkjs.gm.pool.enums.PwdFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 生产级密码厂商凭证安全初始化 Runner
 * 遵循 AIO Framework 国密组件接入规范（7.2.4 落地实战样例 1）
 *
 * @author wuyw
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AppCryptoInitRunner implements ApplicationRunner {

    private final PwdPoolClient pwdPoolClient;
    private final CryptoVoucherManager voucherManager;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[密码服务自检] 正在并发安全初始化所有密码厂商凭证...");
        initVoucherIfAbsent(PwdFactory.CUSTOM);
    }

    private void initVoucherIfAbsent(PwdFactory factory) {
        try {
            String existing = voucherManager.getVoucher(factory);
            if (existing != null && !existing.isBlank()) {
                log.info("[密码服务自检] 厂商 [{}] 有效操作凭证已就绪，跳过初始化。", factory);
                return;
            }
        } catch (Exception ignored) {
            log.warn("[密码服务自检] 厂商 [{}] 凭证未就绪，正在执行自动化初始化...", factory);
        }

        if (factory == PwdFactory.CUSTOM) {
            initCustomVoucher();
        }
    }

    private void initCustomVoucher() {
        log.info("[密码服务初始化] 正在生成软国密 CUSTOM 密钥对...");
        // 1. 生成 16 字节随机 Hex SM4 对称密钥（32 字符）
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String sm4Key = sb.toString();

        // 2. 生成 SM2 非对称密钥对 (兼容大写与驼峰命名)
        Map<String, String> sm2Keys = pwdPoolClient.getCustomSm2PubAndPriKeys();
        String priKey = "";
        String pubKey = "";
        if (sm2Keys != null) {
            priKey = sm2Keys.get("PRIVATE_KEY") != null ? sm2Keys.get("PRIVATE_KEY") : sm2Keys.get("privateKey");
            pubKey = sm2Keys.get("PUBLIC_KEY") != null ? sm2Keys.get("PUBLIC_KEY") : sm2Keys.get("publicKey");
        }

        // 3. 按照中台 SDK 规范拼接为 Base64: sm4Key-priKey-pubKey
        String raw = sm4Key + "-" + (priKey != null ? priKey : "") + "-" + (pubKey != null ? pubKey : "");
        String voucher = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        // 4. 落库并同步写入 Redis 两级缓存
        voucherManager.saveAndCacheVoucher(PwdFactory.CUSTOM, voucher);
        log.info("[密码服务初始化] 软国密 CUSTOM 凭证初始化成功！");
    }
}
