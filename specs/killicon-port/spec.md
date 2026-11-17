# 击杀图标模块（gd656killicon）移植规格

## 1. 目标

把 `C:\Users\xzy18\Desktop\GaleMod_KillIcon`（原 GaleMod-2.0 内置 gd656killicon）移植进 DangoMusic 的 1.8.9 Forge 工程，
并**接入宿主已有的液态玻璃设置页**，而不是新增任何界面：

- 击杀 / 助攻时在屏幕播放图标动画，播放图标音效，42 套预设可切换；
- 所有可调参数（开关、音效、音量、预设、锚点、尺寸）出现在现有「设置 → 音乐与 HUD」里；
- 位置按屏幕百分比锚点自适应，缩放沿用 `scale = size / 96`，不按 GUI Scale / 分辨率分支；
- 不新增 Screen / GuiScreen / HUD 面板 / 设置控件；不写 mixin；不改宿主已有 UI 布局。

## 2. 硬性约束（来自 PORTING_PROMPT.txt）

1. 不引入 Gale 框架：无 Module 基类、Setting、事件总线、ConfigManager、HUD 管理器、Gale.LOG。
   只保留 `gale.kill.gd` 包和 3 个替身：
   - `gale.core.Scheduler`（ticks / tick）
   - `gale.event.impl.Render2DEvent`（width / height / partialTicks）
   - `gale.util.RenderUtil`（rect → Gui.drawRect）
2. 资源命名空间保持 `gd656killicon`，放在宿主 jar 的 `assets/gd656killicon/` 根下（不是 `assets/dangomusic/`）。
3. 参数只用 public 字段暴露，由宿主设置页读写；模块自身不创建任何 UI。
4. 位置用屏幕百分比锚点，缩放 `scale = size / 96`；不引入 GUI Scale / 分辨率分支。
5. 不写 mixin、不 hook 渲染管线；渲染只从宿主 `RenderGameOverlayEvent.Post` 调用。
6. 使用 MCP stable 22-1.8.9 开发名；不出现 `func_` / `field_`。

## 3. 源码与资源落点

### 3.1 新增 Java 源码（legacy/src/main/java）

| 源路径（GaleMod_KillIcon\src） | 宿主任意路径 |
|---|---|
| `fpsport\killicon\KillIconModule.java` | `legacy/src/main/java/fpsport/killicon/KillIconModule.java` |
| `gale\kill\gd\**`（71 个类） | `legacy/src/main/java/gale/kill/gd/**` |
| `gale\core\Scheduler.java` | `legacy/src/main/java/gale/core/Scheduler.java` |
| `gale\event\impl\Render2DEvent.java` | `legacy/src/main/java/gale/event/impl/Render2DEvent.java` |
| `gale\util\RenderUtil.java` | `legacy/src/main/java/gale/util/RenderUtil.java` |

原样复制，包名 / 类名不改；按「已知坑」做 3 处反编译产物修复：

1. `CardBarRenderer`：`private static class WaveEffectSystem.Wave` → `static class Wave`；
2. `compat/BufferBuilder`：删除 `net.minecraft.client.renderer.vertex.VertexFormat` 同名 import，
   参数用全限定 `gale.kill.gd.compat.VertexFormat.Mode`；
3. `SubtitleRenderer`：两处 lambda 捕获被重新赋值的 `interpolatedColor`，各引入 final 局部变量
   （`prefixColor` / `emphasisColor`）。

`ClientMessageLogger` 保持 `System.err` + 静态方法签名不变。

### 3.2 新增资源（legacy/src/main/resources）

`GaleMod_KillIcon\assets\gd656killicon\**`（371 个文件）→
`legacy/src/main/resources/assets/gd656killicon/**`。

`GdKillEffect` 静态初始化读取 `/assets/gd656killicon/presets/index.json`；
`ConfigManager` / `ExternalTextureManager` / `ExternalSoundManager` 硬编码
`gd656killicon:textures/...`、`gd656killicon:sounds/...`，**不得改命名空间**。

