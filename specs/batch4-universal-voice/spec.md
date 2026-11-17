# 批次 4：稳定性 · 参数补全 · 性能 · 音乐语音大厅

> 版本基线 1.5.0（`DangoMusic-main/version.properties`），本批完成后升到 **1.6.0**。
> 目标平台：MC 1.8.9 Forge（`legacy`）。`modern*` 不参与本批。

## 0. 铁律与前置结论

- 零新 UI：不新建 Screen / HUD 面板、不改主题/字体/排版；只允许用户明确要求的重命名（「一起听」→「音乐语音大厅」）。
- 宿主无 Mixin：游戏内逻辑一律走 `legacy/src/main/java/fpsport/**` + Forge 事件 + 现有 coremod ASM。
- 不新增第三方依赖：语音只用 JDK 自带 `javax.sound.sampled` 与现有 MQTT 中继。
- 先改参数模型（`common`），再改运行层（`legacy`），最后改设置页展示。

### 0.1 必须向用户说明的物理事实（第 12 项）

**跨公网语音延迟 < 10 ms 不可能实现**。仅 20 ms 音频帧 + 编解码/抖动缓冲就 > 20 ms；公网单程 RTT 通常 30–120 ms。
本批采用可落地目标：**同一局域网约 30–60 ms；跨公网经公共 MQTT 中继约 150–450 ms**，并在语音大厅界面显示实时往返延迟。不会伪造 10 ms。

## 1. 逐项方案

### 项 1 · 再次回到主界面 UI 消失 / 全屏灰黑
根因判断：`SplashGlassHook.drawMainMenuForeground` 先整屏画不透明深色底（`drawBase`），再用静态 `BUTTONS` 列表补画标题与玻璃按钮。只要该帧 `BUTTONS` 为空（ASM 按钮钩子未命中 / 二次进入时按钮尚未记录）或中途抛异常，就会只剩深色底 → “全屏灰黑、UI 消失”。次要风险：`GlassBackdrop.capture()` 失败后仍保留上次的 `ready=true`，玻璃窗口用空纹理渲染成黑块。

改动（`legacy/src/main/java/me/sorihiko/dango/core/SplashGlassHook.java`）：
1. `drawMainMenuForeground` 拆成三段独立 try/catch：背景 → 标题 → 按钮。任一段失败不影响其余段。
2. 新增 `collectButtonsFromScreen(GuiScreen)`：`BUTTONS` 为空时用反射读 `GuiScreen.buttonList`（兼容 MCP `buttonList` / srg `field_146292_n`），把每个可见 `GuiButton` 的 x/y/w/h/label/enabled 补进 `BUTTONS`，保证按钮一定画得出来。
3. 标题入场透明度下限抬高：`alpha = 0.45f + 0.55f*ease`，避免首帧文字全透明。
4. `GlassBackdrop.capture()` 包 try/catch，失败时 `ready=false`；`GlassRenderContext.drawGlass` 本来就有 fallback 分支，会退化为纯玻璃面（可读）。

### 项 2 · 方块描边颜色 / 透明度
现状：`FpsToolsModule.boOutlineColor` / `boOutlineAlpha` 与 `BlockOverlay.outlineColor` 已完成接线（1.5.0）。本批只做“确保真的生效”：
- `BlockOverlay.setColor(int argb)` 在 `GlStateManager.color` 之外**同时**调用 `GL11.glColor4f`，绕开 GlStateManager 状态缓存导致的不上色。
- `drawFaces/drawOutline` 前后各 `setColor` 一次（在 `drawFaces` 前设置），避免 Tessellator 复用上一状态。
- 设置页无需新增行，仅校验 `swatchesFor("boOutlineColor")` 走 `overlaySwatches`。

### 项 3 · 性能模块参数
`Performance.java` 里绝大多数开关没有实现，直接暴露是“假开关”，本批只增加**真实可落地**的 4 个参数：

