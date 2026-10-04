package com.example.netease.svc;

import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本地歌单（0.6.0「歌单切换 → 本地歌单」）。
 *
 * <p><b>它解决什么问题</b>：在线歌单每次切换都要走一遍网络（风控/限速/断网时直接切不动）。
 * 所以每成功载入一张歌单（或每日推荐 / 我喜欢的音乐），就把**曲目快照**落到本地；
 * 下次打开窗口不联网也能列出、能看曲目、能直接发起播放（直链仍需联网换取，这本来就绕不开）。</p>
 *
 * <p><b>存储格式</b>：{@code <插件数据目录>\playlists\<key>.tsv}，纯文本制表符分隔——
 * 刻意不用 JSON：写方与读方都在本插件内，TSV 少一层序列化依赖，出错时肉眼就能排查。
 * 第一行是元信息（{@code #key=… \t name=… \t creator=… \t tracks=… \t savedAt=…}），
 * 之后每行一首：{@code id \t 歌名 \t 歌手 \t 专辑 \t 时长ms \t fee \t playable \t 封面URL}。</p>
 *
 * <p><b>容错原则</b>：任何一行读不动就跳过（旧版本写的文件、手工改坏的文件都不该让整个窗口打不开）；
 * 任何写失败只记日志，绝不上抛——本地缓存是「锦上添花」，不能因为它挡住在线功能。</p>
 *
 * <p><b>线程约定</b>：本类方法都会做文件 IO，只允许在后台线程（UI 的 {@code netease-playlist} 池）调用，
 * 不要在 EDT 上批量读写。</p>
 */
public final class LocalPlaylists {

    private static final String TAG = "local.playlist";

    /** 单张本地歌单的曲目上限（超过只存前 N 首，避免把磁盘写爆）。 */
    private static final int MAX_TRACKS = 5000;

    /** 列出的本地歌单上限（读取目录时的保护）。 */
    private static final int MAX_LIST = 200;

    private LocalPlaylists() {
    }

    /**
     * 一张本地歌单的摘要。
     *
     * @param key       文件键（{@code p<歌单id>}=在线歌单快照，{@code l<时间戳>}=手工保存）
     * @param id        对应的在线歌单 id；手工保存的为 0
     * @param name      歌单名
     * @param creator   创建者（手工保存固定「本地」）
     * @param trackCount 曲目数
     * @param savedAtMs 落盘时间（epoch 毫秒）
     */
    public record Entry(String key, long id, String name, String creator, int trackCount, long savedAtMs) {
    }

    /** 本地歌单目录（{@code <data>\playlists}）。 */
    public static Path dir() {
        return DataPaths.ensure(DataPaths.data().resolve("playlists"));
    }

    /**
     * 保存一张在线歌单的快照（同 id 覆盖）。
     *
     * @param playlistId 在线歌单 id；负数表示虚拟歌单（-1 每日推荐、-2 我喜欢的音乐），
     *                   {@code 0} 表示无 id，直接返回 false
     * @param name       歌单名
     * @param creator    创建者
     * @param songs      曲目（可为空列表，此时不落盘）
     * @return 是否落盘成功
     */
    public static boolean saveSnapshot(long playlistId, String name, String creator, List<Dto.Song> songs) {
        if (playlistId == 0L) {
            return false;
        }
        return save("p" + playlistId, playlistId, name, creator, songs);
    }

    /**
     * 手工保存当前曲目为一个本地歌单（键用时间戳，不覆盖任何东西）。
     *
     * @return 落盘成功时返回新的键，否则返回 null
     */
    public static String saveManual(String name, List<Dto.Song> songs) {
        long now = System.currentTimeMillis();
        String key = "l" + now;
        return save(key, 0L, name, "本地", songs) ? key : null;
    }

    private static boolean save(String key, long id, String name, String creator, List<Dto.Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return false;
        }
        try {
            Path f = dir().resolve(key + ".tsv");
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("#key=").append(key)
                    .append('\t').append("id=").append(id)
                    .append('\t').append("name=").append(clean(name))
                    .append('\t').append("creator=").append(clean(creator))
                    .append('\t').append("tracks=").append(songs.size())
                    .append('\t').append("savedAt=").append(System.currentTimeMillis())
                    .append('\n');
            int n = 0;
            for (Dto.Song s : songs) {
                if (s == null) {
                    continue;
                }
                if (n++ >= MAX_TRACKS) {
                    break;
                }
                sb.append(s.id()).append('\t')
                        .append(clean(s.name())).append('\t')
                        .append(clean(s.artists())).append('\t')
                        .append(clean(s.album())).append('\t')
                        .append(s.durationMs()).append('\t')
                        .append(s.fee()).append('\t')
                        .append(s.playable() ? 1 : 0).append('\t')
                        .append(clean(s.picUrl())).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            PluginLog.d(TAG, "本地歌单已保存：" + f.getFileName() + "（" + Math.min(n, MAX_TRACKS) + " 首）");
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "保存本地歌单失败 key=" + key + " :: " + t);
            return false;
        }
    }

    /** 列出全部本地歌单（按落盘时间倒序；目录不存在 = 空列表，绝不返回 null）。 */
    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            Path base = dir();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, "*.tsv")) {
                for (Path f : stream) {
                    Entry e = readEntry(f);
                    if (e != null) {
                        out.add(e);
                    }
                    if (out.size() >= MAX_LIST) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取本地歌单目录失败（按空处理）：" + t);
        }
        out.sort(Comparator.comparingLong(Entry::savedAtMs).reversed());
        return out;
    }

    /** 读回一张本地歌单的全部曲目（读不动 = 空列表）。 */
    public static List<Dto.Song> load(String key) {
        List<Dto.Song> out = new ArrayList<>();
        if (key == null || key.isBlank()) {
            return out;
        }
        try {
            Path f = dir().resolve(key + ".tsv");
            if (!Files.isRegularFile(f)) {
                return out;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                Dto.Song s = parseSong(lines.get(i));
                if (s != null) {
                    out.add(s);
                }
            }
            PluginLog.d(TAG, "读回本地歌单 " + key + "：" + out.size() + " 首");
        } catch (Throwable t) {
            PluginLog.w(TAG, "读取本地歌单失败 key=" + key + " :: " + t);
        }
        return out;
    }

    /** 删除一张本地歌单（失败只记日志）。 */
    public static boolean remove(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(dir().resolve(key + ".tsv"));
        } catch (Throwable t) {
            PluginLog.w(TAG, "删除本地歌单失败 key=" + key + " :: " + t);
            return false;
        }
    }

    /** 本地歌单数量（供自检/状态行使用）。 */
    public static int count() {
        return list().size();
    }

    // ------------------------------------------------------------------ 内部解析

    private static Entry readEntry(Path f) {
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String head = lines.get(0);
            String fileName = f.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String key = dot > 0 ? fileName.substring(0, dot) : fileName;
            long id = 0L;
            String name = key;
            String creator = "";
            int tracks = 0;
            long savedAt = 0L;
            for (String part : head.split("\t")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                switch (k) {
                    case "id" -> id = parseLong(v, 0L);
                    case "name" -> name = v;
                    case "creator" -> creator = v;
                    case "tracks" -> tracks = (int) parseLong(v, 0L);
                    case "savedAt" -> savedAt = parseLong(v, 0L);
                    default -> {
                    }
                }
            }
            if (tracks <= 0) {
                tracks = Math.max(0, lines.size() - 1);
            }
            return new Entry(key, id, name, creator, tracks, savedAt);
        } catch (Throwable t) {
            PluginLog.d(TAG, "跳过读不动的本地歌单文件 " + f.getFileName() + " :: " + t);
            return null;
        }
    }

    private static Dto.Song parseSong(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] c = line.split("\t", -1);
        if (c.length < 7) {
            return null;
        }
        try {
            long id = parseLong(c[0], 0L);
            if (id <= 0L) {
                return null;
            }
            long duration = parseLong(c[4], 0L);
            int fee = (int) parseLong(c[5], 0L);
            boolean playable = !"0".equals(c[6].trim());
            String pic = c.length > 7 ? c[7] : "";
            return new Dto.Song(id, c[1], c[2], c[3], duration, fee, playable, pic);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** TSV 字段清洗：制表符/换行会把一行拆坏，统一压成空格。 */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
