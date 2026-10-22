package com.example.netease.ui;

import com.example.netease.cfg.PluginConfig;
import com.example.netease.NeteasePlugin;
import com.example.netease.core.Levels;
import com.example.netease.core.Notifier;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.svc.LocalPlaylists;
import com.example.netease.svc.LyricService;
import com.example.netease.ui.controller.PlaylistIds;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌单 / 每日推荐窗口（P3，0.6.0 扩成「歌单切换 + 整单连播 + 封面 + 本地歌单」）。
 *
 * <p><b>0.6.0 的三处变化</b>：
 * <ol>
 *   <li><b>歌单切换</b>：曲目数右边一个「歌单切换 ▾」按钮，一次拿到
 *       我喜欢的音乐 / 每日推荐 / 我创建的歌单 / 我收藏的歌单 / 本地歌单；</li>
 *   <li><b>点哪首从哪首连播</b>：曲目表任意行单击（或行内「播放」按钮）= 把**整张列表**交给宿主，
 *       从这一首开始往下连播（0.6.0 起宿主队列是真队列，不是过去那种「一次只灌一首」）；</li>
 *   <li><b>封面</b>：曲目表第一列画封面缩略图（异步下载 + 内存/磁盘缓存，见 {@link CoverCache}）。</li>
 * </ol>
 *
 * <p>数据来源：登录后是我的歌单（含「我喜欢的音乐」）、每日推荐；未登录仍可用
 * 「歌单 ID / 链接」载入公开歌单（私密歌单需要登录）。载入成功的列表会快照到本地
 * （{@link LocalPlaylists}），断网时「本地歌单」里仍能看到曲目并发起播放。</p>
 *
 * <p>线程规则：所有网络调用在自建的守护单线程池 {@code netease-playlist} 上跑（绝不在 EDT，
 * 也不占 HostBridgeWorker）；结果回 EDT 刷新表格。</p>
 */
public final class PlaylistWindow {

    private static final String TAG = "ui.playlist";
    private static final Pattern ID_PATTERN = Pattern.compile("(?:[?&]id=|/playlist/|^)(\\d{3,})");

    /** 封面缩略图边长（像素）。 */
    private static final int COVER_PX = 44;

    /** 「播放」按钮列（视图列号 == 模型列号：表禁止列重排）。 */
    private static final int PLAY_COL = 6;

    /** 「封面」列。 */
    private static final int COVER_COL = 0;

    /** 载入歌单时提前抓歌词的首数上限（见 {@code warmupLyrics}）。 */
    private static final int LYRIC_WARMUP = 30;

    private static volatile PlaylistWindow instance;

    /** 打开（或前置）。可从任意线程调用。 */
    public static void open() {
        if (SwingUtilities.isEventDispatchThread()) {
            doOpen();
        } else {
            SwingUtilities.invokeLater(PlaylistWindow::doOpen);
        }
    }

    private static void doOpen() {
        try {
            PlaylistWindow w = instance;
            if (w != null && w.frame.isDisplayable()) {
                w.frame.toFront();
                w.frame.requestFocus();
                return;
            }
            PlaylistWindow created = new PlaylistWindow();
            instance = created;
            UiRegistry.track(created.frame);
            created.frame.setVisible(true);
            created.frame.setLocationRelativeTo(null);
            created.onOpened();
            PluginLog.i(TAG, "歌单窗口已打开");
        } catch (Throwable t) {
            PluginLog.e(TAG, "创建歌单窗口失败", t);
        }
    }

    // ------------------------------------------------------------------ 实例

    private final JFrame frame = new JFrame("网易云音乐 · 歌单");
    private final JLabel accountLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel countLabel = new JLabel("歌曲 · 0 首");
    private final JLabel sourceLabel = new JLabel(" ");
    private final JTextField idField = new JTextField(16);
    private final JComboBox<String> levelBox = new JComboBox<>(
            Levels.ALL.toArray(new String[0]));

    private final PlaylistModel playlistModel = new PlaylistModel();
    private final JTable playlistTable = new JTable(playlistModel);
    private final TrackModel trackModel = new TrackModel();
    private final JTable trackTable = new JTable(trackModel);

