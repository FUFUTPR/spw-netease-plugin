# 0.12.0 迭代记录

本文件随源码交付（`docs/` 被忽略，故正式记录放仓库根）。依据 `docs/PLAN.md` 分批推进；
自动检查通过不代表真机验收完成，未真机确认的项目一律标 `2`（待验），不写成通过。

状态码：`0` 通过 / `1` 失败 / `2` 待验项。

## 批次进度

| 批次 | 内容 | 状态 | 说明 |
| --- | --- | --- | --- |
| 0 | 建立可复现基线 | 0 | 见下「已验证事实」 |
| 1 | 构建迁移 + 最小原生 Compose 页面 | 0 | 构建/自动检查/真机验收全部完成；**用户已真机确认最小页面**（显示在主窗口内、关闭恢复宿主内容、重开正常） |
| 2 | 业务控制器抽取、搜索与歌单迁移 | — | 未开始 |
| 3 | 登录、账号及附属界面迁移 | — | 未开始 |
| 4 | WebView2 验证区域改造 | — | 未开始 |
| 5 | 旧 UI 清理与双渠道交付 | — | 未开始 |

---

## 一、批次 0 已验证事实（本机实测，可复跑）

### 1. 环境与版本

- 宿主 **Salt Player for Windows 1.19.0**（`app/Salt Player for Windows.cfg`：`-Djpackage.app-version=1.19.0`）。
- 宿主 API/宿主 class 为 **Java 25**（class 版本 69）。JDK 21 的 javac 编译宿主 API 报
  `类文件具有错误的版本 69.0, 应为 65.0`，故本轮统一改用 **JDK 25**（Temurin 25.0.4.1+1，
  `C:\Users\XOS\tools\jdk25`）。
- 源码版本 0.11.59；`project.json` 的 `javaRelease=21`、`targetSpwVersion=1.18.5` 本批未改
  （class 65 可在 Java 25 宿主加载；目标版本等批次 1 正式迁移时再动）。
- Git：分支 `main`，批次 0 起点 `4c28170`；本批开工时工作区仅含本批工具链改动。

### 2. API 缓存来源核对（PLAN §5 第 4 条）

`pwsh -NoProfile -File tools/extract-api.ps1 -Force` 从本机宿主重新抽取：

| 项 | 值 |
| --- | --- |
| 宿主 JAR（`app/ffmpeg-x64.dll`，实为 ZIP） | sha256 `4b159412884f1be7f0ff18e068ab05abc8f2f8eb14a5379954968ef4a2a1ebd3` |
| API JAR | **37 class / 37 条目 / 46,919 B**，sha256 `3ba2c209855931da84b7e6c0389d43bd94f60d40a4b03f6005b5ae4d3504486a` |
| PF4J JAR | 95 条目 / 102,876 B，sha256 `6af8f8a2f3cdfe14cd212888c6ab16550ae76d904b2038f7baa1aa975a691063` |

1.18.5 是 19 个 API class，1.19.0 新增 18 个（含 `api/hook` 包），旧缓存**不能**当新版用。
`extract-api.ps1` 已改为：打印宿主版本与三份 SHA-256，class 数 ≠ 37 时警告。

### 3. 基线包与可复现性

- 从 `HEAD=4c28170` 重建：`build/dist/plugin-com.example.netease-0.11.59.spmod`，
  **14,062,207 B**，sha256 `b4ca2d4f1f392fdc92573abc53411b0ed8b14f53ba000a4d893601b4c4802c1a`；
  连续两次构建哈希一致（构建确定性成立）。
- 宿主实际安装的 0.11.59：`%APPDATA%\Salt Player for Windows\workshop\plugins\plugin-com.example.netease-0.11.59.zip`，
  14,056,509 B，sha256 `e4da3b6f6ba5ae6cf586dc1c15f67c35b8b46faf29fbeb266604f211ef7f4706`。
  差异原因：安装器重新打包（202 条目含目录条目 vs 重建包 184 条目），35 个 `.class` 与旧工具链字节不同。
