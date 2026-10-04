package com.example.netease.net;

import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * <b>离线</b>探针：证明「插件内原生登录」（取消 ClientBridge 之后）的登录路径
 * <b>请求构造正确</b>，全程<b>零网络请求</b>、<b>零本机客户端参与</b>。
 *
 * <p>为什么需要它：R2 要求登录方式至少两条（手机号 + 短信验证码、扫码二维码），
 * 其中至少一条必须有真机成功实证。真机成功由 {@code tools/smoke/smoke-sms.ps1} 系列
 * （联调，打真网）负责；本探针负责另一半——把「我们发出去的请求体到底长什么样、服务端能不能
 * 解回来」钉成可复现的断言，这样每条路径即使一时没有真机凭据也能被验证，且日后重构 weapi
 * 内核时有回归护栏。</p>
 *
 * <p>断言面（七节）：空参护栏不打网络；验证码 / 发码两条载荷的键集合与取值；weapi 双层 AES
 * 往返可被独立实现解回；{@code encSecKey} 与「secretKey 逆序 → hex →
 * ^0x10001 mod n → 左补零 256」的独立复算逐字相同（公钥方向可证，服务端持 d 解得 secretKey）；
 * 设备字段齐全且<b>刻意不含 clientSign</b>；
 * {@code Device.ensureOnJar()} 只补缺项、不覆盖服务端回写值、不清空已有会话 cookie；日志脱敏
 * （完整 deviceId / 会话金丝雀 / 明文密码 / 验证码一律 0 命中，并以掩码 deviceId 作阳性对照证明
 * 扫描有效）；<b>退役不留痕</b>——「手机号 + 密码」路径的公开方法（{@code loginByPassword} /
 * {@code passwordPayload}）与兼容入口 {@code loginCellphone} 必须以反射断言「不存在」，
 * 防止日后回潮。</p>
 *
 * <p><b>本文件不属于插件产物</b>（{@code tools/} 下的开发期探针）；放在
 * {@code com.example.netease.net} 包内是为了直接使用包内可见的
 * {@code WeapiCrypto} / {@code Http} / {@code Device.ensureOnJar()}，不做反射。</p>
 *
 * <p>构建与运行（仓库根）——<b>输出目录必须先删干净</b>：本探针用反射断言「某方法不存在」，
 * 若输出目录里残留上一版编译出来的旧 {@code .class}（旧版还带 {@code loginByPassword} /
 * {@code promptPassword}），它们会盖在同名新类前面，把退役断言全部伪判成 FAIL（已踩过一次）。</p>
 * <pre>
 * Remove-Item -Recurse -Force build\w1-check -ErrorAction SilentlyContinue
 * $cp = "tools\.cache\spw-workshop-api-host.jar;tools\.cache\pf4j-3.12.0.jar;libs\sqlite-jdbc-3.41.2.2.jar"
 * javac --release 21 -encoding UTF-8 -cp $cp -d build\w1-check `
 *   (Get-ChildItem src\main\java\com\example\netease -Recurse -Filter *.java | % FullName) `
 *   tools\smoke\LoginShapeProbe.java
 * java "-Dstdout.encoding=UTF-8" -cp "build\w1-check;$cp" com.example.netease.net.LoginShapeProbe
 * </pre>
 * <p>退出码：0 = 全部 PASS，1 = 有 FAIL。全程不打印密码/验证码/cookie 值。</p>
 */
public final class LoginShapeProbe {

    private static final String TAG = "w1.login-shape";
    private static final String PHONE = "13800000000";
    private static final String FAKE_CAPTCHA = "907531";                 // 判别性取值：避免撞上随机十六进制
    private static final String FIXED_KEY = "0123456789abcdef";          // 16 位，等价于随机 secretKey 的位置
    private static final String SERVER_DEVICE_ID = "server-written-device-id";
    private static final String SESSION_CANARY = "canary-session-value";
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");

    private static final List<String> FAILURES = new ArrayList<>();
    private static int pass;
    private static int fail;