| 设置项 | 字段 | 范围/默认 | 实现 |
|---|---|---|---|
| 帧率上限 | `fpsLimit`（已有） | 0–260，0=不限 | `Display.sync`（已有） |
| 渲染距离 | `perfRenderDistance` | 0–16，0=跟随原版 | 变化时写 `mc.gameSettings.renderDistanceChunks` 并 `renderGlobal.loadRenderers()` |
| 实体渲染距离 | `perfEntityDistance` | 0–128，0=不限 | 新 Forge 监听 `RenderLivingEvent.Pre`，超距 `event.isCanceled=true`（含玩家，`RenderPlayerEvent` 是其子类） |
| 关闭实体阴影 | `perfEntityShadows` | 默认 false | 写 `mc.gameSettings.entityShadows = !on` |
| 玻璃质量 | `perfGlassQuality` | 0 高 / 1 中 / 2 低，默认 1 | 控制 `GlassBackdrop` 捕获频率与模糊迭代（见项 5） |

接线：`FpsPortRuntime.sync()` 写入；`Performance.onClientTick()` 执行延迟刷新；新增 `FpsPortRuntime.onRenderLivingPre(...)` 在 `ForgeBridge` 注册。

### 项 4 · 床提醒全服务器通用 + Hypixel 统计独立开关/参数/仿 Lunar 展示
1. **床提醒通用化**：`MatchTracker.update` 增加 `boolean relaxed` 入参；`BedAlertModule.setRelaxed()`。
   - relaxed 模式：Sidebar 只要 `distinctTeams >= 2` 即有效（不再强制出现 `YOU` 行）；自己队伍取皮革胸甲 → TAB 名字色 → Sidebar 本地行；任意一路证据 `requiredStableSamples = 1`；胸甲证据只要本地胸甲已知 + `bedCount > 0`。
   - 严格模式（仅 Hypixel）保持原三路证据逻辑不变。
2. **Hypixel 统计参数**（`BedWarsModule` 新增，全部经 `BedWarsData` 镜像到 legacy）：
   - `hypStats`（总开关，默认 false）、`hypAutoQuery`（自动查询对手，默认 true）、`hypCacheMinutes`（5–360，默认 60）、`hypStyle`（0 紧凑 / 1 Lunar / 2 详细，默认 1）、`hypDecimals`（0–2，默认 1）
   - 展示开关：`hypStars`、`hypFkdr`、`hypWlr`、`hypBblr`、`hypWinStreak`、`hypWins`、`hypNetLevel`（默认 true）
3. **运行层门控**（`BedWarsRuntime`）：`hypStats` 关闭时不自动入队查询；手动查询（设置页回车）仍允许。`hypAutoQuery` 关闭时只保留手动查询。缓存时长写入 `RosterStatsService` 新增 `setCacheTtlMillis`（实现时若已有等价 API 直接复用）。
4. **仿 Lunar 展示**：`TargetInfoHudModule.statsText` 按 `BedWarsData` 镜像的开关拼装：
   - 0 紧凑：`★123 FKDR 1.2`
   - 1 Lunar：两行 —— `[MVP+] Lv 120 · ★ 123` / `FKDR 1.2 · WLR 0.8 · BB 0.5 · WS 3`
   - 2 详细：额外附 `W 500 · NL 120`
   - 小数位由 `hypDecimals` 控制；面板高度在换行时自适应（`panelH` 52 → 62）。

