package com.zkjs.aio.swap.crypto.enums;

/**
 * 加密方式枚举
 * 0 - 整段字符加密
 * 1 - 拆分成单个字符加密
 *
 * @author wuyw
 */
public enum CryptoMode {

    FULL(0, "整段字符加密"),
    CHAR_BY_CHAR(1, "拆分成单个字符加密");

    private final int mode;
    private final String desc;

    CryptoMode(int mode, String desc) {
        this.mode = mode;
        this.desc = desc;
    }

    public int getMode() {
        return mode;
    }

    public String getDesc() {
        return desc;
    }

    public static CryptoMode of(int mode) {
        for (CryptoMode m : values()) {
            if (m.getMode() == mode) {
                return m;
            }
        }
        return FULL;
    }
}
