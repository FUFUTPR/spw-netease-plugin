# 0.12.0 迭代记录

本文件随源码交付（`docs/` 被忽略，故正式记录放仓库根）。依据 `docs/PLAN.md` 分批推进；
自动检查通过不代表真机验收完成，未真机确认的项目一律标 `2`（待验），不写成通过。

状态码：`0` 通过 / `1` 失败 / `2` 待验项。

## 批次进度

| 批次 | 内容 | 状态 | 说明 |
| --- | --- | --- | --- |
| 0 | 建立可复现基线 | 0 | 见下「已验证事实」 |
| 1 | 构建迁移 + 最小原生 Compose 页面 | 0 | 构建/自动检查/真机验收全部完成；**用户已真机确认最小页面**（显示在主窗口内、关闭恢复宿主内容、重开正常） |
| 2 | 业务控制器抽取、搜索与歌单 / 同步页面 | 2 | 实现、构建与自动检查全部完成；已做部分真机取证（搜索/播放/歌单页/同步状态），**待用户真机验收** |
| 3 | 登录、账号及附属界面迁移 | 2 | 实现、构建与自动检查全部完成（登录/账号/本地命名控制器 + 原生页）；**待用户真机验收** |
| 4 | WebView2 验证区域改造 | 2 | 助手严格挂载 + 控制器 + 内嵌验证面板 + 构建/自动检查全部完成；**待用户真机验收**（真实风控可见可操作、移动缩放、停用关闭） |
| 5 | 旧 UI 清理与双渠道交付 | 2 | 旧窗口与注册表已删除、门禁/探针已改写、0.12.0 包与双渠道材料完成，构建与 9 项自动检查全部 exit 0；**交付候选，待用户按 `ACCEPTANCE.md` 真机验收**；WebView2 技术关卡仍未闭合 |

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

## 三、批次 2 已验证事实（业务控制器 + 搜索 / 歌单 / 同步页）

### 1. 控制器抽取（行为等价，禁止顺带改语义）

- 契约文件 build/_tmp/batch2-contracts.md 由 Lead 先行锁定公共签名与逐条语义（临时产物，不入库）；
  两个控制器都**不 import Swing/AWT/Kotlin/Compose**，可 headless 运行。
- src/main/java/com/example/netease/ui/controller/SearchController.java：
  Phase{IDLE,SEARCHING,RESULTS,EMPTY,FAILED}、State(...)、LIMIT=30、PLAYBACK_TIMEOUT_MS=30_000；
  异步用 LatestTask(name, 20_000, Runnable::run)（必须 3 参构造，默认构造器会 SwingUtilities::invokeLater）；
  空关键词保留旧列表并取消在途请求、成功同时替换列表与来源并按 songId 恢复仍有效的选中、失败保留旧列表与旧来源、
  重试搜索用上次关键词、重试播放用上次曲目（与当前选中无关）。
- PlaylistController.java + PlaylistIds.java：SourceKind{MINE,LIKED,DAILY,PLAYLIST,LOCAL}、Request（原参数重试）、
  State(...)、BRIEF_LIMIT=200；PlaylistWindow.parseId 改为 PlaylistIds.parse(text)（只改这一处，正则含义不变）。
  A→B 快速切换时 A 的迟到成功按版本号丢弃；B 失败保留 A 的列表与来源且文案含 B；epoch 变化清 briefs、保 tracks；
  **首次载入前**的账号 epoch 抖动只静默同步快照（不显示「账号状态变化」，避免插件启动早期打扰用户）。
- **播放反馈窗口（本批修正过一次契约初稿）**：VoxzenBridge.playOnlineRequest 投递即写入首条反馈「正在解析音源…」，
  若「report 非空即结束」会让后续「已提交给宿主播放器…」「取直链失败…」永远看不到，也偏离旧窗口 30 秒轮询行为。
  现语义：窗口内每条不同的 report 都回显、同值不重复通知；到 30 秒才离开窗口，有反馈则保留最后一条、从未有反馈才用
  「播放请求：等待宿主反馈超时（30 秒），可重试」；playRetryable = lastPlaySongId != null（窗口内也可重试）。
  控制器永不把「已投递播放请求」写成「已实际出声」。

### 2. 页面与同步状态入口

- NativeUiSession.kt：安装 Hook 成功后创建两个控制器，单个后台 Timer("native-ui-tick", true) 250ms tick
  （控制器 tick 只读宿主播放回报与账号 epoch，不做网络请求），每 4 次 tick 刷新一次只读同步状态；
  停用插件时 stop() 取消定时器并 close() 两个控制器。
- 同步只读入口（NeteasePlugin）：syncStatusSnapshot() 返回 SyncCoordinator.Status；syncRequestFromNativeUi()
  调 SYNC.request(List.of(), "force:native-ui") 并把 RequestResult 映射成中文（STARTED=已接收 / BUSY=已有任务 /
  DISABLED / UNAUTHENTICATED / STOPPED / FAILED），**不打开旧窗口、不另写同步流程**。
- 页面（src/main/kotlin/com/example/netease/nativeui/）：NativeUiPage.kt（外壳/主题/首页/同步摘要 + trackNote/formatDuration）、
  NativeSearchPage.kt（回车或按钮提交、播放所选在无选中时禁用、搜索重试与播放重试分开、新搜索保留旧列表并标明新关键词、
  双击结果行播放、底部规则说明）、NativePlaylistPage.kt（左栏歌单分类/条目、ID 与链接载入、本地歌单展开载入、存为本地歌单、
  右栏来源+曲目整单播放、页内同步状态区；窄窗 <760dp 左栏默认收起）、NativeSyncPage.kt（只读 Status 全字段 + 立即同步/刷新）。
