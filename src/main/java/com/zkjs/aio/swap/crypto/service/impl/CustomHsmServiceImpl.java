package com.zkjs.aio.swap.crypto.service.impl;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.service.HsmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 自定义密码机实现（基于纯软对称加解密算法，作为本地测试与系统默认实现）
 *
 * @author wuyw
 */
@Slf4j
@Service
public class CustomHsmServiceImpl implements HsmService {

    // 默认内置 16 字节密钥 (AES-128 / 国密 SM4 兼容等长密钥)
    private static final byte[] DEFAULT_KEY = "AIO_HSM_KEY_2026".getBytes(StandardCharsets.UTF_8);
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    @Override
    public HsmVendor getVendor() {
        return HsmVendor.CUSTOM;
    }

    @Override
    public String encrypt(String plainText, CryptoMode mode) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            if (mode == CryptoMode.CHAR_BY_CHAR) {
                // 模式 1：紧凑型逐字符保序加密（1个字符输出4位Hex，杜绝数据截断）
                return CharFeistelCipher.encryptString(plainText, DEFAULT_KEY);
            } else {
                // 模式 0：整段字符加密
                return encryptBlock(plainText);
            }
        } catch (Exception e) {
            log.error("自定义密码机加密异常: plainText={}", plainText, e);
            throw new RuntimeException("Custom HSM encrypt failed", e);
        }
    }

    @Override
    public String decrypt(String rawCipher, CryptoMode mode) {
        if (rawCipher == null || rawCipher.isEmpty()) {
            return rawCipher;
        }
        try {
            if (mode == CryptoMode.CHAR_BY_CHAR) {
                // 兼容历史带下划线的旧格式
                if (rawCipher.contains("_")) {
                    String[] tokens = rawCipher.split("_");
                    StringBuilder sb = new StringBuilder();
                    for (String token : tokens) {
                        if (!token.isEmpty()) {
                            sb.append(decryptBlock(token));
                        }
                    }
                    return sb.toString();
                }
                // 紧凑格式还原
                return CharFeistelCipher.decryptString(rawCipher, DEFAULT_KEY);
            } else {
                // 模式 0：整段字符解密
                return decryptBlock(rawCipher);
            }
        } catch (Exception e) {
            log.error("自定义密码机解密异常: rawCipher={}", rawCipher, e);
            throw new RuntimeException("Custom HSM decrypt failed", e);
        }
    }

    @Override
    public String sign(String plainText) {
        if (plainText == null) {
            return null;
        }
        return Sm3DigestUtil.hmacSm3Hex(DEFAULT_KEY, plainText);
    }

    private String encryptBlock(String text) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKeySpec keySpec = new SecretKeySpec(DEFAULT_KEY, ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encryptedBytes = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(encryptedBytes);
    }

    private String decryptBlock(String cipherBlock) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKeySpec keySpec = new SecretKeySpec(DEFAULT_KEY, ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decodedBytes = HexFormat.of().parseHex(cipherBlock);
        return new String(cipher.doFinal(decodedBytes), StandardCharsets.UTF_8);
    }
}
