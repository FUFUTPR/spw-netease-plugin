package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import com.example.netease.core.Hashes;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 播放侧封面投递（task-8 / D4 路线 ①-a：<b>经宿主 live 图片磁盘缓存写键</b>）。
 *
 * <p><b>为什么是这条路</b>（P-4 探针的两条决定性实证）：</p>
 * <ol>
 *   <li>整单试播 60 s 窗口内，宿主<b>零次</b>为插件曲目请求过封面键（cache 文件数 +0、journal 新增 0 行）
 *       ⇒ 宿主的行内/播放条缩略图不会自己去找我们曲目对应的封面，<b>键必须由插件自己写</b>；</li>
 *   <li>键形态逐字节固定为
 *       {@code <Track.path 原文>?v=1&t=<PINNED_MODIFIED_TIME>&s=<size>&r=<coverRevision>&w=<W>&h=<W>}，
 *       其中 {@code path} 必须是 {@code http://127.0.0.1:<port>/netease/<songId>} 形态；
 *       换成 {@code file:///…audio-cover/*.flac} 后 4×5×3×9 组组合<b>零命中</b>
 *       ⇒ RowCover「翻 path 换封面」那条老路是负资产，已删除。</li>
 * </ol>
 *
 * <p><b>与 {@link CoverPrimer} 的分工</b>：{@code CoverPrimer} 是同步完成后的<b>全量预热</b>（直接写
 * {@code <宿主数据>\cache\shared_cover\<hex>.0/.1} 并追加 journal，属「会话外写」⇒ 下一次会话可见，
 * 这是 A11 的吞吐路线）；本类处理<b>播放侧单曲</b>，走宿主<b>活着的</b> {@code DiskCache} 对象
 * （{@code openEditor → 写字节 → commit}），宿主在同会话立即可见 —— 这是 A17「未播放过的曲目选中进
 * 播放条即出图」的唯一实现方式。</p>
 *
 * <p><b>红线</b>：① 只允许<b>插件工作线程</b>调用（绝不从宿主回调线程进来）；② 全程零音频下载 ——
 * 只取封面<b>图片</b> URL（且通常已在 {@code cover\img\} 里）；③ 写宿主缓存前图字节必须已经完整到手，
 * 绝不边下边写半张图；④ 任何失败都记日志并计数，不静默。</p>
 */
public final class CoverDelivery {

    private static final String TAG = "cover-deliver";

    /** 孔位：与 P-4 实测一致（journal 6534 键 = 3267 行 × 2 档）。 */
    private static final int[] SIZES = CoverPrimer.SIZES;

    /** 投递统计（配置页/日志判据）：成功键数 / 尝试曲目数 / 失败数。 */
    private static final AtomicInteger KEYS_OK = new AtomicInteger();
    private static final AtomicInteger SONGS = new AtomicInteger();
    private static final AtomicInteger FAILS = new AtomicInteger();

    /**
     * 本会话已写成功的键 —— {@code openSnapshot} 句柄拿不到时用它兜底做幂等。
     *
     * <p>背景（2026-10-01 真机缺陷）：{@code mOpenSnapshot} 曾被签名猜名误判成 null，于是「读回」永远
     * 返回 0 —— 幂等检查失效 ⇒ 每 7–13 s 把同一批键（队列前瞻 4 首 × 2 档）重写一次，且回验必然
     * 判为「宿主未接受该条目」。有这张集合，即使回读不可用也不会重复写。</p>
     */
    private static final java.util.Set<String> WROTE_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 反射句柄缓存：启动期解析一次，之后纯本地算键（P-4 §9 的生产化建议）。 */
    private static volatile Object cachedDisk;
    private static volatile Method mOpenEditor;
    private static volatile Method mOpenSnapshot;
    private static volatile Method mGetData;
    private static volatile Method mCommit;
    private static volatile boolean resolveFailed;
    /** 各句柄的取得方式（按名 / 签名 / 不可用），只用于启动日志自证。 */
    private static volatile String mEditorHow = "";
    private static volatile String mSnapHow = "";

    private CoverDelivery() {
    }

    /**
     * 给一首曲目补投宿主封面键（幂等：已命中正条目的键直接跳过）。
     *
     * @param songId 插件曲目 id（不含 {@code netease-} 前缀）
     * @return 本次新写入并回验通过的键数（0 = 已就绪或无图/失败）
     */
    public static int deliver(long songId) {
        if (songId <= 0L) {
            return 0;
        }
        SONGS.incrementAndGet();
        return deliverSizes(songId, SIZES);
    }

    /**
     * 追加一档尺寸的键（0.11.16，播放页封面修复轮）。
     *
     * <p>宿主「播放条 / 播放页」两处请求封面时用的尺寸档由
     * {@code com.xuncorp.voxzen.image.CoverSizePolicy.INSTANCE.getCurrentTier()} 决定，而本插件只铺过
     * {@code CoverPrimer.SIZES = {96, 192}} 两档 ⇒ 播放页那种更大的面（真机 2026-10-01 20:28 像素证据
     * 恒为 ♪）键不命中。这里按宿主当前档位补一档：<b>零音频下载</b>，只多一张缩略图。</p>
     *
     * @param songId 插件曲目 id（不含 {@code netease-} 前缀）
     * @param size   追加的档位边长（px；{@code <=0} 忽略）
     * @return 本次新写并回验通过的键数（0 = 已就绪或无图/失败）
     */
    public static int deliverExtra(long songId, int size) {
        if (songId <= 0L || size <= 0) {
            return 0;
        }
        return deliverSizes(songId, new int[]{size});
    }

    /**
     * 给一首曲目补投宿主封面键（幂等：已命中正条目的键直接跳过）。
     *
     * @param songId 插件曲目 id（不含 {@code netease-} 前缀）
     * @param sizes  要铺的档位边长（升序，去重由调用方保证）
     * @return 本次新写入并回验通过的键数（0 = 已就绪或无图/失败）
     */
    private static int deliverSizes(long songId, int[] sizes) {
        if (songId <= 0L || sizes == null || sizes.length == 0) {
            return 0;
        }
        try {
            String[] row = readRow(songId);
            if (row == null) {
                fail("投递跳过 #" + songId + "：宿主库里没有这一行");
                return 0;
            }
            String album = row[0];
            String artist = row[1];
            String refKey = CoverStore.refKey(album, artist);
            String path = row[2];
            if (path == null || !path.startsWith("http://127.0.0.1")) {
                fail("投递跳过 #" + songId + "：Track.path 不是本机流地址（" + path + "）"
                        + "—— 历史本地路径要先由行封面自愈还原，否则算出来的键是孤儿键");
                return 0;
            }
            long size = parseLong(row[3]);
            int rev = (int) parseLong(row[4]);
            // 图字节就位（CoverStore 内部离线优先：cover\img\ 有就直接用，缺了才取图片 URL）
            byte[] src = CoverStore.fetchImage(refKey);
            if (src == null) {
                fail("投递跳过 #" + songId + "：该专辑取不到封面图（无图/失败已分类计数）");
                return 0;
            }
            Object disk = disk();
            if (disk == null) {
                fail("投递降级 #" + songId + "：拿不到宿主 live 图片磁盘缓存（路线①不可用，需 P-4 复测结论）");
                return 0;
            }
            int ok = 0;
            int wrote = 0;
            int hit = 0;
            int stale = 0;
            long srcT = CoverStore.imageTime(refKey);      // 0.11.49：图换过（img 新于键）就得重铺
            StringBuilder detail = new StringBuilder();
            for (int sz : sizes) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                String key = path + "?v=1&t=" + CoverPrimer.KEY_MTIME + "&s=" + size + "&r=" + rev
                        + "&w=" + sz + "&h=" + sz;
                if (snapshotLen(disk, key) > 0L && !staleKey(key, srcT)) {
                    ok++;                              // 已就绪（幂等）
                    hit++;
                    detail.append(sz).append(" 档 命中（跳过 ").append(lastSnapLen).append(" B）");
                    continue;
                }
                if (lastSnapLen > 0L && staleKey(key, srcT)) {
                    stale++;                           // 内容对不上（图换过）：下面按新图重写
                    detail.append(sz).append(" 档 陈旧（图 mtime ").append(srcT).append(" > 键）⇒ 重写；");
                }
                if (WROTE_KEYS.contains(key)) {
                    ok++;                              // 本会话刚写过（回读不可用时的幂等兜底）
                    hit++;
                    detail.append(sz).append(" 档 本会话已写（跳过）");
                    continue;
                }
                byte[] jpg = CoverPrimer.thumb(src, sz);
                if (jpg == null || jpg.length == 0) {
                    fail("投递 #" + songId + "：" + sz + " 档缩略图生成失败");
                    detail.append(sz).append(" 档 缩略图生成失败");
                    continue;
                }
                StringBuilder why = new StringBuilder();
                int n = write(disk, key, jpg, why);
                if (n <= 0) {
                    fail("投递 #" + songId + "：" + sz + " 档写宿主缓存失败 —— " + why);
                    detail.append(sz).append(" 档 写入失败（").append(why).append("）");
                    continue;
                }
                WROTE_KEYS.add(key);
                long back = snapshotLen(disk, key);
                if (mOpenSnapshot == null) {
                    ok++;
                    wrote++;
                    KEYS_OK.incrementAndGet();
                    detail.append(sz).append(" 档 新写 ").append(jpg.length)
                            .append(" B（宿主不给 openSnapshot 句柄，跳过回验）");
                } else if (back == jpg.length) {
                    ok++;
                    wrote++;
                    KEYS_OK.incrementAndGet();
                    detail.append(sz).append(" 档 新写 ").append(jpg.length).append(" B → openSnapshot 回验 ")
                            .append(back).append(" B 一致");
                } else {
                    fail("投递 #" + songId + "：" + sz + " 档回验不一致（写入 " + jpg.length + " B / 读回 "
                            + back + " B）—— 宿主未接受该条目");
                    detail.append(sz).append(" 档 回验不一致（写 ").append(jpg.length).append(" B / 读回 ")
                            .append(back).append(" B）");
                }
            }
            if (ok > 0) {
                String line = "封面投递 #" + songId + "「" + album + "」→ " + ok + "/" + sizes.length
                        + " 档键就位（新写 " + wrote + " 档 / 命中 " + hit + " 档 ｜ " + detail
                        + " ｜ 零音频、图字节 " + src.length + " B）";
                // 同「曲目×结果」指纹只落一次 INFO：队列前瞻每 ~7–13 s 重投同一批 4 首，
                // 不去重会刷出 20+ 行/分钟（2026-10-01 真机实测）。
                if (ANNOUNCED.add(songId + "|" + ok + "|" + wrote + "|" + hit)) {
                    PluginLog.i(TAG, line);
                } else {
                    PluginLog.d(TAG, line);
                }
            }
            return ok;
        } catch (Throwable t) {
            fail("投递异常 #" + songId + "：" + t);
            return 0;
        }
    }

    /**
     * 宿主缓存里这个键是不是「陈旧」（图内容变过但键明文没变 ⇒ 旧图会永久留下）。
     *
     * <p>键 = {@code path?v=1&t=…&s=…&r=…&w=…&h=…}，明文里没有图内容；插件 0.11.49 起在命中
     * 快照之后再看一眼键文件 mtime：比源图「最后变化时刻」旧 ⇒ 判陈旧、按新图重写。</p>
     *
     * @param srcT {@link CoverStore#imageTime} 给的源图时刻（0 = 不知道，不判陈旧）
     */
    private static boolean staleKey(String key, long srcT) {
        if (srcT <= 0L) {
            return false;
        }
        try {
            Path dir = CoverPrimer.cacheDir();
            if (dir == null) {
                return false;
            }
            Path f = dir.resolve(Hashes.sha256Hex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + ".1");
            if (!Files.isRegularFile(f)) {
                return false;
            }
            return Files.getLastModifiedTime(f).toMillis() < srcT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 投递完成度（配置页/日志）：成功键 / 涉及曲目 / 失败。 */
    public static String stats() {
        return "封面投递（播放侧）：键就绪 " + KEYS_OK.get() + " / 涉及曲目 " + SONGS.get()
                + " / 失败 " + FAILS.get() + "（路线①-a：经宿主 live DiskCache 写 96/192 两档键）";
    }

    // ------------------------------------------------------------------ 宿主库行

    /** {@code album / artist / path / size / coverRevision}；读不到返回 null。 */
    private static String[] readRow(long songId) {
        Path db = CoverPrimer.hostDb();
        if (db == null) {
            return null;
        }
        String sql = "SELECT album, artist, path, size, coverRevision FROM Track WHERE id = ?";
        try (java.sql.Connection c = openReadOnly(db);
             java.sql.PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, "netease-" + songId);
            try (java.sql.ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new String[]{
                        r.getString(1) == null ? "" : r.getString(1),
                        r.getString(2) == null ? "" : r.getString(2),
                        r.getString(3) == null ? "" : r.getString(3),
                        Long.toString(r.getLong(4)),
                        Integer.toString(r.getInt(5))};
            }
        } catch (Throwable t) {
            fail("读宿主库失败（投递 #" + songId + "）：" + t);
            return null;
        }
    }

    private static java.sql.Connection openReadOnly(Path db) throws Exception {
        try {
            Class.forName("org.sqlite.JDBC");           // 插件类加载器看不见 jar 里的驱动注册（真机踩过）
        } catch (Throwable ignored) {
            // 已注册时忽略
        }
        java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        try (java.sql.Statement s = c.createStatement()) {
            s.execute("PRAGMA query_only=ON");
        }
        return c;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 失败记账 + 去重 WARN。
     *
     * <p>真机教训（2026-10-01）：投递每 7–13 s 重跑一轮（队列前瞻 4 首 × 2 档），失败时逐键 WARN
     * ⇒ 一上午刷出 7600+ 行同形日志，把真正有用的首行埋了。现在按「词形」（数字归一成 {@code #}）
     * 去重：每种失败词形只落一行，其余只计数（{@link #stats()} 里看得见）。</p>
     */
    private static void fail(String why) {
        FAILS.incrementAndGet();
        String shape = why.replaceAll("\\d+", "#");
        if (WARNED_SHAPES.add(shape)) {
            PluginLog.w(TAG, why);
        } else {
            PluginLog.d(TAG, why);
        }
    }

    /** 已落过 WARN 的失败词形（去重用）。 */
    private static final java.util.Set<String> WARNED_SHAPES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 已落过「档键就位」INFO 的曲目×结果指纹（同状态重复轮次只进 debug）。 */
    private static final java.util.Set<String> ANNOUNCED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ 反射：宿主 live 图片磁盘缓存

    /**
     * 拿到宿主封面 {@code ImageLoader} 上的 live {@code DiskCache}。
     *
     * <p>宿主侧事实（P-4 S5b 实测）：封面单例 = {@code com.xuncorp.voxzen.image.CoverImageLoader_desktopKt
     * .getCoverImageLoader()}，其 {@code diskCache} 是 {@code coil3.disk.RealDiskCache}，
     * 目录 = {@code <宿主数据>\cache\shared_cover}。{@code coil3.disk.*} 的公开成员<b>名是可读的</b>
     * （P-4 S5d 用 {@code openEditor / getData / commitAndOpenSnapshot} 名字直接调通），所以解析
     * <b>优先按名</b>、签名猜名只作兜底 —— 旧版纯猜名把 {@code openSnapshot} 误判成编辑器方法
     * （见 {@link #hasNestedPathGetter}），导致回验与幂等全废。</p>
     */
    private static Object disk() {
        Object d = cachedDisk;
        if (d != null) {
            return d;
        }
        if (resolveFailed) {
            return null;
        }
        synchronized (CoverDelivery.class) {
            if (cachedDisk != null) {
                return cachedDisk;
            }
            try {
                Class<?> kt = Class.forName("com.xuncorp.voxzen.image.CoverImageLoader_desktopKt");
                Object loader = kt.getMethod("getCoverImageLoader").invoke(null);
                if (loader == null) {
                    resolveFailed = true;
                    return null;
                }
                Object disk = findDisk(loader);
                if (disk == null) {
                    resolveFailed = true;
                    PluginLog.w(TAG, "宿主 ImageLoader 上找不到 DiskCache（coil3 结构变化？）");
                    return null;
                }
                mOpenEditor = byName(disk.getClass(), "openEditor");
                if (mOpenEditor != null) {
                    mEditorHow = "按名";
                } else {
                    mOpenEditor = oneStringParamGetters(disk.getClass(), true);
                    mEditorHow = mOpenEditor != null ? "签名" : "不可用";
                }
                mOpenSnapshot = byName(disk.getClass(), "openSnapshot");
                if (mOpenSnapshot != null) {
                    mSnapHow = "按名";
                } else {
                    mOpenSnapshot = oneStringParamGetters(disk.getClass(), false);
                    mSnapHow = mOpenSnapshot != null ? "签名" : "不可用";
                }
                if (mOpenEditor != null) {
                    Class<?> ed = mOpenEditor.getReturnType();
                    mGetData = byName0(ed, "getData");
                    if (mGetData == null) {
                        mGetData = zeroArgReturning(ed, "okio.Path");
                    }
                    mCommit = byName0(ed, "commitAndOpenSnapshot");
                    if (mCommit == null) {
                        mCommit = zeroArgReturning(ed, null);
                    }
                }
                if (mOpenEditor == null || mGetData == null || mCommit == null) {
                    PluginLog.w(TAG, "宿主 DiskCache 结构不认识：openEditor=" + mOpenEditor
                            + " getData=" + mGetData + " commit=" + mCommit);
                    resolveFailed = true;
                    return null;
                }
                cachedDisk = disk;
                PluginLog.i(TAG, "宿主图片磁盘缓存已就位：" + disk.getClass().getName()
                        + "（openSnapshot=" + mOpenSnapshot + "〔" + mSnapHow + "〕 / openEditor="
                        + mOpenEditor + "〔" + mEditorHow + "〕 / getData=" + mGetData
                        + " / commit=" + mCommit + "）");
                if (mOpenSnapshot == null) {
                    PluginLog.w(TAG, "宿主 DiskCache 读不到 openSnapshot —— 回验与幂等改用本会话已写键集合"
                            + "（写入仍按 openEditor/commit 反射链走）");
                }
                return disk;
            } catch (Throwable t) {
                resolveFailed = true;
                PluginLog.w(TAG, "解析宿主封面链路失败（路线①不可用）：" + t);
                return null;
            }
        }
    }

    /** 在 loader 的方法/字段里按<b>返回类型</b>找 {@code coil3.disk.DiskCache}（成员名不可读）。 */
    private static Object findDisk(Object loader) {
        for (Method m : loader.getClass().getMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (!m.getReturnType().getName().startsWith("coil3.disk.")) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object v = m.invoke(loader);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        for (java.lang.reflect.Field f : loader.getClass().getDeclaredFields()) {
            if (!f.getType().getName().startsWith("coil3.disk.")) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(loader);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /**
     * 在 {@code DiskCache} 上按签名找「取快照」或「开编辑器」的那个方法。
     *
     * <p>其余判据：返回类型里有一个 0 参方法返回 {@code okio.Path}（快照/编辑器都靠它拿数据）。
     * 编辑器再多一层特征 —— 它的某个 0 参方法返回的类型同样带 {@code okio.Path} 取数器（commit 后拿快照）。</p>
     */
    private static Method oneStringParamGetters(Class<?> diskClass, boolean editor) {
        for (Method m : diskClass.getMethods()) {
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != String.class) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || !ret.getName().startsWith("coil3.")) {
                continue;
            }
            if (zeroArgReturning(ret, "okio.Path") == null) {
                continue;
            }
            boolean editorish = hasNestedPathGetter(ret);
            if (editorish == editor) {
                return accessible(m);
            }
        }
        return null;
    }

    /**
     * 该类型是否有「0 参 → 另一个带 {@code okio.Path} 取数器的类型」的方法（Editor 的 commit 特征）。
     *
     * <p><b>2026-10-01 真机缺陷</b>：原先只要 {@code r != t} 就算数，而 {@code okio.Path} 自己就有 0 参
     * 返回 {@code okio.Path} 的方法（Kotlin 的 {@code parent} / {@code normalized} 等属性 getter）
     * ⇒ {@code zeroArgReturning(okio.Path, "okio.Path") != null} ⇒ <b>Snapshot 也被判成「编辑器」</b>
     * ⇒ 非编辑器分支取不到 {@code openSnapshot}（实测启动行 {@code openSnapshot=null}）。
     * 现在要求嵌套类型本身必须是 {@code coil3.*}（Editor 的 commit 返回 {@code coil3.disk.*}，
     * Snapshot 的方法只返回 {@code okio.Path} / void，不再误判）。</p>
     */
    private static boolean hasNestedPathGetter(Class<?> t) {
        for (Method m : t.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType().isPrimitive()) {
                continue;
            }
            Class<?> r = m.getReturnType();
            if (r == t || !r.getName().startsWith("coil3.")) {
                continue;
            }
            if (zeroArgReturning(r, "okio.Path") != null) {
                return true;
            }
        }
        return false;
    }

    /** 按可读名取「1 个 String 参数」的方法（coil3 在本机是可读名，P-4 S5d 就是这么调通的）。 */
    private static Method byName(Class<?> k, String name) {
        try {
            return accessible(k.getMethod(name, String.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按可读名取 0 参方法。 */
    private static Method byName0(Class<?> k, String name) {
        try {
            return accessible(k.getMethod(name));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 反射句柄必须 {@code setAccessible(true)} —— <b>2026-10-01 第三处真机缺陷</b>：coil3 的
     * {@code RealDiskCache$RealEditor}/{@code RealSnapshot} 是<b>非 public 内部类</b>，它们的
     * {@code getData()}/{@code close()} 从插件包调用会抛
     * {@code IllegalAccessException: class com.example.netease.svc.CoverDelivery cannot access a member of
     * class coil3.disk.RealDiskCache$RealSnapshot} —— 旧版把异常吞进 {@code PluginLog.d} 或静默返回 0，
     * 于是「回读永远 0 ⇒ 幂等失效 ⇒ 反复重写已有键」，而当时的验收探针 {@code ProbeCover}
     * （0.11.10 已随临时探针组退役删除，留档见 {@code docs/51-探针报告-P4P5.md}）一直在用
     * {@code setAccessible} 所以它的写链是通的。
     */
    private static Method accessible(Method m) {
        if (m != null) {
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // 拿不到就按原样用（public 成员仍可调）
            }
        }
        return m;
    }

    /** 0 参方法：返回类型名含 {@code keyword}（{@code keyword=null} 表示任意非 void 非原始类型）。 */
    private static Method zeroArgReturning(Class<?> t, String keyword) {
        Method fallback = null;
        for (Method m : t.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class || m.getReturnType().isPrimitive()) {
                continue;
            }
            if (keyword == null) {
                if (!m.getReturnType().getName().startsWith("coil3.")) {
                    fallback = fallback == null ? m : fallback;
                    continue;
                }
                return accessible(m);
            }
            if (m.getReturnType().getName().equals(keyword)) {
                return accessible(m);
            }
            if (m.getReturnType().getName().contains(keyword) && fallback == null) {
                fallback = m;
            }
        }
        return accessible(fallback);
    }

    /**
     * 读回某键的字节长度（0 = 未命中/负条目）。
     *
     * <p><b>2026-10-01 第二处真机缺陷</b>：路径取数器原先用 {@code zeroArgReturning(snap.getClass(),
     * "okio.Path")} 按签名猜 —— 快照类上 {@code getData()}（数据文件 {@code .1}）与 {@code getMetadata()}
     * （元数据文件 {@code .0}，coil3 恒为 0 字节）**返回类型相同**，谁在 {@code getMethods()} 里排前面全看
     * 实现顺序 ⇒ 猜中元数据就永远读到 0 ⇒「未命中」⇒ 把已经就位的键当缺键反复重写（真机 journal 里那些
     * {@code DIRTY}/{@code CLEAN} 对与 {@code READ} 风暴就是这么来的）。现在按名取 {@code getData}，
     * 签名只作兜底，并把结果写进 {@link #lastSnapWhy} 让「未命中」自解释。</p>
     */
    private static long snapshotLen(Object disk, String key) {
        try {
            if (mOpenSnapshot == null) {
                lastSnapWhy = "openSnapshot 句柄不可用";
                return 0L;
            }
            Object snap = mOpenSnapshot.invoke(disk, key);
            if (snap == null) {
                lastSnapWhy = "openSnapshot(key) 返回 null";
                return 0L;
            }
            Method getData = byName0(snap.getClass(), "getData");
            String how = "按名";
            if (getData == null) {
                getData = zeroArgReturning(snap.getClass(), "okio.Path");
                how = "按签名";
            }
            if (getData == null) {
                lastSnapWhy = "快照上找不到 okio.Path 取数器";
                return 0L;
            }
            Object okioPath = getData.invoke(snap);
            Path p = toPath(okioPath);
            long n = p == null ? 0L : Files.size(p);
            lastSnapWhy = "命中 " + n + " B（" + how + " " + getData.getName() + " → " + p + "）";
            lastSnapLen = n;
            closeQuietly(snap);
            return n;
        } catch (Throwable t) {
            lastSnapWhy = "读键异常 " + t.getClass().getSimpleName() + "：" + t.getMessage();
            PluginLog.d(TAG, "读宿主缓存键失败（当作未命中）：" + t);
            return 0L;
        }
    }

    /** 最近一次 {@link #snapshotLen} 的结论（失败详情里引用，避免「未命中」黑箱）。 */
    private static volatile String lastSnapWhy = "（本轮未读过）";

    /** 最近一次 {@link #snapshotLen} 读到的字节数（命中详情里带上，让证据自含）。 */
    private static volatile long lastSnapLen = -1L;

    /** 宿主 {@code DiskLruCache} 的内存状态只 dump 一次（只有第一现场有诊断价值）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean CACHE_DUMPED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 一次性 dump 宿主 {@code DiskLruCache} 的内存状态（只读反射；keys 名为可读名）。
     *
     * <p>目的：{@code openEditor(key)} 返回 null 时回答「为什么」—— 宿主缓存里这条键在不在内存索引、
     * 条目的 {@code readable}/{@code currentEditor}/{@code lengths} 是什么、缓存 {@code size/maxSize}
     * 是否触顶。插件不写宿主的任何字段，只看。</p>
     */
    private static void dumpCacheState(Object disk, String key) {
        try {
            Object cache = null;
            String fieldName = "?";
            for (Class<?> k = disk.getClass(); k != null && cache == null; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DiskLruCache")) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(disk);
                    if (v != null) {
                        cache = v;
                        fieldName = k.getSimpleName() + "." + f.getName();
                        break;
                    }
                }
            }
            if (cache == null) {
                PluginLog.w(TAG, "cache-dump：DiskCache 上没有 DiskLruCache 字段（" + disk.getClass().getName() + "）");
                return;
            }
            String hash = com.example.netease.core.Hashes
                    .sha256Hex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            sb.append("cache-dump（").append(fieldName).append(" → ").append(cache.getClass().getName())
                    .append("）keyHash=").append(hash, 0, 12).append("…");
            for (java.lang.reflect.Field f : cache.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                String n = f.getName();
                Object v;
                try {
                    v = f.get(cache);
                } catch (Throwable t) {
                    continue;
                }
                if (v instanceof java.util.Map<?, ?> map) {
                    Object hit = null;
                    for (Object k : map.keySet()) {
                        String s = String.valueOf(k);
                        if (s.contains(hash) || s.equals(key)) {
                            hit = k;
                            break;
                        }
                    }
                    sb.append(" ").append(n).append("=").append(map.size()).append(" 条（本键 ")
                            .append(hit == null ? "不在内存索引" : "在索引：" + dumpEntry(map.get(hit))).append("）");
                } else if (n.equals("size") || n.equals("maxSize") || n.equals("maxSizeBytes")
                        || n.equals("redundantOpCount") || n.equals("closed") || n.contains("directory")) {
                    sb.append(" ").append(n).append("=").append(v);
                }
            }
            PluginLog.w(TAG, sb.toString());
        } catch (Throwable t) {
            PluginLog.w(TAG, "cache-dump 失败：" + t);
        }
    }

    /** 条目关键字段（{@code readable}/{@code currentEditor}/{@code lengths}）—— openEditor 为何 null 的现场。 */
    private static String dumpEntry(Object entry) {
        if (entry == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("entry{");
        for (java.lang.reflect.Field f : entry.getClass().getDeclaredFields()) {
            String n = f.getName();
            if (!n.equals("key") && !n.equals("readable") && !n.equals("currentEditor")
                    && !n.equals("lengths") && !n.equals("zombie")) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(entry);
                if (v instanceof long[] ls) {
                    v = java.util.Arrays.toString(ls);
                }
                if (v instanceof String s) {
                    v = s.length() > 16 ? s.substring(s.length() - 12) + "(len=" + s.length() + ")" : s;
                }
                b.append(n).append('=').append(v).append(' ');
            } catch (Throwable ignored) {
                // 跳过读不到的字段
            }
        }
        return b.append('}').toString();
    }

    /**
     * 经活着的 DiskCache 写一个键：{@code openEditor → getData → 写字节 → commit}。
     *
     * <p>{@code why} 收失败原因（写进 {@code deliver} 的 WARN 行）—— 旧版把异常只丢给
     * {@code PluginLog.d}（debug 级不进日志文件），真机 7659 行失败没有一行带得出原因。</p>
     */
    private static int write(Object disk, String key, byte[] bytes, StringBuilder why) {
        Object editor = null;
        try {
            editor = mOpenEditor.invoke(disk, key);
            if (editor == null) {
                why.append("openEditor(").append(key.length()).append(" 字符键) 返回 null")
                        .append("（同轮回读诊断：").append(lastSnapWhy).append("）");
                if (CACHE_DUMPED.compareAndSet(false, true)) {
                    dumpCacheState(disk, key);          // 只有第一现场有诊断价值
                }
                return 0;
            }
            Object okioPath = mGetData.invoke(editor);
            Path p = toPath(okioPath);
            if (p == null) {
                why.append("getData 拿不到落盘路径（句柄=").append(mGetData).append(" 返回=")
                        .append(okioPath).append("）");
                return 0;
            }
            Files.write(p, bytes);
            Object snap = mCommit.invoke(editor);
            editor = null;                                  // commit 之后不再 abort
            if (snap == null) {
                why.append("commit 返回 null（宿主未接受：").append(p.getFileName()).append("）");
                return 0;
            }
            closeQuietly(snap);
            why.append("新写 ").append(bytes.length).append(" B → ").append(p.getFileName());
            return bytes.length;
        } catch (Throwable t) {
            PluginLog.w(TAG, "写宿主缓存键异常（abort）：" + t);
            why.append("异常 ").append(t.getClass().getSimpleName()).append("：").append(t.getMessage());
            return 0;
        } finally {
            if (editor != null) {
                abortQuietly(editor);
            }
        }
    }

    /** {@code okio.Path} → {@code java.nio.file.Path}（okio 3 的 Path 有 {@code toFile()}）。 */
    private static Path toPath(Object okioPath) {
        if (okioPath == null) {
            return null;
        }
        if (okioPath instanceof Path p) {
            return p;
        }
        try {
            Method toFile = accessible(okioPath.getClass().getMethod("toFile"));
            Object f = toFile.invoke(okioPath);
            if (f instanceof java.io.File file) {
                return file.toPath();
            }
        } catch (Throwable ignored) {
            // 退 toString
        }
        try {
            return Path.of(String.valueOf(okioPath));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 关掉快照：优先按名 {@code close()}（coil3 在本机可读名），拿不到再按签名兜底 ——
     * 兜底<b>排除</b> {@code commit*} 系列，绝不把「本该放弃」的编辑器误提交。
     */
    private static void closeQuietly(Object o) {
        if (o == null) {
            return;
        }
        if (invokeNoArgVoid(o, "close")) {
            return;
        }
        try {
            for (Method m : o.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class || m.isSynthetic()) {
                    continue;
                }
                if (m.getParameterCount() != 0 || m.getReturnType() != void.class) {
                    continue;
                }
                if (m.getName().startsWith("commit")) {
                    continue;
                }
                m.invoke(o);
                return;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 放弃一个编辑器：优先 {@code abort()}，没有就退回 {@link #closeQuietly}。 */
    private static void abortQuietly(Object editor) {
        if (editor == null) {
            return;
        }
        if (!invokeNoArgVoid(editor, "abort")) {
            closeQuietly(editor);
        }
    }

    /** 按名调一个 0 参 void 方法；成功 true。 */
    private static boolean invokeNoArgVoid(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            if (m.getReturnType() != void.class || m.getParameterCount() != 0) {
                return false;
            }
            m.setAccessible(true);
            m.invoke(o);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
