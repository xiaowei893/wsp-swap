package com.zkjs.aio.swap.crypto.service;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;

/**
 * 密码机抽象标准接口 (SPI)
 * 各密码机硬件厂商适配器必须实现此接口
 *
 * @author wuyw
 */
public interface HsmService {

    /**
     * 获取支持的厂商枚举
     */
    HsmVendor getVendor();

    /**
     * 加密接口
     *
     * @param plainText 明文字符串
     * @param mode      加密方式（0-整段，1-逐字符）
     * @return 原始密文字符串（未带信封前缀）
     */
    String encrypt(String plainText, CryptoMode mode);

    /**
     * 解密接口
     *
     * @param rawCipher 原始密文字符串（已剥离信封前缀）
     * @param mode      加密方式（0-整段，1-逐字符）
     * @return 还原后的明文字符串
     */
    String decrypt(String rawCipher, CryptoMode mode);

    /**
     * 数据完整性防篡改签名计算
     *
     * @param plainText 按 @IntegrityField 顺序拼接的待签明文字符串
     * @return 签名值（纯 Hex 字符串）
     */
    String sign(String plainText);

    /**
     * 校验数据完整性签名
     *
     * @param plainText 待校验明文字符串
     * @param signature 表中存储的签名值
     * @return true 签名一致（未篡改），false 签名不匹配（被篡改）
     */
    default boolean verify(String plainText, String signature) {
        if (signature == null || plainText == null) {
            return false;
        }
        return signature.equalsIgnoreCase(sign(plainText));
    }
}
