package com.zkjs.aio.swap.crypto.enums;

/**
 * 密码机厂家枚举（移除 AUTO，默认值为 CUSTOM）
 *
 * @author wuyw
 */
public enum HsmVendor {

    CUSTOM("custom", "自定义密码机(国密软实现)"),
    YUWENG("yuweng", "渔翁密码机"),
    YISUO("yisuo", "一所密码机");

    private final String code;
    private final String desc;

    HsmVendor(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据厂商编码解析，未匹配时默认回退 CUSTOM
     */
    public static HsmVendor of(String code) {
        if (code == null || code.trim().isEmpty()) {
            return CUSTOM;
        }
        for (HsmVendor vendor : values()) {
            if (vendor.getCode().equalsIgnoreCase(code.trim())) {
                return vendor;
            }
        }
        return CUSTOM;
    }
}
