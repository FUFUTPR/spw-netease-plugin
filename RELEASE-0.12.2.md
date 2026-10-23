# 0.12.2：页内嵌入与椒盐主题跟随

已知故障：本版安装后页面入口不显示。以下记录描述实现意图和离屏结果，未覆盖真实 Hook 转换器的默认参数分支，不能作为实际挂载成功的结论。请使用 [0.12.3 修复版](RELEASE-0.12.3.md)。

本版纠正 0.12.1 的实现方向：**同一主窗口里的遮罩弹窗，不等于页面内容区内嵌。** 登录和账号现在作为椒盐内容区中的页面切换，本地命名在歌单内展开；删除了遮罩、弹窗绘制和整页实色背景。支持基线仍为本机 Salt Player for Windows 1.19.0。

## 页面怎样嵌入

| 状态 | 实际呈现 |
|---|---|
| 登录、账号 | 在当前内容区切换；使用宿主页头、返回和内容布局 |
| 本地命名 | 在歌单的输入区下面展开；当前歌单与其它操作仍在页面中 |
| 返回配置 | 恢复宿主原配置页，并保留原生入口按钮 |
| 其它插件配置 | 原宿主页面照常绘制，不注入网易云入口或内容 |

实现使用宿主 `BasicScreenBox`（`androidx.compose.ui.\u03b4`）和 `ItemOuter`（`androidx.compose.ui.\u02bf`），页头、间距、分组和材质都由宿主组件负责。按钮、输入、曲目行继续直接调用宿主自身组件，没有 Material3。

`HostUiAdapter` 使用 `replaceComposable` 精确替换宿主导航中调用 ModConfigScreen 的一个调用点，仍按 `ModConfigContent.pluginId` 限定本插件。原配置方法本身没有被替换：其它插件、或本插件内容收起时，`HostConfigContent` 直接调用原方法，保留传入路由与 ViewModel；默认 ViewModel 调用沿用已核对的 mask=2，显式 ViewModel 使用 mask=0。

这样，打开插件内容时，底下没有仍在绘制的配置列表；不需要再用一个不透明色块盖住它。窗口标题栏、侧栏、播放条、背景与壁纸仍由椒盐原来的布局负责。没有新的窗口、模态浮层或独立主题。

## 不同主题怎样跟随

从安装版的 AppearanceScreen / AppTheme / AppConfig 核实到：Windows 的窗口样式包括 **None、WindowsAcrylic、WindowsMica、WindowsMicaAlt**，深浅色另外由宿主 LightDarkTheme 控制，壁纸还可选择 None / BlurryGlass / Acrylic / Mica / Premium 材质。MacOSVibrancy 是另一平台选项，不作为 Windows 支持结论。

插件继续消费当前宿主 AppTheme 提供的颜色、字体、形状和材质 CompositionLocals；不以系统深色检测替代宿主主题偏好，不自己生成另一套主题。页根和未选中的曲目行不再刷不透明背景，分组交给宿主 ItemOuter，所以 Mica、透明与壁纸效果不会被插件整页遮住。

主题检查现在实际调用**安装版 AppTheme**，并在同一 Compose scene 中修改宿主 `setSpwWindowStyle` / `setLightDarkTheme` / 壁纸材质偏好，检查画面随之重新绘制。没有用插件自造的浅色/深色配色充当主题验证。

## 业务边界

生产 Java 业务代码和 `NativeUiSession.kt` 均未修改。搜索、播放、锁序、最新请求优先、登录成功判据、账号恢复、同步、保存快照、封面、歌词及缓存继续使用原实现。

登录页的返回或 Esc 仍关闭本次流程，取消不退出账号；本地命名的取消或 Esc 只收起输入区。原生输入框保留 Tab / Shift+Tab 导航。验证网页仍是同一主窗口中的 WebView2 子窗口，继续按页内坐标定位，完全滚出可见范围时隐藏；没有改变验证协议和父窗口。

## 自动验证与限度

```powershell
pwsh -NoProfile -File build.ps1 -SkipApi
pwsh -NoProfile -File tools/verify-spmod.ps1
pwsh -NoProfile -File harness/run-harness.ps1
pwsh -NoProfile -File harness/run-regression.ps1
pwsh -NoProfile -File harness/run-behavior.ps1
python -m unittest discover -s harness/regression -p test_acceptance.py -v
pwsh -NoProfile -File harness/run-native-ui.ps1
```

新增/修正验证覆盖：

- 对安装包检查：禁止模态 UI、Material3 引用及宿主运行时副本。
- 800dp / 480dp 下首页、搜索、歌单、登录、短信、验证、账号、同步及命名区；检查输入、键盘、整行双击、取消保存和页面返回。
- PNG 边界 alpha 检查：内容页与展开的命名区不绘制覆盖宿主的整页背景/暗色遮罩。
- 4 种 Windows 样式 × 深浅色在同一 scene 中动态切换，并检查实际 UI 图像变化；再检查 5 种壁纸材质下的内容与透明边界。
- 直接调用生产 PanelHook：其它插件保留原配置、本插件打开时替换原内容、返回配置恢复、路由及默认/显式 ViewModel 参数保持。
- 既有控制器、会话、挂载、颜色位模式、封面、缓存、事务与生命周期回归。

运行于独立 APPDATA / LOCALAPPDATA / user.home；账号、网络和最终播放端口为测试夹具。离屏验证不会启动、安装或接管真实播放器，也没有登录或听音。

**离屏图展示的是页内容，不是完整播放器窗口截图。** 主题测试证明实际 AppTheme 和组件能随偏好更新、插件未遮挡背景；Windows 的最终 Acrylic/Mica 合成、实际安装 Hook、真实登录、风控与声音仍需要在播放器中按 `ACCEPTANCE.md` 确认，不能把这些 PNG 当成已完成真机验收。

本次包结构 78 PASS、加载 56 PASS、业务回归批次 1–9 与行为/unittest 均通过；页内交互 108、主题/壁纸材质 31、配置替换/恢复 9 项通过，共 148 项 UI 检查。

最终检查日志及汇总放在本机 `build/evidence/inline-theme-review/`；图像、语义树和主题矩阵放在 `build/evidence/inline-theme-verified/`（均不入库）。

## 安装

产物：`build/dist/plugin-com.example.netease-0.12.2.spmod`，14,348,248 bytes。

SHA-256：`d8cb0e79742319da79444d27a8e3cc28478584d5153ef528714cddc93c51768f`。

完全退出椒盐后，用工坊 / Mod 管理导入新包；同 ID 的旧目录先移出活动插件目录归档，保留数据目录。权限仍只有 `class-transform`。也可使用会自动归档旧目录的安装脚本：

```powershell
pwsh -NoProfile -File tools/install-plugin.ps1 -Spmod build/dist/plugin-com.example.netease-0.12.2.spmod -Force
```

重启确认版本 0.12.2，再检查页内呈现、主题切换及原配置恢复。沿用此前由用户进行真实播放器操作的约定，本次没有替用户重启、安装或提交登录。