### 项 5 · 听歌时掉帧
根因：所有玻璃面板（音乐界面与 HUD）每帧都执行 `GlassBackdrop.capture()`（全屏 copy + 2 级降采样 + 5 次模糊），这是主要开销。
改动（`legacy/src/main/kotlin/me/sorihiko/dango/ui/glass/GlassBackdrop.kt` + `GlassRenderContext.kt`）：
1. `capture()` 增加节流：按质量档位最小间隔 **高 16 ms / 中 33 ms / 低 50 ms**；未到间隔直接复用上一帧模糊纹理。
2. 模糊迭代按档位：高 `(2,3)`、中 `(1,2)`、低 `(1,1)`；降采样基数仍为 1/4 与 1/8。
3. `ready=false` 时不进入模糊（直接返回）。
4. `MusicScreen.render()` 里把 `CoverCache.upload()` 从每帧改为“有新封面时才调用”（由 `CoverCache` 内部版本号判断）。
5. 结果：界面与 HUD 玻璃视觉效果不变（静态场景可接受 30 Hz 背景），GPU 峰值显著下降。

### 项 6 · 设置页拆分不同模块
只改 `SettingsPage.kt` 的排版数据，不新建界面：
- 新增 `Group.section`（段名）。`draw()` 在每组之前，若 `section` 与上一组不同，画一条 12 px 段标题 + 细分隔线（复用现有 `ui.text` / `ctx.drawShape`）。
- 分段：`音乐`（歌词/播放器/卡片/灵动岛/2D 歌词）、`组件位置`、`信息与 HUD`（信息胶囊/按键/敌人状态/打击颜色）、`网络与对战`（床提醒/Hypixel 统计）、`PVP · 打击`、`PVP · 视角与画面`、`PVP · 自动化`、`PVP · 性能`、`PVP · 方块与物品`、`PVP · 聊天与护甲`。
- 「床提醒」组从音乐标签移到独立 `网络与对战` 段（仍在“音乐与 HUD”标签内，避免新增标签）。

### 项 7 · 自动疾跑自查与修复
发现的真实问题：
1. 打开任意 GUI（聊天/背包）时 `InputEvent.KeyInputEvent` 仍会把按键送进 `Sprint.onKey`，在输入框里按疾跑键会静默切换疾跑 → 加 `mc.currentScreen != null` 守卫。
2. 切换模式关闭（一直疾跑）时，若上一状态 `Sprint.sprint=false`，重新开启不会自动恢复 → `onUpdate()` 里强制 `sprint=true`（已有，保留并显式在 `sync` 时纠正）。
3. `releaseSprintKey()` 在玩家仍自己按住疾跑键时不会清除；但模块关闭时 `Feature.onDisable` 已清。额外：`forceSprint` 在潜行时不再强按，避免与潜行冲突。
4. 模块关闭瞬间残留按键：`FpsPortRuntime.sync` 末尾在 `sprint` 未启用时调用 `releaseSprintKey()`。
5. 修正 `forceSprint` 只对 `movementInput.moveForward>=0.8f` 显式 `setSprinting(true)` 的前置判断，保留原版逻辑。

### 项 8 · 多个快捷发言 + 按键绑定
`FpsToolsModule` 在现有第 1 组（`keyAutoText` / `msgAutoText`）基础上新增 3 组：`keyQuick2/3/4`、`msgQuick2/3/4`。
`FpsPortRuntime` 的 `sync` 改为遍历 4 组构建 `AutoText.entries`，只有 key≠0 且消息非空才加入；`AutoText.MAX_CAPACITY` 已 20，足够。

### 项 9 · 击中特效不生效
现状：`MoreParticles` 对 Heart/Flame/Blood 各只发 **1 个**粒子（Blood 还是极小 REDSTONE），位于实体包围盒中心，实际几乎看不见；且 `AttackEntityEvent` 命中瞬间实体常处于受击无敌帧。
修复（`MoreParticles.onAttack`）：
- Heart：在实体头部上方 `y+0.6`、半径 0.35 的圆上发 **5** 个 `HEART`。
- Flame：围绕实体腰部发 **6** 个 `FLAME`，带小速度。
- Blood：围绕命中点发 **8** 个红色 `REDSTONE`（`spawnParticle` 带速度扩散）+ 现有音效；去掉 `hurtResistantTime<=10` 的苛刻前置（改为只在命中瞬间调用一次由事件保证）。
- 全部走 `world.spawnParticle(type, x,y,z, vx,vy,vz)`，失败再退回 `emitParticleAtEntity`。