    private LoginShapeProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("w1-login-shape-");
        System.out.println("=== LoginShapeProbe：插件内原生登录的请求构造（离线，零网络）===");
        System.out.println("临时数据目录：" + dir);
        PluginLog.init(dir);
        try {
            PluginLog.setLevel("debug");
        } catch (Throwable ignored) {
            // 级别设置失败不影响断言（下面用 INFO 级日志行作阳性对照）
        }
        System.out.println("插件日志：" + PluginLog.file());
        System.out.println();

        int statusBefore = Http.lastStatus();

        section("一、空参护栏（不得发出任何请求）");
        NeteaseApi.LoginOutcome g3 = NeteaseApi.loginByCaptcha(PHONE, "");
        check("GUARD 验证码：验证码为空 → 拒绝",
                !g3.ok() && g3.code() == -2, "code=" + g3.code() + " msg=" + g3.message());
        NeteaseApi.LoginOutcome g4 = NeteaseApi.loginByCaptcha("", "");
        check("GUARD 验证码：手机号与验证码都为空 → 拒绝",
                !g4.ok() && g4.code() == -2, "code=" + g4.code() + " msg=" + g4.message());
        Dto.SmsResult g5 = NeteaseApi.smsSend("");
        check("GUARD 发码：手机号为空 → 拒绝",
                g5.code() == -2, "code=" + g5.code() + " msg=" + g5.message());

        section("一之二、退役不留痕（手机号 + 密码路径必须已彻底消失）");
        check("RETIRE 载荷构造 passwordPayload 不存在（包内可见也不许留）",
                !hasMethod(NeteaseApi.class, "passwordPayload", String.class, String.class),
                "反射查 com.example.netease.net.NeteaseApi#passwordPayload(String,String) ⇒ 未找到");
        check("RETIRE 公开入口 loginByPassword 不存在",
                !hasMethod(NeteaseApi.class, "loginByPassword", String.class, String.class),
                "反射查 com.example.netease.net.NeteaseApi#loginByPassword(String,String) ⇒ 未找到");
        check("RETIRE 兼容入口 loginCellphone 不存在",
                !hasMethod(NeteaseApi.class, "loginCellphone", String.class, String.class),
                "反射查 com.example.netease.net.NeteaseApi#loginCellphone(String,String) ⇒ 未找到");
        check("RETIRE 服务层入口 AccountService#loginByPassword / #loginCellphone 不存在",
                !hasMethod(Class.forName("com.example.netease.svc.AccountService"),
                        "loginByPassword", String.class, String.class)
                        && !hasMethod(Class.forName("com.example.netease.svc.AccountService"),
                        "loginCellphone", String.class, String.class),
                "反射查 svc.AccountService 两个密码入口 ⇒ 均未找到");
        // 0.11.22：登录整组搬到配置页原生控件（用户规格：不弹独立窗口）⇒ 独立模态窗**整类**退役。
        //  断言从「只剩 promptSms」升级为「类不存在」：Class.forName 成功反而说明回潮。
        boolean lpGone;
        String lpDetail;
        try {
            Class.forName("com.example.netease.ui.LoginPrompt");
            lpGone = false;
            lpDetail = "类仍在（0.11.22 起应为不存在）";
        } catch (ClassNotFoundException expected) {
            lpGone = true;
            lpDetail = "Class.forName 抛 ClassNotFoundException：整类已删除（promptSms 随之一并退役）";
        } catch (Throwable t) {
            lpGone = false;
            lpDetail = "反射异常（不算通过）：" + t;
        }
        check("RETIRE 独立模态窗 ui.LoginPrompt 整类不存在（0.11.22 登录改走配置页原生控件）",
                lpGone, lpDetail);

        // 0.11.30 需求②：扫码登录与验证码登录必须是**同一张卡片**，独立扫码窗（ui/LoginWindow，0.11.22
        //  的整屏原生化产物）整类退役。与 LoginPrompt 同构的反向断言：Class.forName 成功即回潮。
        //  注意 NeteasePlugin.showLoginDialog 这个**入口方法保留**（它是「扫码兜底」的路标，H9.5 仍在查），
        //  退役的只是那个独立窗口类。
        boolean lwGone;
        String lwDetail;
        try {
            Class.forName("com.example.netease.ui.LoginWindow");
            lwGone = false;
            lwDetail = "类仍在（0.11.30 起应为不存在）";
        } catch (ClassNotFoundException expected) {
            lwGone = true;
            lwDetail = "Class.forName 抛 ClassNotFoundException：独立扫码窗整类已删除";
        } catch (Throwable t) {
            lwGone = false;
            lwDetail = "反射异常（不算通过）：" + t;
        }
        check("RETIRE 独立扫码窗 ui.LoginWindow 整类不存在（0.11.30 扫码并入 LoginDialog 卡片）",
                lwGone, lwDetail);

