package com.example.netease.svc;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在播放路径上的 Range 缓存：上游字节优先发给播放器，同时按原始偏移落盘。
 *
 * <p>只有已覆盖 {@code [0,total)} 的文件才会从 {@code .part} 原子变成
 * {@code .cache}；断网、跳播或宿主关闭连接留下的稀疏文件不会被当成完整音频。
 * 缓存失败永远只降级为纯转发，绝不能中断播放。</p>
 */
final class StreamingAudioCache {

    private static final String TAG = "stream-cache";
    private static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;

    private static final Map<String, State> STATES = new HashMap<>();
    /**
     * 已完整落盘的键 → 字节数（0.11.0）。
     *
     * <p>为什么需要它：<b>完成的曲目没有 {@code State}</b>（{@code finish()} 在改名成 {@code .cache}
     * 后会把 State 删掉），于是一个「只知道问 State」的水位查询会返回 0 —— 全量预取线程据此
     * 从第 0 字节重新下一遍，白吃一遍流量、还会把刚改名的文件旁边再养出一个 {@code .part}。
     * 判据：文件级真相（{@code .cache} 存在即全覆盖），而不是 State 表的存活期。</p>
     */
    private static final Map<String, Long> COMPLETE = new HashMap<>();
    private static long currentSong = -1L;

    private record Span(long from, long to) {
    }

    private static final class State {
        long total = -1L;
        int active;
        boolean discardWhenIdle;
        final List<Span> spans = new ArrayList<>();
    }

    /** 一条上游响应对应的稀疏写入器。对外所有方法都不抛。 */
    static final class Sink implements AutoCloseable {
        private final String key;
        private final Path part;
        private final long start;
        private RandomAccessFile file;
        private long written;
        private boolean failed;
        private boolean closed;

        Sink(String key, Path part, long start) throws IOException {
            this.key = key;
            this.part = part;
            this.start = start;
            this.file = new RandomAccessFile(part.toFile(), "rw");
            this.file.seek(start);
        }

        void write(byte[] data, int off, int len) {
            if (closed || failed || file == null || len <= 0) {
                return;
            }
            try {
                file.write(data, off, len);
                written += len;
            } catch (Throwable t) {
                failed = true;
                closeFile();
                PluginLog.w(TAG, "缓存写入失败，本次降级为纯转发：" + brief(t));
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeFile();
            finish(key, part, start, written, failed);
        }

        private void closeFile() {
            RandomAccessFile f = file;
            file = null;
            if (f != null) {
                try {
                    f.close();
                } catch (Throwable ignored) {
                    // 已降级，不影响播放
                }
            }
        }
    }

    private StreamingAudioCache() {
    }

    static synchronized void initialize() {
        STATES.clear();
        COMPLETE.clear();
        currentSong = -1L;
        try {
            Files.createDirectories(dir());
            try (var files = Files.list(dir())) {
                for (Path p : files.toList()) {
                    if (isPart(p)) {
                        Files.deleteIfExists(p);
                    } else if (isComplete(p)) {
                        COMPLETE.put(keyOf(p), Files.size(p));
                    }
                }
            }
            trim();
        } catch (Throwable t) {
            PluginLog.w(TAG, "初始化失败（播放不受影响）：" + brief(t));
        }
    }

