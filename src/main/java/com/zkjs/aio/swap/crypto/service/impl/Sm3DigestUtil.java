package com.zkjs.aio.swap.crypto.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 国密 SM3 杂凑算法标准软实现 (GM/T 0004-2012)
 * 输出 256 比特 (32 字节，64 位大写/小写 16 进制字符串)
 *
 * @author wuyw
 */
public final class Sm3DigestUtil {

    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    private Sm3DigestUtil() {
    }

    /**
     * 计算输入字节数组的标准 SM3 摘要（输出 64 位纯小写 Hex）
     */
    public static String sm3Hex(byte[] msg) {
        byte[] md = sm3(msg);
        return HexFormat.of().formatHex(md);
    }

    /**
     * 计算带密钥的 HMAC-SM3（保证签名不可伪造）
     */
    public static String hmacSm3Hex(byte[] key, String plainText) {
        byte[] textBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] k = key;
        int blockSize = 64;
        if (k.length > blockSize) {
            k = sm3(k);
        }
        if (k.length < blockSize) {
            byte[] temp = new byte[blockSize];
            System.arraycopy(k, 0, temp, 0, k.length);
            k = temp;
        }

        byte[] iPad = new byte[blockSize];
        byte[] oPad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            iPad[i] = (byte) (k[i] ^ 0x36);
            oPad[i] = (byte) (k[i] ^ 0x5c);
        }

        byte[] inner = new byte[blockSize + textBytes.length];
        System.arraycopy(iPad, 0, inner, 0, blockSize);
        System.arraycopy(textBytes, 0, inner, blockSize, textBytes.length);
        byte[] innerHash = sm3(inner);

        byte[] outer = new byte[blockSize + innerHash.length];
        System.arraycopy(oPad, 0, outer, 0, blockSize);
        System.arraycopy(innerHash, 0, outer, blockSize, innerHash.length);
        byte[] mac = sm3(outer);

        return HexFormat.of().formatHex(mac);
    }

    public static byte[] sm3(byte[] msg) {
        int len = msg.length;
        long bitLen = ((long) len) * 8;
        int remainder = len % 64;
        int padLen = (remainder < 56) ? (56 - remainder) : (120 - remainder);
        byte[] padded = new byte[len + padLen + 8];
        System.arraycopy(msg, 0, padded, 0, len);
        padded[len] = (byte) 0x80;

        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((bitLen >>> (i * 8)) & 0xff);
        }

        int[] v = IV.clone();
        int[] w = new int[68];
        int[] w1 = new int[64];

        for (int i = 0; i < padded.length; i += 64) {
            for (int j = 0; j < 16; j++) {
                w[j] = ((padded[i + j * 4] & 0xff) << 24)
                        | ((padded[i + j * 4 + 1] & 0xff) << 16)
                        | ((padded[i + j * 4 + 2] & 0xff) << 8)
                        | (padded[i + j * 4 + 3] & 0xff);
            }
            for (int j = 16; j < 68; j++) {
                w[j] = p1(w[j - 16] ^ w[j - 9] ^ Integer.rotateLeft(w[j - 3], 15))
                        ^ Integer.rotateLeft(w[j - 13], 7)
                        ^ w[j - 6];
            }
            for (int j = 0; j < 64; j++) {
                w1[j] = w[j] ^ w[j + 4];
            }

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];

            for (int j = 0; j < 64; j++) {
                int tj = (j <= 15) ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(tj, j % 32), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int tt1 = ff(a, b, c, j) + d + ss2 + w1[j];
                int tt2 = gg(e, f, g, j) + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = p0(tt2);
            }

            v[0] ^= a;
            v[1] ^= b;
            v[2] ^= c;
            v[3] ^= d;
            v[4] ^= e;
            v[5] ^= f;
            v[6] ^= g;
            v[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) ((v[i] >>> 24) & 0xff);
            out[i * 4 + 1] = (byte) ((v[i] >>> 16) & 0xff);
            out[i * 4 + 2] = (byte) ((v[i] >>> 8) & 0xff);
            out[i * 4 + 3] = (byte) (v[i] & 0xff);
        }
        return out;
    }

    private static int ff(int x, int y, int z, int j) {
        return (j <= 15) ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    private static int gg(int x, int y, int z, int j) {
        return (j <= 15) ? (x ^ y ^ z) : ((x & y) | (~x & z));
    }

    private static int p0(int x) {
        return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17);
    }

    private static int p1(int x) {
        return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23);
    }
}
