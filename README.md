# SPW 网易云音乐接入

Salt Player for Windows 的网易云音乐插件。

## 功能

- 搜索与在线播放，音频走宿主播放链路
- 本地曲目匹配网易云逐行歌词，含翻译与罗马音
- 列表、播放条、迷你播放器使用网易云封面
- 同步网易云歌单，可与本地歌单合并显示
- 扫码或短信验证码登录，凭据加密存储于本机
- 界面为主窗口内原生页面，跟随宿主主题

非官方项目，与 Salt Player、网易云音乐无关。安装包见 Releases。

## 构建

环境：JDK 25、Python 3。宿主 API 为 Java 25 字节码，JDK 21 无法编译。

    pwsh -File build.ps1

JDK 探测顺序：`SPW_JDK_HOME`、`JAVA_HOME`、`%ProgramFiles%\Java`、Eclipse Adoptium、`%USERPROFILE%\tools\jdk25`。均未找到且 `PATH` 中 javac 版本低于 25 时，构建中止。

Gradle 9.2.1，由 Wrapper 下载，本机无需预装。无法访问 Gradle 官方源或 Maven Central 时，修改 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`，或配置镜像。

## 许可

MIT，见 LICENSE。
