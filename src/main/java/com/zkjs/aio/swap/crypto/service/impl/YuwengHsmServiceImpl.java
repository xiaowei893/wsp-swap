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
 * 渔翁密码机服务实现
 *
 * @author wuyw
 */
@Slf4j
@Service
public class YuwengHsmServiceImpl implements HsmService {

    private static final byte[] YUWENG_KEY = "YUWENG_HSM_KEY26".getBytes(StandardCharsets.UTF_8);
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    @Override
    public HsmVendor getVendor() {
        return HsmVendor.YUWENG;
    }

    @Override
    public String encrypt(String plainText, CryptoMode mode) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        log.debug("[渔翁密码机] 执行硬件加密, mode={}", mode);
        try {
            if (mode == CryptoMode.CHAR_BY_CHAR) {
                return CharFeistelCipher.encryptString(plainText, YUWENG_KEY);
            } else {
                return encryptBlock(plainText);
            }
        } catch (Exception e) {
            log.error("[渔翁密码机] 加密失败: plainText={}", plainText, e);
            throw new RuntimeException("Yuweng HSM encrypt failed", e);
        }
    }

    @Override
    public String decrypt(String rawCipher, CryptoMode mode) {
        if (rawCipher == null || rawCipher.isEmpty()) {
            return rawCipher;
        }
        log.debug("[渔翁密码机] 执行硬件解密, mode={}", mode);
        try {
            if (mode == CryptoMode.CHAR_BY_CHAR) {
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
                return CharFeistelCipher.decryptString(rawCipher, YUWENG_KEY);
            } else {
                return decryptBlock(rawCipher);
            }
        } catch (Exception e) {
            log.error("[渔翁密码机] 解密失败: rawCipher={}", rawCipher, e);
            throw new RuntimeException("Yuweng HSM decrypt failed", e);
        }
    }

    @Override
    public String sign(String plainText) {
        if (plainText == null) {
            return null;
        }
        return Sm3DigestUtil.hmacSm3Hex(YUWENG_KEY, plainText);
    }

    private String encryptBlock(String text) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKeySpec keySpec = new SecretKeySpec(YUWENG_KEY, ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encryptedBytes = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(encryptedBytes);
    }

    private String decryptBlock(String cipherBlock) throws Exception {
        String actualCipher = cipherBlock.startsWith("YW") ? cipherBlock.substring(2) : cipherBlock;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKeySpec keySpec = new SecretKeySpec(YUWENG_KEY, ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decodedBytes = HexFormat.of().parseHex(actualCipher);
        return new String(cipher.doFinal(decodedBytes), StandardCharsets.UTF_8);
    }
}
