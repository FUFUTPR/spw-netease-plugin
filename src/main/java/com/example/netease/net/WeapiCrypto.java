package com.example.netease.net;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.example.netease.core.Json;

/**
 * 网易云 weapi 加密（零第三方依赖，只用 java.base 的 javax.crypto / java.security / java.util.Base64）。
 *
 * <p>算法（与 docs/03 调研、docs/05 §5 一致）：</p>
 * <ol>
 *   <li>随机生成 16 个 BASE62 字符作为 secretKey；</li>
 *   <li>first = AES-128-CBC-PKCS5(明文 JSON, key = PRESET_KEY, iv = IV) → Base64；</li>
 *   <li>params = AES-128-CBC-PKCS5(first, key = secretKey, iv = IV) → Base64；</li>
 *   <li>encSecKey = RSA(secretKey 字符逆序 → UTF-8 字节 → 十六进制大整数) ^ 0x10001 mod n，
 *       结果十六进制左补零到 256 位。</li>
 * </ol>
 *
 * <p>线程安全：全部静态方法无共享可变状态；随机数是每实例一个 SecureRandom（内部已同步）。</p>
 */
final class WeapiCrypto {

    /** 第一层固定 AES 密钥。 */
    static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    /** 固定 IV。 */
    static final String IV = "0102030405060708";
    /** secretKey 字符表。 */
    static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** RSA 公钥指数。 */
    static final BigInteger PUB_KEY_E = BigInteger.valueOf(0x010001L);
    /** RSA 模数（256 位十六进制）。 */
    static final String MODULUS_HEX =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41"
            + "7629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee25593257"
            + "5cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    /** encSecKey 十六进制定长。 */
    static final int ENC_SEC_KEY_HEX_LEN = 256;
    /** secretKey 长度。 */
    static final int SECRET_KEY_LEN = 16;

    private static final BigInteger MODULUS = new BigInteger(MODULUS_HEX, 16);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] IV_BYTES = IV.getBytes(StandardCharsets.UTF_8);

    private WeapiCrypto() {
    }

    /** weapi 请求体的两个表单字段。 */
    static final class Payload {
        private final String params;
        private final String encSecKey;

        public Payload(String params, String encSecKey) {
            this.params = params;
            this.encSecKey = encSecKey;
        }

        public String params() {
            return params;
        }

        public String encSecKey() {
            return encSecKey;
        }

        public String getParams() {
            return params;
        }

        public String getEncSecKey() {
            return encSecKey;
        }

        /** 只暴露长度，绝不把密文/密钥写进日志。 */
        @Override
        public String toString() {
            return "Payload{params=" + (params == null ? 0 : params.length()) + "chars, encSecKey="
                    + (encSecKey == null ? 0 : encSecKey.length()) + "chars}";
        }
    }

    /** 加密一段 JSON 文本（每次调用使用新随机 secretKey）。 */
    static Payload encryptJson(String json) {
        return encryptJsonWithKey(json, randomSecretKey());
    }

    /** 加密任意 map（内部走 core.Json.stringify，绝不抛）。 */
    static Payload encrypt(java.util.Map<String, Object> payload) {
        String json;
        try {
            json = Json.stringify(payload == null ? new java.util.LinkedHashMap<String, Object>() : payload);
        } catch (Throwable t) {
            json = "{}";
        }
        if (json == null || json.isEmpty()) {
            json = "{}";
        }
        return encryptJsonWithKey(json, randomSecretKey());
    }

    /**
     * 测试钩子：用指定 secretKey 加密，便于做确定性自检（固定输入 → 固定输出）。
     *
     * <p>不对外承诺稳定性，仅供 smoke 自测使用。</p>
     */
    static Payload encryptJsonWithKey(String json, String secretKey) {
        String plain = (json == null || json.isEmpty()) ? "{}" : json;
        String key = (secretKey == null || secretKey.length() != SECRET_KEY_LEN)
                ? randomSecretKey() : secretKey;
        try {
            String first = aesCbcBase64(plain, PRESET_KEY);
            String params = aesCbcBase64(first, key);
            String encSecKey = rsaEncryptSecretKey(key);
            return new Payload(params, encSecKey);
        } catch (Exception e) {
            // 加密失败在语义上等于"请求构造失败"，用 RuntimeException 让上层统一降级
            throw new IllegalStateException("weapi 加密失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static String randomSecretKey() {
        StringBuilder sb = new StringBuilder(SECRET_KEY_LEN);
        for (int i = 0; i < SECRET_KEY_LEN; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    /** AES-128-CBC / PKCS5Padding，加密后 Base64（标准表，带 '=' 填充）。 */
    private static String aesCbcBase64(String plain, String keyText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] keyBytes = keyText.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(IV_BYTES);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(out);
    }

    /** secretKey 字符逆序 → UTF-8 字节 → 十六进制字面量 → 大整数 → modPow(0x10001, n)。 */
    private static String rsaEncryptSecretKey(String secretKey) {
        StringBuilder reversed = new StringBuilder(secretKey).reverse();
        byte[] bytes = reversed.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(HEX[(b >>> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return rsaEncrypt(hex.toString());
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 十六进制大整数 ^ e mod n，再左补零到 256 位十六进制。 */
    private static String rsaEncrypt(String textHex) {
        BigInteger base = new BigInteger(textHex, 16);
        String out = base.modPow(PUB_KEY_E, MODULUS).toString(16);
        if (out.length() >= ENC_SEC_KEY_HEX_LEN) {
            return out;
        }
        StringBuilder sb = new StringBuilder(ENC_SEC_KEY_HEX_LEN);
        for (int i = out.length(); i < ENC_SEC_KEY_HEX_LEN; i++) {
            sb.append('0');
        }
        return sb.append(out).toString();
    }
}
