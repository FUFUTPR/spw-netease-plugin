package spw.harness;

import com.xuncorp.spw.workshop.api.WorkshopApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link WorkshopApi} 的桩实现：getPlayback/getUi/getManager 全给安全实现。
 *
 * <p>关键点：真实宿主里 {@code WorkshopApi} 的静态访问器
 * （{@code WorkshopApi.ui()} 等）读的是 Companion.instance；
 * 插件内部固定走静态访问器，所以 harness 只需注入这里的一个实例。
 */
public final class StubWorkshopApi implements WorkshopApi {

    private final StubUi ui;
    public final StubPlayback playback;
    private final StubManager manager;

    /** 收集所有 toast，供断言/报告使用。 */
    public final List<String> toasts = Collections.synchronizedList(new ArrayList<>());

    public StubWorkshopApi(StubManager manager) {
        this.ui = new StubUi(toasts);
        this.playback = new StubPlayback();
        this.manager = manager;
    }

    @Override
    public WorkshopApi.Ui getUi() {
        return ui;
    }

    @Override
    public WorkshopApi.Playback getPlayback() {
        return playback;
    }

    @Override
    public WorkshopApi.Manager getManager() {
        return manager;
    }

    // ------------------------------------------------------------------ Ui
    /** toast 直接打印（宿主里是宿主 UI 线程弹的，普通 JVM 无 UI）。 */
    public static final class StubUi implements WorkshopApi.Ui {
        private final List<String> sink;

        StubUi(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public void toast(String text, WorkshopApi.Ui.ToastType type) {
            String line = "  [toast/" + (type == null ? "null" : type.name()) + "] " + text;
            System.out.println(line);
            sink.add(line);
        }
    }

    // ------------------------------------------------------------ Playback
    /** 记录播放控制调用，不真的做任何事。 */
    public static final class StubPlayback implements WorkshopApi.Playback {
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        private void rec(String s) {
            calls.add(s);
            System.out.println("  [playback] " + s);
        }

        @Override public void changeExclusive(boolean b) { rec("changeExclusive(" + b + ")"); }
        @Override public void pause()                    { rec("pause()"); }
        @Override public void play()                     { rec("play()"); }
        @Override public void previous()                 { rec("previous()"); }
        @Override public void next()                     { rec("next()"); }
        @Override public void seekTo(long ms)            { rec("seekTo(" + ms + ")"); }
    }

    // ------------------------------------------------------------- Manager
    /**
     * 配置管理器桩：每个 config 名一个内存表 + 一个临时 json 文件。
     * get(key, default) 在无值时返回 default（插件据此走「首次运行」分支）。
     */
    public static final class StubManager implements WorkshopApi.Manager {
        private final java.nio.file.Path dir;
        public final ConcurrentMap<String, StubConfigHelper> configs = new ConcurrentHashMap<>();

        public StubManager(java.nio.file.Path dir) {
            this.dir = dir;
        }

        @Override
        public com.xuncorp.spw.workshop.api.config.ConfigManager createConfigManager() {
            return createConfigManager("config");
        }

        /** javap 实测：接口只有 createConfigManager() 与 createConfigManager(String)，**没有** varargs 版。 */
        @Override
        public com.xuncorp.spw.workshop.api.config.ConfigManager createConfigManager(String name) {
            return new StubConfigManager(this, dir, name);
        }

        StubConfigHelper helper(String name) {
            return configs.computeIfAbsent(name, n -> new StubConfigHelper(dir, n));
        }
    }
}