- 同步状态映射：IDLE 尚未同步 / WAITING 等待同步 / FETCHING 拉取中 / WRITING 写入中 / COMPLETED 同步完成 /
  ABORTED 同步中止 / FAILED 同步失败；只有 STARTED 文案写「已提交（宿主已接收）」，不按动画时间伪造进度。

### 3. 自动检查（全部 exit 0，证据目录 build/evidence/batch2/）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| build.ps1 全链路 | 228 个 .class、资源 15 个，size 14249236 B，sha256 3014a0063d1f518947bd1bd553b30601d24650dedadfbb73cb363fc17542755c | 05-build.txt |
| tools/verify-spmod.ps1 | 76 项 PASS | 06-verify-spmod.txt |
| harness/run-harness.ps1 | PASS = 56 FAIL = 0 | 07-harness.txt |
| harness/run-regression.ps1（批次 1–4 回归） | 12 / 6+7+3 / 9+7 / 4+10 PASS，全 0 FAIL | 08-regression.txt |
| harness/run-regression.ps1（批次 5 = 新控制器探针） | SEARCH_CONTROLLER_RESULT=92 PASS、PLAYLIST_CONTROLLER_RESULT=146 PASS | 08-regression.txt |
| harness/run-behavior.ps1 | HOST_TRANSACTION_RESULT=7 PASS、HOST_PROFILE_RESULT=1 PASS | 09-behavior.txt |
| python -m unittest … | Ran 5 tests … OK | 10-unittest.txt |

harness/run-regression.ps1 本批扩展：-Batch 5 编译并运行 SearchControllerProbe 与 PlaylistControllerProbe；
-Batch all 现包含 5。PlaylistIds 的 10 条等价性断言已并入 PlaylistControllerProbe。

### 4. 真机取证（部分，非完整验收；证据 build/evidence/batch2/）

- 15:57 安装 0.11.60（旧包目录自动归档）后宿主自动加载；插件日志
  「[native-ui] 原生页面控制器已启动（tick=250ms）」「install 成功 … 匹配数=1」。
- 22：插件首页（标题/版本/密度 1.00×/字体 1.00×/深色、挂载状态、三个页面入口、同步摘要）。
- 23：搜索页空态；24/25：输入 Jay 提交后 30 条结果（列表 5 列、来源「Jay」、消息「找到 30 条」、无选中时「播放所选」禁用）。
- 26：点选一行（选中行高亮）；27：点「播放所选」后文案
  「播放反馈（宿主回报，不代表已实际出声）：播放请求：已提交给宿主播放器：布拉格广场 - 蔡依林/周杰伦」，
  宿主标题栏同步变为该曲 → 请求编号通道与反馈回显真实可用。
- 28：歌单页渲染（分类按钮、ID/本地歌单输入、左右两栏、页内同步状态区）；同步区显示真实
  「同步完成：歌单 4 / 曲目 3290 / 关联 5985（宿主间连接）；无曲目来源：0 首」。
- 观察到的偏差：歌单页首次打开显示「账号状态变化，请重新载入；当前仍显示上次载入的曲目」（插件启动早期 epoch 抖动）。
  已按 §1 末条改为「首次载入前静默同步快照」，并有探针断言覆盖；**该修正后的版本尚未再真机复测**（记入待验项）。

## 四、批次 3 已验证事实（登录 / 账号 / 本地命名，含账号变化失效）

### 1. 控制器抽取（离线探针，不联网、不碰 Swing）

- `LoginController`（885 行）：`Mode{QR,SMS,RISK}`、`Phase{IDLE,WAITING_QR,QR_READY,SCANNED,EXPIRED,ERROR,SENDING,CODE_SENT,LOGGING_IN,RISK,LOGGED_IN,CLOSED}`，
  `Ports` 把二维码/短信/风控/验证窗/账号快照全部外置；常量 `QR_TICK_MS=1000`、`QR_MIN_GAP_MS=2000`、`COOLDOWN_SEC=60`、
  `PHONE_RE=^1[3-9]\d{9}$`、`CODE_RE=^\d{4,6}$`。扫码成功 `loginVia="扫码"`、短信成功 `loginVia="验证码"`；
  风控迁移自动打开验证页并给出「我已完成，重试」路径；`close()` 幂等且**绝不退出账号**（只停轮询、关验证窗）。
  探针 `LoginControllerProbe`：**LOGIN_CONTROLLER_RESULT=253 PASS**。
- `LocalSaveController`（389 行）：命名去空白、无曲目/空名/忙碌三种拒绝、提交时固定 `finalName+finalSongs` 快照、
  成功后刷新本地歌单条目；`close()` 于 :346（取消在途但不撤销已提交写盘，与 `cancelPending` 语义一致）。
  探针 `LocalSaveControllerProbe`：**LOCAL_SAVE_CONTROLLER_RESULT=156 PASS**（含 `closeLifecycle` 用例）。