    /** 设置改变时异步应用新容量，不占宿主配置回调线程。 */
    static void reconfigureAsync() {
        Thread t = new Thread(() -> {
            synchronized (StreamingAudioCache.class) {
                if (PluginConfig.audioCacheBytes() == 0L) {
                    purgeExcept(currentSong);
                } else {
                    trim();
                }
            }
        }, "netease-cache-trim");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 记录真正的切歌。宿主会并发预读下一首，所以只有从头读的请求才切换当前曲目。
     */
    static synchronized void select(long songId, boolean startsAtBeginning) {
        select(songId, startsAtBeginning, PluginConfig.audioCacheBytes());
    }

    /** 分离容量参数，便于离线探针精确验证 0 GB 与小容量 LRU。调用方已持有类锁。 */
    private static void select(long songId, boolean startsAtBeginning, long limit) {
        if (songId <= 0L || (!startsAtBeginning && currentSong > 0L) || songId == currentSong) {
            return;
        }
        currentSong = songId;
        // 0.11.0：真正的切歌 —— 上一首的全量预取立刻作废（它的 .part 马上要被删掉）。
        StreamPrefetcher.cancelExcept(songId);
        if (limit == 0L) {
            purgeExcept(songId);
        } else {
            trim(limit);
        }
    }

    /** 当前播放曲目（0.11.0：预取只服务这一首）。 */
    static synchronized long current() {
        return currentSong;
    }

    /**
     * {@link #COMPLETE} 里的字节数，<b>并在每次查询时与磁盘对账</b>。
     *
     * <p>为什么要对账：这条记录是「水位查询的唯一真相」，而 {@code .cache} 可能被本类之外的
     * 任何一次清理（或用户在资源管理器里删文件）弄没。曾经出现过「记录还在、文件已不在」，
     * 于是预取线程以为整首已落盘、直接收工，宿主却只能回源转发（又慢回 49 KB/s）。
     * 对账代价是一次 {@code stat}，相对于一次 512 KB 的请求可以忽略。</p>
     *
     * @return 完整文件的字节数；没有或已销账返回 {@code -1}
     */
    private static long completeBytes(String key) {
        Long done = COMPLETE.get(key);
        if (done == null) {
            return -1L;
        }
        Path p = completePath(key);
        try {
            if (Files.isRegularFile(p)) {
                long size = Files.size(p);
                if (size == done.longValue()) {
                    return size;
                }
                COMPLETE.put(key, size);      // 文件换代过：以磁盘为准
                return size;
            }
        } catch (Throwable ignored) {
            // 读不到就当没有
        }
        COMPLETE.remove(key);
        PluginLog.i(TAG, "完整缓存已不在（销账）：" + key);
        return -1L;
    }

    /**
     * 取写入期状态，顺手与磁盘对账：{@code .part} 与 {@code .cache} 都不在了就销账。
     *
     * <p>理由同 {@link #completeBytes}:只要有任何一条「记录还在、文件没了」的缝隙，
     * 预取线程就会以为整首已落盘而收工，宿主只能回源转发。{@code Sink} 构造时就创建
     * {@code .part}，所以「有状态却没有文件」只可能是被外部删掉了。</p>
     */
    private static State liveState(String key) {
        State s = STATES.get(key);
        if (s == null) {
            return null;
        }
        if (!Files.isRegularFile(partPath(key)) && !Files.isRegularFile(completePath(key))) {
            STATES.remove(key);
            PluginLog.i(TAG, "临时缓存已不在（销账）：" + key);
            return null;
        }
        return s;
    }

    static synchronized long coveredOf(long songId, String level) {
        String k = key(songId, level);
        long done = completeBytes(k);
        if (done >= 0L) {
            return done;
        }
        State s = liveState(k);
        if (s == null) {
            return 0L;
        }
        long c = covered(s.spans);
        return s.total > 0L ? Math.min(c, s.total) : c;
    }

    /** 已知的整首字节数；未知返回 -1。 */
    static synchronized long totalOf(long songId, String level) {
        String k = key(songId, level);
        long done = completeBytes(k);
        if (done >= 0L) {
            return done;
        }
        State s = liveState(k);
        return s == null ? -1L : s.total;
    }

    /** {@code [from, to)} 是否已完全落盘（预取跳过「已被转发路径补齐」的前缀时用）。 */
    static synchronized boolean coversRange(long songId, String level, long from, long to) {
        String k = key(songId, level);
        if (to <= from) {
            return false;
        }
        if (completeBytes(k) >= 0L) {
            return true;
        }
        State s = STATES.get(k);
        if (s == null) {
            return false;
        }
        long p = from;
        for (Span sp : s.spans) {
            if (sp.from() > p) {
                return false;
            }
            if (sp.to() > p) {
                p = sp.to();
                if (p >= to) {
                    return true;
                }
            }
        }
        return p >= to;
    }

    /** 未完成的 {@code .part} 路径（本地直出用；不创建文件）。 */
    static synchronized Path partOf(long songId, String level) {
        return partPath(key(songId, level));
    }

    static synchronized Path complete(long songId, String level) {
        if (PluginConfig.audioCacheBytes() == 0L && currentSong > 0L && songId != currentSong) {
            return null;
        }
        String k = key(songId, level);
        Path p = completePath(k);
        try {
            if (Files.isRegularFile(p) && Files.size(p) > 0L) {
                COMPLETE.put(k, Files.size(p));
                Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
                return p;
            }
        } catch (Throwable ignored) {
            // 当作未命中，回源播放
        }
        return null;
    }

    static synchronized Sink sink(long songId, String level, long start, long total) {
        if (songId <= 0L || start < 0L || total <= 0L || start >= total || total > MAX_ENTRY_BYTES) {
            return null;
        }
        // 0 GB 时宿主可能并发探测/预读下一首；它不是“正在听”的曲目，不能留下临时片段。
        // 首次请求 currentSong 尚未确定时仍允许写，随后 select() 会把它认作当前曲目。
        if (PluginConfig.audioCacheBytes() == 0L && currentSong > 0L && songId != currentSong) {
            return null;
        }
        try {
            if (dir().toFile().getUsableSpace() < MIN_FREE_BYTES) {
                PluginLog.w(TAG, "剩余空间不足 " + (MIN_FREE_BYTES / (1024L * 1024L))
                        + " MB，本次只转发不落盘");
                return null;
            }
            String key = key(songId, level);
            if (completeBytes(key) >= 0L) {
                // 已经有完整 .cache（刚才那轮预取或上一轮播放留下的）：绝不再养一个 .part，
                // 否则预取线程会把整首重下一遍（见 COMPLETE 的注释）。
                return null;
            }
            State state = STATES.computeIfAbsent(key, ignored -> new State());
            if (state.total > 0L && state.total != total) {
                // 同一首/档位的源已换代，不能把两个不同文件的 Range 拼在一起。
                if (state.active > 0) {
                    return null;
                }
                Files.deleteIfExists(partPath(key));
                state.spans.clear();
            }
            state.total = total;
            Sink sink = new Sink(key, partPath(key), start);
            state.active++;
            return sink;
        } catch (Throwable t) {
            PluginLog.d(TAG, "建立缓存写入失败（忽略）：" + brief(t));
            return null;
        }
    }

    static synchronized int purgeAll() {
        // 0.11.0：手动清理/凭据变化时，正在跑的全量预取也要停 —— 否则它会把刚删的字节再拉一遍。
        StreamPrefetcher.cancelAll();
        for (State state : STATES.values()) {
            state.discardWhenIdle = true;
        }
        int deleted = deleteFilesExcept(-1L);
        STATES.entrySet().removeIf(e -> {
            if (e.getValue().active > 0) {
                return false;
            }
            deleteQuietly(partPath(e.getKey()));
            return true;
        });
        return deleted;
    }

    static synchronized String stats() {
        long bytes = 0L;
        int complete = 0;
        int partial = 0;
        try (var files = Files.list(dir())) {
            for (Path p : files.filter(StreamingAudioCache::isCacheFile).toList()) {
                bytes += Files.size(p);
                if (isComplete(p)) {
                    complete++;
                } else {
                    partial++;
                }
            }
        } catch (Throwable ignored) {
            // 诊断信息尽力即可
        }
        return complete + " 首完整"
                + (partial > 0 ? " + " + partial + " 首临时" : "")
                + " / " + (bytes / (1024L * 1024L)) + " MB";
    }

    private static synchronized void finish(String key, Path part, long start, long written, boolean failed) {
        State state = STATES.get(key);
        if (state == null) {
            return;
        }
        state.active = Math.max(0, state.active - 1);
        if (!failed && written > 0L) {
            state.spans.add(new Span(start, Math.min(state.total, start + written)));
            merge(state.spans);
        }
        if (state.discardWhenIdle && state.active == 0) {
            deleteQuietly(part);
            STATES.remove(key);
            return;
        }
        if (state.active == 0 && state.total > 0L && covered(state.spans) >= state.total) {
            try {
                Path done = completePath(key);
                moveComplete(part, done);
                COMPLETE.put(key, state.total);
                STATES.remove(key);
                PluginLog.i(TAG, "边听边下完成：" + key + " / " + (state.total / 1024L) + " KB");
                trim();
            } catch (Throwable t) {
                PluginLog.d(TAG, "完成缓存失败（忽略）：" + brief(t));
            }
        }
    }

    private static void merge(List<Span> spans) {
        spans.sort(Comparator.comparingLong(Span::from));
        List<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            if (span.to() <= span.from()) {
                continue;
            }
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            Span last = merged.get(merged.size() - 1);
            if (span.from() <= last.to()) {
                merged.set(merged.size() - 1, new Span(last.from(), Math.max(last.to(), span.to())));
            } else {
                merged.add(span);
            }
        }
        spans.clear();
        spans.addAll(merged);
    }

