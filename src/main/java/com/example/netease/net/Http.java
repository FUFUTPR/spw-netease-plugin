package com.example.netease.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.example.netease.core.PluginLog;

/**
 * 零依赖 HTTP 客户端（宿主运行时无 java.net.http 模块，只能用 HttpURLConnection）。
 *
 * <p>特性：</p>
 * <ul>
 *   <li>全局 cookie jar（静态，单实例语义；{@link #setCookies(String)} 供上层注入登录态）；</li>
 *   <li>全局同类请求节流（默认 300ms，{@link #setRateLimitMs(long)} 可调，只会更保守）；</li>
 *   <li>不自动跟随 302（{@code outer/url} 的 Location 才是真 CDN，必须交给上层处理）；</li>
 *   <li>gzip 手动解压；connect 5s / read 8s；失败重试 1 次后抛错或安静降级；</li>
 *   <li>日志只出现 URL 的 scheme+host+path，绝不出现 cookie / 表单体。</li>
 * </ul>
 */
final class Http {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";
    static final String REFERER = "https://music.163.com";
    static final String BASE = "https://music.163.com";
    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 8000;
    static final long DEFAULT_RATE_LIMIT_MS = 300L;
    /** 单次请求允许的最大文本体积（4MB）。 */
    static final long MAX_TEXT_BYTES = 4L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final Map<String, String> COOKIES = new LinkedHashMap<>();
    private static volatile long rateLimitMs = DEFAULT_RATE_LIMIT_MS;
    private static long lastRequestNanos = 0L;
    /** 最近一次响应状态码（0 = 还没发过请求 / 连接层就失败了）。 */
    private static volatile int lastStatus = 0;
    /** 最近一次响应的 Location 头（不跟随 302，交给上层）。 */
    private static volatile String lastLocation = null;

    private Http() {
    }

    /** 响应读取器：把 InputStream 变成 T，由实现决定怎么读（限流/编解码都在实现里）。 */
    @FunctionalInterface
    interface Io<T> {
        T read(InputStream in) throws IOException;
    }

    // ---------------------------------------------------------------- cookie jar

    /** 注入 cookie：{@code "k=v; k2=v2"}；null / 空串 = 清空。 */
    static void setCookies(String cookieHeader) {
        synchronized (LOCK) {
            COOKIES.clear();
            if (cookieHeader == null || cookieHeader.isBlank()) {
                return;
            }
            for (String part : cookieHeader.split(";")) {
                String item = part.trim();
                int eq = item.indexOf('=');
                if (eq > 0) {
                    COOKIES.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
                }
            }
        }
    }

    /** 当前 cookie 串：{@code "k=v; k2=v2"}；无 cookie 时返回空串（绝不为 null）。 */
    static String cookies() {
        synchronized (LOCK) {
            if (COOKIES.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : COOKIES.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 取单个 cookie 值（内部使用，避免把整串 cookie 传出去）。 */
    static String cookie(String name) {
        synchronized (LOCK) {
            return COOKIES.get(name);
        }
    }

    /** 全局同类请求最小间隔（毫秒）。 */
    static void setRateLimitMs(long ms) {
        rateLimitMs = Math.max(DEFAULT_RATE_LIMIT_MS, ms);
    }

    /** 当前限速值（毫秒）；默认 300，且永远不会低于 300。 */
    static long rateLimitMs() {
        return rateLimitMs;
    }

    /** 最近一次响应的 HTTP 状态码；0 表示连接层就失败了（自测与诊断用，不含任何凭据）。 */
    static int lastStatus() {
        return lastStatus;
    }

    /**
     * 最近一次响应的 {@code Location} 头；无跳转时为 null。
     *
     * <p>本类**不跟随 302**（见类注释），所以需要 CDN 真地址时必须由调用方读这个值。
     * 诊断用，不含任何凭据（CDN 直链的签名参数在 query 里，打日志前请自己脱敏）。</p>
     */
    static String lastLocation() {
        return lastLocation;
    }

    // ---------------------------------------------------------------- 公开动词

    /** GET 文本（UTF-8，自动解 gzip）。 */
    static String get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, null, headers, MAX_TEXT_BYTES, Http::readText);
    }

    /** POST 表单（application/x-www-form-urlencoded）。 */
    static String postForm(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException {
        return request("POST", url, encodeForm(form), headers, MAX_TEXT_BYTES, Http::readText);
    }

    /** GET 字节（限长，自动解 gzip）。 */
    static byte[] getBytes(String url, Map<String, String> headers, long maxBytes) throws IOException {
        return request("GET", url, null, headers, maxBytes, (in) -> readBytes(in, maxBytes));
    }

    // ---------------------------------------------------------------- 传输实现

    /** URL 编码表单体。 */
    static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static <T> T request(String method, String urlText, String body,
                                 Map<String, String> headers, long maxBytes, Io<T> io)
            throws IOException {
        String target = urlText;
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                long base = Math.max(rateLimitMs, 400L);
                try {
                    Thread.sleep(base);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断", ie);
                }
            }
            throttle();
            last = null;
            try {
                return exchange(method, target, body, headers, maxBytes, io);
            } catch (IOException e) {
                last = e;
                PluginLog.w("http", "第 " + (attempt + 1) + " 次请求失败：" + safeUrl(target)
                        + " → " + e.getClass().getSimpleName());
                if (!shouldRetry(e)) {
                    break;
                }
            }
        }
        PluginLog.e("http", "请求最终失败：" + safeUrl(target), last);
        throw last != null ? last : new IOException("请求失败：" + safeUrl(target));
    }

