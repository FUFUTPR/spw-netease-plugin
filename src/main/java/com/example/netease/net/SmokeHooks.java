package com.example.netease.net;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.netease.core.Json;
import com.example.netease.core.QrEncoder;

/**
 * 自测桥（public，供 {@code tools/smoke/SmokeNet.java} 这类跨包的冒烟入口调用）。
 *
 * <p>为什么需要它：{@code WeapiCrypto} / {@code Http} 是包内实现细节（svc 层只走
 * {@link NeteaseApi}），而确定性加密自检必须拿到包内测试钩子
 * {@code WeapiCrypto.encryptJsonWithKey}，所以在这里开一个最小公开面。</p>
 *
 * <p>业务代码不该用这个类；它只出现在 tools/smoke 下。</p>
 */
public final class SmokeHooks {

    private SmokeHooks() {
    }

    /**
     * 直链只读探针（自测专用，桥接包内可见的 {@code Http}）。
     *
     * <p>先带 {@code Range: bytes=0-0} 请求一次：若返回 302/301（CDN 跳转，Http 刻意不跟随），
     * 就跟着 {@code Location} 再请求一次拿最终状态。**只取 1 字节，不整首下载**。</p>
     *
     * @return 一行人类可读诊断（含 HTTP 状态、CDN 跳转计数、最终状态），绝不含完整签名 URL
     */
    public static String httpProbe(String url) {
        if (url == null || url.isBlank()) {
            return "探针跳过：URL 为空";
        }
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Range", "bytes=0-0");
            Http.get(url, headers);
            int first = Http.lastStatus();
            StringBuilder sb = new StringBuilder("首次 HTTP ").append(first);
            if (first == 301 || first == 302) {
                String loc = Http.lastLocation();
                if (loc == null || loc.isBlank()) {
                    sb.append("（有跳转但无 Location 头）");
                    return sb.toString();
                }
                sb.append(" → 跳转 CDN（Location ").append(loc.length()).append(" 字符），再探读 ");
                Http.get(loc, headers);
                sb.append("最终 HTTP ").append(Http.lastStatus());
            }
            return sb.toString();
        } catch (IOException e) {
            return "探针 IO 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable t) {
            return "探针异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 公开歌单搜索（自测专用，桥接包内的 {@code cloudsearch type=1000}）。
     *
     * <p>用途：在<b>未登录</b>下拿到真实歌单 id，从而让 {@link NeteaseApi#playlist(long)}
     * 的分页链路能被真跑一遍（{@code userPlaylists/dailySongs/likedSongs} 仍需登录态）。</p>
     *
     * <p>注意：这不属于 §6.6 的对外契约（§6.6 未定义歌单搜索），只为冒烟自测存在。</p>
     */
    public static List<Dto.PlaylistBrief> searchPlaylists(String keyword, int limit) throws NeteaseException {
        String kw = keyword == null ? "" : keyword;
        int n = limit <= 0 ? 5 : Math.min(limit, 50);
        // 走免登录公开接口（与 search() 同族）；weapi 的 cloudsearch 匿名下会回 code=50000005
        String url = Http.BASE + "/api/search/get/web?s=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
                + "&type=1000&offset=0&limit=" + n + "&total=true";
        String body;
        try {
            body = Http.get(url, null);
        } catch (IOException e) {
            throw new NeteaseException("公开歌单搜索请求失败：" + e.getClass().getSimpleName(), e);
        }
        List<Dto.PlaylistBrief> out = new ArrayList<>();
        try {
            Object root = Json.parse(body);
            Object result = Json.get(root, "result");
            if (result == null) {
                result = Json.get(root, "data");
            }
            for (Object node : Json.list(Json.get(result, "playlists"), "")) {
                long id = Json.lng(node, "id", 0L);
                if (id <= 0L) {
                    continue;
                }
                out.add(new Dto.PlaylistBrief(id,
                        Json.str(node, "name", ""),
                        Json.str(node, "creator.nickname", ""),
                        Json.integer(node, "trackCount", 0),
                        Json.lng(node, "playCount", 0L)));
            }
        } catch (Throwable t) {
            throw new NeteaseException("公开歌单搜索解析失败：" + t.getClass().getSimpleName(), t);
        }
        return out;
    }

    /**
     * 匿名直链端点裸探（仅诊断用）：直接打公开端点并回报原始响应片段。
     *
     * <p>用来区分「端点在匿名会话下确实不给直链」与「我的解析代码写错了」。</p>
     */
    public static String anonymousUrlRaw(long songId, int br) {
        String url = Http.BASE + "/api/song/enhance/player/url?id=" + songId
                + "&ids=%5B" + songId + "%5D&br=" + br;
        try {
            String body = Http.get(url, null);
            String head = body.length() > 300 ? body.substring(0, 300) + "…" : body;
            return "HTTP " + Http.lastStatus() + " / " + body.length() + " 字符 :: " + head;
        } catch (IOException e) {
            return "IO 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable t) {
            return "异常：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 拉一次 music.163.com 首页，丢弃正文，只为让 cookie jar 收下匿名会话 cookie。 */
    public static String warmupSession() {
        try {
            Http.get(Http.BASE + "/", null);
            int st = Http.lastStatus();
            String ck = Http.cookies();
            int n = 0;
            if (ck != null && !ck.isEmpty()) {
                n = ck.split(";").length;
            }
            return "HTTP " + st + "，cookie jar 现有 " + n + " 项";
        } catch (Throwable t) {
            return "预热失败：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 当前 Http 模块的限速值（毫秒），证明节流没被调快。 */
    public static long rateLimitMs() {
        return Http.rateLimitMs();
    }

    /** 一条断言结果：名称 / 是否通过 / 实际描述。 */
    public record Check(String what, boolean ok, String detail) {
    }

    /**
     * weapi 加密确定性自检（纯离线，不发网络请求）。
     *
     * <p>固定 secretKey → 固定输出，用来证明 params 是合法 base64、encSecKey 是 256 位十六进制、
     * 且换 key 会改变结果。</p>
     */
    public static List<Check> cryptoSelfTest() {
        List<Check> out = new ArrayList<>();
        String json = "{\"ids\":\"[347230]\",\"level\":\"exhigh\",\"encodeType\":\"mp3\"}";

        WeapiCrypto.Payload a = WeapiCrypto.encryptJsonWithKey(json, "aaaaaaaaaaaaaaaa");
        WeapiCrypto.Payload b = WeapiCrypto.encryptJsonWithKey(json, "aaaaaaaaaaaaaaaa");
        WeapiCrypto.Payload c = WeapiCrypto.encryptJsonWithKey(json, "bbbbbbbbbbbbbbbb");

        out.add(new Check("params 非空", a.params() != null && !a.params().isEmpty(),
                "len=" + (a.params() == null ? 0 : a.params().length())));
        out.add(new Check("params 是合法 base64", isBase64(a.params()), head(a.params(), 16) + "…"));
        boolean keyOk = a.encSecKey() != null && a.encSecKey().length() == 256
                && a.encSecKey().matches("[0-9a-f]{256}");
        out.add(new Check("encSecKey 是 256 位小写十六进制", keyOk, head(a.encSecKey(), 16) + "…"));
        out.add(new Check("同输入同 key → params 稳定", a.params().equals(b.params()), "相等"));
        out.add(new Check("同输入同 key → encSecKey 稳定", a.encSecKey().equals(b.encSecKey()), "相等"));
        out.add(new Check("换 key → params 改变", !a.params().equals(c.params()), "不同"));
        out.add(new Check("换 key → encSecKey 改变", !a.encSecKey().equals(c.encSecKey()), "不同"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phone", "13800000000");
        payload.put("password", "d41d8cd98f00b204e9800998ecf8427e");
        payload.put("rememberLogin", "true");
        WeapiCrypto.Payload d = WeapiCrypto.encrypt(payload);
        out.add(new Check("encrypt(Map) 可用",
                d.params() != null && d.encSecKey() != null && d.encSecKey().length() == 256,
                "encSecKey len=" + d.encSecKey().length()));
        String firstLayer = new String(Base64.getDecoder().decode(d.params()), java.nio.charset.StandardCharsets.UTF_8);
        out.add(new Check("params 是二次加密（解一层仍非明文 JSON）", !firstLayer.contains("\"phone\""),
                head(firstLayer, 12) + "…"));
        out.add(new Check("params 外层不再含 '=' 之外的分隔（无非法字符）",
                d.params().matches("[A-Za-z0-9+/]+=*"), "base64 表内"));

        WeapiCrypto.Payload e = WeapiCrypto.encryptJson(null);
        out.add(new Check("null 输入不炸", e.params() != null && e.encSecKey().length() == 256, "{} 加密成功"));

        WeapiCrypto.Payload f = WeapiCrypto.encryptJsonWithKey(json, "");
        out.add(new Check("非法 secretKey 自动兜底（不抛）",
                f.params() != null && f.encSecKey().length() == 256, "使用随机 key"));
        return out;
    }

    /** cookie jar 行为自检：setCookies / cookies / 单值读取。 */
    public static List<Check> cookieSelfTest() {
        List<Check> out = new ArrayList<>();
        String backup = Http.cookies();
        try {
            Http.setCookies("MUSIC_U=abc123; __csrf=deadbeef");
            out.add(new Check("setCookies 解析多值", "MUSIC_U=abc123; __csrf=deadbeef".equals(Http.cookies()),
                    Http.cookies().replaceAll("=[^;]*", "=***")));
            out.add(new Check("cookies() 单值可读", "abc123".equals(Http.cookie("MUSIC_U")), "MUSIC_U 命中"));
            out.add(new Check("未设置的名字返回 null", Http.cookie("NOPE") == null, "null"));
            Http.setCookies(null);
            out.add(new Check("setCookies(null) 清空", Http.cookies().isEmpty(), "空串"));
            Http.setCookies("   ");
            out.add(new Check("空白串等同清空", Http.cookies().isEmpty(), "空串"));
        } finally {
            Http.setCookies(backup);
        }
        return out;
    }

    /**
     * 二维码 PNG 生成自检（纯离线）。
     *
     * <p>0.11.30 起编码器统一到 {@code core.QrEncoder}（M 等级、版本 1–20），旧的 {@code net.QrCode}
     * 已退役 ⇒ 契约随之改为「空串也能出图（v1）」「超长抛 IllegalArgumentException」。</p>
     */
    public static List<Check> qrSelfTest() {
        List<Check> out = new ArrayList<>();
        String url = "https://music.163.com/login?codekey=" + "deadbeefdeadbeefdeadbeefdeadbeefdead";
        int urlBytes = url.getBytes(StandardCharsets.UTF_8).length;
        byte[] png = null;
        String diag = "ok";
        try {
            png = QrEncoder.png(url, 232);
            if (png == null) {
                diag = "QrEncoder.png 返回 null（ImageIO 写内存流失败）";
            }
        } catch (Throwable t) {
            diag = t.getClass().getName() + ": " + t.getMessage();
        }
        out.add(new Check("二维码 PNG 生成成功", png != null && png.length > 100,
                png == null ? ("null（诊断：" + diag + "）") : png.length + " 字节"));
        if (png != null) {
            out.add(new Check("PNG magic 正确", png.length > 8 && (png[0] & 0xFF) == 0x89
                    && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "\\x89PNG"));
            out.add(new Check("IEND 结尾存在", png[png.length - 8] == 'I' && png[png.length - 7] == 'E'
                    && png[png.length - 6] == 'N' && png[png.length - 5] == 'D', "IEND"));
        }
        // 二维码内容 = Http.BASE(21) + "/login?codekey="(15) + unikey(36) = 72 字节 ⇒ M 等级 v5
        // （M 级容量：v4 上限 62、v5 上限 84、v6 上限 106）
        out.add(new Check("版本选择：36 字符 unikey 链接 = " + urlBytes + " 字节 ⇒ v5",
                QrEncoder.versionFor(urlBytes) == 5,
                "v" + QrEncoder.versionFor(urlBytes)));
        out.add(new Check("空串也能出图（v1 / 29 边长 1:1）",
                QrEncoder.image("", 0).getWidth() == 29, QrEncoder.image("", 0).getWidth() + "px"));
        try {
            QrEncoder.png("x".repeat(5000), 232);
            out.add(new Check("超长输入抛 IllegalArgumentException（带上限数字）", false, "未抛错"));
        } catch (IllegalArgumentException e) {
            out.add(new Check("超长输入抛 IllegalArgumentException（带上限数字）",
                    e.getMessage() != null && e.getMessage().contains("666"),
                    e.getMessage()));
        } catch (Throwable t) {
            out.add(new Check("超长输入抛 IllegalArgumentException（带上限数字）", false,
                    t.getClass().getSimpleName()));
        }
        return out;
    }

    /**
     * 账号字段解析自测（0.11.40）：锁住「名字取 {@code profile.nickname}、不是 {@code account.userName}」。
     *
     * <p>回归背景：真机 {@code nuser/account/get} 里 {@code account.userName} 是<b>登录名</b>（打码手机号
     * {@code 138_********0000}），{@code profile.nickname} 才是<b>展示昵称</b>（{@code TPR-A}）。0.11.39 及以前
     * 先读前者 ⇒ 名字被判成号码形态、退成 UID，用户看到「账号名字 = UID」。下面四条把新旧口径都钉住。</p>
     */
    public static List<Check> accountSelfTest() {
        List<Check> out = new ArrayList<>();
        // ① 真机信封（按原样抄的字段名与取值；昵称/uid 都不是敏感值，userName 本就是打码形态）
        Object real = Json.object("{\"code\":200,"
                + "\"account\":{\"id\":123456789,\"userName\":\"138_********0000\",\"vipType\":11},"
                + "\"profile\":{\"userId\":123456789,\"nickname\":\"TPR-A\","
                + "\"userName\":\"138_********0000\",\"vipType\":110}}");
        Dto.Account a = NeteaseApi.parseAccount(real);
        out.add(new Check("真机信封：名字取 profile.nickname（不是打码登录名）",
                a != null && "TPR-A".equals(a.nickname()),
                a == null ? "parseAccount=null" : "nickname=" + a.nickname()));
        out.add(new Check("真机信封：uid 取 account.id、VIP=true",
                a != null && a.userId() == 123456789L && a.vip(),
                a == null ? "parseAccount=null" : "uid=" + a.userId() + " vip=" + a.vip()));
        // ② profile 整段缺失 ⇒ 退回 account.userName（老信封行为不变）
        Dto.Account onlyAccount = NeteaseApi.parseAccount(Json.object(
                "{\"account\":{\"id\":7,\"userName\":\"138_********0000\",\"vipType\":11}}"));
        out.add(new Check("profile 缺失 ⇒ 退回 account.userName（老口径不变）",
                onlyAccount != null && "138_********0000".equals(onlyAccount.nickname()),
                onlyAccount == null ? "parseAccount=null" : "nickname=" + onlyAccount.nickname()));
        // ③ profile.nickname 是空白 ⇒ 也算缺失，继续往后退
        Dto.Account blankNick = NeteaseApi.parseAccount(Json.object(
                "{\"account\":{\"id\":8,\"userName\":\"88\"},\"profile\":{\"nickname\":\"   \"}}"));
        out.add(new Check("profile.nickname 空白 ⇒ 退回 account.userName",
                blankNick != null && "88".equals(blankNick.nickname()),
                blankNick == null ? "parseAccount=null" : "nickname=" + blankNick.nickname()));
        // ④ 两段都没有 ⇒ null（旧版同口径：accountInfo 无法判定时返回 null，调用方走降级）
        Object empty = Json.object("{\"code\":200}");
        out.add(new Check("两段节点都没有 ⇒ null（调用方降级，不许造「UID 0」）",
                NeteaseApi.parseAccount(empty) == null, "parseAccount=" + NeteaseApi.parseAccount(empty)));
        // ⑤ 会员档位名（0.11.41，用户 m00221 ①）：按「权益从高到低」识别，认不出的档位绝不猜名字。
        //    信封按真机 vip/info 探针响应抄（tools/smoke/AccountNickProbe，2026-10-03）：
        //    redVipLevel=7、redplus.vipLevel=7(vipCode 300)、musicPackage.vipLevel=7(vipCode 220)。
        long future = 4102444800000L;   // 2100-01-01：视为「未过期」
        Object svip = Json.object("{\"redVipLevel\":7,"
                + "\"redplus\":{\"vipLevel\":7,\"vipCode\":300,\"expireTime\":" + future + "},"
                + "\"musicPackage\":{\"vipLevel\":7,\"vipCode\":220,\"expireTime\":" + future + "},"
                + "\"associator\":{\"vipLevel\":7},\"voiceBookVip\":{},\"albumVip\":{\"vipLevel\":7}}");
        out.add(new Check("档位：redplus 生效 ⇒ 黑胶SVIP（真机信封）",
                "黑胶SVIP".equals(NeteaseApi.vipTierName(svip)), NeteaseApi.vipTierName(svip)));
        out.add(new Check("档位：只有 redVipLevel ⇒ 黑胶VIP",
                "黑胶VIP".equals(NeteaseApi.vipTierName(Json.object("{\"redVipLevel\":7}"))),
                NeteaseApi.vipTierName(Json.object("{\"redVipLevel\":7}"))));
        out.add(new Check("档位：只有 musicPackage ⇒ 音乐包",
                "音乐包".equals(NeteaseApi.vipTierName(Json.object(
                        "{\"musicPackage\":{\"vipLevel\":7,\"expireTime\":" + future + "}}"))),
                NeteaseApi.vipTierName(Json.object(
                        "{\"musicPackage\":{\"vipLevel\":7,\"expireTime\":" + future + "}}"))));
        out.add(new Check("档位：redplus 已过期 ⇒ 认不出档位、退空串（界面用通用「会员」）",
                NeteaseApi.vipTierName(Json.object(
                        "{\"redplus\":{\"vipLevel\":7,\"expireTime\":1}}")).isEmpty(),
                "[" + NeteaseApi.vipTierName(Json.object(
                        "{\"redplus\":{\"vipLevel\":7,\"expireTime\":1}}")) + "]"));
        out.add(new Check("档位：认不出的权益（associator 等）⇒ 空串，绝不猜名字",
                NeteaseApi.vipTierName(Json.object(
                        "{\"associator\":{\"vipLevel\":7},\"albumVip\":{\"vipLevel\":7}}")).isEmpty(),
                "[" + NeteaseApi.vipTierName(Json.object(
                        "{\"associator\":{\"vipLevel\":7},\"albumVip\":{\"vipLevel\":7}}")) + "]"));
        return out;
    }

    private static boolean isBase64(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(s);
            return s.matches("[A-Za-z0-9+/]+=*");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "null";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
