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

需要 JDK 21，机器上还要装好 Salt Player for Windows。

    pwsh -File build.ps1

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
