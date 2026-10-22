# SPW 网易云音乐接入

Salt Player for Windows 的第三方插件，把网易云音乐接进本地播放器：在线播放、歌词、封面。

非官方项目，跟 Salt Player 和网易云音乐都没有关系。只建议个人使用，别拿它批量抓曲库、也别拿它绕会员限制。

## 安装

需要 Salt Player for Windows 1.19.0（Steam 版亦可）。在工坊 / Mod 管理里导入 `plugin-com.example.netease-0.12.0.spmod`，启用时勾选「允许修改应用行为与界面」（`class-transform` 权限），按提示重启播放器。升级前先移除同 ID 的旧版本目录，数据目录不用动。

## 能做什么

- 播放：搜索、在线播放网易云曲目，交给宿主自己的播放链路
- 歌词：本地曲目匹配网易云的逐行歌词，带翻译和罗马音
- 封面：列表、播放条、迷你播放器都用网易云的图
- 歌单：同步自己的歌单，可以和本地歌单合并显示
- 账号：手机扫码或短信验证码登录，凭据加密存放在本机
- 界面：搜索、歌单、登录、账号都在宿主主窗口内打开，跟随宿主深浅色，不再弹独立窗口

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
    python -m unittest discover -s harness/regression -p test_acceptance.py -v

`run-regression.ps1 -Batch 4 -Spmod <旧包路径>` 可运行封面负缓存的反向对照；
旧包应检出缺陷并返回非零。所有行为探针使用独立沙箱，不访问真实曲库。

真机验收先退出宿主，运行 `tools/accept-host.ps1 -Phase Pre`；操作后以相同的
`-EvidenceDir` 和 `-DevMode Off`（或 `On`）运行 `-Phase Post`。
退出码 0 表示通过、1 表示失败、2 表示仍有未验证项。声音和画面需要另附实际证据。
原始日志、数据库、凭据、截图及生成的证据不进入仓库。

当前迭代的改动、自动检查结果和待验项目见 [迭代记录](CHANGELOG.md) 与 [验收清单](ACCEPTANCE.md)。

## 目录

    src/main/java        插件源码
    src/main/resources   打进包里的资源
    libs                 运行期依赖
    tools                构建和自检脚本
    harness              离线测试脚手架

## 许可

MIT，见 LICENSE。
