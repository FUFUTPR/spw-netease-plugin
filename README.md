# SPW 网易云音乐接入

Salt Player for Windows 的第三方插件，把网易云音乐接进本地播放器：在线播放、歌词、封面。

非官方项目，跟 Salt Player 和网易云音乐都没有关系。只建议个人使用，别拿它批量抓曲库、也别拿它绕会员限制。

## 安装

支持 Salt Player for Windows 1.19.0 / 1.19.1 / 1.19.2（Steam 版亦可）。在工坊 / Mod 管理里导入 `plugin-com.example.netease-0.12.13.spmod`，首次启用时勾选「允许修改应用行为与界面」（`class-transform` 权限）。日常升级直接在椒盐内导入新版，无需关闭应用或手动搬目录；插件自身在停用及卸载路径自动释放 SQLite 资源归档句柄，数据目录保留。

早期 0.12.4 曾复用版本号交付多个构建，其中一份没有资源收尾及 1.19.1 UI 适配。自 0.12.5 起使用独立版本交付；安装后可运行 `pwsh -NoProfile -File tools/verify-installed.ps1` 比较实际安装文件与交付包，不能只看版本文字。若从旧版迁移仍遇占用，可在线运行一次 `tools/prepare-live-upgrade.ps1`；新版本日常升级、卸载不依赖该工具。详见 [0.12.5 说明](RELEASE-0.12.5.md)。

0.12.5 真实界面检查还发现宿主 1.19.1 的歌单关联模型变化导致同步失败。0.12.6 增加兼容并收敛布局，检查范围及安装状态见 [0.12.6 说明](RELEASE-0.12.6.md)。

0.12.7 修正账号正文黑底、短信输入框与登录居中、弹窗 Esc、邻曲关联及搜索播放封面映射。自动检查已通过，实际安装及播放条／播放页显示须独立确认，见 [0.12.7 说明](RELEASE-0.12.7.md)。

0.12.10 修正单击选中延迟、重复起播及同曲循环封面去重，加入椒盐原生右键菜单，可添加到网易云喜欢列表和自己的歌单。搜索提交／候选播放后清空输入，重进配置不再保留搜索页面；“音乐”栏提供默认开启的搜索框开关，“歌单与同步状态”只在开发者模式中显示。验证范围与安装边界见 [0.12.10 说明](RELEASE-0.12.10.md)。

0.12.11 修正双击不自动起播、歌单添加后封面等待和同曲图片源丢失；云端成功后立即增量刷新本地歌单。宿主歌单、全部歌曲和播放条的云歌曲右键增加云歌单增删，移除前内嵌确认，并隐藏无效文件操作。顶栏加入伸缩、圆角和候选动效，点击外部可直接失焦。交付与验证范围见 [0.12.11 说明](RELEASE-0.12.11.md)。

0.12.12 修正 0.12.11 的宿主歌单移除确认框因缺少 PopupHazeState 而崩溃，沿用宿主实际材质状态。旧包复现及修正版检查见 [0.12.12 说明](RELEASE-0.12.12.md)。

0.12.13 修复停用后重新启用时播放、流服务及同步无法恢复，云歌单修改超时／中断后的迟到写入，以及添加期间切换账号造成的菜单忙碌状态无法恢复。发布前审查与验证边界见 [0.12.13 说明](RELEASE-0.12.13.md)。

## 能做什么

- 播放：搜索、在线播放网易云曲目，交给宿主自己的播放链路
- 歌词：本地曲目匹配网易云的逐行歌词，带翻译和罗马音
- 封面：列表、播放条、迷你播放器都用网易云的图
- 歌单：同步自己的歌单，可以和本地歌单合并显示
- 账号：手机扫码或短信验证码登录，凭据加密存放在本机
- 界面：搜索、歌单与同步状态使用主窗口内嵌页面；登录、账号、本地命名等使用主窗口内嵌弹窗。直接复用椒盐自身组件，跟随宿主主题

## 自己编译

需要 **JDK 25** 和 Salt Player for Windows 1.19.0（其 API 是 Java 25 字节码，JDK 21 的 javac 读不了）。

`tools/jdk.ps1` 会自动探测：`SPW_JDK_HOME` → `JAVA_HOME` → `%ProgramFiles%\Java`、
Eclipse Adoptium、`%USERPROFILE%\tools\jdk25` 等位置；都找不到且 `PATH` 上的 javac 低于 25 时会
直接报错，不会悄悄退回 JDK 21。

    pwsh -File build.ps1

构建分三段：抽取宿主 API → Gradle Wrapper 编译 Java + Kotlin + Compose → Python 打包 `.spmod`。
Gradle 发行版固定为 9.2.1 并做了 Wrapper 校验值校验（`gradle/wrapper/gradle-wrapper.properties`）；
本机没有 Gradle 也能跑，Wrapper 会自己下载。Java 与 Kotlin 产物都落在 `build/plugin-classes`，
Python 侧只做组装与自检（`tools/build.py --package-only --classes-dir build/plugin-classes`）。

`gradle.properties` 里已固定 UTF-8 与 JDK 25 探测器（`JAVA_HOME` / `SPW_JDK_HOME`）。
若网络访问不了 Gradle 官方发行版与 Maven Central，需要先改 Wrapper 的 `distributionUrl` 或加镜像；
`settings.gradle.kts` 的仓库顺序是 Maven Central、Google Maven。

产物在 build/dist/ 下。

## 开发验证

    pwsh -File tools/verify-spmod.ps1
    pwsh -File harness/run-harness.ps1
    pwsh -File harness/run-regression.ps1
    pwsh -File harness/run-behavior.ps1
    pwsh -File harness/run-playback.ps1
    pwsh -File harness/run-cloud-edit.ps1
    pwsh -File harness/run-playlist-schema.ps1
    pwsh -File harness/run-native-ui.ps1
    python -m unittest discover -s harness/regression -p test_acceptance.py -v

`run-regression.ps1 -Batch 4 -Spmod <旧包路径>` 可运行封面负缓存的反向对照；
旧包应检出缺陷并返回非零。所有行为探针使用独立沙箱，不访问真实曲库。

真机验收先退出宿主，运行 `tools/accept-host.ps1 -Phase Pre`；操作后以相同的
`-EvidenceDir` 和 `-DevMode Off`（或 `On`）运行 `-Phase Post`。
退出码 0 表示通过、1 表示失败、2 表示仍有未验证项。声音和画面需要另附实际证据。
原始日志、数据库、凭据、截图及生成的证据不进入仓库。

宿主组件复用的 ABI、验证方式与兼容边界见 [0.12.2 UI 接入说明](RELEASE-0.12.2.md)。

当前迭代的改动、自动检查结果和待验项目见 [迭代记录](CHANGELOG.md) 与 [验收清单](ACCEPTANCE.md)。

## 目录

    src/main/java        插件源码
    src/main/resources   打进包里的资源
    libs                 运行期依赖
    tools                构建和自检脚本
    harness              离线测试脚手架

## 许可

MIT，见 LICENSE。