        // 0.11.26：用户规格「点开登录弹出一个框，框里能分别填手机号和验证码」⇒ 登录块 = 配置页一个
        //  按钮（openLoginDialog）+ 插件自绘对话框 ui/LoginDialog（手机号框 + 验证码框 + 右侧
        //  「获取验证码」+ 底部「取消」「登录」）。宿主 edittext 行点开只有**一个**输入框，多控件表单
        //  只能自绘（宿主不提供对话框 API，docs/00 §6.27）。这里钉住「入口在、对话框在、公开面无漂移」。
        //  ⚠️ 一律 Class.forName(..., false, ...)：只验类与签名，不触发 Swing 静态初始化（无显示环境也能跑）。
        section("一之三、登录对话框形态（0.11.26 用户规格）");
        Class<?> dialogClz = null;
        String dialogDetail;
        try {
            dialogClz = Class.forName("com.example.netease.ui.LoginDialog", false,
                    LoginShapeProbe.class.getClassLoader());
            dialogDetail = "Class.forName 命中 com.example.netease.ui.LoginDialog";
        } catch (ClassNotFoundException e) {
            dialogDetail = "Class.forName 抛 ClassNotFoundException：登录对话框缺失（0.11.26 起必须存在）";
        } catch (Throwable t) {
            dialogDetail = "反射异常（不算通过）：" + t;
        }
        check("DIALOG ui.LoginDialog 存在（两框 + 获取验证码 + 取消/登录 的唯一载体）",
                dialogClz != null, dialogDetail);
        boolean hasOpen = hasMethod(dialogClz, "open", String.class);
        boolean hasIsOpen = hasMethod(dialogClz, "isOpen");
        check("DIALOG 公开面 open(String) / isOpen() 齐备（调用方只依赖这两条）",
                hasOpen && hasIsOpen,
                "反射查 open(String) ⇒ " + (hasOpen ? "在" : "缺") + "；isOpen() ⇒ " + (hasIsOpen ? "在" : "缺"));
        check("DIALOG 开窗签名 = public static void open(String)（宿主线程侧立即返回，建框交给 EDT）",
                isStaticVoid(dialogClz, "open", String.class),
                "反射查修饰符 ⇒ 要求 public static void、单参 String");
        Class<?> pluginClz = null;
        try {
            pluginClz = Class.forName("com.example.netease.NeteasePlugin", false,
                    LoginShapeProbe.class.getClassLoader());
        } catch (Throwable ignored) {
            // 下面两条断言会以「未找到」的形式失败，细节更清楚
        }
        check("ENTRY NeteasePlugin.openLoginDialog 存在（配置页「登录」按钮的 on_click 落点）",
                hasMethod(pluginClz, "openLoginDialog"),
                "反射查 NeteasePlugin#openLoginDialog() ⇒ "
                        + (hasMethod(pluginClz, "openLoginDialog") ? "找到无参方法" : "未找到"));
        check("ENTRY 入口签名 = public static void 无参（与 H9.4 同一把尺：宿主反射调用不得抛异常）",
                isStaticVoid(pluginClz, "openLoginDialog"),
                "反射查修饰符 ⇒ 要求 public static void、零参");
        check("ENTRY NeteasePlugin.showLoginDialog 保留（扫码兜底路标；0.11.30 起它开的也是 LoginDialog）",
                isStaticVoid(pluginClz, "showLoginDialog"),
                "反射查 showLoginDialog() ⇒ "
                        + (hasMethod(pluginClz, "showLoginDialog") ? "找到无参方法" : "未找到"));