- `SearchController.onAccountChanged()`（本批新增，PLAN §8.2「账号变化使旧搜索回调失效」）：
  锁内 `version++` 作废在途回调；若 `phase==SEARCHING` 回退为 `IDLE`（无旧列表）或 `RESULTS`（有旧列表），
  避免永久停在「搜索中…」；清空 `pendingPlayRequest/playing/lastPlaySongId/lastPlaybackReport/playbackText/playRetryable`，
  保留已拿到的列表与选中（搜索结果是匿名数据）；`async.cancel()` 在锁外调用（同一锁序理由）。
  文案「账号状态已变化，旧的搜索请求与播放反馈已作废」。探针由 92 → **109 PASS**（新增 17 条断言）。

### 2. 页面与会话

- `NativeUiSession` 现在维护 4 个控制器（搜索/歌单/登录/本地命名）+ 250ms tick；每 tick 比对
  `AccountService.sessionEpoch()`，变化即调 `SearchController.onAccountChanged()`（PLAN §8.2）；
  `stop()` 依次 `close()` 四个控制器。
- `NativeLoginPage`：扫码/短信分段切换、二维码图片按 `qrKey` 缓存解码、60 秒冷却倒计时、
  风控面板（打开验证窗 / 我已完成，重试 / 返回扫码，注「批次 4 迁 WebView2」）；
  登录成功 `LaunchedEffect(phase, loginVia)` 自动切到账号页。
- `NativeAccountPage`：账号行复用 `AccountStatusWindow.lineForTest()`（单一算法），显示 `state/sessionEpoch`；
  刷新快照、切换账号（→登录页）、退出登录（二次确认后走 `NeteasePlugin.logout()` 原路径）。
- 本地命名内联进歌单页：`defaultName` 取自当前来源名，占位符显示默认名；保存/取消按钮与「待保存 N 首」行。
- 宿主配置页四个入口（搜索/歌单/登录/当前账号）已改为**先开原生页、失败回退旧窗口**：
  `HostUiBridge.openNativePage("search"/"playlist"/"login"/"account")` 为 `false` 才调用旧窗口。

### 3. Swing 自建窗口迁移对照

| 旧入口 | 位置 | 现在 |
| --- | --- | --- |
| 搜索窗 JFrame | `src/main/java/com/example/netease/ui/SearchWindow.java:85` | 原生 SEARCH 页（旧窗口保留 fallback） |
| 歌单窗 JFrame | `src/main/java/com/example/netease/ui/PlaylistWindow.java:124` | 原生 PLAYLIST 页（同上） |
| 登录窗 JDialog | `src/main/java/com/example/netease/ui/LoginDialog.java:318` | 原生 LOGIN 页（同上） |
| 当前账号 JDialog | `src/main/java/com/example/netease/ui/AccountStatusWindow.java:265` | 原生 ACCOUNT 页（同上） |
| WebView2 验证窗 JDialog | `src/main/java/com/example/netease/ui/VerifyWindow.java:199` | **批次 4 范围**，本批仍走旧窗口 |
| 本地歌单命名 JOptionPane | `src/main/java/com/example/netease/ui/PlaylistWindow.java:471` | 已内联进原生歌单页 |
| 打开数据目录 / 打开浏览器 | `NeteasePlugin.java:1568`、`LoginDialog.java:948` | 非自建窗口（系统资源管理器/浏览器），保留 |
| 开发者诊断按钮 | `preference_config.dev.json` | 宿主配置页按钮 + `Notifier` toast/日志，无自建窗口 |

### 4. 自动检查（全部 exit 0，证据 `build/evidence/batch3/`）

| 检查 | 结果 |
| --- | --- |
| `build.ps1 -SkipApi` | 254 个 `.class`、资源 15、产物自检通过；size **14338291 B**、sha256 **ef9f0f1c…ece7e89** |
| `verify-spmod.ps1` | 全部通过（76 项 PASS） |
| `run-harness.ps1` | HARNESS_RESULT=ALL_PASS（56 项） |
| `run-regression.ps1` | 批次 1–4：12 / 6+7+3 / 9+7 / 4+10 PASS；批次 5：搜索 **109** / 歌单 146；批次 6：登录 **253** / 本地命名 **156** |
| `run-behavior.ps1` | BEHAVIOR 27 + ASYNC 18 + HOST_TRANSACTION 7 + HOST_PROFILE 1 PASS |
| `test_acceptance.py` | Ran 5 tests OK（10.4s） |

### 5. 真机状态

本批**未做新的真机取证**（按用户要求停止鼠标自动化；批次 2 的页面已在宿主上运行过）。
待用户验收项见下一节；批次 5 会统一升版本号并交付最终预览包。

## 五、批次 4 已验证事实（WebView2 内嵌验证区域）

### 1. 助手进程（`tools/verify-host`，C# + WebView2）

- 严格挂载模式新增参数：`--session`、`--parent-hwnd`、`--parent-class`、`--parent-title`、`--expect-client`。
  父窗定位要求**顶层（GetParent==0）+ 同 pid + 同类名 + 可见**；候选 ≥2 且标题无法唯一命中时输出
  `parent-ambiguous count=N` 并退出（**拒绝挂载，绝不取第一个**）；`--parent-hwnd` 无效时输出
  `parent-invalid <reason>`；超时输出 `parent-timeout`。采用后输出
  `parent <hwnd> class=<cls> pid=<pid> title-match=… count=… session=<sid>`、`adopted rect=0,0,1,1`
  （严格模式不先闪）与 `client w=<物理宽> h=<物理高>`；`--expect-client` 与实测差 >12px 输出
  `parent-client-mismatch` 并退出。
