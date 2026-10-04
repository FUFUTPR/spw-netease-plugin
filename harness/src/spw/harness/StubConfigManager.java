package spw.harness;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@code ConfigManager} / {@code ConfigHelper} 的桩实现（内存表 + 临时 json 文件）。
 *
 * <p>签名按 javap 实测：
 * <pre>
 * ConfigManager: getConfig(); getConfig(String);
 *                addConfigChangeListener(Consumer); addConfigChangeListener(String, Consumer);
 *                removeConfigChangeListener(Consumer)
 * ConfigHelper :  &lt;T&gt; T get(String, T); void set(String, Object); boolean save();
 *                boolean reload(); Path getConfigPath()
 * </pre>
 *
 * <p>行为约定：{@code get(key, default)} 在无值时返回 default → 插件走「首次运行/默认配置」分支，
 * 这是新装用户的真实路径，正好拿来测。
 */
public final class StubConfigManager implements ConfigManager {

    private final StubWorkshopApi.StubManager mgr;
    private final Path dir;
    private final String name;

    public StubConfigManager(StubWorkshopApi.StubManager mgr, Path dir, String name) {
        this.mgr = mgr;
        this.dir = dir;
        this.name = (name == null || name.isEmpty()) ? "config" : name;
    }

    private StubConfigHelper h() {
        return mgr.helper(name);
    }

    @Override public ConfigHelper getConfig()                  { return h(); }
    @Override public ConfigHelper getConfig(String n)          { return mgr.helper((n == null || n.isEmpty()) ? name : n); }

    @Override public void addConfigChangeListener(Consumer<ConfigHelper> l)                 { h().listeners.add(l); }
    @Override public void addConfigChangeListener(String n, Consumer<ConfigHelper> l)       { mgr.helper(n).listeners.add(l); }
    @Override public void removeConfigChangeListener(Consumer<ConfigHelper> l)              { h().listeners.remove(l); }
}