        section("二、载荷构造（键集合 / 取值 / 无明文）");
        Map<String, Object> cap = NeteaseApi.captchaPayload(PHONE, FAKE_CAPTCHA);
        check("PAYLOAD 验证码：键集合 = {phone,countrycode,captcha,rememberLogin}",
                keys(cap).equals(sorted("phone,countrycode,captcha,rememberLogin")), "keys=" + keys(cap));
        check("PAYLOAD 验证码：captcha 原样、且不含 password 键",
                FAKE_CAPTCHA.equals(cap.get("captcha")) && !cap.containsKey("password"),
                "captcha=(" + FAKE_CAPTCHA.length() + " 位) 含 password=" + cap.containsKey("password"));
        check("PAYLOAD 验证码：phone 原样、countrycode=86、rememberLogin=true（会话要持久，A5 依赖）",
                PHONE.equals(cap.get("phone")) && "86".equals(cap.get("countrycode"))
                        && "true".equals(String.valueOf(cap.get("rememberLogin"))),
                "phone=" + maskPhone(String.valueOf(cap.get("phone"))) + " countrycode=" + cap.get("countrycode")
                        + " rememberLogin=" + cap.get("rememberLogin"));
        // 验证码与已退役的密码路径**安全属性不同**：旧密码路径在载荷里放的是 md5(明文)，所以当时
        // 能断言「请求体不含明文密码」；验证码是一次性口令，按 weapi 约定不做 md5 —— 原值进载荷、
        // 出网前由整包双层 AES 覆盖（该性质由 §三 的密文断言钉住）。这里能且只能断言的是：
        // 原值只存在于载荷对象里，**任何打印/日志文本一律脱敏**（探针自身脱敏自证）。
        check("PAYLOAD 验证码：凭据以原值进载荷（验证码按约定不做 md5），且任何打印文本里一律脱敏",
                FAKE_CAPTCHA.equals(cap.get("captcha")) && !redacted(cap).contains(FAKE_CAPTCHA),
                "json=" + redacted(cap) + "；原值进载荷 → weapi 双层 AES 后出网（见 §三）");

        Map<String, Object> sms = NeteaseApi.smsSendPayload(PHONE);
        check("PAYLOAD 发码：键集合 = {cellphone,ctcode}、ctcode=86",
                keys(sms).equals(sorted("cellphone,ctcode")) && "86".equals(sms.get("ctcode")),
                "keys=" + keys(sms) + " ctcode=" + sms.get("ctcode"));

        section("三、线格式：weapi 双层 AES 往返 + encSecKey 公钥还原");
        String json = Json.stringify(cap);
        WeapiCrypto.Payload enc = WeapiCrypto.encryptJsonWithKey(json, FIXED_KEY);
        check("WIRE params：非空且是合法 Base64",
                enc.params() != null && !enc.params().isEmpty() && decodes(enc.params()),
                "params=" + enc.params().length() + " 字符");
        String layer1 = aesDecBase64(enc.params(), FIXED_KEY);      // 外层：本次 secretKey
        String plain = aesDecBase64(layer1, WeapiCrypto.PRESET_KEY); // 内层：网易云预置密钥
        check("WIRE 往返：外层 AES(secretKey) 解得内层、内层 AES(PRESET_KEY) 解出原文 JSON",
                Json.parse(plain).equals(Json.parse(json)),
                "解回 " + plain.length() + " 字节，与原始载荷 JSON 语义相等");
        check("WIRE params 密文里不含明文手机号/验证码",
                !enc.params().contains(PHONE) && !enc.params().contains(FAKE_CAPTCHA),
                "params 为 Base64 密文");
        check("WIRE encSecKey：256 位小写 hex",
                enc.encSecKey() != null && enc.encSecKey().matches("[0-9a-f]{256}") && enc.encSecKey().length() == 256,
                "encSecKey=" + enc.encSecKey().length() + " 位");
        String expected = rsaExpectedEncSecKey(FIXED_KEY);
        check("WIRE encSecKey：独立实现复算（secretKey 逆序 → hex → ^0x10001 mod n → 左补零 256）== 产品输出",
                expected.equals(enc.encSecKey()),
                "复算" + (expected.equals(enc.encSecKey()) ? "逐字相同" : "不一致") + "（长度 " + expected.length()
                        + "；服务端以私钥 d 解开后得到的正是 secretKey 的逆序）");

