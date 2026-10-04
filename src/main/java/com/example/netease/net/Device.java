package com.example.netease.net;

import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备身份（0.11.7，W1）：插件自带「精简客户端」的设备指纹。
 *
 * <p><b>为什么需要它</b>：0.11.6 及以前，插件的设备身份是从<b>本机网易云客户端</b>的
 * cookie 里捡来的（已随 0.11.7 删除的 {@code svc/ClientBridge.java} 经 CDP 读取）——客户端
 * 一关，插件就成了「没有设备的裸请求」，网易云的风控（8821 / 503）更容易命中，而且这条依赖
 * 正是验收 A4 要拆掉的东西。现在设备身份由插件自己持有：{@code deviceId} 本地生成并持久化，
 * 其余字段是固定的「手机形态」客户端画像。</p>
 *
 * <p><b>落盘</b>：{@code <插件数据>\device.json}，内容只有设备 id 与画像字段
 * （{@code deviceId} / {@code appver} / {@code os} / {@code createdAt}）。**不含任何凭据** ——
 * 登录 cookie 仍然只走 {@link com.example.netease.svc.CookieVault} 的 AES-GCM 密文
 * （{@code account.json} / {@code account.key}）。</p>
 *
 * <p><b>线程</b>：{@link #init(Path)} 只写内存（宿主回调线程可以安全调用，绝不做磁盘 IO）；
 * 真正的读/建文件发生在第一次 {@link #deviceId()}，而它只在 {@code netease-account} 线程上
 * 被登录路径调用。</p>
 *
 * <p><b>日志红线</b>：{@code deviceId} 视同设备指纹，日志里只出现掩码（{@link #masked()}），
 * 永不打印完整值。</p>
 */
public final class Device {

    /** 设备身份文件名（落在插件数据目录，与凭据密文分开）。 */
    public static final String FILE_NAME = "device.json";

    private static final String TAG = "netease-device";

    // ---------------- 精简客户端画像（手机形态：与 weapi 的 phone/countrycode 语义一致） ----------------

    /** 客户端平台标识（网易云 cookie 的 {@code os} 字段）。 */
    public static final String OS = "android";
    /** 客户端系统版本（{@code osver}）。 */
    public static final String OSVER = "13";
    /** 分发渠道（{@code channel}）。 */
    public static final String CHANNEL = "netease";
    /**
     * 精简客户端对外声明的应用版本（{@code appver}）。
     *
     * <p>注意：这是**网易云客户端的版本号**，不是插件版本（插件版本一律走
     * {@link com.example.netease.core.PluginVersions#read()}，不得硬编码）。取一个长期存在的
     * 稳定版号即可——服务端只把它当画像字段，不参与 weapi 签名。</p>
     */
    public static final String APPVER = "9.1.0";

    /** deviceId 字节数（16B = 32 位十六进制，与客户端同形）。 */
    private static final int ID_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    /** 插件数据目录（{@link #init(Path)} 写入；null = 无盘可用，退化成内存身份）。 */
    private static volatile Path dir;
    /** 已加载的 deviceId（32 位小写十六进制）；null = 尚未加载。 */
    private static volatile String deviceId;
    /** 本次会话的 NMTID（每次进程/每次登录会话随机，不落盘）。 */
    private static volatile String nmtid;
    /** 身份来源（日志用）：{@code 待加载} / {@code 文件} / {@code 新建} / {@code 内存}。 */
    private static volatile String source = "待加载";

    private Device() {
    }

    /**
     * 登记插件数据目录（**只写内存，不做磁盘 IO**）。
     *
     * <p>由 {@code svc.AccountService.init(Path)} 调用；可在宿主 UI 线程 / {@code HostBridgeWorker}
     * 上安全调用。真正的读/建文件推迟到第一次 {@link #deviceId()}（账号线程）。</p>
     *
     * @param dataDir 插件数据目录；null = 无盘（身份退化成内存态，日志会说明）
     */
    public static void init(Path dataDir) {
        dir = dataDir;
        deviceId = null;
        nmtid = null;
        source = "待加载";
        PluginLog.d(TAG, "设备身份目录已登记：" + (dataDir == null ? "(null，将退化为内存身份)" : dataDir.toString()));
    }

    /** 冒烟钩子：只清内存态（**不删** {@code device.json}），等价于「下个进程重新加载」。 */
    public static void reset() {
        synchronized (LOCK) {
            deviceId = null;
            nmtid = null;
            source = "待加载";
        }
    }

    /**
     * 取设备 id（32 位十六进制）。首次调用会读盘；文件缺失/损坏时新建并写盘。
     *
     * <p>只在 {@code netease-account} 线程上被登录路径调用（读盘 + 可能的写盘）。</p>
     */
    public static String deviceId() {
        String id = deviceId;
        if (id != null) {
            return id;
        }
        synchronized (LOCK) {
            if (deviceId != null) {
                return deviceId;
            }
            Path d = dir;
            if (d == null) {
                deviceId = randomHex(ID_BYTES);
                source = "内存（无 data 目录，身份不落盘）";
                PluginLog.w(TAG, "无插件数据目录：设备身份仅本次进程有效（deviceId=" + maskedOf(deviceId) + "）");
                return deviceId;
            }
            File f = d.resolve(FILE_NAME).toFile();
            String loaded = readId(f);
            if (loaded != null) {
                deviceId = loaded;
                source = "文件";
                PluginLog.i(TAG, "设备身份已加载：" + describeLoaded());
                return deviceId;
            }
            String fresh = randomHex(ID_BYTES);
            boolean written = writeId(d, f, fresh);
            deviceId = fresh;
            source = written ? "新建" : "内存（写入失败，身份不落盘）";
            if (written) {
                PluginLog.i(TAG, "设备身份已新建并落盘 " + FILE_NAME + "：" + describeLoaded());
            } else {
                PluginLog.w(TAG, "设备身份新建但**未能落盘**（下次启动会换新 id）：" + describeLoaded());
            }
            return deviceId;
        }
    }

    /** 设备 id 的掩码形态（日志用，永不打印完整值）；尚未加载时返回 {@code （未加载）}。 */
    public static String masked() {
        String id = deviceId;
        if (id == null) {
            return "（未加载）";
        }
        return maskedOf(id);
    }

    /** 设备身份来源（日志/排障用）。 */
    public static String source() {
        return source;
    }

    /** 一行身份描述（**不含完整 deviceId**，不触发读盘）。 */
    public static String describe() {
        String id = deviceId;
        String head = (id == null) ? "（未加载）" : maskedOf(id);
        return "os=" + OS + "/" + OSVER + " appver=" + APPVER + " channel=" + CHANNEL
                + " deviceId=" + head + "（来源 " + source + "）";
    }

    /**
     * 设备 cookie 的表单（顺序固定；{@code NMTID} 每次会话随机）。
     *
     * <p>刻意**不含** {@code clientSign}：那是客户端用私钥算出来的签名，插件算不出正确的值，
     * 宁可缺项也不要塞错项（服务端对缺项宽容，对错值不宽容）。</p>
     */
    public static Map<String, String> cookies() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("os", OS);
        out.put("appver", APPVER);
        out.put("osver", OSVER);
        out.put("channel", CHANNEL);
        out.put("deviceId", deviceId());
        out.put("NMTID", nmtid());
        return out;
    }

    /** 设备 cookie 的 {@code "k=v; k2=v2"} 形态（值含 deviceId，**不得**整体进日志）。 */
    public static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies().entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 把设备 cookie 补进 {@link Http} 的进程级 cookie jar（<b>只补缺项，绝不覆盖已有值</b>）。
     *
     * <p>为什么只补缺项：登录之后服务端可能回写它自己的 {@code deviceId} / {@code NMTID}
     * （客户端里看到的真实值），那些值比我们本地造的可信；用本地值覆盖会让「登录前后
     * 设备 id 不一致」，反而更容易被风控盯上。</p>
     *
     * <p>只在 {@code netease-account} 线程的登录路径上调用。</p>
     */
    static void ensureOnJar() {
        String current = Http.cookies();
        StringBuilder merged = new StringBuilder(current == null ? "" : current);
        int added = 0;
        for (Map.Entry<String, String> e : cookies().entrySet()) {
            if (Http.cookie(e.getKey()) != null) {
                continue;               // 已有（可能来自服务端 Set-Cookie）→ 不动
            }
            if (merged.length() > 0) {
                merged.append("; ");
            }
            merged.append(e.getKey()).append('=').append(e.getValue());
            added++;
        }
        if (added == 0) {
            return;
        }
        Http.setCookies(merged.toString());
        PluginLog.i(TAG, "设备身份已补进 cookie jar：" + added + " 项，deviceId=" + maskedOf(deviceId())
                + "（已有项一律保留）");
    }

    // ---------------------------------------------------------------- 内部

    /** 每次会话的 NMTID（16B 随机 → 32 位十六进制）。 */
    private static String nmtid() {
        String n = nmtid;
        if (n == null) {
            synchronized (LOCK) {
                if (nmtid == null) {
                    nmtid = randomHex(ID_BYTES);
                }
                n = nmtid;
            }
        }
        return n;
    }

    /** 读 {@code device.json}：合法（32 位十六进制）返回 id，缺失/损坏返回 null（永不抛）。 */
    private static String readId(File f) {
        try {
            if (!f.isFile() || f.length() <= 0L) {
                return null;
            }
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            String id = Json.str(root, "deviceId", null);
            if (id == null) {
                return null;
            }
            String t = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.matches("[0-9a-f]{32}")) {
                PluginLog.w(TAG, "device.json 里的 deviceId 形态非法（长度 " + t.length() + "）→ 重新生成");
                return null;
            }
            return t;
        } catch (Throwable t) {
            PluginLog.w(TAG, "读取 " + FILE_NAME + " 失败（按新建处理，不删文件）：" + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 写 {@code device.json}；成功返回 true（失败只记日志，绝不抛）。 */
    private static boolean writeId(Path dataDir, File f, String id) {
        try {
            Files.createDirectories(dataDir);
            Map<String, Object> obj = Json.newObject();
            obj.put("deviceId", id);
            obj.put("appver", APPVER);
            obj.put("os", OS);
            obj.put("createdAt", System.currentTimeMillis());
            Json.writeObject(f.toPath(), obj);
            return f.isFile() && f.length() > 0L;
        } catch (Throwable t) {
            PluginLog.e(TAG, "写入 " + FILE_NAME + " 失败（设备身份仅本次进程有效）", t);
            return false;
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 掩码：前 6 位 + 长度（{@code 3f2a1b…(32)}）。 */
    private static String maskedOf(String id) {
        if (id == null || id.isBlank()) {
            return "（无）";
        }
        String head = id.length() <= 6 ? id : id.substring(0, 6);
        return head + "…(" + id.length() + ")";
    }

    private static String describeLoaded() {
        return "deviceId=" + maskedOf(deviceId) + "，画像 os=" + OS + "/" + OSVER
                + " appver=" + APPVER + " channel=" + CHANNEL + "，来源=" + source;
    }
}
