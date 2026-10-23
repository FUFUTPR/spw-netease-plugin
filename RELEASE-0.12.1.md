# 0.12.1：直接复用椒盐 UI

> 历史版本：当前页面/弹窗区分、主题跟随、入口修复及安装验收请以 [0.12.4](RELEASE-0.12.4.md) 为准。

本版把 0.12.0 的 Material3 页面替换为椒盐自身的组件。基线是本机 Steam 版 Salt Player for Windows 1.19.0，JDK 25。Java 业务代码和 `NativeUiSession.kt` 未改动。

## 接入依据

对实际安装文件 `C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll`（ZIP/JAR 内容）检查了 Kotlin metadata、SMAP、Compose trace、方法签名与字节码，而非凭外观猜测。上游 [SaltUI](https://github.com/Moriafly/SaltUI) 只用于确认组件来源，实际 ABI 以已安装宿主为准。

宿主混淆了组件包名，所以插件通过 `HostSaltComponents.kt` 按完整参数类型精确匹配方法，直接传入当前宿主 Composer，沿用同一套 CompositionLocals。不额外打包或复制 Salt UI、Kotlin、Compose，也没有 Material3 主题映射或替代页。

| 用途 | 宿主实际组件 | 1.19.0 运行类 |
|---|---|---|
| 按钮 | `com.xuncorp.voxzen.uikit.Button` | `androidx.compose.ui.\u0168` |
| 文本 | Salt `Text` | `androidx.compose.ui.\u0622` |
| 输入、设置条目 | Salt `ItemEdit`、`Item` | `androidx.compose.ui.\u0824` |
| 曲目行 | 播放器 `TrackItem` | `androidx.compose.ui.\u0994` |
| 内容表面 | Salt `Surface` | `androidx.compose.ui.\u02e0` |
| 弹窗框架、标题与按钮栏 | 宿主 `SpwBasicDialog` / `ColumnDialog` 内容 | `androidx.compose.ui.\u0675` |
| 主题 | 宿主 SaltTheme locals | `androidx.compose.ui.\u02b6` |

按钮特意使用 **Voxzen uikit 封装**：它在 Salt BasicButton 上指定播放器的小圆角、细边框等规则。直接调用 Salt 的默认 Button 会使用另一种胶囊外形，因此不能把“来自 SaltUI”就当作“与椒盐当前按钮相同”。

`HostUiAdapter.install()` 在注册 Hook 前验证组件及主题 ABI；缺失或歧义会停止挂载并通过既有日志报告。不提供 Material3 回退。混淆名是内部接口，升级椒盐后必须重新核对、构建与渲染验证，不能承诺未来宿主版本兼容。

## 行为保留

- 搜索的来源/结果同步、超时、失败保留旧列表、重试、键盘焦点与 ↑/↓/Enter 都继续由既有控制器处理。
- 曲目映射成仅供显示的宿主 `Track`，不插入数据库。原生曲目行的本地专辑导航被覆盖层隔离：搜索继续单击选择、双击播放；歌单继续单击从当前曲目开始。无版权警示和会员提示保留。
- 歌单来源、同步、账号状态、本地保存、登录和验证码冷却继续读原状态。确认保存仍走 `submitLocalSave()`，在确认时原子捕获当前可见列表；取消不保存。
- 登录、账号和本地命名复用宿主弹窗内容，显示在当前主窗口中；没有新增 AWT Dialog，因此验证助手的父窗口与坐标契约保留。滚动后验证占位完全不可见时隐藏原生子窗口。
- 弹窗支持 Esc，Tab 焦点限制在弹窗内；宽窄操作栏可换行。首页使用宿主 Item 列表，诊断入口只在开发者模式显示。

## 自动检查

```powershell
pwsh -NoProfile -File build.ps1 -SkipApi
pwsh -NoProfile -File tools/verify-spmod.ps1
pwsh -NoProfile -File harness/run-harness.ps1
pwsh -NoProfile -File harness/run-regression.ps1
pwsh -NoProfile -File harness/run-behavior.ps1
python -m unittest discover -s harness/regression -p test_acceptance.py -v
pwsh -NoProfile -File harness/run-native-ui.ps1
```

新增的 `run-native-ui.ps1` 从实际 `.spmod` 提取类，在独立数据目录中使用已安装椒盐的真实组件与 Skia/Compose 渲染器，输出 800dp / 480dp 的首页、搜索、歌单、扫码、短信、验证、账号、同步以及本地命名画面。网络、账号及最终播放端口为内存夹具，不访问真实账号、不起播、不安装插件。

自动检查还覆盖：包内没有 Material3 引用和宿主运行时副本；原生输入框改值回写；搜索框回车只搜索；方向键转移焦点并选择；结果区回车播放；曲目整行双击；本地命名取消保留歌单页；登录弹窗 Esc 收尾；短信输入框的 Tab 到达性。现有控制器/会话/锁序/封面/缓存/事务等回归继续保留。

渲染测试通过表示本机宿主组件能画出页面、这些夹具交互正常；不等于真实账号登录、WebView2 风控、声音、真实曲库写入或安装后的 Hook 链路已由用户确认。本次最终包 SHA-256：`8c9284021434ca4b73c31e282988766866bc84058c11b4e6a521821aa8fc26fc`（14,345,683 bytes）。包检查 78 PASS，加载 56 PASS，回归批次 1–9、行为与 unittest 全部通过；真实宿主组件宽窄渲染与交互 90 项通过，输出 18 张图片。最终机器结果和本机证据目录见 `build/evidence/host-salt-review/RESULT.md`（生成文件，不入库）。

## 安装与真机验收

安装包：`build/dist/plugin-com.example.netease-0.12.1.spmod`。

1. 完全退出椒盐，保留现有数据目录；将同 ID 的旧插件目录移出 `workshop/plugins` 归档，避免 PF4J 扫到旧版。
2. 通过椒盐工坊 / Mod 管理导入新包并启用。原有 `class-transform` 权限不变。
3. 重启后确认插件版本 **0.12.1**，进入本插件配置页；对照宿主自己的设置项、按钮、曲目行及弹窗检查画面。
4. 按根目录 `ACCEPTANCE.md` 检查深浅色、缩放、真实播放、登录/风控、验证区域滚动对齐和本地保存。

也可在退出播放器后执行：

```powershell
pwsh -NoProfile -File tools/install-plugin.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.1.spmod -Force
```

该命令把同 ID 旧目录归档，不删除账号/缓存/数据库，不修改 `enabled.txt`。按此前约定，实际播放器操作、听音与画面确认由用户进行；本次没有替用户重启播放器、安装或提交登录。
