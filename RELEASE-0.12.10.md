# 0.12.10：搜索交互、循环封面与原生歌单菜单

本版处理单击延迟、重复起播、同曲循环封面去重，精简播放状态，加入椒盐原生右键歌单操作。顶栏在提交搜索或点击候选后清空；离开搜索页面后重进插件直接显示配置。“音乐”栏提供默认开启的“搜索框”开关，“歌单与同步状态”移至开发者工具。

运行日志确认当前宿主为 1.19.2；它更换了数据库入口类，导致旧版歌曲过滤／刷新联动安装失败。本版补充经实际运行库签名核对的 1.19.2 数据库能力档，保留原有两版兼容，避免误退回旧接口。

歌曲行在按下时立即选择，双击时单次播放。未变化的歌曲行可跳过重组；封面仍通过原有后台 CoverStore 管线加载。播放链路只保留一次宿主队列选曲入口，删除旧的直驱、取流探针及定时二次起播，过期网络任务与旧歌曲保活会被丢弃。正常提示为“正在解析：歌曲”，宿主实际播放回调与当前曲目 ID 对上后才显示“正在播放：歌曲”；失败仍如实提示。

封面去重现在区分歌曲与播放轮次：同一 ID 播完循环会允许新一轮投递；暂停／继续不会重置，过期排队封面不能覆盖新一轮。仍保留权威曲目核对、缓存及有界落地复核。

右键菜单直接调用宿主 SPC PopupMenu 及其条目 DSL，定位到鼠标位置并跟随宿主主题。列表只展示当前账号自己创建的歌单，提供独立的“添加到我喜欢的音乐”。按账号缓存 60 秒，读取、写入与登录／退出串行，切换账号后旧菜单无效。只将明确成功的响应显示为成功；网络异常不自动重发，防止重复写入；成功后合并请求本地同步，已有同步在运行则等待。

接口实现参照开源实现的 [喜欢歌曲](https://raw.githubusercontent.com/IcyBlue17/NeteaseCloudMusicApiVercel/master/module/like.js) 与 [歌单添加](https://raw.githubusercontent.com/IcyBlue17/NeteaseCloudMusicApiVercel/master/module/playlist_tracks.js) 请求格式；没有采用其不确定失败后的重复写入降级。

构建、校验与证据记录见 [RESULT.md](build/evidence/0.12.10/RESULT.md)。最终安装包在全部检查结束后才写入 build/dist，中间构建在 build/staging。未在真实账号里代选歌曲／歌单进行测试写入；长时间真实循环与整机流畅度须安装后验收。详细清单见 [ACCEPTANCE.md](ACCEPTANCE.md)。

<!-- final-artifact -->
安装包：[plugin-com.example.netease-0.12.10.spmod](build/dist/plugin-com.example.netease-0.12.10.spmod)。

SHA-256：`b4f1c83f85b4e4feb7fe928ce047aa6dd794e0d2c47ccbc0728c95cfdd4cd4c0`，14,472,580 字节。