### 项 10 · 缩放倍率下限
`FpsToolsModule.zoomScale`：`FloatValue(1f, 20f, 4f, 1)`；`FpsPortRuntime.sync` 的 `coerceIn(1f, 20f)`；设置页格式不变。

### 项 11 · 伤害数字看不见 / 越过玩家
`DamageIndicator.doRender` 重写绘制顺序：
1. 去掉 `x + 1` / `y - 1` 的整格偏移（`onUpdate` 记录 `posY - 0.2`，绘制用原坐标）。
2. 完整 billboard：先 `rotate(-playerViewY, 0,1,0)` 再 `rotate(-playerViewX, 1,0,0)`，抬头/低头都能正面看到。
3. 全程 `GlStateManager.disableDepth()` 且**不**在中途 `enableDepth()`；文字绘制前后统一用 `GlStateManager`（不混用裸 GL 状态），保证永远压在人身上方。
4. 缩放从 `-scale/2, -scale, -scale` 改为 `-scale, -scale, -scale`（尺寸翻倍，配合默认 0.065 更清晰），淡出 alpha 从 255 均匀衰减。
5. `clearState()` 不变。

### 项 12 · 「一起听」→「音乐语音大厅」+ 语音聊天
**改名**：`Sidebar.kt` 导航文案、`PartyPage.kt` 标题/副标题/说明、`MusicScreen.kt` 注释（`Library.kt` 的枚举 `PARTY` 不改，避免配置/状态迁移）。
**语音实现**（新增 `common/core/src/main/kotlin/me/sorihiko/dango/party/VoiceChat.kt`）：
- 传输：复用 `ListenParty` 的 MQTT 连接与房间码，新增主题 `dango/party/{code}/v/{uid}`；QoS0、不 retain、二进制载荷。
- 编码：8 kHz / 16-bit 单声道采集 → 降采样 + 线性预测简化 → **µ-law 8-bit**（64 kbps/人），20 ms 一帧（160 样本 → 160 字节）。
- 播放：`javax.sound.sampled.SourceDataLine`；抖动缓冲默认 60 ms（可调 20–120 ms），按序播放、丢弃过期帧。
- 处理：简易高通（去直流/低频噪声）+ 噪声门（阈值可调）+ 增益（0–4×）+ 输出音量（0–2×）。
- 按键说话：默认按住 `V`；可改键（`IntValue` 0–255）；关闭即常开麦。
- 设备：自动选择默认 `TargetDataLine` / `SourceDataLine`；无设备时状态栏提示“未检测到麦克风”，不影响听歌。
- 延迟显示：`VoiceChat.roundTripMs` 由音频帧时间戳估算（发送帧带 `seq` 与本地毫秒，接收端回显？为避免额外流量，改用“帧携带发送端帧序号与时间”，对端按到达时间估算抖动缓冲延迟）。界面在语音成员行显示 `延迟 ~xxx ms`。
- UI 只复用 `PartyPage` 现有卡片/成员行与 `SettingsPage` 行，不新建面板。
**语音设置**（挂在 `NeteaseMusicModule`，随音乐配置自动存盘；在 SettingsPage「音乐与 HUD」标签新增 `语音大厅` 分组）：
`voiceEnabled`、`voicePushToTalk`、`voiceKey`、`voiceInputGain`(0.1–4)、`voiceOutputVolume`(0–2)、`voiceGate`(0–100)、`voiceBufferMs`(20–120)、`voiceMicMuted`。
**风险**：公共 MQTT 中继的吞吐与延迟不可控；多说话人（>4）建议关闭语音。所有线程 daemon、异常吞掉并写日志，绝不因语音影响游戏主循环。

## 2. 影响文件清单