- 两个包都已归档到 `build/baseline/0.11.59/`（含 `SHA256SUMS.txt`、`SOURCE.md`），
  `build/` 被忽略，不入库。

### 4. 自动检查（全部 exit 0）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `build.ps1 -SkipApi`（无 JAVA_HOME，自动选 JDK 25） | 成功，62 源文件，自检通过 | `build/evidence/batch0/01-build.txt` |
| `tools/verify-spmod.ps1 -Spmod <重建包>` | **76 PASS / 0 FAIL** | `build/evidence/batch0/02-verify-spmod.txt` |
| `harness/run-harness.ps1 -Spmod <重建包>` | **56 PASS / 0 FAIL**（`HARNESS_RESULT=ALL_PASS`） | `build/evidence/batch0/03-harness.txt` |
| `harness/run-regression.ps1 -Spmod <重建包>` | **58 PASS / 0 FAIL**（F1–F8 共 8 组） | `build/evidence/batch0/04-regression.txt` |
| `harness/run-behavior.ps1 -Spmod <重建包>` | **27 + 18 + 7 + 1 = 53 PASS** | `build/evidence/batch0/05-behavior.txt` |
| `python -m unittest discover -s harness/regression -p test_acceptance.py -v` | **5 tests OK** | `build/evidence/batch0/06-unittest.txt` |

### 5. 基线期定位并修复的脚手架问题（宿主升级引起）

1.19.0 给 API 接口补了抽象成员，旧 harness 桩因此编译失败（**不是本批业务改动引入**）：

- `WorkshopApi.getLibrary()`
- `Playback.getCurrentMediaItem()`
- `Manager.getKeyBindingManager()`
- `WorkshopApi.getHookRegistrar()`（`HookRegistrar` 新增抽象类）

修法：只在 `harness/src/spw/harness/StubWorkshopApi.java` 补最小空实现
（`StubLibrary`、`StubHookRegistrar`、返回 null 的当前曲目、空实现快捷键管理器），
未删断言、未改业务代码。修后 harness 由 exit 1 → exit 0。

### 6. 工具链统一到 JDK 25

- 新增 `tools/jdk.ps1`：`Resolve-RepoJdk` 依次探测 `SPW_JDK_HOME` → `JAVA_HOME` → 常见安装位置 →
  `PATH`，并强制 `major >= 25`，杜绝静默回退 JDK 21。
- `build.ps1`（新增 0 段）、`tools/build.py`（`find_javac()` 同规则）、
  `harness/run-harness.ps1`、`run-regression.ps1`、`run-behavior.ps1` 全部改用解析出的绝对路径。
- 首次构建日志确认：`javac = C:\Users\XOS\tools\jdk25\bin\javac.exe (major 25)`。

### 7. 1.19.0 原生 UI 钩子接口（批次 1 的前置侦察）

`api/hook` 包新增：`HookRegistrar`、`ComposableHook.Content(UiHookCall, Composer, int)`、
`ContentHook<S>.buildContent(S, UiHookCall)`、`HookHandle`、`UiHookCall`、`MethodHook` 等。
`WorkshopApi.hookRegistrar()` 提供入口；权限走 `PluginPermission.CLASS_TRANSFORM`。
具体签名（CFR 反编译 `com/xuncorp/voxzen/workshop/hooks/` 下的混淆类可对照）：

- `hookMethod(String className, String methodName, List<String> parameterTypes, MethodHook)`
- `replaceComposable(String className, String methodName, String content, List<String> parameterTypes, int priority, ComposableHook)`
- `afterComposable(...)` 同上形态
- `appendContent(String className, String methodName, String content, String parameter, Class<S> scopeType, List<String> parameterTypes, int priority, ContentHook<S>)`
  —— `parameter` 对应的目标参数必须是 `kotlin.jvm.functions.Function1`，否则抛
  `UI parameter '...' is missing or is not an ordinary Function1`。

---

## 二、批次 1 已验证事实（Gradle + 最小原生 Compose 页面）

