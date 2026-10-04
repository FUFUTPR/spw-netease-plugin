package com.example.netease.core;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 开发者模式（0.11.30）：用**两份随包资源**切换配置页的行显隐。
 *
 * <p>为什么是「改写装机目录下的文件」：宿主打开配置页时**不重读 schema**，配置页长什么样完全由
 * 插件安装目录下的 {@code classes\preference_config.json} 决定（打包时从
 * {@code src\main\resources\preference_config.json} 平铺进来）。所以要让某些行「显示 / 消失」，
 * 唯一办法就是插件**改写自己装机目录里的那一份**：</p>
 * <ul>
 *   <li>开关关闭 ⇒ 把 {@code /preference_config.user.json}（用户档主本）原样覆盖过去；</li>
 *   <li>开关打开 ⇒ 把 {@code /preference_config.dev.json}（开发者档）原样覆盖过去。</li>
 * </ul>
 *
 * <p><b>0.11.35 修复（真机根因：用户报「关掉开发者模式后开发者入口没隐藏」）</b>：插件类加载器的
 * classpath 根**就是** {@code classes\}，所以早期实现拿 {@code /preference_config.json} 当用户档源时，
 * 它解析到的**正是本类自己要改写的那个文件** —— {@code sameContent(源, 目标)} 于是恒为真，
 * 「关」方向每次都提前 return（还打「结果=成功」），装机文件永远停在开发者档。现在用户档源换成
 * **独立主本** {@code /preference_config.user.json}（与 {@code preference_config.json} 逐字节相同，
 * 随 {@code tools/build.py} 的资源整目录拷贝一起进 {@code classes\}），源与目标物理分离；另加
 * {@link #isSelfRead(String)} 自读守卫兜底：源若又指回目标本身 ⇒ 记 ERROR、按失败上报，绝不假成功。</p>
 *
 * <p>三份资源都躺在插件类加载器可见的 {@code classes\} 根，直接用
 * {@link #class}／{@link Class#getResourceAsStream(String)} 读（{@link PluginVersions#read()}
 * 读 {@code /META-INF/MANIFEST.MF} 用的是同一条通路）。</p>
 *
 * <p><b>铁律</b>：本类会被宿主配置线程与 {@code HostBridgeWorker} 线程调用，所以</p>
 * <ul>
 *   <li>所有公开方法**绝不抛**，异常一律自己吞掉并记日志；</li>
 *   <li>写盘走「临时文件 + {@code ATOMIC_MOVE}」，避免宿主正好在读的时候看到半截 JSON
 *       （读失败时会回退成普通覆盖写）；</li>
 *   <li>文件内容没变时不重复写盘（宿主回调很勤，别做无谓 IO）。</li>
 * </ul>
 *
 * <p>生效时机：宿主**已经打开**的配置页不会自己刷新，改完之后要重开配置页（或重启宿主）才看得见。</p>
 *
 * <p><b>0.11.35 起不再管账号行</b>：0.11.32–0.11.34 曾把登录态替换进账号行标题（{@code {{ACCOUNT}}}
 * 占位），但用户 m00001 明确要求账号信息**不得常驻**在配置页上——账号行已改成按钮「当前账号」，
 * 点开 {@code ui.AccountStatusWindow} 才展示名字 / id / 会员。配置页里再没有任何承载账号文本的位置，
 * 于是占位替换与 {@code setAccountText(...)} 一并退役：本类现在只做一件事——切档。</p>
 *
 * <p>可见性：本类按 {@code core} 包其余工具类（{@link PluginLog} / {@link DataPaths} / {@link PluginVersions}）
 * 的惯例声明为 {@code public} —— 它必须被**另一个包**的 {@code cfg.PluginConfig} 调用
 * （配置快照刷新时同步开关值），包私有会直接编译不过。</p>
 */
public final class DevMode {

    /** 日志 tag。 */
    private static final String TAG = "dev-mode";

    /**
     * 随包资源：**用户档主本**（开发者模式关闭时用户看到的样子）。
     *
     * <p>⚠️ 绝不能再指回 {@code /preference_config.json}：那个名字解析到的就是 {@link #target} 自己
     * （classpath 根 = {@code classes\}），会让「关」方向变成空转并假报成功（0.11.35 真机缺陷）。
     * 主本与 {@code preference_config.json} **逐字节相同**，由 {@code tools/build.py} 的资源整目录拷贝
     * 随包进 {@code classes\}；{@code tools\smoke\DevModeProbe} 有逐字节相等断言把这条钉住。</p>
     */
    private static final String RES_USER = "/preference_config.user.json";

    /** 随包资源：开发者档（用户档 + 12 行开发者入口）。 */
    private static final String RES_DEV = "/preference_config.dev.json";

    private DevMode() {
    }

    /** 被改写的那个文件在装机目录里的位置：{@code <pluginPath>\classes\preference_config.json}。 */
    private static volatile Path target;

    private static volatile boolean devMode;
    private static volatile boolean writable;

    /**
     * 初始化：记下装机目录并立刻按当前开关值 {@link #apply()} 一次（幂等）。
     *
     * <p>{@code pluginPath} 为空、不是目录、或目录里没有 {@code classes\} ⇒ 进入**不可写**状态，
     * 之后一切调用都是安全 no-op（只记一条 WARN）。</p>
     *
     * <p>每次启动都会重跑一遍 {@link #apply()}：升级换包后两份资源都变了，靠这条把装机目录里的
     * 那一份同步到新版本（内容相同的场合不写盘，见 {@link #apply()}）。</p>
     */
    public static void init(Path pluginPath) {
        try {
            if (pluginPath == null) {
                enterUnwritable();
                PluginLog.w(TAG, "装机目录为空，开发者模式不可用（配置页行显隐已跳过）");
                return;
            }
            Path classes = pluginPath.resolve("classes");
            if (!Files.isDirectory(classes)) {
                // 装机目录里 classes\ 理论上必然存在（宿主就是从那儿装的插件），但目录缺失不该让
                // 整个功能躺平：顺手建出来，后面 apply() 照常把配置页写进去。
                Files.createDirectories(classes);
            }
            target = classes.resolve("preference_config.json");
            writable = true;
            PluginLog.i(TAG, "开发者模式已初始化：dev=" + devMode + " target=" + target.toAbsolutePath());
            apply();
        } catch (Throwable t) {
            enterUnwritable();
            PluginLog.w(TAG, "开发者模式初始化失败（按不可用处理）", t);
        }
    }

    /** 进入「不可写」状态：目标清空（下次 init 成功时必须重新落一次盘）。 */
    private static void enterUnwritable() {
        writable = false;
        target = null;
    }

    /** 当前开关值（默认关）。 */
    public static boolean on() {
        return devMode;
    }

    /**
     * 切换开关并立刻 {@link #apply()}。
     *
     * <p>值没变时什么都不做。⚠️ 写盘完成、配置页也已改写，但宿主**已经打开**的配置页不会自己刷新
     * ——文案里会写明这一点。</p>
     */
    public static void setOn(boolean want) {
        if (want == devMode) {
            return;
        }
        devMode = want;
        boolean ok = apply();
        PluginLog.i(TAG, "开发者模式 = " + (want ? "开" : "关")
                + "；配置页已改写（" + (want ? "开发者档" : "用户档") + "，结果=" + (ok ? "成功" : "失败")
                + "）；需重开配置页或重启宿主后生效");
    }

    /**
     * 把当前档位对应的资源写到装机目录的 {@code classes\preference_config.json}。
     *
     * <p>幂等、绝不抛。返回 {@code true} = 文件内容已经是（或刚写成）目标档位；
     * {@code false} = 不可写 / 资源读不到 / 写盘失败（原因见日志）。</p>
     */
    public static boolean apply() {
        try {
            if (!writable || target == null) {
                return false;
            }
            boolean wantDev = devMode;
            String res = wantDev ? RES_DEV : RES_USER;
            if (isSelfRead(res)) {
                // 自读守卫（0.11.35 真机缺陷的兜底）：源资源与被改写目标是同一个物理文件 ⇒
                // 下面 sameContent(源, 目标) 必然为真，那句「跳过写盘」会把空操作报成成功。
                PluginLog.e(TAG, "拒绝改写：随包资源 " + res + " 解析到的物理文件就是被改写目标本身（"
                        + target.toAbsolutePath() + "）——源与目标同一份，写盘必然空转；"
                        + "请修正 DevMode.RES_* 或装机布局（本次未写盘，按失败上报）");
                return false;
            }
            byte[] bytes = readResource(res);
            if (bytes == null || bytes.length == 0) {
                PluginLog.w(TAG, "读不到随包配置页资源，跳过改写：" + res);
                return false;
            }
            if (sameContent(target, bytes)) {
                // 磁盘上已经是这份内容：不重复写盘，也避免无谓地刷时间戳。
                PluginLog.d(TAG, "配置页内容已是最新，跳过写盘：" + target.toAbsolutePath());
                return true;
            }
            writeAtomic(target, bytes);
            if (!sameContent(target, bytes)) {
                // 写盘「成功」了但内容对不上（磁盘满 / 被占用 / 落盘失败）：绝不当成功上报
                PluginLog.w(TAG, "配置页写盘后校验不一致（" + bytes.length + " 字节 → "
                        + target.toAbsolutePath() + "），下次调用会重试");
                return false;
            }
            PluginLog.i(TAG, "配置页已改写为" + (wantDev ? "开发者档" : "用户档")
                    + "（" + bytes.length + " 字节 → " + target.toAbsolutePath() + "）");
            return true;
        } catch (Throwable t) {
            PluginLog.w(TAG, "改写配置页失败（已忽略，不影响插件运行）", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 读随包资源全部字节；读不到返回 {@code null}（绝不抛）。 */
    private static byte[] readResource(String name) {
        try (InputStream in = DevMode.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            byte[] raw = in.readAllBytes();
            // 吃掉可能的 UTF-8 BOM：宿主解析器不一定认它
            if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
                return Arrays.copyOfRange(raw, 3, raw.length);
            }
            return raw;
        } catch (Throwable t) {
            PluginLog.w(TAG, "读取随包资源失败 " + name + " :: " + t);
            return null;
        }
    }

    /**
     * 自读守卫：给定随包资源名，它的物理文件是否**就是**被改写的那个目标文件。
     *
     * <p>为什么需要它：本插件的类加载器把装机目录的 {@code classes\} 当 classpath 根，于是
     * {@code getResource("/preference_config.json")} 与 {@link #target} 指的是**同一份文件**
     * （0.11.35 真机缺陷：用户档源用这个名字 ⇒ {@code sameContent} 恒真、「关」方向从不写盘）。
     * 正常路径上不该出现「源 == 目标」，出现即说明资源布局或 {@code RES_*} 常量坏了，
     * 必须报错而不是空转。</p>
     *
     * <p>判定走 {@link Files#isSameFile(Path, Path)}（大小写、短名、符号链接等别名都能识破）；
     * 拿不到 {@code file:} URL（HTTP / jar 内等无法比较的场合）或任何异常 ⇒ 返回 {@code false}
     * —— 宁可放过也不误伤。本方法绝不抛。</p>
     *
     * @param name 随包资源名（以 {@code /} 开头）
     * @return true = 源与目标是同一个物理文件
     */
    static boolean isSelfRead(String name) {
        try {
            Path dst = target;
            if (dst == null || name == null) {
                return false;
            }
            URL url = DevMode.class.getResource(name);
            if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
                return false;
            }
            Path src = Paths.get(url.toURI());
            return Files.exists(src) && Files.exists(dst) && Files.isSameFile(src, dst);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 目标文件是否已经与给定内容逐字节相同（读不到、长度不同 ⇒ false）。 */
    private static boolean sameContent(Path file, byte[] bytes) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
                return false;
            }
            return Arrays.equals(bytes, Files.readAllBytes(file));
        } catch (Throwable ignored) {
            // 读不了就当作「不一样」，让它走正常写盘流程去报告真正的原因
            return false;
        }
    }

    /**
     * 先写同目录临时文件，再覆盖目标；**每次写后都回读校验**，不认任何一步的返回值。
     *
     * <p>为什么这么啰嗦：写盘失败的花样比想象中多（目标被同名目录或占用中的文件顶掉、
     * 宿主正在读、杀软/索引器插一脚），而「{@code Files.move} 报成功但内容没落盘」这类
     * 情况光看返回值是发现不了的。配置页写错等于用户看不到正确的行，所以这里按「只认结果」写：
     * ATOMIC_MOVE → 普通 MOVE → 原地直接写，最多三轮，每轮之间退避 20/40ms。</p>
     *
     * <p>三轮都不成 ⇒ 删临时文件并抛，由 {@link #apply()} 记日志、返回 {@code false}。</p>
     */
    private static void writeAtomic(Path dst, byte[] bytes) throws Exception {
        Path dir = dst.getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(dst.getFileName().toString() + ".devtool.tmp");
        Files.write(tmp, bytes);

        Throwable last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (attempt == 1) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else if (attempt == 2) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // 最后一招：原地覆盖写（非原子，但内容一定落地；宿主读到的窗口极小）
                    Files.write(dst, bytes);
                    Files.deleteIfExists(tmp);
                }
                if (sameContent(dst, bytes)) {
                    return;
                }
            } catch (Throwable t) {
                last = t;
            }
            try {
                Thread.sleep(20L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (Throwable ignored) {
            // 临时文件删不掉不影响功能
        }
        if (last != null) {
            throw new java.io.IOException("配置页写了 3 次都没落盘", last);
        }
        throw new java.io.IOException("配置页写了 3 次都没落盘：写后校验一直不符");
    }
}
