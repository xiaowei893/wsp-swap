package com.zkjs.aio.swap.crypto.envelope;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加密标签（encipher_tag）辅助工具类
 * 规范格式：字段名:厂商:方式|字段名:厂商:方式
 * 示例：taskName:custom:0|sourceNode:plaintext:0
 *
 * @author wuyw
 */
public final class EncipherTagHelper {

    public static final String VENDOR_PLAINTEXT = "plaintext";
    public static final String TAG_DELIMITER = "|";
    public static final String FIELD_DELIMITER = ":";

    private EncipherTagHelper() {
    }

    /**
     * 单个加密字段的元数据条目
     */
    public record TagItem(String fieldName, String vendor, int mode) {
        public boolean isEncrypted() {
            return StringUtils.hasText(vendor) && !VENDOR_PLAINTEXT.equalsIgnoreCase(vendor.trim());
        }
    }

    /**
     * 将多个字段元数据组装为 encipher_tag 字符串
     */
    public static String buildTag(List<TagItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            TagItem item = items.get(i);
            sb.append(item.fieldName())
              .append(FIELD_DELIMITER)
              .append(StringUtils.hasText(item.vendor()) ? item.vendor() : VENDOR_PLAINTEXT)
              .append(FIELD_DELIMITER)
              .append(item.mode());
            if (i < items.size() - 1) {
                sb.append(TAG_DELIMITER);
            }
        }
        return sb.toString();
    }

    /**
     * 解析 encipher_tag 字符串为 Map<字段名, TagItem>
     */
    public static Map<String, TagItem> parseTag(String tagStr) {
        if (!StringUtils.hasText(tagStr)) {
            return Collections.emptyMap();
        }
        Map<String, TagItem> map = new LinkedHashMap<>();
        String[] tokens = tagStr.split("\\" + TAG_DELIMITER);
        for (String token : tokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String[] parts = token.split(FIELD_DELIMITER);
            if (parts.length >= 3) {
                String fieldName = parts[0].trim();
                String vendor = parts[1].trim();
                int mode = 0;
                try {
                    mode = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {
                }
                map.put(fieldName, new TagItem(fieldName, vendor, mode));
            }
        }
        return map;
    }
}
