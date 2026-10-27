package com.zkjs.aio.swap.crypto.service.impl;

/**
 * 紧凑型字符级保序置换加解密器（基于 16 位 4 轮 Feistel 网络）
 * 专用于 Mode 1（拆分成单个字符加密）：
 * 1. 严格针对每个字符独立加密，天然支持字符级匹配与模糊检索（LIKE %enc(c)%）；
 * 2. 彻底解决分组密码（AES/SM4）因 PKCS5Padding 导致的【1个字符膨胀为32个字节】的存储灾难；
 * 3. 单字符固定输出 4 位 Hex（如 7 个字符仅输出 28 位密文），杜绝 MySQL Data truncation 异常；
 * 4. 100% 双向数学保真还原，支持中文、英文字母、数字及全部 Unicode 字符。
 *
 * @author wuyw
 */
public final class CharFeistelCipher {

    private CharFeistelCipher() {
    }

    /**
     * 逐字符加密为紧凑 Hex 字符串
     */
    public static String encryptString(String text, byte[] key) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int[] roundKeys = deriveRoundKeys(key);
        StringBuilder sb = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int enc = encryptChar(c, roundKeys);
            sb.append(String.format("%04x", enc));
        }
        return sb.toString();
    }

    /**
     * 将 4 位 Hex 密文字符还原为原字符
     */
    public static String decryptString(String cipher, byte[] key) {
        if (cipher == null || cipher.isEmpty()) {
            return cipher;
        }
        int[] roundKeys = deriveRoundKeys(key);
        StringBuilder sb = new StringBuilder(cipher.length() / 4);
        for (int i = 0; i < cipher.length(); i += 4) {
            String hex = cipher.substring(i, Math.min(i + 4, cipher.length()));
            int val = Integer.parseInt(hex, 16);
            char dec = decryptChar(val, roundKeys);
            sb.append(dec);
        }
        return sb.toString();
    }

    private static int encryptChar(char c, int[] k) {
        int left = (c >>> 8) & 0xFF;
        int right = c & 0xFF;
        for (int i = 0; i < 4; i++) {
            int nextLeft = right;
            int f = ((right * 31) ^ k[i]) & 0xFF;
            int nextRight = left ^ f;
            left = nextLeft;
            right = nextRight;
        }
        return (left << 8) | right;
    }

    private static char decryptChar(int cipher, int[] k) {
        int left = (cipher >>> 8) & 0xFF;
        int right = cipher & 0xFF;
        for (int i = 3; i >= 0; i--) {
            int prevRight = left;
            int f = ((left * 31) ^ k[i]) & 0xFF;
            int prevLeft = right ^ f;
            left = prevLeft;
            right = prevRight;
        }
        return (char) ((left << 8) | right);
    }

    private static int[] deriveRoundKeys(byte[] key) {
        int[] roundKeys = new int[4];
        for (int i = 0; i < 4; i++) {
            roundKeys[i] = (key[i % key.length] ^ key[(i + 4) % key.length] ^ (i * 0x5A)) & 0xFF;
        }
        return roundKeys;
    }
}