## 4. 宿主接线（4 个时机）

新增 `legacy/src/main/kotlin/me/sorihiko/dango/legacy/KillIconRuntime.kt`：

```kotlin
object KillIconRuntime {
    val effect = KillIconModule()              // fpsport.killicon.KillIconModule
    init { effect.applyPreset(8) }             // 只做一次，8 = 原 "00009"

    fun tick()                                 // 每 tick：sync + onServerTick + 世界变化 reset
    fun onAttack(target: EntityLivingBase)     // 记录最近攻击目标，用于击杀判定兜底
    fun onLivingDeath(event: LivingDeathEvent) // 死亡时：自己死亡 reset；目标死亡 onKill(1, name)
    fun onRenderOverlay(partialTicks: Float)   // ScaledResolution 宽高 → render
    fun reset()                                // 清空播放状态
}
```

接线点（`legacy/src/main/kotlin/me/sorihiko/dango/forge/ForgeBridge.kt`）：

| 时机 | 位置 | 调用 |
|---|---|---|
| ① 客户端 tick | `onClientTick`（`FPSPortRuntime.tick()` 之后） | `KillIconRuntime.tick()` |
| ② 击杀 | 新增 `@SubscribeEvent onLivingDeath(e: LivingDeathEvent)` | `KillIconRuntime.onLivingDeath(e)` |
| ② 兜底 | 现有 `onAttack(e: AttackEntityEvent)` | `KillIconRuntime.onAttack(e.target)` |
| ③ HUD 渲染 | `onRenderGameOverlayPost`（`ElementType.ALL`，在 `FpsPortRuntime.onRenderOverlay()` 之前） | `KillIconRuntime.onRenderOverlay(e.partialTicks)` |
| ④ 切服 / 死亡 / 关闭 | `KillIconRuntime.tick()` 内比较 `mc.theWorld`、`mc.thePlayer`，变化时 `effect.reset()` | — |

顺序约束：tick 必须在 render 之前；缺 tick 会停在第一帧，缺 render 则不显示。

击杀判定规则（保证客户端可触发）：

1. `LivingDeathEvent` 的 entity 是本地玩家 → 只 `reset()`，不弹图标；
2. 其它实体死亡，满足任一条件即 `onKill(1, entity.displayName)`：
   - `event.source?.entity === mc.thePlayer`；
   - 该实体是 `KillIconRuntime` 最近 200 tick 内由本地玩家攻击过的目标。
3. 击杀后清除 `lastAttacked`，避免同一目标重复计数。

## 5. 设置页接入（不加新控件类型）

新增 `common/ui/src/main/kotlin/me/sorihiko/dango/module/impls/music/KillIconHudModule.kt`：

```kotlin
@ModuleMeta(Category.MUSIC, "KillIcon")
class KillIconHudModule : Module() {
    @ModuleValue("音效", "击杀时播放图标音效") val playSound = BooleanValue(true)
    @ModuleValue("音效音量", "0 ~ 100%") val soundVolume = FloatValue(0f, 1f, 0.7f, 2)
    @ModuleValue("预设", "42 套击杀图标预设") val presetIndex = IntValue(0, 41, 8)
    @ModuleValue("横向锚点", "屏幕宽度百分比，5 ~ 95") val anchorXPercent = FloatValue(5f, 95f, 50f, 0)
    @ModuleValue("纵向锚点", "屏幕高度百分比，10 ~ 90") val anchorYPercent = FloatValue(10f, 90f, 66f, 0)
    @ModuleValue("图标尺寸", "64 ~ 200，基准 96") val size = FloatValue(64f, 200f, 96f, 0)
    @ModuleValue("预览循环", "无击杀时也循环播放当前预设") val demoMode = BooleanValue(false)
    companion object { @JvmField var presetLabels: Array<String> = ... }
}
```

