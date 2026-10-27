package com.example.netease.svc;

import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cached plugin originals supply visible list rows after normal host LRU eviction. No network/audio. */
public final class RowCoverSource {
    private static volatile boolean active;
    private static final java.util.regex.Pattern DIMENSIONS =
            java.util.regex.Pattern.compile("(?:[?&])w=([0-9]+)&h=([0-9]+)(?:&|$)");
    private static Field data, key;
    private static Method read, write, result, vri, path;
    private RowCoverSource() { }
    public static void setActive(boolean value) { active = value; }
    static boolean active() { return active; }

    public static synchronized Class<?> validate() throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.xuncorp.voxzen.image.DesktopAudioCoverFetcher", false, RowCoverSource.class.getClassLoader());
        data = type.getDeclaredField("data"); data.setAccessible(true);
        key = type.getDeclaredField("diskCacheKey"); key.setAccessible(true);
        read = type.getDeclaredMethod("readFromDiskCache"); read.setAccessible(true);
        write = type.getDeclaredMethod("writeToDiskCache", byte[].class); write.setAccessible(true);
        result = type.getDeclaredMethod("toFetchResult", read.getReturnType()); result.setAccessible(true);
        vri = data.getType().getMethod("getVri");
        path = java.util.Arrays.stream(vri.getReturnType().getMethods()).filter(method ->
                method.getParameterCount() == 0 && method.getReturnType() == String.class
                        && !method.getName().equals("toString")).findFirst().orElseThrow();
        return type;
    }

    public static Object fetch(Object fetcher) throws ReflectiveOperationException {
        Object previous = read.invoke(fetcher);
        Object snapshot = refill(fetcher, previous);
        if (snapshot == previous) { close(snapshot); return null; }
        if (snapshot == null) return null;
        try {
            Object fetched = result.invoke(fetcher, snapshot);
            snapshot = null;
            return fetched;
        } finally { close(snapshot); }
    }

    /** Called after the host's ordinary cache reader; never hook its unsupported suspend fetch(). */
    public static Object refill(Object fetcher, Object snapshot) throws ReflectiveOperationException {
        String value = (String) path.invoke(vri.invoke(data.get(fetcher)));
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { return snapshot; }
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPath() == null || !uri.getPath().matches("/netease/[1-9][0-9]*")) return snapshot;
        long song;
        try { song = Long.parseLong(uri.getPath().substring("/netease/".length())); }
        catch (NumberFormatException invalid) { return snapshot; }
        String cacheKey = (String) key.get(fetcher);
        var dimension = DIMENSIONS.matcher(cacheKey);
        if (!dimension.find()) return snapshot; // Playback artwork keeps its existing audio-aligned pipeline.
        int width, height;
        try { width = Integer.parseInt(dimension.group(1)); height = Integer.parseInt(dimension.group(2)); }
        catch (NumberFormatException invalid) { return snapshot; }
        if (width <= 0 || height <= 0 || width > 192 || height > 192) return snapshot;
        if (snapshot != null) {
            try {
                var getter = snapshot.getClass().getMethod("getData"); getter.setAccessible(true);
                Object location = getter.invoke(snapshot);
                if (Files.size(Path.of(location.toString())) > 0) return snapshot;
            } catch (java.io.IOException missing) {
                // A cache eviction raced with the read; the original is still available below.
            }
        }
        String ref = CoverStore.refKeyOf(song);
        if (ref == null) {
            String[] row = CoverDelivery.readRow(song);
            if (row == null) return snapshot;
            ref = CoverStore.refKey(row[0], row[1]);
        }
        byte[] original = CoverStore.originalImage(ref);
        if (original == null) return snapshot;
        byte[] thumb = CoverPrimer.thumb(original, Math.max(width, height));
        if (thumb == null) return snapshot;
        close(snapshot); // A negative snapshot cannot be kept open while editing the same key.
        try { return write.invoke(fetcher, (Object) thumb); }
        catch (ReflectiveOperationException unavailable) {
            com.example.netease.core.PluginLog.d("rowcover","本地封面提交失败，交回宿主重试："+unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private static void close(Object snapshot) {
        if (snapshot instanceof AutoCloseable owned) try { owned.close(); } catch (Exception ignored) { }
    }
}
