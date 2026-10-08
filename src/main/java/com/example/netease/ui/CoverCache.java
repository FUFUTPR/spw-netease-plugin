package com.example.netease.ui;

import com.example.netease.core.DataPaths;
import com.example.netease.core.Hashes;
import com.example.netease.core.PluginLog;
import com.example.netease.svc.CoverStore;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面板封面缩略图缓存（0.6.0 起；0.11.8 收敛）。
 *
 * <p><b>0.11.8 改了什么（Q1 / A13 收敛）</b>：以前这里自带一套 HTTP 取图（自己拼 {@code ?param=240y240}、
 * 自己写 {@code cover\<md5>_<px>.png}）—— 那是「每个模块各拼一次尺寸参数」的老毛病，也是 A13 尺寸分布
 * 收敛不掉的一个来源。现在：</p>
 * <ol>
 *   <li><b>取图唯一入口</b>：{@link CoverStore#imageForUrl(String)}（内部走 1200 单尺寸 + sha256 去重 +
 *       失败分类的那条管线）。本类<b>不再</b>开 HTTP、不再拼尺寸参数；</li>
 *   <li>本类只做「大图 → 面板要的 px 小图」的缩放与缓存，落 {@code cover\thumb\}
 *       （在 {@code cover\} 树里 ⇒ R8「清理音乐封面缓存」按钮一并清掉，不会留下孤儿）；</li>
 *   <li>面板需要的 px 与宿主无关（宿主行内缩略图自己缩，96/192 由 {@code CoverPrimer} 铺）。</li>
 * </ol>
 *
 * <p><b>线程约定（最关键的一条）</b>：{@link #get(String, int)} 由 {@link javax.swing.table.TableCellRenderer}
 * 在 <b>EDT</b> 上同步调用，必须<b>永不阻塞</b>：命中内存缓存立即返回；未命中就登记任务、立刻返回
 * {@code null}（本帧先不画图），完成后经 {@link #onLoaded(Runnable)} 注册的回调在 EDT 上触发一次重绘。</p>
 *
 * <p><b>失败负缓存（0.11.55 F8 起自愈）</b>：拿不到的封面记进 {@code FAILED}（连完成纪元一起记），
 * 同一纪元内不再重试（避免每帧发起同一条请求）；<b>有新批次落地</b>（{@link CoverStore#doneEpoch()} 前进，
 * 下载池又造好了桩）或失败已超 {@code failTtlMs} 就清掉负缓存放行重试 —— 面板滚动中「先失败后成功」
 * 的 URL 无需重启面板也能出图。</p>
 */
final class CoverCache {

    private static final String TAG = "ui.cover";

    /** 单张缩略图最大字节数（超过视为异常，直接放弃）。 */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final Map<String, ImageIcon> MEM = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    /** 失败负缓存：key → {失败时的完成纪元, 失败时刻 ms}；自愈口径见 {@link #get(String, int)}。 */
    private static final Map<String, long[]> FAILED = new ConcurrentHashMap<>();

    /** 失败负缓存最长寿命 ms（0.11.55 F8；超过即无条件放行重试一次）。 */
    private static volatile long failTtlMs = 120_000L;

    /** 缩放线程池：守护线程，2 条足够（现在是纯本地解码+缩放，瓶颈在磁盘）。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "netease-cover-ui");
        t.setDaemon(true);
        return t;
    });

    private static volatile Runnable listener = () -> {
    };

    private CoverCache() {
    }

    /** 注册「有新封面可用」回调（会在 EDT 上执行，函数体应只做重绘这类轻活）。 */
    static void onLoaded(Runnable callback) {
        listener = callback == null ? () -> {
        } : callback;
    }

    /**
     * 取封面缩略图：**永不阻塞**。
     *
     * @param url 原始封面 URL（可为 null / 空串，此时直接返回 null）
     * @param px  目标边长（像素）
     * @return 已就绪的图标；未就绪返回 {@code null}（后台加载，完成后触发回调）
     */
    static ImageIcon get(String url, int px) {
        if (url == null || url.isBlank() || px <= 0) {
            return null;
        }
        String key = px + "|" + url;
        ImageIcon hit = MEM.get(key);
        if (hit != null) {
            return hit;
        }
        if (negativeCached(key) || !PENDING.add(key)) {
            return null;
        }
        POOL.submit(() -> {
            try {
                ImageIcon icon = load(url, px, key);
                if (icon != null) {
                    MEM.put(key, icon);
                    FAILED.remove(key);
                    SwingUtilities.invokeLater(listener);
                } else {
                    markFailed(key);
                }
            } catch (Throwable t) {
                markFailed(key);
                PluginLog.d(TAG, "封面加载失败（忽略）：" + t);
            } finally {
                PENDING.remove(key);
            }
        });
        return null;
    }

    /** 失败负缓存判定：同纪元且未超龄 ⇒ 仍压制；否则清掉负缓存放行（0.11.55 F8 自愈）。 */
    private static boolean negativeCached(String key) {
        long[] rec = FAILED.get(key);
        if (rec == null) {
            return false;
        }
        if (rec[0] != CoverStore.doneEpoch() || System.currentTimeMillis() - rec[1] >= failTtlMs) {
            FAILED.remove(key);        // 有新批次落地 / 失败超龄 ⇒ 负缓存失效
            return false;
        }
        return true;
    }

    /** 记一条失败负缓存（连当时的完成纪元一起记，供 {@link #negativeCached} 判自愈）。 */
    private static void markFailed(String key) {
        FAILED.put(key, new long[]{CoverStore.doneEpoch(), System.currentTimeMillis()});
    }

    /** 已就绪（内存里已有图）的数量，供自检/状态行使用。 */
    static int readyCount() {
        return MEM.size();
    }

    // ------------------------------------------------------------------ 内部

    private static ImageIcon load(String url, int px, String key) {
        BufferedImage img = readDisk(key, px);
        if (img == null) {
            byte[] bytes = CoverStore.imageForUrl(url);       // 唯一入口：不再自己开 HTTP
            if (bytes == null) {
                return null;
            }
            try {
                img = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (Throwable ioe) {
                PluginLog.d(TAG, "封面解码失败 :: " + ioe);
                return null;
            }
            if (img == null) {
                PluginLog.d(TAG, "封面不是图片（" + bytes.length + " 字节），已放弃");
                return null;
            }
            writeDisk(key, img, px);
        }
        return new ImageIcon(scale(img, px));
    }

    /** 等比缩放到不超过 px×px 的方图（封面本身是方图，这里仍然按比例算，避免拉伸变形）。 */
    private static BufferedImage scale(BufferedImage src, int px) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        double k = Math.min((double) px / w, (double) px / h);
        int tw = Math.max(1, (int) Math.round(w * k));
        int th = Math.max(1, (int) Math.round(h * k));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 缩略图磁盘缓存：{@code <插件数据目录>\cover\thumb\<sha256("px|url")>_<px>.png}（R8 一起清）。 */
    private static Path diskFile(String key, int px) {
        try {
            Path dir = DataPaths.ensure(DataPaths.data().resolve("cover").resolve("thumb"));
            String hash = Hashes.sha256Hex(key.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(hash + "_" + px + ".png");
        } catch (Throwable t) {
            return null;
        }
    }

    private static BufferedImage readDisk(String key, int px) {
        try {
            Path f = diskFile(key, px);
            if (f == null || !Files.isRegularFile(f)) {
                return null;
            }
            long size = Files.size(f);
            if (size <= 0L || size > MAX_BYTES) {
                return null;
            }
            return ImageIO.read(f.toFile());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(String key, BufferedImage img, int px) {
        try {
            Path f = diskFile(key, px);
            if (f == null) {
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            if (!ImageIO.write(scale(img, px), "png", bos)) {
                return;
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, bos.toByteArray());
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            PluginLog.d(TAG, "封面缩略图落盘失败（忽略）：" + t);
        }
    }
}
