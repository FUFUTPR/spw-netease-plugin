package spw.harness;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopPluginManager;

import org.pf4j.ExtensionFactory;
import org.pf4j.PluginFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * {@link WorkshopPluginManager} 子类：唯一被支持的「换工厂」方式。
 *
 * <p><b>为什么必须子类化</b>（javap 实测 PF4J 3.12.0）：
 * {@code AbstractPluginManager} 上 <b>没有</b> {@code setPluginFactory} / {@code setExtensionFactory}
 * 公开方法，也没有 {@code setAutoUpdatePluginState}；只有：
 * <pre>
 *   protected org.pf4j.PluginFactory    getPluginFactory();
 *   protected abstract org.pf4j.PluginFactory    createPluginFactory();
 *   protected abstract org.pf4j.ExtensionFactory createExtensionFactory();
 *   public    org.pf4j.ExtensionFactory getExtensionFactory();
 * </pre>
 * 即工厂只能在 {@code initialize()}（构造期）由 {@code createXxxFactory()} 产出。
 *
 * <p><b>为什么 ctx 用 Supplier 而不是构造期传值</b>：{@code createPluginFactory()} 在
 * {@code super(...)} 构造期就被调用，那时子类字段还没赋值；工厂的 {@code create()} 只在
 * {@code loadPlugins()}（构造之后）才被调用，所以运行时读字段是安全的。
 */
public final class HarnessPluginManager extends WorkshopPluginManager {

    private volatile PluginContext ctx;

    public HarnessPluginManager(Path... pluginsRoots) {
        super(pluginsRoots);
    }

    /** 必须在 {@code loadPlugins()} 之前调用。 */
    public void setHarnessContext(PluginContext ctx) {
        this.ctx = ctx;
    }

    public PluginContext getHarnessContext() {
        return ctx;
    }

    @Override
    protected PluginFactory createPluginFactory() {
        return HarnessFactories.pluginFactory(new Supplier<PluginContext>() {
            @Override
            public PluginContext get() {
                return HarnessPluginManager.this.ctx;
            }
        });
    }

    @Override
    protected ExtensionFactory createExtensionFactory() {
        return HarnessFactories.extensionFactory();
    }
}