### 1. 构建链迁移（Gradle 9.2.1 + Kotlin 2.4.20 + Compose 编译器 2.4.20 + JDK 25）

- Gradle 发行版：`gradle-9.2.1-bin.zip`，137,358,119 B，
  sha256 `72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f`；
  Wrapper 的 `distributionUrl` 指向腾讯镜像，`distributionSha256Sum` 即该校验值，
  `validateDistributionUrl=true`。官方 `services.gradle.org` 会重定向到 GitHub（本机不可达）。
- 版本选择依据（实测，不是猜）：
  - Kotlin **2.4.20**：宿主 `kotlin/KotlinVersionCurrentValue` 字节码常量为 2.4.20，
    宿主类 `@Metadata mv={2,4,0}`；
  - Compose 运行时 **1.12.2**（`runtime-desktop` / `foundation-desktop` / `ui-desktop`）：
    其 class 名集合与宿主 runtime 完全一致（宿主多出 `retain`/`saveable`/注解类，属宿主自带）；
  - Material3 **1.10.0-alpha05**（`org.jetbrains.compose.material3:material3-desktop`）：
    与宿主同名类公共方法集完全同数（MaterialTheme=9、TextKt=10、ButtonKt=12、ColorScheme=59、
    Typography=58 …），1.9.0/1.12.0-alpha03 等版本均不一致；
  - JDK **25**（宿主 class 版本 69）。
- 仓库：`mavenCentral()` + `google()`；Gradle 插件从 Maven Central/Plugin Portal 取
  （未使用官方 Workshop Gradle 插件：其坐标在 JitPack，本机不可达；PLAN 6.1 允许继续用自有打包器）。
- 编译产物落 `build/plugin-classes`，再由 `python tools/build.py --package-only --classes-dir ...`
  完成资源、manifest、`.spmod` 打包与自检；`--package-only` 不调用 `javac`、不清理输入目录。
- 包格式与污染检查不变：`classes/` 下禁止出现 `com/xuncorp/`、`org/pf4j/`、`kotlin/`、
  `androidx/`、`org/jetbrains/`；`lib/*.jar` 做内部条目检查；不打包宿主 JAR/API/PF4J/Kotlin/Compose。

### 2. 宿主 UI 适配层（混淆名只出现在这一层）

`src/main/kotlin/com/example/netease/nativeui/HostUiAdapter.kt` 集中记录：

| 项 | 值（宿主 1.19.0 实测） |
| --- | --- |
| 目标类 | `androidx.compose.ui.ȫ`（U+022B，实为 `ModConfigScreenKt`） |
| 目标方法 | `Ϳ`（U+037F），业务参数 `[BoxScope, java.util.List, java.lang.String, PaddingValues]` |
| JVM 参数 | 上述 4 个 + `androidx.compose.runtime.Composer, int, int` |
| 调用点所在类（inClass） | `com.xuncorp.voxzen.ui.navigation.ComposableSingletons$AppNavigation_desktopKt` |
| 方式 / 优先级 | `afterComposable` / 100 |

- 选点结论：宿主配置内容区的私有 composable 在宿主字节码里有 **2 个 UI 调用点**，Hook 引擎因此报
  `UI call missing or ambiguous ... (2 calls)`；公共 `ModConfigScreen` 全宿主**唯一**调用，
  且 `afterComposable` 不改写原调用，规避了该调用点以默认参数（mask=2）传参的问题。
- 失败语义（都写日志且不装 Hook）：目标类不存在 / 目标方法 0 个 / 目标方法 >1 个 /
  未获 `class-transform` 权限。成功日志含宿主版本、插件版本、目标签名、inClass 与匹配数。
- 权限：`project.json` 的 `permissions: ["class-transform"]`（真实标识，非枚举名）；
  宿主「启用模组」确认框勾选「允许修改应用行为与界面」后 `isPermissionGranted(CLASS_TRANSFORM)` 通过。

### 3. 生命周期与页面