    /** 曲目数右边的「歌单切换」（0.6.0 主入口；同一个动作在顶部还有一个入口，见 {@link #switcherBtnTop}）。 */
    private final JButton switcherBtn = new JButton("歌单切换 ▾");
    /** 顶部那份「歌单切换」：Swing 一个组件只能有一个父容器，所以同一个动作要两个按钮实例。 */
    private final JButton switcherBtnTop = new JButton("歌单切换 ▾");
    private final JButton refreshBtn = new JButton("刷新歌单");
    private final JButton loadIdBtn = new JButton("载入歌单");
    private final JButton loginBtn = new JButton("扫码登录");

    private final JPopupMenu switchMenu = new JPopupMenu();

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "netease-playlist");
        t.setDaemon(true);
        return t;
    });

    /** 在线歌单摘要快照（我创建 + 我收藏）。 */
    private volatile List<Dto.PlaylistBrief> briefs = List.of();
    /** 本地歌单摘要快照。 */
    private volatile List<LocalPlaylists.Entry> localEntries = List.of();
    private volatile boolean briefsLoaded;

    /** 当前载入的曲目来源名（进日志与状态栏）。 */
    private volatile String sourceName = "歌单";
    private volatile boolean busy;
    private boolean closed;
    private long accountEpoch = AccountService.sessionEpoch();
    private final LatestTask loads = new LatestTask("netease-playlist-load");
    private final JButton retryBtn = new JButton("重试");
    private final JButton syncBtn = new JButton("同步到播放器");
    private final JLabel syncLabel = new JLabel("同步状态：尚未同步");
    private Runnable retryLoad = this::loadMine;
    private int playbackRequest;
    private long playbackUntil;

    private Timer ticker;

    private PlaylistWindow() {
        accountLabel.setFont(accountLabel.getFont().deriveFont(Font.BOLD));
        statusLabel.setForeground(new java.awt.Color(0x44, 0x44, 0x44));
        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD));
        sourceLabel.setForeground(new java.awt.Color(0x55, 0x55, 0x55));

        // 左表：在线歌单总览（点选即切换）
        playlistTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistTable.setRowHeight(22);
        playlistTable.getTableHeader().setReorderingAllowed(false);
        playlistTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Dto.PlaylistBrief b = playlistModel.at(playlistTable.getSelectedRow());
            if (b != null) {
                loadPlaylist(b.id(), "歌单「" + b.name() + "」");
            }
        });

        // 右表：曲目（封面 + 点行连播 + 行内「播放」按钮）
        trackTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        trackTable.setRowHeight(COVER_PX + 2);
        trackTable.getTableHeader().setReorderingAllowed(false);
        trackTable.getColumnModel().getColumn(COVER_COL).setCellRenderer(new CoverRenderer(COVER_PX));
        trackTable.getColumnModel().getColumn(COVER_COL).setPreferredWidth(COVER_PX + 8);
        trackTable.getColumnModel().getColumn(COVER_COL).setMaxWidth(COVER_PX + 12);
        TableButtons.install(trackTable, PLAY_COL, "播放", this::playFromRow);
        trackTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() > 2) {
                    return;
                }
                // Ctrl/Shift 是「连选」手势，不触发播放；「播放」列由 TableButtons 负责
                if ((e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)) != 0) {
                    return;
                }
                int viewRow = trackTable.rowAtPoint(e.getPoint());
                int viewCol = trackTable.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol == PLAY_COL) {
                    return;
                }
                playFromRow(trackTable.convertRowIndexToModel(viewRow));
            }
        });
        CoverCache.onLoaded(() -> {
            if (trackTable.isDisplayable()) {
                trackTable.repaint();
            }
        });

        // ---- 顶部：账号 / 登录 / 刷新 / 歌单切换 ----
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(accountLabel);
        top.add(loginBtn);
        top.add(refreshBtn);
        top.add(switcherBtnTop);

        JPanel idRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        idRow.add(new JLabel("歌单 ID / 链接"));
        idRow.add(idField);
        idRow.add(loadIdBtn);

        JPanel head = new JPanel(new BorderLayout());
        head.add(top, BorderLayout.NORTH);
        head.add(idRow, BorderLayout.SOUTH);

        JScrollPane leftScroll = new JScrollPane(playlistTable);
        leftScroll.setBorder(BorderFactory.createTitledBorder("歌单（创建 / 收藏，点选即切换）"));
        leftScroll.setPreferredSize(new Dimension(280, 420));

        // 右表表头：曲目数 + 「歌单切换」按钮（0.6.0 新增；同时保留顶部入口，两个都能点）
        JPanel trackHead = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        trackHead.add(countLabel);
        trackHead.add(switcherBtn);
        trackHead.add(sourceLabel);

        JScrollPane rightScroll = new JScrollPane(trackTable);
        rightScroll.setBorder(BorderFactory.createTitledBorder("歌曲（点任意一行 = 从该首整单连播）"));

        JPanel rightPane = new JPanel(new BorderLayout());
        rightPane.add(trackHead, BorderLayout.NORTH);
        rightPane.add(rightScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPane);
        split.setResizeWeight(0.3);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        actions.add(new JLabel("音质"));
        actions.add(levelBox);
        actions.add(retryBtn);
        actions.add(syncBtn);
        retryBtn.setEnabled(false);
        retryBtn.addActionListener(e -> retryLoad.run());
        syncBtn.addActionListener(e -> NeteasePlugin.syncNow());

        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.NORTH);
        JPanel feedback = new JPanel(new java.awt.GridLayout(0,1,0,4));
        feedback.setBorder(BorderFactory.createEmptyBorder(2,8,8,8));
        feedback.add(statusLabel);
        feedback.add(syncLabel);
        south.add(feedback, BorderLayout.SOUTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));

        frame.setLayout(new BorderLayout());
        frame.add(head, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(south, BorderLayout.SOUTH);
        frame.setMinimumSize(new Dimension(820, 460));
        frame.setPreferredSize(new Dimension(1000, 560));
        frame.pack();
        HostTheme.placeWindow(frame);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        HostTheme.applyTree(frame.getContentPane());

        // 0.11.30：独立的扫码窗退役 ⇒ 未登录时点这里开登录对话框（默认就是扫码档）。
        loginBtn.addActionListener(e -> LoginDialog.open(""));
        refreshBtn.addActionListener(e -> {
            briefsLoaded = false;
            loadMine();
            refreshLocalEntries();
        });
        String tip = "切换歌单：我喜欢的音乐 / 每日推荐 / 我创建 / 我收藏 / 本地歌单";
        switcherBtn.setToolTipText(tip);
        switcherBtnTop.setToolTipText(tip);
        switcherBtn.addActionListener(e -> openSwitcher());
        switcherBtnTop.addActionListener(e -> openSwitcher());
        loadIdBtn.addActionListener(e -> {
            long id = parseId(idField.getText());
            if (id <= 0) {
                statusLabel.setText("请填写有效歌单 ID 或链接");
                return;
            }
            loadPlaylist(id, "歌单 #" + id);
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closed = true;
                loads.close();
                if (ticker != null) {
                    ticker.stop();
                    ticker = null;
                }
                pool.shutdownNow();
                if (instance == PlaylistWindow.this) {
                    CoverCache.onLoaded(null);
                    instance = null;
                }
                PluginLog.i(TAG, "歌单窗口已关闭");
            }
        });
    }

    private void onOpened() {
        levelBox.setSelectedItem(PluginConfig.audioLevel());
        ticker = new Timer(1000, e -> refreshStatus());
        ticker.setInitialDelay(300);
        ticker.start();
        refreshStatus();
        refreshLocalEntries();
        if (AccountService.loggedIn()) {
            loadMine();
        } else {
            accountLabel.setText("未登录（公开歌单仍可用歌单 ID 载入）");
        }
    }

    private void refreshStatus() {
        try {
            accountLabel.setText(AccountService.statusLine());
            syncLabel.setText("同步状态：" + NeteasePlugin.syncStatus());
            if (accountEpoch != AccountService.sessionEpoch()) {
                accountEpoch = AccountService.sessionEpoch();
                loads.cancelCurrent(); busy = false;
                briefs = List.of(); briefsLoaded = false; playlistModel.set(briefs);
                playbackRequest = 0;
                retryLoad = this::loadMine; retryBtn.setEnabled(true);
                statusLabel.setText("账号状态变化，请重新载入；当前仍显示上次载入的曲目");
            }
            if (playbackRequest > 0 && System.nanoTime() < playbackUntil) {
                String report = VoxzenBridge.playbackReport(playbackRequest);
                if (report != null) { statusLabel.setText("播放请求：" + report); }
            }
        } catch (Throwable t) {
            PluginLog.d(TAG, "刷新歌单窗状态失败（忽略）：" + t);
        }
    }

    // --------------------------------------------------------------- 歌单切换（0.6.0）

    /** 弹出「歌单切换」菜单：全部来源都在这里，菜单按快照即时构建（不在 EDT 里联网）。 */
    private void openSwitcher() {
        try {
            switchMenu.removeAll();
            boolean logged = AccountService.loggedIn();

            JMenuItem liked = menuItem("我喜欢的音乐", this::loadLiked);
            liked.setEnabled(logged);
            switchMenu.add(liked);

            JMenuItem daily = menuItem("每日推荐", this::loadDaily);
            daily.setEnabled(logged);
            switchMenu.add(daily);

            switchMenu.addSeparator();
            switchMenu.add(localMenu());
            switchMenu.add(briefMenu("我创建的歌单", true, logged));
            switchMenu.add(briefMenu("我收藏的歌单", false, logged));

            switchMenu.addSeparator();
            switchMenu.add(menuItem("刷新歌单列表", () -> {
                briefsLoaded = false;
                loadMine();
                refreshLocalEntries();
            }));

            switchMenu.show(switcherBtn, 0, switcherBtn.getHeight());
        } catch (Throwable t) {
            PluginLog.w(TAG, "展开歌单切换菜单失败：" + t);
        }
    }

    private JMenu localMenu() {
        List<LocalPlaylists.Entry> list = localEntries;
        JMenu menu = new JMenu("本地歌单（" + list.size() + "）");
        if (list.isEmpty()) {
            JMenuItem none = new JMenuItem("（还没有本地歌单）");
            none.setEnabled(false);
            menu.add(none);
        } else {
            for (LocalPlaylists.Entry e : list) {
                String text = e.name() + " · " + e.trackCount() + " 首";
                menu.add(menuItem(text, () -> loadLocal(e.key(), e.name())));
            }
        }
        menu.addSeparator();
        menu.add(menuItem("把当前曲目存为本地歌单…", this::saveCurrentToLocal));
        return menu;
    }

    private JMenu briefMenu(String title, boolean mine, boolean logged) {
        List<Dto.PlaylistBrief> all = briefs;
        List<Dto.PlaylistBrief> pick = new ArrayList<>();
        for (Dto.PlaylistBrief b : all) {
            if (b != null && b.mine() == mine) {
                pick.add(b);
            }
        }
        JMenu menu = new JMenu(title + "（" + pick.size() + "）");
        if (!logged) {
            JMenuItem hint = new JMenuItem("（需要先登录）");
            hint.setEnabled(false);
            menu.add(hint);
            return menu;
        }
        if (!briefsLoaded) {
            menu.add(menuItem("正在载入歌单列表…（点此重试）", this::loadMine));
            return menu;
        }
        if (pick.isEmpty()) {
            JMenuItem none = new JMenuItem("（没有这一类歌单）");
            none.setEnabled(false);
            menu.add(none);
            return menu;
        }
        for (Dto.PlaylistBrief b : pick) {
            menu.add(menuItem(b.name() + " · " + b.trackCount() + " 首",
                    () -> loadPlaylist(b.id(), "歌单「" + b.name() + "」")));
        }
        return menu;
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> {
            try {
                action.run();
            } catch (Throwable t) {
                PluginLog.w(TAG, "菜单动作失败（" + text + "）：" + t);
            }
        });
        return item;
    }

    /** 把当前曲目手工存成一个本地歌单（名字来自输入框；写盘在后台线程）。 */
    private void saveCurrentToLocal() {
        List<Dto.Song> songs = trackModel.snapshot();
        if (songs.isEmpty()) {
            statusLabel.setText("当前没有曲目可保存，请先载入歌单");
            return;
        }
        String def = sourceName == null || sourceName.isBlank() ? "我的本地歌单" : sourceName;
        String name = JOptionPane.showInputDialog(frame, "本地歌单名称（存 " + songs.size() + " 首）", def);
        if (name == null || name.isBlank()) {
            return;
        }
        final String finalName = name.trim();
        pool.submit(() -> {
            String key = LocalPlaylists.saveManual(finalName, songs);
            SwingUtilities.invokeLater(() -> {
                if (closed) { return; }
                statusLabel.setText(key == null ? "保存本地歌单失败，请重试"
                        : "已存为本地歌单「" + finalName + "」（" + songs.size() + " 首）");
            });
            refreshLocalEntries();
        });
    }

    // --------------------------------------------------------------- 载入动作

    /** 当前登录 uid；未登录或账号服务尚未就绪时返回 -1（绝不抛）。 */
    private static long currentUid() {
        try {
            if (!AccountService.loggedIn()) {
                return -1L;
            }
            return AccountService.status().uid();
        } catch (Throwable t) {
            PluginLog.d(TAG, "读取登录 uid 失败（按未登录处理）：" + t);
            return -1L;
        }
    }

    /** 刷新本地歌单快照（读盘在后台池上做）。 */
    private void refreshLocalEntries() {
        pool.submit(() -> {
            try {
                localEntries = LocalPlaylists.list();
            } catch (Throwable t) {
                PluginLog.d(TAG, "刷新本地歌单失败（忽略）：" + t);
            }
        });
    }

    private void loadMine() {
        long uid = currentUid();
        if (uid <= 0) { statusLabel.setText("请先登录；公开歌单仍可通过 ID 载入"); return; }
        submit("我的歌单", () -> NeteaseApi.userPlaylists(uid, 200), list -> {
            briefs = list == null ? List.of() : List.copyOf(list);
            briefsLoaded = true;
            playlistModel.set(briefs);
            long created = briefs.stream().filter(Dto.PlaylistBrief::mine).count();
            statusLabel.setText("已载入 " + briefs.size() + " 个歌单（创建 " + created + " / 收藏 "
                    + (briefs.size()-created) + "），点左侧列表切换");
        });
    }
    private void loadDaily() {
        submit("每日推荐", NeteaseApi::dailySongs, songs -> {
            setSongs("每日推荐", songs, "每日推荐 " + size(songs) + " 首（今日）");
            cacheSnapshot(-1L,"每日推荐","网易云",songs);
        });
    }
    private void loadLiked() {
        long uid = currentUid();
        if (uid <= 0) { statusLabel.setText("请先登录后载入我喜欢的音乐"); return; }
        submit("我喜欢的音乐", () -> NeteaseApi.likedSongs(uid), songs ->
                setSongs("我喜欢的音乐", songs, "我喜欢的音乐 " + size(songs) + " 首"));
    }
    private void loadPlaylist(long id, String label) {
        submit(label, () -> NeteaseApi.playlist(id), playlist -> {
            String name = playlist.name() == null || playlist.name().isBlank() ? label : playlist.name();
            setSongs("歌单「"+name+"」",playlist.tracks(),"歌单「"+name+"」声明 "+playlist.trackCount()
                    +" 首，已载入 "+size(playlist.tracks())+" 首");
            cacheSnapshot(playlist.id(),playlist.name(),playlist.creator(),playlist.tracks());
        });
    }
    private void loadLocal(String key, String name) {
        submit("本地歌单「"+name+"」", () -> LocalPlaylists.load(key), songs ->
                setSongs("本地歌单「"+name+"」",songs,"本地快照 "+size(songs)+" 首；播放时需要联网"));
    }
    private void cacheSnapshot(long id, String name, String creator, List<Dto.Song> songs) {
        long version = loads.version();
        pool.submit(() -> {
            if (closed || !loads.isCurrent(version)) { return; }
            LocalPlaylists.saveSnapshot(id,name,creator,songs);
            if (!closed) { localEntries = LocalPlaylists.list(); }
        });
    }

    /** 曲目表统一出口：换数据 + 更新曲目数/来源/状态行。只能在 EDT 上调用。 */
    private void setSongs(String source, List<Dto.Song> songs, String status) {
        trackModel.set(songs);
        sourceName = source;
        countLabel.setText("歌曲 · " + trackModel.getRowCount() + " 首");
        sourceLabel.setText("来源：" + source);
        statusLabel.setText(status);
        warmupLyrics(songs);
    }

    /**
     * 载入歌单时提前抓歌词（0.6.0 真机验证发现的首曲无歌词问题）。
     *
     * <p><b>为什么要有这一步</b>：宿主是在<b>起播那一刻</b>同步问歌词的
     * （真机日志：14:31:03 三个钩子全被调用），而抓一首歌词要 1～2 秒
     * （真机：3 首 1781ms）。{@code VoxzenBridge} 里那次预取虽然投递得比注入早，
     * 但仍在同一秒内，于是<b>一个歌单的第一首必然赶不上</b>——真机上就是「点进去听第一首没歌词，
     * 自动播到第二首才出词」。在「载入歌单」到「用户点播放」之间通常隔着好几秒到几分钟，
     * 这段时间足够把词备好。</p>
     *
     * <p><b>为什么只备前 {@link #LYRIC_WARMUP} 首</b>：抓取是串行的（有意如此，顺带满足风控节流），
     * 「我喜欢的音乐」有几千首，全备会压出几千次请求。用户在列表顶部点歌的概率最高，
     * 靠后的那几首交给起播时的 {@code VoxzenBridge} 预取兜底。</p>
     */
    private static void warmupLyrics(List<Dto.Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        try {
            int n = Math.min(songs.size(), LYRIC_WARMUP);
            LyricService.prefetch(new ArrayList<>(songs.subList(0, n)), Map.of());
        } catch (Throwable t) {
            // 备词失败不影响载入歌单这件事本身
            PluginLog.d(TAG, "歌词预热投递失败（已忽略）:: " + t);
        }
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** Latest accepted load owns the rows, source label, error and retry together. */
    private <T> void submit(String what, java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> apply) {
        if (closed) { return; }
        playbackRequest = 0;
        busy = true;
        statusLabel.setText("正在载入「"+what+"」…（保留上次列表）");
        retryBtn.setEnabled(false);
        long epoch = AccountService.sessionEpoch();
        retryLoad = () -> {
            if (epoch != AccountService.sessionEpoch()) { loadMine(); }
            else { submit(what,task,apply); }
        };
        loads.run(task, value -> {
            if (epoch != AccountService.sessionEpoch()) { return; }
            busy = false;
            apply.accept(value);
            retryBtn.setEnabled(true);
        }, error -> {
            if (epoch != AccountService.sessionEpoch()) { return; }
            busy = false;
            statusLabel.setText("载入「"+what+"」失败："+briefError(error)+"；保留上次列表，可重试");
            retryBtn.setEnabled(true);
            PluginLog.w(TAG,"载入歌单失败："+briefError(error));
        });
    }
    private static String briefError(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) { return "请求超时"; }
        return "网络或服务暂不可用";
    }

    // --------------------------------------------------------------- 在线播放

    /**
     * 点行（或行内「播放」）= 从这一首开始**整单连播**（0.6.0）。
     *
     * <p>走 {@link VoxzenBridge#playPlaylist}：宿主进程内反射把
     * {@code [点击那首, 点击那首 + 一窗口)} 一次灌进宿主播放队列，宿主用自己的 HTTP Range
     * 流式实现取流并自动往下播——不落地文件、不整单预取。</p>
     */
    private void playFromRow(int modelRow) {
        List<Dto.Song> songs = trackModel.snapshot();
        if (songs.isEmpty()) {
            statusLabel.setText("曲目表是空的，请先载入一张歌单");
            return;
        }
        int row = Math.max(0, Math.min(modelRow, songs.size() - 1));
        Dto.Song s = songs.get(row);
        String level = String.valueOf(levelBox.getSelectedItem());
        String source = sourceName;
        if (!s.playable()) { statusLabel.setText("这首歌当前无版权或不可播放"); return; }
        playbackRequest = VoxzenBridge.playPlaylistRequest(songs, row, level, source);
        playbackUntil = System.nanoTime() + 30_000_000_000L;
        retryLoad = () -> playFromRow(modelRow);
        retryBtn.setEnabled(true);
        statusLabel.setText("播放请求：" + source + " 自第 " + (row + 1) + " 首「" + s.name()
                + "」起（音质 " + level + "；失败原因见日志 bridge）");
        PluginLog.i(TAG, "请求整单连播 source=" + source + " start=" + (row + 1) + "/" + songs.size()
                + " songId=" + s.id() + " level=" + level);
    }

    // ------------------------------------------------------- 测试钩子（0.6.0 UI 真机取证）

    /** UI 自检里「载入歌单」到「起播」之间的等待（毫秒）：留够封面下载 + 歌词预热。 */
    private static final int UI_AUTOTEST_PLAY_DELAY_MS = 8000;

    /**
     * UI 自检钩子：载入第一个歌单，并在 8 秒后从第 1 首开始整单连播。
     *
     * <p>只为无人值守真机取证用（由 {@code NeteasePlugin} 的 {@code ui-autotest-playlist.flag}
     * 驱动）：曲目表得有数据才会画封面、才会走 {@link #setSongs} 里的歌词预热，
     * 而开窗时 {@link #loadMine()} 只拉歌单清单、不载曲目，所以自检必须自己选一张歌单——
     * 等价于用户手点左侧列表第一项。</p>
     */
    public static void __testLoadFirstAndPlay() {
        SwingUtilities.invokeLater(() -> {
            try {
                PlaylistWindow w = instance;
                if (w == null || !w.frame.isDisplayable()) {
                    PluginLog.i(TAG, "UI 自检：窗口不在，跳过");
                    return;
                }
                List<Dto.PlaylistBrief> list = w.briefs;
                if (list == null || list.isEmpty()) {
                    PluginLog.i(TAG, "UI 自检：歌单清单为空（登录态或网络问题），跳过");
                    return;
                }
                Dto.PlaylistBrief b = list.get(0);
                PluginLog.i(TAG, "UI 自检：载入第一个歌单 id=" + b.id() + "「" + b.name()
                        + "」声明 " + b.trackCount() + " 首");
                w.loadPlaylist(b.id(), b.name());
                javax.swing.Timer t = new javax.swing.Timer(UI_AUTOTEST_PLAY_DELAY_MS, e -> {
                    try {
                        PluginLog.i(TAG, "UI 自检：从第 1 首开始整单连播");
                        w.playFromRow(0);
                    } catch (Throwable x) {
                        PluginLog.e(TAG, "UI 自检起播失败（已忽略）", x);
                    }
                });
                t.setRepeats(false);
                t.start();
            } catch (Throwable t) {
                PluginLog.e(TAG, "UI 自检失败（已忽略）", t);
            }
        });
    }

    /** 从用户输入里解析歌单 id（支持纯数字、带 id= 的链接、#/playlist/ 链接）；实现见 PlaylistIds。 */
    static long parseId(String text) {
        return PlaylistIds.parse(text);
    }

    // ------------------------------------------------------------------ 渲染 / 模型

    /** 封面列渲染器：取不到图就先留空（后台下完会触发一次重绘）。 */
    private static final class CoverRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        private final int px;

        CoverRenderer(int px) {
            this.px = px;
            setHorizontalAlignment(CENTER);
            setVerticalAlignment(CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, "", selected, focused, row, column);
            setIcon(null);
            setText("");
            String url = value == null ? "" : String.valueOf(value);
            if (url.isEmpty()) {
                return this;
            }
            ImageIcon icon = CoverCache.get(url, px);
            if (icon != null) {
                setIcon(icon);
            } else {
                setText("♪");
            }
            return this;
        }
    }

    private static String fmtDuration(long ms) {
        if (ms <= 0) {
            return "";
        }
        long total = ms / 1000;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    private static final class PlaylistModel extends AbstractTableModel {
        private final String[] cols = {"歌单", "来源", "曲目"};
        private List<Dto.PlaylistBrief> rows = new ArrayList<>();

        void set(List<Dto.PlaylistBrief> list) {
            rows = list == null ? new ArrayList<>() : new ArrayList<>(list);
            fireTableDataChanged();
        }

        Dto.PlaylistBrief at(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
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
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Dto.PlaylistBrief b = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> b.name();
                case 1 -> b.mine() ? "创建" : "收藏";
                default -> b.trackCount();
            };
        }
    }

    private static final class TrackModel extends AbstractTableModel {
        private final String[] cols = {"封面", "歌名", "歌手", "专辑", "时长", "备注", "播放"};
        private List<Dto.Song> rows = new ArrayList<>();

        void set(List<Dto.Song> list) {
            rows = list == null ? new ArrayList<>() : new ArrayList<>(list);
            fireTableDataChanged();
        }

        Dto.Song at(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        /** 当前列表的不可变快照（整单连播直接把这张表交给宿主）。 */
        List<Dto.Song> snapshot() {
            return List.copyOf(rows);
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
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Dto.Song s = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> s.picUrl();
                case 1 -> s.name();
                case 2 -> s.artists();
                case 3 -> s.album();
                case 4 -> fmtDuration(s.durationMs());
                case 5 -> s.playable() && s.fee() == 0 ? "" : "受限";
                case 6 -> "播放";
                default -> "";
            };
        }
    }
}