        section("四、设备身份（net/Device.java）");
        Device.init(dir);
        Map<String, String> dev = Device.cookies();
        check("DEVICE 字段：os/appver/osver/channel/deviceId/NMTID 齐全",
                dev.containsKey("os") && dev.containsKey("appver") && dev.containsKey("osver")
                        && dev.containsKey("channel") && dev.containsKey("deviceId") && dev.containsKey("NMTID"),
                "keys=" + new TreeSet<>(dev.keySet()));
        check("DEVICE deviceId：32 位小写 hex",
                HEX32.matcher(String.valueOf(dev.get("deviceId"))).matches(),
                "deviceId=" + Device.masked());
        check("DEVICE 显式声明：刻意不含 clientSign（缺项比错值安全，非疏漏）",
                !dev.containsKey("clientSign") && !Device.cookieHeader().contains("clientSign"),
                "cookieHeader 项数=" + dev.size());
        check("DEVICE NMTID：32 位小写 hex",
                HEX32.matcher(String.valueOf(dev.get("NMTID"))).matches(),
                "NMTID=<32 位 hex>（值不打印）");
        String id1 = Device.deviceId();
        Device.reset();
        String id2 = Device.deviceId();
        check("DEVICE 落盘复用：reset() 后从 device.json 读回同一 deviceId",
                id1.equals(id2) && Files.isRegularFile(dir.resolve(Device.FILE_NAME)),
                "device.json 存在=" + Files.isRegularFile(dir.resolve(Device.FILE_NAME)) + " id 稳定=" + id1.equals(id2));
        check("DEVICE describe()：只给掩码，不给完整 deviceId",
                Device.describe().contains(Device.masked()) && !Device.describe().contains(id1),
                Device.describe());

        section("五、cookie jar：只补缺项、不覆盖、不清空");
        Http.setCookies("deviceId=" + SERVER_DEVICE_ID + "; MUSIC_U=" + SESSION_CANARY);
        Device.ensureOnJar();
        check("JAR 不覆盖：服务端回写的 deviceId 保留",
                SERVER_DEVICE_ID.equals(Http.cookie("deviceId")),
                "deviceId=" + (SERVER_DEVICE_ID.equals(Http.cookie("deviceId")) ? "<服务端值保留>" : "<被本地值覆盖>"));
        check("JAR 清空保护：已有会话 cookie（MUSIC_U）不丢",
                SESSION_CANARY.equals(Http.cookie("MUSIC_U")), "MUSIC_U 保留=" + (Http.cookie("MUSIC_U") != null));
        check("JAR 补缺：NMTID / os / appver 已补进 jar",
                Http.cookie("NMTID") != null && Http.cookie("os") != null && Http.cookie("appver") != null,
                "NMTID=" + (Http.cookie("NMTID") != null) + " os=" + (Http.cookie("os") != null)
                        + " appver=" + (Http.cookie("appver") != null));

        section("六、日志脱敏（阳性对照 + 阴性 0 命中）");
        Path log = PluginLog.file();
        String text;
        if (log == null || !Files.isRegularFile(log)) {
            text = "";
        } else {
            text = Files.readString(log, StandardCharsets.UTF_8);
        }
        check("LOG 扫描有效性：日志已落盘且含掩码 deviceId（阳性对照）",
                !text.isEmpty() && text.contains(Device.masked()),
                "日志 " + text.length() + " 字符，掩码 deviceId 命中=" + text.contains(Device.masked()));
        check("LOG 完整 deviceId 0 命中",
                !text.contains(id1), "完整 id 命中=" + text.contains(id1));
        check("LOG 会话 cookie 值 0 命中",
                !text.contains(SESSION_CANARY), "金丝雀命中=" + text.contains(SESSION_CANARY));
        check("LOG 验证码 0 命中",
                !text.contains(FAKE_CAPTCHA), "验证码命中=" + text.contains(FAKE_CAPTCHA));

        section("七、离线声明");
        check("OFFLINE 本探针零网络请求（Http.lastStatus 未变化）",
                Http.lastStatus() == statusBefore && statusBefore == 0,
                "lastStatus=" + Http.lastStatus() + "（0 = 从未发起请求）");
        check("OFFLINE 无本机客户端参与：设备身份完全由插件自己生成/落盘",
                Files.isRegularFile(dir.resolve(Device.FILE_NAME)),
                "仅写 " + Device.FILE_NAME + "（含 deviceId/appver/os/createdAt，无凭据）");