- 模块本身只存配置；`KillIconRuntime.sync()` 把字段写入 `effect` 的 public 字段：
  `enabled = module.enabled`、`playSound`、`soundVolume`、`presetIndex`、`anchorXPercent`、
  `anchorYPercent`、`size`、`demoMode`。
- `KillIconRuntime` 构造时调用 `effect.presets()`，写入 `KillIconHudModule.presetLabels`，
  供设置页格式化预设名；`presetIds()` / `currentPresetId()` 仅调试/日志用。
- 注册：`common/ui/src/main/kotlin/me/sorihiko/dango/DangoClient.kt` 的模块列表追加
  `KillIconHudModule()`。
- 设置页：`common/ui/src/main/kotlin/me/sorihiko/dango/musicui/SettingsPage.kt`
  - `buildSettings()` 增加 `val killIcon = Src(mm.get(KillIconHudModule::class.java), fmts = ...)`
    - `presetIndex` → `presetLabels[index]`（缺失时回退 `第 N 套`）
    - `anchorXPercent` / `anchorYPercent` → `"${value.roundToInt()}%"`
    - `size` → `"${value.roundToInt()} px"`
    - `soundVolume` → 百分号（复用 `::percent`）
  - 在 `section = "PVP · 打击"` 下、`PVP 工具` 组之后插入：
    `Group("击杀图标", "42 套预设 · 屏幕锚点", killIcon.m, true, killIcon.take(), section = "PVP · 打击")`
  - 组标题总开关绑定 `KillIconHudModule.enabled`（即 `owner.enabled`），不新增控件类型。

## 6. 渲染与 UI 自适应

- 宽高来自 `ScaledResolution(mc).scaledWidth / scaledHeight`（GUI 缩放后的像素），
  与模块 `render(width, height, partialTicks)` 的约定一致。
- 锚点：`x = width * anchorXPercent / 100`，`y = height * anchorYPercent / 100`；
  缩放：`scale = size / 96`。模块内部不再添加任何按分辨率分支的逻辑。
- 只在 `RenderGameOverlayEvent.Post(ALL)` 调用，与现有 HUD 同帧；调用在动态模糊之前，
  避免图标被后处理模糊。
- `mc.gameSettings.hideGUI` 时模块内部已直接 return（按 F1 隐藏 HUD 时不显示）。

## 7. 验证

1. **编译**：`legacy` 下
   `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot'; .\gradlew.bat --no-daemon --console=plain --offline build`
   期望 `BUILD SUCCESSFUL`，允许 getVersionJson PKIX 告警和 JavaCompile deprecation note。
2. **静态自检**：
   - `grep -r "gale.module\|gale.event.bus\|Gale.LOG\|Setting"`（限定新增的 gale/kill/gd 与 fpsport/killicon）→ 0；
   - `grep -rE "func_[0-9]+_|field_[0-9]+_"`（新增目录）→ 0；
   - 资源路径为 `assets/gd656killicon/...`；
   - `git status` 确认未新增 Screen 类、未改宿主现有 UI 文件布局（SettingsPage 仅追加一行组）。
3. **游戏内**（`runClient` 或打包后进服）：
   - 设置页可见「击杀图标」组，展开后 7 行控件，改动立即生效；
   - 拖动预设行立即切换图标风格；
   - 击杀任意实体 → 屏幕锚点处播放图标 + 音效；
   - F1 隐藏 HUD 时不显示；切服 / 死亡后图标清空。
4. **产物**：`version.properties` 从 2.0.1 → 2.0.2，构建后备份
   `music pvp 2.0.2+1.8.9.jar` 与 `music pvp 2.0.2+1.8.9-src.zip`，删除旧的 2.0.1 同名备份。

## 8. 非目标

- 不移植 KillEffects 的 GD656 主题切换与 2021/2023/2024 主题；
- 不移植原 Gale 的 HUD 拖拽编辑（用百分比锚点滑条代替）；
- 不做多人 / 网络同步，纯客户端表现；
- 不新增任何 Screen 或独立设置界面。