- 进程协议（stdin UTF-8 逐行）：`rect x y w h`（**物理像素**，只 MoveWindow）、`show` / `hide`
  （SW_SHOWNA / SW_HIDE，相同可见性不重复回报）、`close`（有界清理后退出）、`info`（回显 rect/visible/
  adopted/ready/session），未知命令 `ignore-cmd`；每条 rect/show 前复核 `IsWindow(parent)`。
- 父窗监视改为**独立后台线程（200ms）**：宿主销毁子窗口时消息循环可能先结束，WinForms Timer 收不到
  tick；`FormClosed` 与 `Application.Run` 返回后只要 `_adopted && !_closing` 就回报 `parent-gone exit`，
  让 Java 侧能区分「宿主关窗」与「助手崩溃」。
- `tools/verify-host/build.ps1`：先 publish 到 `build/_tmp/verify-host-publish-<时间>` 临时目录 →
  校验 exe 存在且 >1024 B、exe 写入时间不早于 `tools/verify-host` 下最新源码（容差 2s）→ 跑 `--selftest`
  失败则保留旧资源目录并退出 → 成功才替换 `src/main/resources/verify-host`（12 文件 / 2,052,265 B）→
  最后校验 exe **与 VerifyHost.dll** 的 sha256 都与临时产物一致（exe 只是 apphost，托管代码在 dll）。
  本次：exe `19fd1d88…0236`，dll `7c222cf3…1c3`。
- 真实协议探针 `tools/smoke/VerifyHostProtocolProbe.java`（`harness/run-verifyhost-probe.ps1` 编译运行）：
  真实 `VerifyHost.exe` + 真实 AWT 父窗 7 组场景 **41 PASS，exit 0**（正常握手/rect/show/hide/info/close、
  候选歧义拒绝、指定 HWND 无效、父窗超时、父窗销毁 `parent-gone`、stdin EOF、旧 `SunAwtDialog` 模式）。
  证据 `build/evidence/batch4/01-verifyhost-build.txt`、`02-verifyhost-protocol.txt`。

### 2. 控制器（`VerifyEmbedController`，Java，离线探针）

- `Phase{IDLE,STARTING,READY,FAILED,CLOSED}`；`State{phase,message,sessionId,embedded,retryable,width,height}`；
  监听器只收状态快照，异常被吞掉，不反噬调用方。
- `open` 幂等；cookie 最多等 1500ms（迟到回调不启动进程）；每次 open/retry 生成新的 8 位十六进制
  sessionId，**旧 session 的所有输出一律丢弃**；账号 epoch 变化后旧行不回灌 cookie、不改状态；
- `setBounds(逻辑坐标×4, scale)`：逻辑 → 物理只在控制器里 `Math.round` **换算一次**，重复值不发，
  READY 前缓存、adopted 后补发；`setVisible` 去重；`retry()` 关旧进程开新会话；
  `close()` 有界（发 close → 等 600ms → destroy → 等 400ms → destroyForcibly）、幂等、只通知一次 CLOSED。
- 失败语义逐字映射（parent-ambiguous / parent-invalid / parent-client-mismatch / parent-timeout / nav-done
  false / 进程提前退出 / 父窗消失 / WebView2 失败），全部 `retryable` 且**不跳到外部浏览器**。
- 探针 `harness/regression/cover/VerifyEmbedControllerProbe.java` + `FakeVerifyHost.java`：
  **VERIFY_EMBED_RESULT=185 PASS**，连续 3 次一致（A–J：构造/open 41、关闭 16、rect 15、可见性 16、
  失败映射 44、retry 16、epoch 6、迟到 cookie 10、清理 6）。证据 `build/evidence/batch4/03-regression-batch7.txt`。

### 3. 原生页接线（登录页风控面板）

- `NativeLoginPage.RiskPane` 不再起旧 `VerifyWindow` JDialog：内嵌 `EmbeddedVerifyArea`（400.dp 占位，
  `onGloballyPositioned` 取 `boundsInWindow` → `NativeUiSession.updateEmbeddedVerify` → `setBounds`），
  显示助手状态、失败原因与「重试」按钮；说明「本机 WebView2 渲染，不会打开外部浏览器」。
- 宿主窗口信息由 `NativeUiShell` 用 `LocalAwtWindow.current` 捕获（标题、内容区逻辑尺寸、
  `graphicsConfiguration.defaultTransform.scaleX`；125% 下为 1.25），确保风控由后台 tick 触发时也有父窗信息。
- 可见性规则：只有 `READY` 且页面可见且 `page==LOGIN` 才 `setVisible(true)`；关闭登录、切页、
  停用插件都会先 hide/close（`DisposableEffect` 兜底），网页区域不会残留压住其它面板。
- `parentTitle` 传空串：宿主窗口标题是当前播放曲名、随时会变，拿标题核对会永久错过唯一窗口；
  身份核对退化为「同进程 + 顶层 SunAwtFrame + 唯一」，不唯一时助手拒绝挂载并回报原因。
- 旧 `ui/VerifyWindow` 只被旧 Swing `LoginDialog` 自己的风控路径引用（`LoginDialog.java:1688` 等），
  批次 5 清理旧 UI 时一并删除；原生页已不再调用它。
- 构建：268 个 `.class`、资源 15 个、size **14,384,220 B**、sha256 `64528f65c11b2b95756312f73d0da8e88ad10d3c568fe99aff091b2d200d4ac1`。