    private static long covered(List<Span> spans) {
        if (spans.isEmpty() || spans.get(0).from() > 0L) {
            return 0L;
        }
        return spans.get(0).to();
    }

    private static void trim() {
        trim(PluginConfig.audioCacheBytes());
    }

    /** 调用方持有类锁；参数化后可用极小文件覆盖容量/LRU 边界，无需制造 GB 级测试文件。 */
    private static void trim(long limit) {
        try {
            Files.createDirectories(dir());
            List<Path> files;
            try (var stream = Files.list(dir())) {
                // 容量上限必须覆盖完整文件和未听完的 .part。若只统计 .cache，频繁
                // 切歌留下的片段会绕过 10 GB 等上限，最终仍可把磁盘吃满。
                files = stream.filter(StreamingAudioCache::isCacheFile).toList();
            }
            // 0.11.0：按「曲目」聚合后再 LRU —— 一首歌的 .cache/.part（多档位也算一笔）整体进出，
            // 逐文件删会把「听了一半的歌」拆散，也让上限在大歌单下算不准。
            Map<Long, List<Path>> bySong = new LinkedHashMap<>();
            for (Path p : files) {
                bySong.computeIfAbsent(songIdOf(p), ignored -> new ArrayList<>()).add(p);
            }
            Map<Long, Long> usedAt = new HashMap<>();
            long total = 0L;
            for (Map.Entry<Long, List<Path>> e : bySong.entrySet()) {
                long newest = 0L;
                for (Path p : e.getValue()) {
                    newest = Math.max(newest, mtime(p));
                    total += Files.size(p);
                }
                usedAt.put(e.getKey(), newest);
            }
            List<Long> order = new ArrayList<>(bySong.keySet());
            order.sort(Comparator.comparingLong(k -> usedAt.getOrDefault(k, 0L)));
            for (Long songId : order) {
                if (limit > 0L && total <= limit) {
                    break;
                }
                // 不缓存模式仍要让当前曲目在本次播放内可复用，切歌再删。
                if (songId != null && songId.longValue() == currentSong) {
                    continue;
                }
                List<Path> group = bySong.get(songId);
                if (group == null || isBusy(group)) {
                    // Windows 不能可靠删除仍打开的文件；等最后一个 Sink.close() 后再整理。
                    continue;
                }
                long sum = 0L;
                for (Path p : group) {
                    long size = Files.size(p);
                    if (Files.deleteIfExists(p)) {
                        sum += size;
                        total -= size;
                        if (isPart(p)) {
                            STATES.remove(keyOf(p));
                        } else {
                            COMPLETE.remove(keyOf(p));
                        }
                    }
                }
                PluginLog.i(TAG, "LRU 清理：曲目 " + songId + " / " + (sum / 1024L) + " KB（"
                        + group.size() + " 个文件）");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "容量整理失败（忽略）：" + brief(t));
        }
    }

