package com.ossfilebrowse.plus.utils;

import java.util.Base64;

public class Base64Utils {

    // Base64 编码
    public static String encode(String plainText) {
        return Base64.getEncoder().encodeToString(plainText.getBytes());
    }

    // URL safe Base64 编码（避免 '+' '/' '=' 在 query 中带来兼容性问题）
    public static String encodeUrlSafe(String plainText) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plainText.getBytes());
    }

    // Base64 解码
    public static String decode(String base64Text) {
        byte[] decodedBytes = Base64.getDecoder().decode(base64Text);
        return new String(decodedBytes);
    }

}
