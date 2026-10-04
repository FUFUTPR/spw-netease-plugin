package com.example.netease.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具（零依赖，全部在 java.base）。
 *
 * <p>用途：match_cache 的路径键（md5）、weapi 加密里的 md5、凭据完整性校验（sha256）。
 * 纯函数、无状态、可在任意线程调用。</p>
 */
public final class Hashes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    /** 字节数组 → 小写十六进制串。 */
    public static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 文本（UTF-8）→ md5 十六进制串。 */
    public static String md5Hex(String text) {
        return md5Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /** 字节数组 → md5 十六进制串。 */
    public static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    /** 字节数组 → sha256 十六进制串。 */
    public static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return hex(md.digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            // MD5 / SHA-256 是 JDK 必备算法，理论上不可达
            throw new IllegalStateException(algorithm + " 不可用", e);
        }
    }
}
