package com.example.netease.net;

import java.util.List;

/**
 * 网络层数据载体（全部为不可变 record，可安全跨线程传递）。
 *
 * <p>约定：字段语义与冻结合同 docs/00 §6.3 完全一致，不得增删字段。</p>
 */
public final class Dto {

    private Dto() {
    }

    /**
     * 一首歌的检索结果。
     *
     * @param id         网易云歌曲 id
     * @param name       歌名
     * @param artists    歌手，多歌手用 "/" 连接（可能为空串）
     * @param album      专辑名（可能为空串）
     * @param durationMs 时长（毫秒，接口缺失时为 0）
     * @param fee        付费类型（0 免费 / 1 会员 / 4 购买专辑 / 8 低音质免费等，接口缺失时为 0）
     * @param playable   是否可播放（优先取 privilege 信息，缺失时按"有版权"乐观处理）
     * @param picUrl     封面图<b>原始</b> URL（0.6.0 新增）；<b>绝不为 null</b>，缺失时为空串 ""。
     *                   来源 {@code al.picUrl}（没有再看 {@code album.picUrl}）。
     *                   net 层<b>不</b>拼接 {@code ?param=} 之类的尺寸参数——显示尺寸由 UI 决定，
     *                   在数据层写死会让同一份数据无法适配列表缩略图 / 详情大图两种用途。
     */
    public record Song(long id, String name, String artists, String album,
                       long durationMs, int fee, boolean playable, String picUrl) {
    }

    /**
     * 一首歌的歌词三件套（0.6.0 恢复；0.5.0 瘦身时曾连代码删除）。
     *
     * @param lrc        主歌词（带时间戳）；纯音乐/无歌词时为 null
     * @param translated 翻译歌词；无翻译时为 null
     * @param romaji     罗马音歌词；无罗马音时为 null
     */
    public record Lyric(String lrc, String translated, String romaji) {
    }

    /**
     * 二维码登录会话。
     *
     * @param key      轮询用 unikey
     * @param pngBytes 二维码 PNG 图片字节；离线生成失败时为 null（调用方可退化为链接/文本）
     */
    public record QrSession(String key, byte[] pngBytes) {
    }

    /**
     * 当前登录账号。
     *
     * @param nickname 昵称
     * @param userId   用户 id
     * @param vip      是否 VIP（VIP / 黑胶 / 音乐包任一为真即为真）
     * @param vipName  会员<b>档位名</b>（0.11.41：{@code 黑胶SVIP} / {@code 黑胶VIP} / {@code 音乐包}；
     *                 查不到或认不出 ⇒ 空串，界面退成通用「会员」；无会员 ⇒ 空串）
     */
    public record Account(String nickname, long userId, boolean vip, String vipName) {
    }

    /**
     * 短信验证码「发送」的业务结果（0.2.4，docs/00 §6.3 修正第 11 条）。
     *
     * <p>发送接口不是「成功 / 失败」二值：网易云会返回 503（发送太频繁）、
     * 8821（风控：需要行为验证码验证）等业务码。所以把码与伺服端原话原样带出来，
     * 由 svc / UI 决定文案，**不要在 net 层把它们压成 boolean**。</p>
     *
     * @param code    业务码：200=已发送；-1=网络异常/超时（没拿到伺服端码）；-2=本地参数非法
     * @param message 伺服端 message（已脱敏、已去掉换行）；没有时为空串，绝不为 null
     */
    public record SmsResult(int code, String message) {

        /** 是否真的发出去了（只有 200 算成功）。 */
        public boolean sent() {
            return code == 200;
        }
    }

    /**
     * 歌单摘要（用于「我的歌单」列表，不含曲目）。
     *
     * @param id         歌单 id
     * @param name       歌单名
     * @param creator    创建者昵称（可能为空串）
     * @param trackCount 曲目数（接口缺失时为 0）
     * @param playCount  播放量（接口缺失时为 0）
     * @param mine       该歌单是否由<b>当前登录用户</b>创建（0.6.0 新增）。
     *                   判据：{@code creator.userId == 当前 uid}，或响应里 {@code subscribed == false}
     *                   （没订阅 = 自己建的）。未登录/uid 未知时恒为 false。
     */
    public record PlaylistBrief(long id, String name, String creator, int trackCount, long playCount,
                                boolean mine) {

        /**
         * 5 参兼容构造器（0.6.0 加 {@code mine} 时补）。
         *
         * <p><b>为什么留着它</b>：{@code net\SmokeHooks.java:96} 也会构造这个 record，而该文件在
         * 本任务里是禁改文件。加一个 5 参重载既能让它是 0 改动继续编译，又不动冻结的规范构造器
         * 签名。语义上「没有 mine 信息」= {@code false}（不声称是自己的歌单）——
         * 这个降级方向是安全的：UI 顶多少显示一个「我的」标记。</p>
         */
        public PlaylistBrief(long id, String name, String creator, int trackCount, long playCount) {
            this(id, name, creator, trackCount, playCount, false);
        }
    }

    /**
     * 歌单完整内容（含曲目）。
     *
     * @param id         歌单 id
     * @param name       歌单名
     * @param creator    创建者昵称（可能为空串）
     * @param trackCount 曲目数（接口缺失时为 0）
     * @param tracks     曲目列表，<b>绝不为 null</b>（无数据 = 空列表）
     * @param coverUrl   歌单封面<b>原始</b> URL（0.10.0 新增；来自 {@code playlist.coverImgUrl}）。
     *                   <b>绝不为 null</b>，缺失时为空串 ""。宿主写库侧用它给「歌单封面文件」取图，
     *                   是否加尺寸参数由写库侧决定（与 {@link Song#picUrl()} 同一约定）。
     */
    public record Playlist(long id, String name, String creator, int trackCount, List<Song> tracks,
                           String coverUrl) {

        /**
         * 5 参兼容构造器（0.10.0 加 {@code coverUrl} 时补）：语义 = 「没有封面 URL」（空串）。
         *
         * <p>与 {@link PlaylistBrief} 的兼容构造器同一思路：不动冻结规范签名，老调用点零改动，
         * 降级方向安全（写库侧拿不到图就只同步行、不写封面文件）。</p>
         */
        public Playlist(long id, String name, String creator, int trackCount, List<Song> tracks) {
            this(id, name, creator, trackCount, tracks, "");
        }
    }
}