### 4. 自动检查（全部 exit 0，证据 `build/evidence/batch4/`）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `build.ps1 -SkipApi` | 268 class / 15 资源 / 自检通过 / 14,384,220 B | `04-build.txt` |
| `verify-spmod` | **77 项 PASS**（新增 P10.39：助手 exe+dll 随包且与资源目录逐字节一致） | `05-verify-spmod.txt` |
| `run-harness` | **PASS = 56 / FAIL = 0** | `06-harness.txt` |
| `run-regression -Batch all` | 批次 1–4 = 12 / 6+7+3 / 9+7 / 4+10；批次 5 = 109+146；批次 6 = 253+156；批次 7 = **185** | `07-regression.txt` |
| `run-behavior` | BEHAVIOR 27 + ASYNC 18 + HOST_TRANSACTION 7 + HOST_PROFILE 1 PASS | `08-behavior.txt` |
| `python -m unittest … test_acceptance` | Ran 5 tests，OK | `09-unittest.txt` |

### 5. 真机状态

本批**未做真机取证**：内嵌验证区域需要真实网易云风控触发，且用户已要求停止鼠标自动化、由用户自测（m01954）。
自动侧已覆盖助手协议 41 条与控制器 185 条；真机项记入下一节待验清单。

## 六、批次 5 已验证事实（旧 UI 清理、门禁改写与 0.12.0 交付）

### 1. 旧实现清理

- 删除 8 个自建 Swing 类：`ui/AccountStatusWindow`、`ui/HostTheme`、`ui/LoginDialog`、`ui/PlaylistWindow`、
  `ui/SearchWindow`、`ui/TableButtons`、`ui/UiRegistry`、`ui/VerifyWindow`。
- 新增 `ui/AccountReport`（纯 Java，不 import AWT/Swing）作为「当前账号」文案唯一真源：
  `NOT_LOGGED_IN`、`FACT_LABELS`、`rows/values/vipText/render/oneLine`；原生账号页改用 `AccountReport.render`。
- `NeteasePlugin` 删除 4 个窗口 import、`UI_AUTOTEST_PLAYLIST_FLAG`/`UI_AUTOTEST_DELAY_MS`/`scheduleUiSelfTest()`；
  `stop()` 不再调 `UiRegistry.disposeAll()`，统一由 `HostUiBridge.uninstall()` → `NativeUiSession.stop()` 收尾。
- 五个配置入口（`openSearch`/`showLoginDialog`/`openLoginDialog`/`openPlaylist`/`currentAccount`）与 `syncNow()`
  只走 `HostUiBridge.openNativePage(...)`；失败时 `nativePageUnavailable(label)` 写 WARN 并 toast，**不再回退旧窗口**。
- 删除后全仓库 `new JFrame/new JDialog/extends JFrame/extends JDialog/JOptionPane/new JWindow` 为 0 处。
- 同步更新 `SmsLogin`/`AccountService`/`DevMode`/`PluginConfig`/`VerifyHostPaths` 与控制器里指向旧窗口的注释。

### 2. 测试与门禁改写

- verify-spmod 76 → **78** 项：`[P10.10]` 改为 14 个新架构关键类；新增 `[P10.40]`「包内没有 8 个旧 Swing 窗口类」。
- `P3Checks`：`[H9.9]` 改为「`currentAccount()` 不抛异常 + 不再创建标题『当前账号』的 AWT Dialog（headless 只 info）+
  `AccountReport.render` 行数与登录态一致」；`[H9.16]` 删除 Swing 组件树检查，改为 `AccountReport` 合同 +
  4 种登录态矩阵 + 8 个旧类 `ClassNotFoundException`。
- 探针：删除 `DialogSkinProbe`/`LayoutDump`/`VerifyEmbedProbe`；`LoginShapeProbe` 保留业务断言（40 PASS），
  「登录对话框形态」5 条改为「旧类不可加载 + `HostUiBridge.openNativePage` 存在 + 登录控制器方法齐全」；
  `DevModeProbe` 账号检查改为 `AccountReport.render` + `HostUiBridge.openNativePage` + 旧类不存在（67 PASS）。

### 3. 构建与包

| 项 | 结果 |
| --- | --- |
| 版本 | `project.json` → 0.12.0；ID/主类/扩展类/权限 `class-transform`/`targetSpwVersion 1.19.0`/`javaRelease 25` 未变 |
| 构建 | `build.ps1 -SkipApi` exit 0：JDK 25 → Gradle → `--package-only`；233 个 `.class`、资源 15 个 |
| 产物 | `build/dist/plugin-com.example.netease-0.12.0.spmod`，14,282,949 B，sha256 `d5e43efaded706b26680d9cb6dce4f3a7857662e7908636acbc79496d81a330a`（两次构建同哈希） |
| 包结构 | 254 条目；`META-INF/MANIFEST.MF` + `classes/**` + `lib/**`；无宿主/API/Kotlin/Compose 类 |

### 4. 自动检查（0.12.0 最终包，证据 `build/evidence/batch5/01–09`）

| # | 检查 | 结果 | 退出码 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 构建（JDK 25 → Gradle → package-only） | 233 class / 15 资源 / 自检通过 | 0 | `01-build.txt` |
| 2 | verify-spmod | 78 项 PASS | 0 | `02-verify-spmod.txt` |
| 3 | harness | PASS 56 / FAIL 0 | 0 | `03-harness.txt` |
| 4 | run-regression -Batch all | 批次 1–4：12 / 6+7+3 / 9+7 / 4+10；批次 5：109+146；批次 6：253+156；批次 7：185 | 0 | `04-regression.txt` |
| 5 | run-behavior | 27 + 18 + 7 + 1 PASS | 0 | `05-behavior.txt` |
| 6 | python unittest | Ran 5 tests OK | 0 | `06-unittest.txt` |
| 7 | VerifyHost 真实协议探测 | 41 PASS | 0 | `07-verifyhost-proto.txt` |
| 8 | LoginShapeProbe（登录请求构造 + 旧类已删） | PASS 40 / FAIL 0 | 0 | `08-login-shape.txt` |
| 9 | DevModeProbe（装机布局 + 档位矩阵 + 旧组件树） | PASS 67 / FAIL 0 / SKIP 0 | 0 | `09-devmode-probe.txt` |