    /** 组内是否有还开着的 {@code .part} 写入（有就别删）。 */
    private static boolean isBusy(List<Path> group) {
        for (Path p : group) {
            if (!isPart(p)) {
                continue;
            }
            State state = STATES.get(keyOf(p));
            if (state != null && state.active > 0) {
                return true;
            }
        }
        return false;
    }

    private static void purgeExcept(long songId) {
        deleteFilesExcept(songId);
        STATES.entrySet().removeIf(e -> {
            if (songIdOf(e.getKey()) == songId) {
                return false;
            }
            State state = e.getValue();
            state.discardWhenIdle = true;
            if (state.active > 0) {
                return false;
            }
            deleteQuietly(partPath(e.getKey()));
            return true;
        });
    }

    private static int deleteFilesExcept(long songId) {
        int deleted = 0;
        try {
            Files.createDirectories(dir());
            try (var stream = Files.list(dir())) {
                for (Path p : stream.toList()) {
                    if (songId > 0L && songIdOf(p) == songId) {
                        continue;
                    }
                    // 打开的 part 由 Sink.close() 在空闲后删，避免 Windows 上删文件失败。
                    String key = keyOf(p);
                    State state = STATES.get(key);
                    if (isPart(p) && state != null && state.active > 0) {
                        state.discardWhenIdle = true;
                        continue;
                    }
                    if (Files.deleteIfExists(p)) {
                        deleted++;
                        if (isComplete(p)) {
                            COMPLETE.remove(keyOf(p));
                        }
                    }
                }
            }
            if (deleted > 0) {
                // 留痕：真机排查「完整缓存去哪儿了」时，这一行能指认是本插件删的。
                PluginLog.i(TAG, "清理缓存文件：" + deleted + " 个（"
                        + (songId > 0L ? "保留曲目 " + songId : "全部清理") + "）");
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "清理失败（忽略）：" + brief(t));
        }
        return deleted;
    }

