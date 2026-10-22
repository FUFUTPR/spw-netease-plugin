package com.example.netease.ui;

import com.example.netease.core.PluginLog;
import com.example.netease.cfg.PluginConfig;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索窗口（P2，Lead 所有）。
 *
 * <p>线程规则（docs/00 §4 铁律）：
 * <ul>
 *   <li>网络搜索<b>只在自己的有界后台线程池</b>里跑（{@code netease-search} 守护线程），<b>绝不在 EDT</b>，
 *       也不占用 {@code HostBridgeWorker}（那个线程专供宿主对象访问，不能被 2 秒级网络请求阻塞）；</li>
 *   <li>所有 UI 变更回到 EDT（{@code SwingUtilities.invokeLater}）；</li>
 *   <li>窗口注册进 {@link UiRegistry}，插件停用时被 {@code disposeAll()} 关掉，不会滞留在宿主进程里。</li>
 * </ul>
 */
public final class SearchWindow {

    private static final String TAG = "ui.search";
    private static final int SEARCH_LIMIT = 30;

    private static volatile SearchWindow instance;

    // ------------------------------------------------------------------ 静态入口

    /** 打开（或前置）搜索窗口。可从任意线程调用，内部切到 EDT。 */
    public static void open() {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                doOpen();
            } else {
                SwingUtilities.invokeLater(SearchWindow::doOpen);
            }
        } catch (Throwable t) {
            PluginLog.e(TAG, "打开搜索窗口失败", t);
        }
    }

    private static void doOpen() {
        try {
            SearchWindow w = instance;
            if (w != null && w.frame.isDisplayable()) {
                w.frame.setExtendedState(w.frame.getExtendedState() & ~JFrame.ICONIFIED);
                w.frame.toFront();
                w.frame.requestFocus();
                return;
            }
            SearchWindow created = new SearchWindow();
            instance = created;
            UiRegistry.track(created.frame);
            created.frame.setVisible(true);
            PluginLog.i(TAG, "搜索窗口已打开");
        } catch (Throwable t) {
            PluginLog.e(TAG, "创建搜索窗口失败", t);
        }
    }

    // ------------------------------------------------------------------ 实例

    private final JFrame frame = new JFrame("网易云音乐 · 搜索");
    private final JTextField keyword = new JTextField(22);
    private final JButton searchBtn = new JButton("搜索");
    private final JLabel status = new JLabel("就绪");
    private final JProgressBar busy = new JProgressBar();

    private final SearchModel searchModel = new SearchModel();
    private final JTable searchTable = new JTable(searchModel);

    private final LatestTask requests = new LatestTask("netease-search");
    private final JButton playBtn = new JButton("播放所选");
    private final JButton retryBtn = new JButton("重试");
    private final JLabel resultSource = new JLabel("暂无搜索结果");
    private javax.swing.Timer playbackTimer;
    private Runnable retry = this::doSearch;
    private boolean closed;

    private SearchWindow() {
        searchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchTable.setRowHeight(22);
        searchTable.getTableHeader().setReorderingAllowed(false);

        sized(searchTable, 0, 60);
        sized(searchTable, 1, 220);
        sized(searchTable, 2, 150);
        sized(searchTable, 3, 150);
        sized(searchTable, 4, 60);
        sized(searchTable, 5, 120);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("关键词"));
        top.add(keyword);
        top.add(searchBtn);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bottom.add(resultSource);
        bottom.add(playBtn);
        bottom.add(retryBtn);
        playBtn.setEnabled(false);
        retryBtn.setEnabled(false);
        bottom.add(status);
        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setPreferredSize(new Dimension(90, 14));
        bottom.add(busy);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll(searchTable), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 480));
        frame.setSize(940, 620);
        frame.setLocationByPlatform(true);

        searchBtn.addActionListener(e -> doSearch());
        keyword.addActionListener(e -> doSearch());
        DecimalRenderer.apply(searchTable, 1, 2, 3);
        playBtn.addActionListener(e -> playSelected());
        retryBtn.addActionListener(e -> retry.run());
        searchTable.getSelectionModel().addListSelectionListener(e -> playBtn.setEnabled(searchTable.getSelectedRow() >= 0));
        searchTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) { playSelected(); }
            }
        });
        searchTable.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "play-selected");
        searchTable.getActionMap().put("play-selected", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { playSelected(); }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                shutdown();
            }
        });
    }

    private static JScrollPane scroll(JTable t) {
        JScrollPane p = new JScrollPane(t);
        p.setPreferredSize(new Dimension(900, 240));
        return p;
    }

    private static void sized(JTable t, int col, int width) {
        if (col < t.getColumnModel().getColumnCount()) {
            TableColumn c = t.getColumnModel().getColumn(col);
            c.setPreferredWidth(width);
        }
    }

    private void shutdown() {
        closed = true;
        requests.close();
        if (playbackTimer != null) { playbackTimer.stop(); playbackTimer = null; }
        if (instance == this) {
            instance = null;
        }
        PluginLog.i(TAG, "搜索窗口已关闭");
    }

    private void dispose() {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                frame.dispose();
            } else {
                SwingUtilities.invokeLater(frame::dispose);
            }
        } catch (Throwable t) {
            PluginLog.w(TAG, "关闭搜索窗口失败：" + t);
        }
    }

    private void say(String msg) {
        status.setText(msg == null ? "" : msg);
    }

    // ------------------------------------------------------------------ 搜索

    private void doSearch() {
        final String kw = keyword.getText() == null ? "" : keyword.getText().trim();
        if (kw.isEmpty()) {
            say("请输入关键词");
            return;
        }
        if (playbackTimer != null) { playbackTimer.stop(); playbackTimer = null; }
        busy.setVisible(true);
        retryBtn.setEnabled(false);
        say("搜索中…");
        retry = this::doSearch;
        requests.run(() -> NeteaseApi.search(kw, SEARCH_LIMIT), result -> {
            busy.setVisible(false);
            searchTable.clearSelection();
            searchModel.setRows(result == null ? List.of() : result);
            resultSource.setText("结果来源：" + kw);
            say(result == null || result.isEmpty() ? "没有找到结果，可换个关键词重试" : "找到 " + result.size() + " 条");
            retryBtn.setEnabled(true);
        }, error -> {
            busy.setVisible(false);
            say("搜索失败：" + safeMessage(error) + "（保留上次结果）");
            retryBtn.setEnabled(true);
            PluginLog.w(TAG, "搜索失败：" + safeMessage(error));
        });
    }

    private static String safeMessage(Throwable error) {
        String msg = error.getMessage();
        return msg == null || msg.isBlank() ? "网络或服务暂不可用，请重试" : msg.replaceAll("[\\r\\n]", " ");
    }

    private void playSelected() {
        int viewRow = searchTable.getSelectedRow();
        if (viewRow < 0) { say("请先选择一首歌"); return; }
        Dto.Song song = searchModel.rows.get(searchTable.convertRowIndexToModel(viewRow));
        if (!song.playable()) { say("这首歌当前无版权或不可播放"); return; }
        if (playbackTimer != null) { playbackTimer.stop(); }
        int requestId = VoxzenBridge.playOnlineRequest(song, PluginConfig.audioLevel());
        say("播放请求：正在准备「" + song.name() + "」");
        retry = this::playSelected;
        retryBtn.setEnabled(true);
        long started = System.nanoTime();
        playbackTimer = new javax.swing.Timer(250, e -> {
            if (closed) { ((javax.swing.Timer)e.getSource()).stop(); return; }
            String report = VoxzenBridge.playbackReport(requestId);
            if (report != null) { say("播放请求：" + report); }
            if (System.nanoTime() - started > 30_000_000_000L) { ((javax.swing.Timer)e.getSource()).stop(); }
        });
        playbackTimer.start();
    }

    // ------------------------------------------------------------------ 表格模型

    private final class SearchModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"#", "歌名", "歌手", "专辑", "时长", "状态"};
        private List<Dto.Song> rows = new ArrayList<>();

        void setRows(List<Dto.Song> r) {
            rows = new ArrayList<>(r);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Dto.Song s = rows.get(r);
            switch (c) {
                case 0:
                    return String.valueOf(r + 1);
                case 1:
                    return s.name();
                case 2:
                    return s.artists();
                case 3:
                    return s.album();
                case 4:
                    return mmss(s.durationMs());
                case 5:
                    return stateOf(s);
                default:
                    return "";
            }
        }

        /** 曲目可播性提示（无版权 / VIP 受限）。 */
        private String stateOf(Dto.Song s) {
            if (!s.playable()) {
                return "无版权/不可播";
            }
            if (s.fee() == 1) {
                return "VIP 曲目（可能降级）";
            }
            return "";
        }
    }

    private static String mmss(long ms) {
        if (ms <= 0) {
            return "--:--";
        }
        long total = ms / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    /** 把数字列右对齐（纯装饰，失败不影响功能）。 */
    private static final class DecimalRenderer {
        static void apply(JTable t, int... cols) {
            try {
                DefaultTableCellRenderer right = new DefaultTableCellRenderer();
                right.setHorizontalAlignment(JLabel.RIGHT);
                for (int c : cols) {
                    if (c < t.getColumnModel().getColumnCount()) {
                        t.getColumnModel().getColumn(c).setCellRenderer(right);
                    }
                }
            } catch (Throwable ignored) {
                // 装饰性设置，失败忽略
            }
        }
    }
}