- `NeteasePlugin.start()` → `HostUiBridge.install(spwVersion, pluginVersion)`；
  `stop()` 先 `HostUiBridge.uninstall()`。Java 侧通过反射调用 Kotlin 适配层，
  因此 harness 仍只编译 `src/main/java`（不 import Kotlin/Compose）。
- `install`/`uninstall` 幂等：重复进入配置页不会重复注册（日志每次进程启动仅 1 条 `install 成功`）。
- 页面在宿主主窗口内用 Compose 渲染，无独立窗口；关闭后仅保留右下角
  「打开原生 UI 测试区域」胶囊，关闭态下宿主配置页完全可交互。
- 主题/缩放：宿主 1,791 个 `com/xuncorp/**` 类对 `androidx.compose.material3` 引用数为 **0**，
  宿主 UI 全自绘、不使用 `MaterialTheme`，Workshop API 37 个 class 也无主题接口 →
  插件改用与宿主相同的 `isSystemInDarkTheme()` 套 `darkColorScheme()/lightColorScheme()`，
  密度与字体缩放直接读宿主 `CompositionLocals`（页面显示 密度 1.00×、字体缩放 1.00×）。
  **已知偏差**：宿主品牌配色取不到，插件页用 Material3 默认色板（深/浅色模式与宿主一致）。

### 4. 自动检查（全部 exit 0，证据 `build/evidence/batch1/`）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `build.ps1`（JDK25 → Gradle → `--package-only`） | 成功，174 个 `.class`，14,094,406 B，sha256 `f991cc02f2d91fa42bb7494394aa830257c07d2bad7e205a9d3a48a030101d51` | `05-build.txt` |
| `tools/verify-spmod.ps1` | **76 PASS / 0 FAIL** | `06-verify-spmod.txt` |
| `harness/run-harness.ps1` | **56 PASS / 0 FAIL**（`HARNESS_RESULT=ALL_PASS`） | `07-harness.txt` |
| `harness/run-regression.ps1` | **10 PASS / 0 FAIL** | `08-regression.txt` |
| `harness/run-behavior.ps1` | `HOST_TRANSACTION_RESULT=7 PASS`、`HOST_PROFILE_RESULT=1 PASS` | `09-behavior.txt` |
| `python -m unittest ... test_acceptance.py` | **5 tests OK** | `10-unittest.txt` |

### 5. 真机验收记录（宿主 1.19.0，副屏 2398×1288）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 插件加载 | 模块管理「已启用」后 3 秒内启动，日志含宿主/插件版本 | `18-workshop.png`…`22-enabled-confirmed.png` |
| Hook 挂载 | `install 成功：… 匹配数=1`（适配基线 1.19.0） | `30-host-after-restart.png` |
| 页面显示在主窗口内 | 标题/正文/按钮/状态齐全，无独立窗口 | `30`、`32`、`33`、`37`、`41`、`53` |
| 关闭恢复宿主内容 | 关闭后宿主配置项完整可交互，仅留右下角入口 | `31-closed-host-content.png`、`42-closed-dark.png` |
| 重复打开不重复注册 | 重开后状态保留、日志仍只有 1 条 `install` | `32-reopened.png`、`37-panel-reentered.png` |
| 主题跟随 | 宿主机深色 → 页面深色（正文显示「当前为深色」） | `38-theme-dark.png`、`41-theme-follow.png` |
| 缩放/密度跟随 | 页面显示宿主 CompositionLocals 的 1.00× | `43-density-host.png` |
| 停用后消失 | 停用 → `uninstall 完成：Hook 已摘除`，配置页无插件层、无入口 | `44`–`46` |
| 重新启用 | 再次启用 → 重新 `install 成功`，配置页页面恢复（计数归 0） | `47`–`53` |

## 三、已实现但未验收

