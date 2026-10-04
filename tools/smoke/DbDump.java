import java.sql.*;
import java.nio.file.*;

/** 只读转储宿主 spw.db 的 schema 与 netease 曲目的关键列（诊断用，不写库）。 */
public class DbDump {
    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String p = System.getenv("APPDATA") + "\\Salt Player for Windows\\spw.db";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + p.replace('\\', '/') + "?mode=ro")) {
            System.out.println("== tables ==");
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') ORDER BY name")) {
                while (r.next()) System.out.println("  " + r.getString(2) + " " + r.getString(1));
            }
            for (String t : new String[]{"Track", "Album", "Artist", "Playlist", "TrackPlaylist", "Lyric", "TrackArtist"}) {
                System.out.println("== " + t + " ==");
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("PRAGMA table_info(" + t + ")")) {
                    StringBuilder sb = new StringBuilder();
                    while (r.next()) sb.append(r.getString("name")).append(':').append(r.getString("type")).append(' ');
                    System.out.println("  " + (sb.length() == 0 ? "(不存在)" : sb));
                }
            }
            System.out.println("== netease 曲目取样 ==");
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT id, title, album, artist, path, duration, size, bitrate, sampleRate, bitsPerSample, coverRevision, readable FROM Track WHERE path LIKE 'http://127.0.0.1%' LIMIT 6")) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) {
                    StringBuilder sb = new StringBuilder("  ");
                    for (int i = 1; i <= m.getColumnCount(); i++) sb.append(m.getColumnName(i)).append('=').append(r.getString(i)).append(" | ");
                    System.out.println(sb);
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT count(*) FROM Track WHERE path LIKE 'http://127.0.0.1%'")) {
                if (r.next()) System.out.println("  netease 曲目总数=" + r.getInt(1));
            }
        }
    }
}