- 修改：`common/ui/.../FpsToolsModule.kt`、`common/ui/.../SettingsPage.kt`、`common/ui/.../TargetInfoHudModule.kt`、`common/ui/.../BedWarsModule.kt`、`common/ui/.../NeteaseMusicModule.kt`、`common/ui/.../musicui/Sidebar.kt`、`common/ui/.../musicui/PartyPage.kt`、`common/ui/.../music/MusicScreen.kt`
- 修改：`common/core/.../hud/BedWarsData.kt`、`common/core/.../party/ListenParty.kt`、`common/core/.../party/MqttClient.kt`、`common/core/src/main/java/bedwars/MatchTracker.java`、`bedwars/BedAlertModule.java`、`bedwarsstats/RosterStatsService.java`
- 修改：`legacy/.../FpsPortRuntime.kt`、`legacy/.../BedWarsRuntime.kt`、`legacy/.../forge/ForgeBridge.kt`、`legacy/.../ui/glass/GlassBackdrop.kt`、`legacy/.../ui/GlassRenderContext.kt`、`legacy/src/main/java/fpsport/utility/Sprint.java`、`fpsport/optimizes/Performance.java`、`fpsport/render/MoreParticles.java`、`fpsport/render/DamageIndicator.java`、`fpsport/render/BlockOverlay.java`、`legacy/src/main/java/me/sorihiko/dango/core/SplashGlassHook.java`
- 新增：`common/core/.../party/VoiceChat.kt`
- 版本：`version.properties` 1.5.0 → 1.6.0

## 3. 验收（逐项）

1. 主菜单：进世界 → 退出回主菜单，连续 3 次；标题、按钮、玻璃面均可见，无全屏灰黑。
2. 方块高亮：切换 6 种描边色 + 0/50/100% 透明度，颜色与透明度立刻变化。
3. 性能：渲染距离 8→4 后区块重载；实体距离 32 后远处生物不渲染；阴影开关立即生效；帧率上限 60 生效。
4. 床提醒：非 Hypixel 服务器（有床 + 队伍颜色）也能启用；Hypixel 统计总开关关闭后不再自动发请求，手动查询仍可用；敌人状态卡显示 Lunar 两行统计。
5. 音乐界面打开 + 播放：帧率对比开启前后；捕获间隔 ≥ 33 ms。
6. 设置页：不同模块有明显段标题与分隔线。
7. 自动疾跑：切换/常开两种模式；聊天栏按疾跑键不切换；潜行不抢按键；关闭模块无残留疾跑。
8. 快捷发言：4 组按键分别发送对应文本。
9. 命中特效：心形/火焰/血 三种均明显可见。
10. 缩放：倍率可拖到 1.0。
11. 伤害数字：仰视/俯视/目标在玩家正后上方都能看到，无格偏移。
12. 语音大厅：两台不同网络机器同房间；互相能听到；界面显示延迟；断开/退出不残留线程与麦克风占用；无麦克风时提示而不崩溃。

## 4. 构建与备份

```
cwd: DangoMusic-main\legacy
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot'
.\gradlew.bat --no-daemon --console=plain --offline build
```
成功后：
- jar → `C:\Users\xzy18\Desktop\TraeCode CN\music pvp\music pvp 1.6.0+1.8.9.jar`
- 源码 zip → `music pvp 1.6.0+1.8.9-src.zip`（排除 `.gradle/.kotlin/build/run/out/.git/.idea/*.log`）
- 若改动了任何 `*Transformer.java`，用 `javap -p` 校验钩子方法签名；本批不改 Transformer（仅改 Hook）。

## 5. 明确不做（避免误解）

- 不实现真正的 10 ms 语音延迟；不做 P2P 打洞/自建音频服务器。
- 不实现 `Performance` 里无运行层实现的 Mixin 类开关（隐藏方块、字体批处理等）。
- 不新建任何 HUD/屏幕，不改主题色板与字体。
