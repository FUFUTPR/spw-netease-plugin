# SPW 网易云音乐接入

Salt Player for Windows 的第三方插件，把网易云音乐接进本地播放器：在线播放、歌词、封面。

非官方项目，跟 Salt Player 和网易云音乐都没有关系。只建议个人使用，别拿它批量抓曲库、也别拿它绕会员限制。

## 安装

在 Salt Player for Windows 的设置里导入 zip 就行，导入完按提示重启播放器。

## 能做什么

- 播放：搜索、在线播放网易云曲目，交给宿主自己的播放链路
- 歌词：本地曲目匹配网易云的逐行歌词，带翻译和罗马音
- 封面：列表、播放条、迷你播放器都用网易云的图
- 歌单：同步自己的歌单，可以和本地歌单合并显示
- 账号：手机扫码或短信验证码登录，凭据加密存放在本机

## 自己编译

需要 JDK 21，机器上还要装好 Salt Player for Windows（编译期用到的 API 是从宿主里抽出来的）。

    pwsh -File build.ps1

产物在 build/dist/ 下。

## 目录

    src/main/java        插件源码
    src/main/resources   打进包里的资源
    libs                 运行期依赖
    tools                构建和自检脚本
    harness              离线测试脚手架

## 许可

MIT，见 LICENSE。