### 5. 双渠道交付材料与版本

- `RELEASE-0.12.0.md`：发布状态、兼容、安装/权限、改动映射、GitHub 发布清单、Steam 上传与同 ID 冲突/切换、
  回退（0.11.59 两个基线包 SHA）、已知问题、12 项验收表、PLAN §12.3 报告表；两渠道同包同 SHA、本轮不做更新器。
- `ACCEPTANCE.md`：改为 0.12.0 的 12 条用户验收清单（0/1/2 结论 + 失败记录 + 回退步骤）。
- `README.md`：安装段改 1.19.0 / `.spmod` / 授权 / 升级先移除同 ID 旧目录；「能做什么」新增界面一条。
- `CHANGELOG.md`：新增 0.12.0 十条。
- 版本按 PLAN §10.3：0.11.60 作为内部验证包承载批次 1–5，对外 0.11.59 → 0.12.0；版本只在 `project.json` 改。

### 6. 真机状态

本批按用户要求不做鼠标自动化取证（m01954）；0.12.0 包交付用户按 `ACCEPTANCE.md` 真机走查。
删除旧窗口后的入口行为、停用后无残留助手进程等 5 条记入下一节待验清单。

自动可证的部分已做：0.12.0 已装入宿主（`workshop/plugins/plugin-com.example.netease-0.12.0`，同 ID 的 0.11.60 目录
归档到 `_archive/`），17:53:30 插件日志显示「插件版本(上下文)=0.12.0 插件版本(MANIFEST)=0.12.0」、
「原生页面控制器已启动（tick=250ms）」、「install 成功…匹配数=1」；安装后 `VerifyHost.exe` 进程为 0。
证据 `build/evidence/batch5/11-install-host.txt`。

## 七、已实现但未验收

1. `tools/jdk.ps1` 的探测顺序与报错信息（自动化路径已实测，异常机器环境未覆盖）。
2. `extract-api.ps1` 的宿主版本/SHA-256 输出与 37 类阈值（本机通过，换宿主版本时只警告不阻断）。
3. 适配层在**其它宿主版本**上的行为（当前只对 1.19.0 记录锚点；换版本应表现为「目标类/方法不存在」并停在不动手状态）。
4. 宿主机浅色模式的截图（本轮宿主处于深色；浅色分支由同一 `isSystemInDarkTheme()` 决定，未单独截图）。
5. 批次 2 页面中**未在真机点过的路径**（自动化已覆盖控制器逻辑，UI 走查留给用户）：歌单页的「我喜欢的音乐 / 每日推荐 / 载入我的歌单 / ID 或链接载入 / 本地歌单载入 / 存为本地歌单 / 整单播放」；
   窄窗（<760dp）自动收起左栏；同步页「立即同步 / 刷新状态」按钮的四种返回（STARTED / BUSY / UNAUTHENTICATED / DISABLED）。
6. 账号 epoch 抖动修正（歌单页首次打开不再误报「账号状态变化」）只有探针断言覆盖，**尚未在真机上复看首开文案**。
7. 播放失败路径的真机文案（取直链失败 / 超时 30 秒）未实测，只在探针里模拟过。
8. 批次 3 原生登录页的**真机流程**（扫码出码/扫码成功跳账号页、短信发送与 60 秒冷却、风控打开验证区域与「我已完成，重试」）未走查；
   验证区域已在批次 4 改为内嵌 WebView2，其真机行为见本节 13–18 条。
9. 原生账号页（切换账号/刷新快照/退出登录二次确认）与登录页「取消登录」的真机行为未验收。
10. 歌单页内联「存为本地歌单 / 取消命名」的真机路径（含默认名占位符）未走查。
11. `SearchController.onAccountChanged()` 的**真实触发**（宿主里真正退出/切换账号时旧搜索被作废）只在探针里模拟过，
    真机需要配合账号切换才能看到；`NativeUiSession` 的 epoch 比对按 250ms 轮询。
12. 宿主配置页四个入口的**回退分支**（原生层未就绪时回退旧 Swing 窗口）未人为制造失败验证。
13. 内嵌验证区域（批次 4）的**真机可见、可操作**：需要在真实网易云风控下触发（扫码/短信登录被风控），
    自动侧只覆盖到助手协议（41 条）与控制器（185 条）；400.dp 占位区在 125% 缩放下与父窗客户区的像素对齐仍需真机确认。
14. 宿主**移动 / 缩放 / 最大化 / 还原 / 最小化 / 跨显示器与缩放变化**时网页区域的跟随：
    rect 由 `onGloballyPositioned` 驱动、逻辑→物理只在控制器换算一次，真机表现待测；层级冲突时「先隐藏网页区域」的行为同待观察。
