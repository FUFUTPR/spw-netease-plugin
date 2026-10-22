# 0.12.0 迭代记录

本文件随源码交付（`docs/` 被忽略，故正式记录放仓库根）。依据 `docs/PLAN.md` 分批推进；
自动检查通过不代表真机验收完成，未真机确认的项目一律标 `2`（待验），不写成通过。

状态码：`0` 通过 / `1` 失败 / `2` 待验项。

## 批次进度

| 批次 | 内容 | 状态 | 说明 |
| --- | --- | --- | --- |
| 0 | 建立可复现基线 | 0 | 见下「已验证事实」 |
| 1 | 构建迁移 + 最小原生 Compose 页面 | 2 | **硬关卡**：需用户真机确认最小页面 |
| 2 | 业务控制器抽取、搜索与歌单迁移 | — | 未开始 |
| 3 | 登录、账号及附属界面迁移 | — | 未开始 |
| 4 | WebView2 验证区域改造 | — | 未开始 |
| 5 | 旧 UI 清理与双渠道交付 | — | 未开始 |

---

## 一、已验证事实（本机实测，可复跑）

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

## 二、已实现但未验收

本批不改业务行为，用户可见功能无新增。以下为「已写进仓库、尚未真机确认」的部分：

1. `tools/jdk.ps1` 的探测顺序与报错信息（自动化路径已实测，异常机器环境未覆盖）。
2. `extract-api.ps1` 的宿主版本/SHA-256 输出与 37 类阈值（本机通过，换宿主版本时只警告不阻断）。
3. README 的 JDK 25 说明（文档改动，无运行验收项）。

## 三、尚待验证的技术方案

1. **Gradle + Compose 构建链（批次 1）**：本机无 Gradle、GitHub/jitpack 不可达；Maven Central 可用。
   需确认 Gradle 发行版、Kotlin、Compose 编译器插件与宿主 Compose 运行时的版本组合，
   尤其是 Kotlin 版本必须能产出宿主可加载的 Compose 代码。
2. **最小原生 Compose 页面挂载**：`replaceComposable` / `afterComposable` / `appendContent` 的
   真实行为（哪个入口点、是否需要 `CLASS_TRANSFORM` 授权提示）尚未在 1.19.0 真机验证。
3. **混淆锚点稳定性**：1.18.5 → 1.19.0 的宿主类名/参数名已发生变化，批次 1 需要运行期确认实际锚点，
   不能硬编码旧版本字符串。
4. **插件授权维度**：`PluginPermission.CLASS_TRANSFORM` 在 `plugin.json`/宿主设置里的授予方式未知
   （当前 `project.json` 的 `permissions` 为空数组）。

## 四、明确阻塞项

1. 批次 1 是硬关卡：**最小原生 Compose 页面必须由用户真机确认**后才能进入批次 2
   （PLAN 第 767 行：「最小原生页面尚未取得用户真机确认时，停在批次 1 交付验证包和复现说明」）。
2. 网络限制：GitHub、jitpack.io 不可达，仅 Maven Central 可用；若 Gradle 发行版或 Compose 依赖
   只能在 GitHub 获取，需要先解决下载渠道（或改用本机缓存）。

## 五、复跑方式（批次 0 基线）

在工程根目录：

    pwsh -NoProfile -File tools/extract-api.ps1 -Force      # 需要时刷新宿主 API 并核对来源
    pwsh -NoProfile -File build.ps1 -SkipApi
    pwsh -NoProfile -File tools/verify-spmod.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.59.spmod
    pwsh -NoProfile -File harness/run-harness.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.59.spmod
    pwsh -NoProfile -File harness/run-regression.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.59.spmod
    pwsh -NoProfile -File harness/run-behavior.ps1 -Spmod build/dist/plugin-com.example.netease-0.11.59.spmod
    python -m unittest discover -s harness/regression -p test_acceptance.py -v

每条命令单独检查退出码；证据写 `build/evidence/batch0/`，不入库。

## 六、回退

本批只有构建/测试脚本与文档改动，无数据格式、配置键或插件 ID 变化。
回退即恢复上一版工具链脚本；已归档的 0.11.59 包在 `build/baseline/0.11.59/`。

## 七、本批提交

批次 0 独立提交（见 `git log` 中 `批次 0` 条目）。之后批次各自独立提交。
