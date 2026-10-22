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
 *
 * <p>1.19.0 给接口补了三处抽象成员（{@code WorkshopApi.getLibrary()}、
 * {@code Playback.getCurrentMediaItem()}、{@code Manager.getKeyBindingManager()}）。
 * 0.11.59 的插件代码不会调用它们，桩里先给安全空实现，保证 harness 能在 1.19 上编译运行；
 * 真正接入原生 UI（批次 1）时再补语义。
 *
 * <p>UI 钩子（{@code PluginPermission.CLASS_TRANSFORM}）在普通 JVM 里没有意义：
 * {@link StubHookRegistrar} 一律抛 {@link UnsupportedOperationException}，
 * 避免测试里静默「注册成功」却什么都不发生。
 */
public final class StubWorkshopApi implements WorkshopApi {

    private final StubUi ui;
    public final StubPlayback playback;
    private final StubManager manager;
    private final StubLibrary library = new StubLibrary();
    private final StubHookRegistrar hookRegistrar = new StubHookRegistrar();

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

    /** 1.19.0 新增。0.11.59 插件不读宿主资料库，全部返回已完成空值。 */
    @Override
    public WorkshopApi.Library getLibrary() {
        return library;
    }

    /** 1.19.0 新增（原生 UI 迁移的关键能力）。普通 JVM 里没有 Compose 宿主，注册一律失败。 */
    @Override
    public com.xuncorp.spw.workshop.api.hook.HookRegistrar getHookRegistrar() {
        return hookRegistrar;
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

        /** 1.19.0 新增；普通 JVM 里没有「当前曲目」。 */
        @Override public com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem getCurrentMediaItem() {
            return null;
        }
    }

    // ------------------------------------------------------------- Hooks
    /** 1.19.0 新增；harness 无 Compose 运行时，任何钩子注册都直接报错。 */
    public static final class StubHookRegistrar extends com.xuncorp.spw.workshop.api.hook.HookRegistrar {
        private static IllegalStateException unsupported() {
            return new IllegalStateException("harness 不支持 UI 钩子（需要真实宿主）");
        }

        @Override
        public com.xuncorp.spw.workshop.api.hook.HookHandle hookMethod(
                String className, String methodName, List<String> parameterTypes,
                com.xuncorp.spw.workshop.api.hook.MethodHook hook) {
            throw unsupported();
        }

        @Override
        public com.xuncorp.spw.workshop.api.hook.HookHandle replaceComposable(
                String className, String methodName, String content, List<String> parameterTypes,
                int priority, com.xuncorp.spw.workshop.api.hook.ComposableHook hook) {
            throw unsupported();
        }

        @Override
        public com.xuncorp.spw.workshop.api.hook.HookHandle afterComposable(
                String className, String methodName, String content, List<String> parameterTypes,
                int priority, com.xuncorp.spw.workshop.api.hook.ComposableHook hook) {
            throw unsupported();
        }

        @Override
        public <S> com.xuncorp.spw.workshop.api.hook.HookHandle appendContent(
                String className, String methodName, String content, String parameter,
                Class<S> scopeType, List<String> parameterTypes, int priority,
                com.xuncorp.spw.workshop.api.hook.ContentHook<S> hook) {
            throw unsupported();
        }
    }

    // ------------------------------------------------------------- Library
    /** 1.19.0 新增。harness 没有资料库，按接口约定返回空的已完成 Future。 */
    public static final class StubLibrary implements WorkshopApi.Library {
        private static <T> java.util.concurrent.CompletionStage<T> done(T value) {
            return java.util.concurrent.CompletableFuture.completedFuture(value);
        }

        @Override
        public java.util.concurrent.CompletionStage<com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem> getTrackById(String id) {
            return done(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getCoverById(String id) {
            return done(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem>> getAllTracks() {
            return done(java.util.Collections.emptyList());
        }

        @Override
        public java.util.concurrent.CompletionStage<List<com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem>> getTracks(String query, int limit) {
            return done(java.util.Collections.emptyList());
        }
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

        /** 1.19.0 新增。无人注册的快捷键只需记账，不真的进宿主事件表。 */
        @Override
        public com.xuncorp.spw.workshop.api.KeyBindingManager getKeyBindingManager() {
            return keyBindingManager;
        }

        private final com.xuncorp.spw.workshop.api.KeyBindingManager keyBindingManager =
                new com.xuncorp.spw.workshop.api.KeyBindingManager() {
                    @Override
                    public AutoCloseable register(String id, String label,
                                                   com.xuncorp.spw.workshop.api.ActionShortcut shortcut,
                                                   boolean global, Runnable action) {
                        return () -> { };
                    }

                    @Override
                    public void unregister(String id) { }
                };
    }
}