1. `tools/jdk.ps1` 的探测顺序与报错信息（自动化路径已实测，异常机器环境未覆盖）。
2. `extract-api.ps1` 的宿主版本/SHA-256 输出与 37 类阈值（本机通过，换宿主版本时只警告不阻断）。
3. 适配层在**其它宿主版本**上的行为（当前只对 1.19.0 记录锚点；换版本应表现为「目标类/方法不存在」并停在不动手状态）。
4. 宿主机浅色模式的截图（本轮宿主处于深色；浅色分支由同一 `isSystemInDarkTheme()` 决定，未单独截图）。

## 四、尚待验证的技术方案

1. **宿主品牌配色**：宿主不使用 `MaterialTheme`、也不暴露配色 CompositionLocal，
   插件页只能跟到系统深/浅色。后续若需要品牌色，只能解析宿主设置库（`spw.db`）或宿主自绘常量，属方案待定。
2. **适配层锚点随宿主升级的稳定性**：1.19.0 已确认；1.20+ 需要重新核对锚点并更新适配层常量。

## 五、明确阻塞项

1. ~~批次 1 硬关卡：最小原生 Compose 页面须用户真机确认~~ **已解除**：
   用户在宿主「配置」页确认「显示在主窗口内 / 关闭后宿主配置恢复可点 / 右下角入口可重开」三项均正常，
   批次 1 记为 0（通过），可进入批次 2。
2. 网络限制：GitHub、jitpack.io 不可达（Gradle 发行版走腾讯镜像，依赖走 Maven Central），
   因此官方 Workshop Gradle 插件不可用；本批继续使用自有 Python 打包器。

## 六、复跑方式

在工程根目录（0.12.0 目标包名 `plugin-com.example.netease-0.11.60.spmod`，批次 5 才升到 0.12.0）：

    pwsh -NoProfile -File tools/extract-api.ps1 -Force      # 需要时刷新宿主 API 并核对 37 类/SHA-256
    pwsh -NoProfile -File build.ps1                         # JDK25 → Gradle → --package-only
    pwsh -NoProfile -File tools/verify-spmod.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.60.spmod
    pwsh -NoProfile -File harness/run-harness.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.60.spmod
    pwsh -NoProfile -File harness/run-regression.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.60.spmod
    pwsh -NoProfile -File harness/run-behavior.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.60.spmod
    python -m unittest discover -s harness/regression -p test_acceptance.py -v

每条命令单独检查退出码；证据写 `build/evidence/batchN/`，不入库。

真机验收（本批新增工具，均只在本机使用、不进包）：

    pwsh -NoProfile -File tools/host-restart.ps1 -Install   # 停宿主→校验 .spmod→替换目录→重启
    pwsh -NoProfile -File harness/shot-window.ps1 -Out build/evidence/batch1/x.png
    pwsh -NoProfile -File tools/host-click.ps1 -X <截图X> -Y <截图Y>   # 125% 缩放下的坐标换算点击

注意：宿主为 Compose/AWT 自绘，UIA 拿不到按钮，只能截图 + 坐标点击；
截图坐标 = 窗口左上为原点，点击换算 `virtual = (windowRect.Left + X) * 1.25`。
按钮位置建议用像素颜色扫描确定，不要凭缩略图估算（会偏 40–60 px）。

## 七、回退

本批新增 Gradle 构建链与 Kotlin/Compose 源码，但**未改**插件 ID、主类、扩展类、包内布局与数据格式。
回退即：`git revert` 本批提交（恢复 `build.ps1`/`tools/build.py` 的纯 javac 路径），
并用 `build/baseline/0.11.59/` 里的 0.11.59 包重新安装。
运行时副作用：插件停用/卸载时会执行 `HostUiBridge.uninstall()`；若进程被强杀，Hook 随宿主进程消失，无残留。

## 八、本批提交

- 批次 0 独立提交：`f1bd594 0.12.0 批次 0：建立 JDK 25 可复现基线与宿主 1.19 API 核对`。
- 批次 1 独立提交：见 `git log` 中「批次 1」条目（Gradle 构建链 + 原生 UI 适配层 + 最小页面）。
- 之后批次各自独立提交；`docs/`、`build/` 不入库。
