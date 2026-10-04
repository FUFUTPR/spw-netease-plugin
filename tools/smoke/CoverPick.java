import java.sql.*;

/**
 * 只读挑选验收样本（0.11.0 A5/A8 用）：从宿主 spw.db 里按封面状态挑「专辑 + 一首曲目 id」。
 *
 * <pre>
 *   java -cp "tools\smoke\out-coverpick;&lt;插件&gt;\lib\sqlite-jdbc-3.41.2.2.jar" CoverPick stub 8
 *   java -cp … CoverPick nostub 8
 * </pre>
 *
 * 输出每行：{@code ALBUM<TAB>专辑名<TAB>曲目id<TAB>曲目标题}。只读打开（{@code mode=ro}），绝不写库。
 */
public class CoverPick {

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String p = System.getenv("APPDATA") + "\\Salt Player for Windows\\spw.db";
        String mode = a.length > 0 ? a[0] : "stub";
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 8;
        String cond = "stub".equals(mode)
                ? "a.cover LIKE '%cover-stub-%'"
                : "(a.cover IS NULL OR a.cover NOT LIKE '%cover-stub-%')";
        String sql = "SELECT a.title, t.path, t.title, a.cover FROM Track t JOIN Album a ON a.title = t.album"
                + " WHERE t.path LIKE 'http://127.0.0.1%' AND " + cond
                + " GROUP BY a.title ORDER BY a.title LIMIT " + n;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + p.replace('\\', '/') + "?mode=ro");
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            int k = 0;
            while (r.next()) {
                String path = r.getString(2);
                int cut = path.lastIndexOf('/');
                String songId = cut > 0 ? path.substring(cut + 1) : path;
                System.out.println("ALBUM\t" + r.getString(1) + "\t" + songId + "\t" + r.getString(3)
                        + "\tcover=" + r.getString(4));
                k++;
            }
            System.out.println("共 " + k + " 行（mode=" + mode + "）");
        }
    }
}
