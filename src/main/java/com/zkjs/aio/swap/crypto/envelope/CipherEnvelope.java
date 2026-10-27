package com.zkjs.aio.swap.crypto.envelope;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * 生产级自描述密文信封（Cipher Envelope）
 * 规范格式：$HSM${vendor}#{mode}#{rawCipher}
 * 示例：$HSM$yuweng#0#E4A8C5...
 *
 * 核心价值：
 * 1. 密文自带厂商编码与模式信息，历史旧密文无论何时读取均能准确使用原厂商解密；
 * 2. 避免厂商切换迁移期间发生新旧密文混淆导致的解密异常；
 * 3. 避免对已加密数据进行重复加密（幂等）。
 *
 * @author wuyw
 */
public final class CipherEnvelope {

    public static final String PREFIX = "$HSM$";
    public static final String SEPARATOR = "#";

    private CipherEnvelope() {
    }

    /**
     * 将原始密文包装为信封格式
     */
    public static String pack(HsmVendor vendor, CryptoMode mode, String rawCipher) {
        if (!StringUtils.hasText(rawCipher)) {
            return rawCipher;
        }
        HsmVendor actualVendor = vendor != null ? vendor : HsmVendor.CUSTOM;
        CryptoMode actualMode = mode != null ? mode : CryptoMode.FULL;
        return PREFIX + actualVendor.getCode() + SEPARATOR + actualMode.getMode() + SEPARATOR + rawCipher;
    }

    /**
     * 解析信封密文
     */
    public static EnvelopePayload unpack(String cipherText) {
        if (!isEncrypted(cipherText)) {
            return null;
        }
        String body = cipherText.substring(PREFIX.length());
        String[] parts = body.split(SEPARATOR, 3);
        if (parts.length < 3) {
            return null;
        }
        HsmVendor vendor = HsmVendor.of(parts[0]);
        CryptoMode mode = CryptoMode.of(Integer.parseInt(parts[1]));
        return new EnvelopePayload(vendor, mode, parts[2]);
    }

    /**
     * 判断字符串是否属于受纳管的信封密文
     */
    public static boolean isEncrypted(String text) {
        return text != null && text.startsWith(PREFIX);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnvelopePayload {
        private HsmVendor vendor;
        private CryptoMode mode;
        private String rawCipher;
    }
}