15. 关闭登录、切页、停用插件时助手的**有界关闭**（600ms + 400ms）与网页消失；离开登录页后迟到回调不得重新显示验证区域。
16. 三类失败在面板内**显示原因并可重试**：助手进程崩溃、父窗口消失、WebView2 不可用（自动侧覆盖协议，真机需人为 kill 助手或禁用 WebView2）。
17. Cookie 初始化与回传在**真实风控流程**中生效（自动侧只验证管道格式、旧 session/旧 epoch 丢弃与日志脱敏）。
18. ~~旧 Swing `LoginDialog` 的风控路径仍使用旧 `VerifyWindow`，两套验证路径并存期间未在真机对比~~ —— **已随批次 5 删除两者，本条作废**。
19. 删除旧窗口后五个入口的**真机行为**：原生层不可用时只出现 toast「原生界面不可用：…」，不会再弹旧窗口；正常路径进原生页。
20. 停用插件后**无残留辅助进程**（任务管理器里没有 `VerifyHost.exe`）与无残留页面：`UiRegistry` 已删，收尾路径改为
    `HostUiBridge.uninstall()` → `NativeUiSession.stop()` → 各控制器 `close()` + `VerifyEmbedController.close()`（600+400ms）。
21. 0.12.0 包在宿主 1.19.0 上的**首次安装/覆盖安装**：工坊显示 0.12.0、权限只有 `class-transform`、启用弹窗勾选后加载。
22. Steam 渠道：同包上传、创意工坊材料、与本地安装同 ID 的冲突与切换步骤未在真实账号下执行（需用户操作并确认协议）。
23. GitHub Release 未发布（发布物、SHA、说明与清单已备在 `RELEASE-0.12.0.md` §5）。


## 八、尚待验证的技术方案

1. **宿主品牌配色**：宿主不使用 `MaterialTheme`、也不暴露配色 CompositionLocal，
   插件页只能跟到系统深/浅色。后续若需要品牌色，只能解析宿主设置库（`spw.db`）或宿主自绘常量，属方案待定。
2. **适配层锚点随宿主升级的稳定性**：1.19.0 已确认；1.20+ 需要重新核对锚点并更新适配层常量。

## 九、明确阻塞项

1. ~~批次 1 硬关卡：最小原生 Compose 页面须用户真机确认~~ **已解除**：
   用户在宿主「配置」页确认「显示在主窗口内 / 关闭后宿主配置恢复可点 / 右下角入口可重开」三项均正常，
   批次 1 记为 0（通过），可进入批次 2。
2. 网络限制：GitHub、jitpack.io 不可达（Gradle 发行版走腾讯镜像，依赖走 Maven Central），
   因此官方 Workshop Gradle 插件不可用；本批继续使用自有 Python 打包器。
3. 批次 2 **无新增阻塞**：控制器语义由离线探针证明，页面已装到宿主 0.11.60 供用户自测；
   本批交付不依赖用户当次确认（不像批次 1 的硬关卡），用户发现的问题记入下一轮修正。
4. 批次 3 **无新增阻塞**：登录/账号/本地命名控制器均有离线探针（253/156 条断言），构建与全部自动检查 exit 0；
   本批未做新的真机取证（按用户要求停止鼠标自动化），真机项记入上一节待验清单。
5. 批次 4 **无新增阻塞**：助手协议 41 条、控制器 185 条探针全过，构建与全部自动检查 exit 0；
   但本批有**技术关卡**——内嵌验证区域必须由用户在真实风控下确认「网页在主窗口内可见可操作」，
   未确认前批次 4 保持状态 2（交付候选）。
6. 批次 5 **无新增阻塞**：0.12.0 最终包构建与 9 项自动检查全部 exit 0。
   但按用户「按文档全部干完」的指示（m01954）先完成了代码清理与交付材料，因此：
   - 批次 4 的 WebView2 技术关卡**仍未闭合**，批次 4/5 都记状态 2（交付候选），不标「全部验收通过」；
   - 0.12.0 是否可以对外发布，取决于 `ACCEPTANCE.md` 12 条在用户真机上的结论与 `RELEASE-0.12.0.md` §1 的剩余两项。

## 十、复跑方式

在工程根目录（批次 5 起包名为 `plugin-com.example.netease-0.12.0.spmod`）：

    pwsh -NoProfile -File tools/extract-api.ps1 -Force      # 需要时刷新宿主 API 并核对 37 类/SHA-256
    pwsh -NoProfile -File build.ps1                         # JDK25 → Gradle → --package-only
    pwsh -NoProfile -File tools/verify-spmod.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod
    pwsh -NoProfile -File harness/run-harness.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod
    pwsh -NoProfile -File harness/run-regression.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod
    pwsh -NoProfile -File harness/run-regression.ps1 -Batch 5 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod   # 搜索/歌单控制器探针
    pwsh -NoProfile -File harness/run-regression.ps1 -Batch 6 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod   # 登录/本地命名控制器探针
    pwsh -NoProfile -File harness/run-regression.ps1 -Batch 7 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod   # 内嵌验证控制器探针（185 条）
    pwsh -NoProfile -File harness/run-verifyhost-probe.ps1 -Out build/evidence/batch5/07-verifyhost-proto.txt  # 真实助手协议（41 条）

`run-regression.ps1` 的 `-Batch` 取值 1–7 或 all；批次 5 运行 `SearchControllerProbe`（**109** 条断言）
与 `PlaylistControllerProbe`（146 条断言 + PlaylistIds 10 条等价性）；
批次 6 运行 `LoginControllerProbe`（253 条断言）与 `LocalSaveControllerProbe`（156 条断言）；
批次 7 运行 `VerifyEmbedControllerProbe` + `FakeVerifyHost`（**185** 条断言，假助手，不需要桌面）。

