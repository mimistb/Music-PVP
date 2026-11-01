# 批次 4 · 任务拆解

> 依赖顺序：T0 → T1（参数模型）→ T2/T3（运行层）→ T4/T5（展示层）→ T6（构建备份）。
> 每完成一组，立即 `TodoWrite` 勾掉对应任务。

## T0 基线确认（低风险）
- [x] 确认 `version.properties` = 1.5.0，`specs/batch4-universal-voice/` 三件套存在。
- [x] `git status` 记录工作区状态（如仓库存在）。

## T1 参数模型（common/ui）
1. [x] `FpsToolsModule.kt`
   - `zoomScale` → `FloatValue(1f, 20f, 4f, 1)`。
   - 新增快捷发言 2–4：`keyQuick2/3/4`、`msgQuick2/3/4`。
   - 新增性能参数：`perfRenderDistance`、`perfEntityDistance`、`perfEntityShadows`、`perfGlassQuality`。
2. [x] `BedWarsModule.kt`
   - 新增 `bedMode`、`hypStats`、`hypAutoQuery`、`hypCacheMinutes`、`hypStyle`、`hypDecimals`、7 个展示开关；`onTick` 同步进 `BedWarsData`。
3. [x] `BedWarsData.kt`
   - 新增上述镜像字段（@Volatile）+ `clearMatch` 不重置设置镜像。
4. [x] `NeteaseMusicModule.kt`
   - 新增语音设置 8 项（`voiceEnabled/voicePushToTalk/voiceKey/voiceInputGain/voiceOutputVolume/voiceGate/voiceBufferMs/voiceMicMuted`）。

## T2 运行层（legacy + common/core）
5. [x] `FpsPortRuntime.kt`
   - `sync`：4 组快捷发言构建 `AutoText.entries`；`zoomScale` 夹到 1–20；性能参数写入 `Performance`。
   - `forceSprint/releaseSprintKey`：GUI/潜行守卫，模块关闭时释放按键。
   - 新增 `onRenderLivingPre(RenderLivingEvent.Pre<EntityLivingBase>)`：按 `perfEntityDistance` 取消远距实体渲染。
6. [x] `ForgeBridge.kt`
   - 注册 `onRenderLivingPre`。
7. [x] `Sprint.java`
   - `onKey` 增加 `mc.currentScreen != null` 守卫。
8. [x] `Performance.java`
   - 新增 `perfRenderDistance` / `perfEntityShadows` 的挂载点（`applySettings()`）。
9. [x] `BlockOverlay.java`
   - `setColor` 同时写裸 GL；面/线绘制前重设颜色。
10. [x] `MoreParticles.java`
    - 重写 Heart / Flame / Blood 的粒子发射（数量 + 偏移 + 速度）。
11. [x] `DamageIndicator.java`
    - 重写 `doRender`：去整格偏移、完整 billboard、全程关深度、尺寸修正。
12. [x] `bedwars/MatchTracker.java` + `BedAlertModule.java`
    - `update(..., boolean relaxed)`；relaxed 证据规则；`setRelaxed`。
13. [x] `BedWarsRuntime.kt`
    - 按 `bedMode` 调用 `setRelaxed`；`hypStats`/`hypAutoQuery` 门控；缓存时长写入服务。
14. [x] `bedwarsstats/RosterStatsService.java`
    - 暴露 `setCacheTtlMillis(long)`（若无等价 API）。
15. [x] `SplashGlassHook.java`
    - 三段 try/catch；反射补按钮；标题透明度下限 0.45。
    - 补充：`SmoothZoom.java` 滚轮缩放下限由 2 改为 1（项 10 配套）。

## T3 玻璃性能（项 5）
16. [x] `GlassBackdrop.kt`
    - 捕获节流（16/33/50 ms）+ 质量档模糊迭代；失败 `ready=false`。
17. [x] `GlassRenderContext.kt`
    - 暴露 `backdropQuality` 读取 `Performance` / 设置镜像。
18. [x] `MusicScreen.kt`
    - `CoverCache.upload()` 改为按需；确认 `render` 中无每帧全量上传。

## T4 展示层（common/ui）
19. [x] `TargetInfoHudModule.kt`
    - `statsText` 支持 3 种风格 + 开关 + 小数位；面板高度随行数自适应。
20. [x] `PartyPage.kt` + `Sidebar.kt`
    - 「一起听」→「音乐语音大厅」；成员行显示语音状态与延迟；语音开关/静音按钮复用现有 `ui.pill`。
21. [x] `SettingsPage.kt`
    - `Group.section` 分段标题；新增「语音大厅」组；床提醒移至 `网络与对战` 段；性能组加入 4 个新参数；快捷发言 4 组；伤害数字/描边保持。
22. [x] `VoiceChat.kt`（新增）
    - 采集/编码/发送、接收/抖动缓冲/播放、增益/噪声门/音量、PTT、延迟估算、生命周期与异常兜底。
23. [x] `ListenParty.kt` + `MqttClient.kt`
    - 暴露房间码与已连接中继给语音；新增语音主题订阅/发布（QoS0 二进制）。

## T5 版本与构建
24. [x] `version.properties` → 1.6.0。
25. [x] `legacy` 全量构建（offline）。修复所有编译错误。
26. [x] 如改了 ASM Transformer：`javap` 自检钩子签名（本批仅 hook，不改 transformer，执行一次确认即可）。

## T6 备份与交付
27. [x] jar → `music pvp/music pvp 1.6.0+1.8.9.jar`。
28. [x] 源码 zip → `music pvp/music pvp 1.6.0+1.8.9-src.zip`。
29. [x] 更新 `checklist.md` 勾选结果，并按验收项逐条自查（无法实机验证的标注“需用户实机确认”）。

## 回滚策略
- 任一项导致构建失败且短时间无法解决：先 `git checkout --` 该文件（如仓库存在），或删除新增字段并停止接线；语音模块可整体不接线（`voiceEnabled` 默认 false）。
- 版本号仅在构建成功后才改到 1.6.0。
