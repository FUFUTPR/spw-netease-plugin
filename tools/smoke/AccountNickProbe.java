package com.example.netease.net;

import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.CookieVault;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 只读探针：用凭据保险柜里的 cookie 拉一次 {@code nuser/account/get}，把 account / profile 两段里
 * 与「名字」有关的字段原样打出来 —— 用来回答一个问题：**「账号名字」该取哪个字段**。
 *
 * <p>背景（用户 m00221 追加报障）：登录后点「当前账号」，窗里显示 {@code 账号名字：UID 123456789}，
 * 与「账号 ID」重复 —— 说明 {@code SmsLogin.accountName()} 把接口给的昵称判成了「号码形态」而退成 UID。
 * 判定是否判错，必须看**原始字段**：{@code account.userName} 与 {@code profile.nickname} 可能不是一回事
 * （前者是登录名，后者是展示昵称）。</p>
 *
 * <p>不写插件日志（{@link PluginLog} 指向临时目录）、不落盘、只读。用法：</p>
 * <pre>java -cp &lt;classes&gt; com.example.netease.net.AccountNickProbe [数据目录]</pre>
 */
public final class AccountNickProbe {

    /** 与「名字」有关的候选字段（其余字段不打印）。 */
    private static final String[] KEYS = {
            "id", "userId", "userName", "nickname", "vipType", "createTime", "type", "anonimousUser"};

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0]
                : System.getenv("APPDATA") + "/Salt Player for Windows/workshop/data/com.example.netease");
        Path logTmp = Files.createTempDirectory("nickprobe-log");
        PluginLog.init(logTmp);
        CookieVault.init(dataDir);
        String cookies = CookieVault.load();
        System.out.println("vault  = " + dataDir);
        System.out.println("cookie = " + (cookies == null ? 0 : cookies.length()) + " 字符（值不打印）");
        NeteaseApi.importCookies(cookies);
        Method weapi = NeteaseApi.class.getDeclaredMethod("weapi", String.class, Map.class);
        weapi.setAccessible(true);
        Object root = weapi.invoke(null, "nuser/account/get", Json.newObject());
        System.out.println("code   = " + Json.integer(root, "code", -1));
        for (String path : new String[]{"account", "data.account", "profile", "data.profile"}) {
            Object node = Json.get(root, path);
            if (!(node instanceof Map)) {
                continue;
            }
            System.out.println("--- " + path + " ---");
            for (String k : KEYS) {
                Object v = Json.get(node, k);
                if (v != null) {
                    System.out.println("  " + k + " = " + v);
                }
            }
        }
        System.out.println("--- 原始 JSON（本地控制台，不入插件日志） ---");
        System.out.println(Json.stringify(root));
        // 会员档位（0.11.41）：vipType 只是个位掩码/复合量，界面上要的是档位名 ——
        // 用专属接口 music-vip-membership/client/vip/info 拿 redVipLevel / redplus / musicPackage。
        Map<String, Object> vipPayload = Json.newObject();
        vipPayload.put("userId", String.valueOf(Json.lng(Json.get(root, "account"), "id", 0L)));
        Object vip = weapi.invoke(null, "music-vip-membership/client/vip/info", vipPayload);
        System.out.println("--- vip/info ---");
        System.out.println("code = " + Json.integer(vip, "code", -1));
        for (String k : new String[]{"data.redVipLevel", "data.redVipAnnualCount", "data.redVipType",
                "data.musicPackageType", "data.musicPackage.vipLevel", "data.musicPackage.vipCode",
                "data.redplus.vipLevel", "data.redplus.vipCode", "data.redplus.vipName",
                "data.associator.vipLevel", "data.associator.vipName"}) {
            Object v = Json.get(vip, k);
            if (v != null) {
                System.out.println("  " + k + " = " + v);
            }
        }
        System.out.println("--- vip/info 原始 JSON ---");
        System.out.println(Json.stringify(vip));
    }

    private AccountNickProbe() {
    }
}
