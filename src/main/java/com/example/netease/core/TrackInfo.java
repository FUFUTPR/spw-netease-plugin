package com.example.netease.core;

import java.util.Locale;

/**
 * 当前播放曲目的**不可变快照**。
 *
 * <p>为什么不让上层直接持有宿主的 {@code MediaItem}：</p>
 * <ul>
 *   <li>宿主对象来自宿主类加载器，长期持有会拖住宿主的类/实例（内存与类加载面风险）。</li>
 *   <li>扩展点回调在宿主 IO 线程触发，跨线程只应传不可变值。</li>
 * </ul>
 *
 * <p>字段与 1.18.5 的 {@code MediaItem} 一一对应（该版本只有这 5 个字段）：
 * {@code artist} / {@code albumArtist} 可能是多值，用 {@code "/"} 分隔。</p>
 */
public record TrackInfo(String title, String artist, String album, String albumArtist, String path) {

    /** 空快照（未播放任何曲目时使用）。 */
    public static final TrackInfo EMPTY = new TrackInfo("", "", "", "", "");

    public TrackInfo {
        title = nz(title);
        artist = nz(artist);
        album = nz(album);
        albumArtist = nz(albumArtist);
        path = nz(path);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 匹配缓存键：规范化的文件路径的 md5。
     *
     * <p>用路径而不是「标题+歌手」做键：同名的翻唱/现场版会撞车，而路径在同一台机器上稳定。
     * 用户移动文件会导致缓存失效，这是可接受的（重新匹配一次即可）。</p>
     */
    public String cacheKey() {
        return Hashes.md5Hex(normalizePath(path));
    }

    private static String normalizePath(String p) {
        if (p == null) {
            return "";
        }
        return p.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    /** 展示名："歌手 - 标题"，缺一个就退化。 */
    public String displayName() {
        if (!artist.isEmpty() && !title.isEmpty()) {
            return artist + " - " + title;
        }
        if (!title.isEmpty()) {
            return title;
        }
        return fileName();
    }

    /** 路径的文件名（不含扩展名）。 */
    public String fileName() {
        if (path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 扩展名（小写，不含点；没有则空串）。 */
    public String extension() {
        if (path.isEmpty()) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** 第一位歌手（多值以 "/" 分隔；也兼容 "、" 与 "&"）。 */
    public String firstArtist() {
        String a = artist.replace('、', '/').replace('&', '/');
        int idx = a.indexOf('/');
        String first = idx >= 0 ? a.substring(0, idx) : a;
        return first.trim();
    }

    public boolean isEmpty() {
        return path.isEmpty() && title.isEmpty();
    }

    @Override
    public String toString() {
        return "TrackInfo{" + displayName() + " | album=" + album + " | path=" + path + "}";
    }
}
