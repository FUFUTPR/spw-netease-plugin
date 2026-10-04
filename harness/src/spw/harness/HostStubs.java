package spw.harness;

import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopApi;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 宿主桩：把 {@link WorkshopApi} 的桩实例塞进宿主注入点
 * {@code com.xuncorp.spw.workshop.api.WorkshopApi$Companion.instance}（public static 字段）。
 *
 * <p>字节码实测（javap）：
 * <pre>
 * public final class com.xuncorp.spw.workshop.api.WorkshopApi$Companion {
 *   static final WorkshopApi$Companion $$INSTANCE;      // package-private，不用碰
 *   public static WorkshopApi instance;                 // ← 注入这里
 * }
 * </pre>
 * 桩里 {@code ui().toast(...)} 直接打印到 stdout。
 */
public final class HostStubs {

    private HostStubs() {
    }

    /** 把桩塞进 Companion.instance。返回是否成功。 */
    public static boolean inject(WorkshopApi api) {
        try {
            Class<?> companion = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi$Companion");
            Field f = companion.getField("instance");
            f.set(null, api);
            Object back = f.get(null);
            return back == api;
        } catch (Throwable t) {
            System.out.println("  [stub] 注入失败: " + t);
            return false;
        }
    }

    /** 注入后校验宿主静态访问器真的能看到桩。 */
    public static boolean verifyAccessors() {
        try {
            Object ui = WorkshopApi.ui();
            Object pb = WorkshopApi.playback();
            Object mgr = WorkshopApi.manager();
            return ui != null && pb != null && mgr != null;
        } catch (Throwable t) {
            System.out.println("  [stub] 静态访问器探测失败: " + t);
            return false;
        }
    }

    /**
     * 构造插件 {@link PluginContext}。
     * 真实宿主会给 steam 版 → {@link Channel#Steam}。
     */
    public static PluginContext context(String pluginId, String pluginVersion, Path pluginPath, String spwVersion) {
        return new PluginContext(pluginId, pluginVersion, pluginPath.toAbsolutePath().toString(), spwVersion, Channel.Steam);
    }

    public static Path pathOf(String s, String fallback) {
        try {
            return Paths.get(s);
        } catch (Throwable t) {
            return Paths.get(fallback);
        }
    }
}
