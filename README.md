<p align="center"><img src="docs/banner.png" alt="Music PVP" width="640"></p>

# Music PVP

在 Minecraft 里听网易云音乐的 mod（1.8.9 Forge）：液态玻璃风格的PVP客户端

*A Minecraft mod (1.8.9 Forge) that brings a NetEase Cloud Music player into Minecraft — liquid-glass UI, 3D in-world lyrics, a song card HUD, a Dynamic Island and Listen Together.*

## 截图

<p align="center"><img src="docs/screenshots/lyrics-3d.jpg" alt="3D 歌词" width="100%"></p>
<p align="center"><sub>漂浮在世界里的 3D 歌词</sub></p>

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" alt="首页"> | <img src="docs/screenshots/playlist.jpg" alt="歌单列表"> | <img src="docs/screenshots/coverflow.jpg" alt="封面墙"> |
| 首页 · 推荐歌单 | 歌单 · 列表视图 | 歌单 · 封面墙视图 |

| | |
|:---:|:---:|
| <img src="docs/screenshots/settings-lyrics.jpg" alt="歌词设置"> | <img src="docs/screenshots/settings-music.jpg" alt="Music &amp; HUD 设置"> |
| 设置 · Lyrics | 设置 · Music &amp; HUD |

## 功能

- **播放器**（默认按 `.` 打开）：每日推荐、我的歌单、搜索；列表 / 封面墙两种视图；歌单内筛选、一键定位到正在播放；平滑滚动和切换动画
- **歌词**：网易云逐字歌词（yrc）
  - 3D 歌词漂浮在世界里，跟着演唱一句句出现，暂停时自动隐藏
  - Deep Glow 发光、Wiggle 抖动、文闪、Word pop
  - 自动拆开"两句写在一行"的歌词
- **歌曲卡片**：iOS 风格的歌曲卡片，打开聊天栏可以拖动位置，在聊天栏点进度条可直接调节至点击进度位置
- **灵动岛**：屏幕顶部显示正在播放，切歌或显示灵动岛歌词时展开。
- **音乐语音大厅**：开房间、分享房间码，所有人自动同步到同一首歌、同一秒，可语音游玩游戏延迟略高（公共中继，开箱即用）
- **设置**：都在播放器侧栏的 ⚙ 里；可以绑定快捷键（播放 / 暂停、上一首、下一首、音量、开关歌词显示方式[灵动岛显示，3D歌词显示，2D歌词栏显示]）
- **设置**:  信息胶囊可显示玩家状态信息
- **设置**   敌人状态可显示液态玻璃样式的敌人状态显示（持续优化ing）
- **设置**   hypixel的床警报（现有bug维修ing）玩家数据显示
- **设置**   目前以内置一系列pvp组件（自动疾跑，特效，粒子数量改变，自由视角，平滑放大，自定义迷雾，火焰遮盖，自动GG，快捷发言，方块高亮[有向下皮肤变暗的bug，修复ing]，聊天增强，cps显示[无法分割的bug，修复ing]，护甲显示[视觉bug，修复ing]）
- 网易云扫码登录（VIP 歌曲需要会员账号）

## 安装

按游戏版本选 jar（在 [Releases](https://github.com/mimistb/Music PVP/releases) 下载）：

| 版本 | 文件 | 需要 |
|---|---|---|
| 1.8.9 | `Music PVP x.x.x.jar` | Forge 1.8.9（11.15.1.2318），或支持 Forge mod 的客户端（如 Lunar） |

1. 把对应的 jar 放进 `.minecraft/mods`
2. 进游戏按 `.`，点左下角扫码登录网易云

配置、登录凭证、缓存都存在 `.minecraft/config/Music PVP/`，各个版本通用。

## 构建

需要 JDK 8（1.8.9）。

只构建 1.8.9：

```bash
cd legacy
gradlew build                  # 输出 legacy/build/libs/Music PVP x.x.x.jar
gradlew runClient              # 开发环境里直接运行
```

### 项目结构

```

### 音乐语音大厅（公共中继）

一起听歌不需要账号，也不需要配置任何 Key：模组会连公共 MQTT 服务器
（`broker.emqx.io`，连不上时自动换 `broker.hivemq.com`），房主创建房间后得到一个 5 位房间码，
其他人输入同一个码即可跟随同一首歌、同一秒。

- 房间状态只包含"哪首歌、第几秒、在不在放"和玩家昵称，不涉及网易云账号
- 房间码是随机的主题名，房主关闭房间时会把状态清空，不影响别人
- 想换成自己的中转服务器，改 `common/core/.../party/MqttClient.kt` 里的 `BROKERS` 列表即可
- 断线会自动重连并重新订阅；进度按"收到消息时的本机时间"推算，不要求两台电脑时钟一致

## 说明

- 使用的是网易云音乐的非官方接口，仅供个人学习交流；歌曲版权归各平台和版权方所有
- 海外账号需要会员才能播放大部分歌曲

## 第三方

- [Inter](https://github.com/rsms/inter)（SIL Open Font License 1.1），[Roboto Slab](https://fonts.google.com/specimen/Roboto+Slab)（Apache License 2.0）
- [JLayer](http://www.javazoom.net/javalayer/javalayer.html)（LGPL）：MP3 解码
- [ZXing](https://github.com/zxing/zxing)（Apache License 2.0）：登录二维码
- Kotlin 标准库（Apache License 2.0）

## 许可证

[GPL-3.0](LICENSE)：可以自由使用、修改和分发；分发修改后的版本时也必须以 GPL-3.0 开源。