        System.out.println();
        System.out.println("=== 汇总：PASS " + pass + " / FAIL " + fail + " ===");
        for (String f : FAILURES) {
            System.out.println("FAIL: " + f);
        }
        System.out.println("（日志与临时目录保留供取证：" + dir + "）");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 断言框架

    private static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + " — " + detail);
        } else {
            fail++;
            FAILURES.add(name + " — " + detail);
            System.out.println("[FAIL] " + name + " — " + detail);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static TreeSet<String> sorted(String csv) {
        TreeSet<String> set = new TreeSet<>();
        for (String s : csv.split(",")) {
            set.add(s.trim());
        }
        return set;
    }

    private static TreeSet<String> keys(Map<String, Object> payload) {
        return new TreeSet<>(payload.keySet());
    }

    /** 反射查方法（含继承链）：退役断言用——必须「查不到」才算过。 */
    private static boolean hasMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    /** 反射查「public static void 且参数表恰好匹配」——入口签名断言用（{@code type} 为 null ⇒ false）。 */
    private static boolean isStaticVoid(Class<?> type, String name, Class<?>... params) {
        if (type == null) {
            return false;
        }
        try {
            java.lang.reflect.Method m = type.getMethod(name, params);
            int mod = m.getModifiers();
            return java.lang.reflect.Modifier.isPublic(mod) && java.lang.reflect.Modifier.isStatic(mod)
                    && m.getReturnType() == void.class;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只打印键与脱敏后的值（明文验证码绝不进 stdout）。 */
    private static String redacted(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        Object c = copy.get("captcha");
        if (c != null) {
            copy.put("captcha", "<" + String.valueOf(c).length() + " 位数字>");
        }
        Object phone = copy.get("phone");
        if (phone != null) {
            copy.put("phone", maskPhone(String.valueOf(phone)));
        }
        Object cell = copy.get("cellphone");
        if (cell != null) {
            copy.put("cellphone", maskPhone(String.valueOf(cell)));
        }
        return Json.stringify(copy);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "（未填）";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static boolean decodes(String base64) {
        try {
            return Base64.getDecoder().decode(base64).length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 独立实现（不复用产品代码）：AES-128-CBC/PKCS5 解密 + Base64 解码。 */
    private static String aesDecBase64(String base64, String keyText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyText.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(WeapiCrypto.IV.getBytes(StandardCharsets.UTF_8)));
        byte[] out = cipher.doFinal(Base64.getDecoder().decode(base64));
        return new String(out, StandardCharsets.UTF_8);
    }

    /**
     * 用<b>独立实现</b>复算 {@code encSecKey}：{@code secretKey} 字符逆序 → UTF-8 字节 → 十六进制
     * → 大整数 → {@code ^0x10001 mod n} → 左补零到 256 位。
     *
     * <p>注意方向：本密钥对里 {@code (e=0x10001, n)} 是<b>公钥</b>，d 在服务端。所以本地<b>无法</b>把
     * encSecKey 解密回 secretKey（再套一次 e 得到的是 {@code x^(e^2) mod n}，不是 x）。能本地证明的
     * 等价命题是：按上面这套规范算法复算出来的值，与产品输出逐字相同 —— 即「服务端拿 d 解开
     * encSecKey 后得到的正是 secretKey 的逆序」这条链路在客户端一侧的构造没有偏差。</p>
     */
    private static String rsaExpectedEncSecKey(String secretKey) {
        StringBuilder reversed = new StringBuilder(secretKey).reverse();
        StringBuilder hex = new StringBuilder();
        for (byte b : reversed.toString().getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", b));
        }
        BigInteger n = new BigInteger(WeapiCrypto.MODULUS_HEX, 16);
        String out = new BigInteger(hex.toString(), 16).modPow(WeapiCrypto.PUB_KEY_E, n).toString(16);
        StringBuilder sb = new StringBuilder(256);
        for (int i = out.length(); i < 256; i++) {
            sb.append('0');
        }
        return sb.append(out).toString();
    }
}
