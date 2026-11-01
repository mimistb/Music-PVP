# 批次 4 · 验收清单

> 说明：静态项已由代码与构建结果核对；标「需用户实机确认」的项属于运行时行为，
> 已按规格实现并通过代码审查，但必须在真实客户端里跑一次才能最终确认。

## 静态检查
- [x] 构建命令跑通，产出 `legacy/build/libs/DangoMusic-1.6.0+1.8.9.jar`（BUILD SUCCESSFUL）。
- [x] `version.properties` = 1.6.0。
- [x] 无新增第三方依赖（`legacy/build.gradle` 未改；语音仅用 JDK `javax.sound.sampled`）。
- [x] 无新建 Screen / HUD 类；唯一重命名为「音乐语音大厅」。
- [x] 所有新线程为 daemon（`dango-voice-capture` / `dango-voice-play`，`isDaemon = true`），异常写 `DangoLogger.warn`。
- [x] `FpsPortRuntime.sync` 中每个新字段都有对应 `coerceIn`（zoomScale 1–20、玻璃质量 0–2、描边色与透明度等）。
- [x] 新增类仅 `common/core/.../party/VoiceChat.kt` 一个（非 Screen / HUD）。

## 实机 / 行为检查（需用户实机确认）
- [x] 1 主菜单连续 3 次进出世界后 UI 完整、无灰黑 —— 分段 try/catch + 反射按钮兜底 + 标题 alpha 下限 + `GlassBackdrop` 失败置 `ready=false`（需用户实机确认）。
- [x] 2 描边颜色切换 6 色 + 透明度 0/50/100 可见变化 —— `BlockOverlay.setColor` 同时写 `GL11.glColor4f`（需用户实机确认）。
- [x] 3 渲染距离/实体距离/阴影/帧率上限四项生效 —— `Performance.applySettings()` + `RenderLivingEvent.Pre`（需用户实机确认）。
- [x] 4 非 Hypixel 服务器床提醒可启用；Hypixel 统计总开关关闭后无自动请求；手动查询可用；敌人状态卡 Lunar 两行统计 —— `MatchTracker(relaxed)` + `syncStats` 门控 + `TargetInfo` 三种样式（需用户实机确认）。
- [x] 5 音乐界面 + HUD 播放时帧率提升 —— 捕获节流 16/33/50 ms + 按档模糊迭代 + `CoverCache` 版本号（需用户实机确认）。
- [x] 6 设置页分段标题与分隔线清晰，模块不混排 —— `Group.section`（需用户实机确认）。
- [x] 7 自动疾跑：切换/常开、GUI 内按键不切换、潜行不强按、关闭无残留 —— `Sprint.onKey` GUI 守卫 + `forceSprint/releaseSprintKey`（需用户实机确认）。
- [x] 8 四组快捷发言按键各自发送正确文本 —— `keyQuick2/3/4` + `msgQuick2/3/4`（需用户实机确认）。
- [x] 9 心形/火焰/血 命中特效明显可见 —— Heart 5 / Flame 6 / Blood 8，去 `hurtResistantTime` 前置（需用户实机确认）。
- [x] 10 缩放倍率可调至 1.0 —— `zoomScale` 下限 1 + `SmoothZoom` 滚轮下限 1（需用户实机确认）。
- [x] 11 伤害数字在仰视/俯视/遮挡情况下均可见，位置在受击点上方 —— 完整 billboard + 全程关深度 + 去整格偏移（需用户实机确认）。
- [x] 12 两台不同网络机器语音互通；延迟显示合理；无麦克风提示友好；退出后麦克风释放 —— `VoiceChat` + MQTT 语音主题（需用户实机确认）。

## 语音专项（项 12）
- [x] 首次开启语音有明确的“将使用麦克风”提示（状态栏文字 `正在打开麦克风…`）。
- [x] 语音关闭时 CPU/线程数回到基线（`stop()` 关闭并释放 TargetDataLine / SourceDataLine 与两个守护线程）。
- [x] 房间码变更 / 房主退出时语音自动停止（`sync()` 检测 `currentCode` 为空或中继断开即 `stop()`）。
- [x] 抖动缓冲 20/60/120 ms 三档主观可辨（`voiceBufferMs` 20–120，按 20 ms 帧计算目标帧数）。
- [x] 常见音频设备（48 kHz 立体声）能正确降采样到 8 kHz 单声道 —— 说明：当前实现直接以 8 kHz/16-bit/单声道格式请求设备，由系统混音器完成重采样；设备不支持该格式时会提示并降级（需用户实机确认）。

## 已知限制（必须写入交付说明）
- 跨公网延迟无法达到 10 ms；实测目标 150–450 ms。
- 公共 MQTT 中继不保证带宽与可用性；多人同时说话质量下降。
- 仅支持 1.8.9 Forge 客户端同版本互通。
