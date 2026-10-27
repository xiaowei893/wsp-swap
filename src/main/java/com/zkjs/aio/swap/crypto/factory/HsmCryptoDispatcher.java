package com.zkjs.aio.swap.crypto.factory;

import com.zkjs.aio.cache.util.RedisUtil;
import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.envelope.CipherEnvelope;
import com.zkjs.aio.swap.crypto.service.HsmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码机调度工厂门面
 * 核心职能：
 * 1. 纳管各厂家 HsmService 实现；
 * 2. 实时从 Redis 缓存获取当前激活厂家，动态路由；
 * 3. 统一信封封装与解构。
 *
 * @author wuyw
 */
@Slf4j
@Component
public class HsmCryptoDispatcher {

    public static final String REDIS_HSM_ACTIVE_VENDOR_KEY = "hsm:active_vendor";

    private final Map<HsmVendor, HsmService> serviceMap = new ConcurrentHashMap<>();

    public HsmCryptoDispatcher(List<HsmService> services) {
        for (HsmService service : services) {
            serviceMap.put(service.getVendor(), service);
            log.info("[HSM] 注册密码机厂商适配器: {} -> {}", service.getVendor().getCode(), service.getClass().getSimpleName());
        }
    }

    /**
     * 获取当前系统全局激活的密码机厂商
     * 优先从 Redis 获取，若缓存未命中或异常则默认回退至 HsmVendor.CUSTOM
     */
    public HsmVendor getActiveVendor() {
        try {
            String vendorCode = RedisUtil.get(REDIS_HSM_ACTIVE_VENDOR_KEY, String.class);
            if (StringUtils.hasText(vendorCode)) {
                return HsmVendor.of(vendorCode);
            }
        } catch (Exception e) {
            log.warn("[HSM] 读取 Redis 厂商缓存异常，回退至默认厂商 CUSTOM: {}", e.getMessage());
        }
        return HsmVendor.CUSTOM;
    }

    /**
     * 设置并同步当前激活厂商到 Redis 缓存
     */
    public void setActiveVendorCache(HsmVendor vendor) {
        HsmVendor actual = vendor != null ? vendor : HsmVendor.CUSTOM;
        RedisUtil.set(REDIS_HSM_ACTIVE_VENDOR_KEY, actual.getCode());
        log.info("[HSM] 已同步密码机激活厂商到 Redis 缓存: {}", actual.getCode());
    }

    /**
     * 加密统一入口：使用系统全局激活厂商加密
     *
     * @param plainText 明文
     * @param mode      加密模式（0-整段，1-逐字符）
     * @return 带信封前缀的密文
     */
    public String encrypt(String plainText, CryptoMode mode) {
        return encrypt(plainText, getActiveVendor(), mode);
    }

    /**
     * 加密统一入口：自动调用密码机返回纯净裸密文（无前缀）
     *
     * @param plainText    明文
     * @param targetVendor 目标厂商（若为 CUSTOM 则自动从 Redis 缓存获取系统激活厂商）
     * @param mode         加密模式（0-整段，1-逐字符）
     * @return 纯净物理密文（无任何信封前缀）
     */
    public String encrypt(String plainText, HsmVendor targetVendor, CryptoMode mode) {
        if (!StringUtils.hasText(plainText)) {
            return plainText;
        }

        // 默认 CUSTOM 时优先取缓存中的系统激活厂商；若显式指定了非 CUSTOM 厂商则以指定厂商优先
        HsmVendor actualVendor = (targetVendor == null || targetVendor == HsmVendor.CUSTOM)
                ? getActiveVendor()
                : targetVendor;

        HsmService hsmService = serviceMap.get(actualVendor);
        if (hsmService == null) {
            throw new IllegalStateException("未找到厂商 [" + actualVendor + "] 对应的密码机服务实现！");
        }

        CryptoMode actualMode = mode != null ? mode : CryptoMode.FULL;
        // 直接返回纯净密文
        return hsmService.encrypt(plainText, actualMode);
    }

    /**
     * 解密统一入口：依据指定的厂商与模式解密纯密文
     *
     * @param cipherText 待解密密文
     * @param vendor     加密厂商
     * @param mode       加密模式（0-整段，1-逐字符）
     * @return 明文
     */
    public String decrypt(String cipherText, HsmVendor vendor, CryptoMode mode) {
        if (!StringUtils.hasText(cipherText)) {
            return cipherText;
        }
        // 兼容带旧信封前缀的历史密文
        if (CipherEnvelope.isEncrypted(cipherText)) {
            return decrypt(cipherText);
        }

        HsmVendor actualVendor = vendor != null ? vendor : getActiveVendor();
        HsmService hsmService = serviceMap.get(actualVendor);
        if (hsmService == null) {
            throw new IllegalStateException("未找到厂商 [" + actualVendor + "] 对应的密码机服务实现！");
        }

        CryptoMode actualMode = mode != null ? mode : CryptoMode.FULL;
        try {
            return hsmService.decrypt(cipherText, actualMode);
        } catch (Exception e) {
            log.warn("[HSM] 密文解密失败，回退原值: cipher={}, vendor={}, err={}", cipherText, actualVendor.getCode(), e.getMessage());
            return cipherText;
        }
    }

    /**
     * 解密便捷重载：依据当前系统激活厂商解密
     */
    public String decrypt(String cipherText, CryptoMode mode) {
        return decrypt(cipherText, getActiveVendor(), mode);
    }

    /**
     * 解密统一入口：自适应解密（兼容信封密文或默认全段解密）
     *
     * @param cipherText 待解密密文
     * @return 明文
     */
    public String decrypt(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            return cipherText;
        }
        // 若带信封前缀，优先按信封拆包解密
        if (CipherEnvelope.isEncrypted(cipherText)) {
            CipherEnvelope.EnvelopePayload payload = CipherEnvelope.unpack(cipherText);
            if (payload != null) {
                HsmService hsmService = serviceMap.get(payload.getVendor());
                if (hsmService != null) {
                    return hsmService.decrypt(payload.getRawCipher(), payload.getMode());
                }
            }
        }
        return decrypt(cipherText, getActiveVendor(), CryptoMode.FULL);
    }

    /**
     * 获取指定厂商的服务实现
     */
    public HsmService getService(HsmVendor vendor) {
        return serviceMap.get(vendor != null ? vendor : HsmVendor.CUSTOM);
    }

    /**
     * 完整性签名统一入口：使用系统全局当前激活厂商的密码算法进行签名
     *
     * @param plainText 待签文字符串
     * @return 签名值（纯 Hex 格式）
     */
    public String sign(String plainText) {
        return sign(plainText, getActiveVendor());
    }

    /**
     * 指定厂商签名
     */
    public String sign(String plainText, HsmVendor vendor) {
        HsmService service = getService(vendor);
        if (service == null) {
            log.warn("[HSM] 未找到厂商 {} 的密码服务，回退至 CUSTOM", vendor);
            service = getService(HsmVendor.CUSTOM);
        }
        return service.sign(plainText);
    }

    /**
     * 完整性验签统一入口：使用系统全局当前激活厂商的密码算法进行验签
     *
     * @param plainText 待校验明文字符串
     * @param signature 数据库存储的原签名值
     * @return true 签名一致，false 被篡改
     */
    public boolean verify(String plainText, String signature) {
        return verify(plainText, signature, getActiveVendor());
    }

    /**
     * 指定厂商验签
     */
    public boolean verify(String plainText, String signature, HsmVendor vendor) {
        HsmService service = getService(vendor);
        if (service == null) {
            service = getService(HsmVendor.CUSTOM);
        }
        return service.verify(plainText, signature);
    }
}