    private static void moveComplete(Path part, Path done) throws IOException {
        try {
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setLastModifiedTime(done, FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long songIdOf(Path p) {
        return songIdOf(keyOf(p));
    }

    private static long songIdOf(String key) {
        try {
            int dash = key.indexOf('-');
            return Long.parseLong(dash < 0 ? key : key.substring(0, dash));
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String keyOf(Path p) {
        String name = p.getFileName().toString();
        if (name.endsWith(".cache")) {
            return name.substring(0, name.length() - ".cache".length());
        }
        if (name.endsWith(".part")) {
            return name.substring(0, name.length() - ".part".length());
        }
        return name;
    }

    private static boolean isComplete(Path p) {
        return p.getFileName().toString().endsWith(".cache") && Files.isRegularFile(p);
    }

    private static boolean isPart(Path p) {
        return p.getFileName().toString().endsWith(".part") && Files.isRegularFile(p);
    }

    private static boolean isCacheFile(Path p) {
        return isComplete(p) || isPart(p);
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Throwable ignored) {
            // 尽力清理
        }
    }

    /** 音频流缓存目录：{@code <插件数据>/audio-stream/}（包内可见：预取线程要查磁盘余量）。 */
    static Path dir() {
        return DataPaths.data().resolve("audio-stream");
    }

    private static Path partPath(String key) {
        return dir().resolve(key + ".part");
    }

    private static Path completePath(String key) {
        return dir().resolve(key + ".cache");
    }

    private static String key(long songId, String level) {
        String lv = level == null ? "standard" : level.replaceAll("[^a-zA-Z0-9_-]", "");
        return songId + "-" + (lv.isEmpty() ? "standard" : lv);
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