助手本体（C#）重建与验真：

    pwsh -NoProfile -File tools/verify-host/build.ps1   # 先发布到临时目录→自检→成功才替换 src/main/resources/verify-host

    pwsh -NoProfile -File harness/run-behavior.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.0.spmod
    python -m unittest discover -s harness/regression -p test_acceptance.py -v

旧窗口清理后的两条离线探针（0.12.0 复跑：LoginShapeProbe 40 PASS、DevModeProbe 67 PASS）：

    # 编译到临时目录（classpath 用构建产物 + 宿主 jar）：
    javac --release 25 -encoding UTF-8 -cp "build/plugin-classes;tools/.cache/spw-workshop-api-host.jar;tools/.cache/pf4j-3.12.0.jar;tools/.cache/kotlin-stdlib-host.jar" -d build/_tmp/probes tools/smoke/LoginShapeProbe.java tools/smoke/DevModeProbe.java
    # LoginShapeProbe 自己建临时数据目录，直接运行：
    java -cp "build/_tmp/probes;build/plugin-classes;tools/.cache/*" com.example.netease.net.LoginShapeProbe
    # DevModeProbe 要求「沙箱 classes 目录」本身是 classpath 第一项（= 真机 classpath 根），探针类不能放进去：
    java -cp "build/devmode-probe/plugin-com.example.netease-0.11.35/classes;build/_tmp/probes;build/plugin-classes;tools/.cache/*" com.example.netease.core.DevModeProbe build/devmode-probe src/main/resources

每条命令单独检查退出码；证据写 `build/evidence/batchN/`（0.12.0 为 `batch5/01–09`），不入库。

真机验收（本批新增工具，均只在本机使用、不进包）：

    pwsh -NoProfile -File tools/host-restart.ps1 -Install   # 停宿主→校验 .spmod→替换目录→重启
    pwsh -NoProfile -File harness/shot-window.ps1 -Out build/evidence/batch1/x.png
    pwsh -NoProfile -File tools/host-click.ps1 -X <截图X> -Y <截图Y>   # 125% 缩放下的坐标换算点击

内嵌验证区域真机复核要点：登录页进入风控后网页应出现在「验证区域」占位框内（不弹外部浏览器），
拖动/缩放宿主窗口后网页随之移动且不错位，关闭登录后网页立即消失；失败时面板内显示原因与「重试」。

注意：宿主为 Compose/AWT 自绘，UIA 拿不到按钮，只能截图 + 坐标点击；
截图坐标 = 窗口左上为原点，点击换算 `virtual = (windowRect.Left + X) * 1.25`。
按钮位置建议用像素颜色扫描确定，不要凭缩略图估算（会偏 40–60 px）。

## 十一、回退

本批新增 Gradle 构建链与 Kotlin/Compose 源码，但**未改**插件 ID、主类、扩展类、包内布局与数据格式。
回退即：`git revert` 本批提交（恢复 `build.ps1`/`tools/build.py` 的纯 javac 路径），
并用 `build/baseline/0.11.59/` 里的 0.11.59 包重新安装。
运行时副作用：插件停用/卸载时会执行 `HostUiBridge.uninstall()`；若进程被强杀，Hook 随宿主进程消失，无残留。
批次 4 另含 C# 助手二进制：`src/main/resources/verify-host/` 由 `tools/verify-host/build.ps1` 从源码重建，
回退时随提交一并 revert 即可恢复旧助手。
批次 5 删除了 8 个旧窗口类与 `UiRegistry`；`git revert <批次 5 提交>`（或回退到 `34e2682`）即恢复全部旧窗口、旧探针与旧入口回退分支。
回退 0.12.0 包本身按 `RELEASE-0.12.0.md` §7：恢复 `build/baseline/0.11.59/installed.zip`，保留数据目录（无数据迁移）。

## 十二、本批提交

- 批次 0 独立提交：`f1bd594 0.12.0 批次 0：建立 JDK 25 可复现基线与宿主 1.19 API 核对`。
- 批次 1 独立提交：`git log --grep='批次 1'`（Gradle 构建链 + 原生 UI 适配层 + 最小页面）。
- 批次 2 独立提交：`git log --grep='批次 2'`（搜索/歌单/同步控制器 + 原生搜索/歌单/同步页 + 批次 5 探针）。
- 批次 3 独立提交：`git log --grep='批次 3'`（登录/账号/本地命名控制器 + 原生登录/账号页 + 账号变化失效 + 批次 6 探针）。
- 批次 4 独立提交：`git log --grep='批次 4'`（WebView2 内嵌验证：助手严格挂载与协议 41 条、控制器与批次 7 探针 185 条、内嵌验证面板接线、打包自检 77 项）。
- 批次 5 独立提交：`git log --grep='批次 5'`（删除 8 个旧窗口类与 UiRegistry、新增 AccountReport、五入口不回退、
  门禁/探针改写、0.12.0 版本与包、RELEASE/ACCEPTANCE/README/CHANGELOG 与本节记录）。
  0.12.0 包：`build/dist/plugin-com.example.netease-0.12.0.spmod`，14,282,949 B，sha256 `d5e43efa…a330a`。
  上述提交只含源码、脚本与本记录；不含 `build/`、`docs/`、日志、截图、真实数据。
- 之后批次各自独立提交；`docs/`、`build/` 不入库。