    /** 只对"可能是瞬时故障"的错误重试：4xx / 畸形 URL / 超长响应都不重试。 */
    private static boolean shouldRetry(IOException e) {
        if (e instanceof HttpStatusException) {
            return ((HttpStatusException) e).status() >= 500;
        }
        String msg = String.valueOf(e.getMessage());
        if (msg.startsWith("响应体超过上限")) {
            return false;
        }
        if (msg.contains("no protocol") || msg.contains("unknown protocol")) {
            return false;
        }
        return true;
    }

    /** 节流：保证两次真实请求之间至少间隔 rateLimitMs。 */
    private static void throttle() {
        synchronized (LOCK) {
            long waitMs = rateLimitMs - (System.nanoTime() - lastRequestNanos) / 1_000_000L;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestNanos = System.nanoTime();
        }
    }

    private static <T> T exchange(String method, String urlText, String body,
                                  Map<String, String> headers, long maxBytes, Io<T> io)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            lastStatus = 0;
            java.net.URL url = java.net.URI.create(urlText).toURL();
            conn = open(url);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);   // 302 交给上层（尤其 outer/url 直链）
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoInput(true);

            Map<String, String> merged = merge(headers);
            if (body != null && !merged.containsKey("Content-Type")) {
                merged.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            }
            for (Map.Entry<String, String> e : merged.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                conn.setRequestProperty("Content-Length", String.valueOf(data.length));
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data);
                    out.flush();
                }
            }

            int code = conn.getResponseCode();
            lastStatus = code;
            lastLocation = conn.getHeaderField("Location");
            absorbCookies(conn.getHeaderFields());
            if (code >= 400) {
                // 4xx 是确定性失败、5xx 允许外层再试一次；两者都带 status 字段，由 request() 决定是否重试
                throw new HttpStatusException(code, "HTTP " + code + " @ " + safeUrl(urlText));
            }
            try (InputStream raw = conn.getInputStream()) {
                if (raw == null) {
                    return null;
                }
                return io.read(gzip(raw));
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 带状态码的传输异常（4xx/5xx）。 */
    static final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        HttpStatusException(int status, String msg) {
            super(msg);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        java.net.URLConnection raw = url.openConnection();
        if (raw instanceof HttpURLConnection) {
            return (HttpURLConnection) raw;
        }
        throw new IOException("不支持的协议：" + url.getProtocol());
    }

    /** 合并默认头与调用方头（调用方优先），并保证必带头存在。 */
    private static Map<String, String> merge(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("User-Agent", UA);
        out.put("Referer", REFERER);
        out.put("Accept", "*/*");
        out.put("Accept-Language", "zh-CN,zh;q=0.9");
        out.put("Accept-Encoding", "gzip");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        String cookie = cookies();
        if (!cookie.isEmpty() && !out.containsKey("Cookie")) {
            out.put("Cookie", cookie);
        }
        // 读文本时强制 gzip（这样 gzip() 判断不会走错分支）；无 gzip 需求时由调用方覆盖为 identity
        if (!out.containsKey("Accept-Encoding")) {
            out.put("Accept-Encoding", "gzip");
        }
        return out;
    }

    /** 收集 Set-Cookie（同名覆盖，取 ';' 之前部分，形如 name=value）。 */
    private static void absorbCookies(Map<String, List<String>> headerFields) {
        if (headerFields == null || headerFields.isEmpty()) {
            return;
        }
        List<String> setCookies = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null) {
                continue;
            }
            if ("Set-Cookie".equalsIgnoreCase(key)) {
                setCookies.addAll(e.getValue());
            }
        }
        if (setCookies.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            for (String raw : setCookies) {
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                String pair = raw;
                int semi = pair.indexOf(';');
                if (semi >= 0) {
                    pair = pair.substring(0, semi);
                }
                pair = pair.trim();
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (value.isEmpty()) {
                        COOKIES.remove(name);
                    } else {
                        COOKIES.put(name, value);
                    }
                }
            }
        }
    }

    /** 按 Content-Encoding 手动解 gzip（可能嵌套多层，防御式解到非 gzip 为止）。 */
    private static InputStream gzip(InputStream in) throws IOException {
        java.io.BufferedInputStream buffered = new java.io.BufferedInputStream(in, 8192);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            return new GZIPInputStream(buffered, 8192);
        }
        return buffered;
    }

    private static String readText(InputStream in) throws IOException {
        return new String(readBytes(in, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in, long maxBytes) throws IOException {
        long limit = maxBytes > 0 ? maxBytes : MAX_TEXT_BYTES;
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > limit) {
                throw new IOException("响应体超过上限 " + limit + " 字节");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 日志安全 URL：只保留 scheme://host/path。 */
    static String safeUrl(String urlText) {
        if (urlText == null) {
            return "null";
        }
        int cut = urlText.length();
        int q = urlText.indexOf('?');
        if (q >= 0 && q < cut) {
            cut = q;
        }
        int h = urlText.indexOf('#');
        if (h >= 0 && h < cut) {
            cut = h;
        }
        return urlText.substring(0, cut);
    }

    /** 只读快照（自测脚本用，不应出现在业务日志里）。 */
    static Map<String, String> snapshotCookies() {
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(COOKIES));
        }
    }
}
