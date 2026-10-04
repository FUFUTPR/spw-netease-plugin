package com.example.netease.svc;

import com.example.netease.core.DataPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

/** 无网络回归：验证 Range 拼接、音质隔离、0 GB 切歌清理、容量 LRU 与 HTTP Range 解析。 */
public final class StreamingCacheProbe {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("netease-stream-cache-probe-");
        try {
            Field dataDir = DataPaths.class.getDeclaredField("dataDir");
            dataDir.setAccessible(true);
            dataDir.set(null, root);

            StreamingAudioCache.initialize();
            byte[] full = "0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            StreamingAudioCache.Sink tail = StreamingAudioCache.sink(42L, "lossless", 5L, full.length);
            require(tail != null, "tail sink");
            tail.write(full, 5, 5);
            tail.close();
            require(StreamingAudioCache.complete(42L, "lossless") == null, "partial must stay hidden");

            StreamingAudioCache.Sink head = StreamingAudioCache.sink(42L, "lossless", 0L, full.length);
            require(head != null, "head sink");
            head.write(full, 0, 5);
            head.close();

            Path done = StreamingAudioCache.complete(42L, "lossless");
            require(done != null, "complete cache");
            require(Arrays.equals(full, Files.readAllBytes(done)), "range merge bytes");

            Method parse = NativeStreamServer.class.getDeclaredMethod("parseRange", String.class, long.class);
            parse.setAccessible(true);
            Object suffix = parse.invoke(null, "bytes=-3", 10L);
            require(suffix != null, "suffix range");
            Method start = suffix.getClass().getDeclaredMethod("start");
            Method end = suffix.getClass().getDeclaredMethod("end");
            start.setAccessible(true);
            end.setAccessible(true);
            require(((Long) start.invoke(suffix)) == 7L && ((Long) end.invoke(suffix)) == 9L,
                    "suffix range bounds");
            require(parse.invoke(null, "bytes=20-30", 10L) == null, "invalid range rejected");
            require(parse.invoke(null, "bytes=0-1,4-5", 10L) == null, "multi range rejected");

            require(StreamingAudioCache.purgeAll() >= 1, "purge complete cache");
            require(StreamingAudioCache.complete(42L, "lossless") == null, "purged cache hidden");

            // 同一 songId 的不同档位必须是两份缓存，不能把最先拿到的低音质复用给无损。
            Path standard = cacheComplete(43L, "standard", "low".getBytes(StandardCharsets.US_ASCII));
            Path lossless = cacheComplete(43L, "lossless", "lossless".getBytes(StandardCharsets.US_ASCII));
            require(!standard.equals(lossless), "quality cache paths isolated");
            require("low".equals(Files.readString(standard)), "standard cache bytes");
            require("lossless".equals(Files.readString(lossless)), "lossless cache bytes");
            StreamingAudioCache.purgeAll();

            // 用参数化的私有选择器模拟 0 GB，无需篡改真实配置快照。
            Method select = StreamingAudioCache.class.getDeclaredMethod(
                    "select", long.class, boolean.class, long.class);
            select.setAccessible(true);
            select.invoke(null, 100L, true, 0L);
            cacheComplete(100L, "lossless", "current".getBytes(StandardCharsets.US_ASCII));
            select.invoke(null, 101L, true, 0L);
            require(StreamingAudioCache.complete(100L, "lossless") == null,
                    "zero GB removes previous complete song on switch");

            // 正在写的上一首不能在 Windows 上强删；应标记并在 Sink.close() 后立即清掉。
            StreamingAudioCache.Sink active = StreamingAudioCache.sink(101L, "lossless", 0L, 10L);
            require(active != null, "active zero-GB sink");
            active.write("part".getBytes(StandardCharsets.US_ASCII), 0, 4);
            Path activePart = root.resolve("audio-stream").resolve("101-lossless.part");
            select.invoke(null, 102L, true, 0L);
            require(Files.exists(activePart), "active part deferred until close");
            active.close();
            require(!Files.exists(activePart), "previous active part removed after close");

            // 极小容量覆盖 LRU，无需制造 GB 级文件；完整缓存和未完成片段都必须计入。
            StreamingAudioCache.purgeAll();
            StreamingAudioCache.initialize();
            Path old = cacheComplete(201L, "lossless", "123456".getBytes(StandardCharsets.US_ASCII));
            Path recent = cacheComplete(202L, "lossless", "abcdef".getBytes(StandardCharsets.US_ASCII));
            Files.setLastModifiedTime(old, FileTime.fromMillis(1_000L));
            Files.setLastModifiedTime(recent, FileTime.fromMillis(2_000L));
            Method trim = StreamingAudioCache.class.getDeclaredMethod("trim", long.class);
            trim.setAccessible(true);
            trim.invoke(null, 10L);
            require(!Files.exists(old) && Files.exists(recent), "LRU removes oldest complete entry");

            StreamingAudioCache.Sink partial = StreamingAudioCache.sink(203L, "lossless", 0L, 20L);
            require(partial != null, "partial LRU sink");
            byte[] six = "ghijkl".getBytes(StandardCharsets.US_ASCII);
            partial.write(six, 0, six.length);
            partial.close();
            Path partialPath = root.resolve("audio-stream").resolve("203-lossless.part");
            Files.setLastModifiedTime(partialPath, FileTime.fromMillis(500L));
            trim.invoke(null, 10L);
            require(!Files.exists(partialPath) && Files.exists(recent),
                    "LRU counts and removes incomplete entry");

            System.out.println("PASS StreamingCacheProbe");
        } finally {
            deleteTree(root);
        }
    }

    private static void require(boolean ok, String label) {
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static Path cacheComplete(long songId, String level, byte[] bytes) throws Exception {
        StreamingAudioCache.Sink sink = StreamingAudioCache.sink(songId, level, 0L, bytes.length);
        require(sink != null, "complete sink " + songId + "/" + level);
        sink.write(bytes, 0, bytes.length);
        sink.close();
        Path result = StreamingAudioCache.complete(songId, level);
        require(result != null, "complete path " + songId + "/" + level);
        return result;
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private StreamingCacheProbe() {
    }
}
