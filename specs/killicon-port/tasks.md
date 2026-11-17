# 击杀图标模块移植任务清单（已完成）

## T1 复制源码与资源

- [x] 复制 `GaleMod_KillIcon\src\fpsport\killicon\KillIconModule.java` →
      `legacy/src/main/java/fpsport/killicon/KillIconModule.java`
- [x] 复制 `GaleMod_KillIcon\src\gale\kill\gd\**`（70 个类）→ `legacy/src/main/java/gale/kill/gd/**`
- [x] 复制 `gale\core\Scheduler.java`、`gale\event\impl\Render2DEvent.java`、`gale\util\RenderUtil.java` →
      `legacy/src/main/java/gale/core|event/impl|util/`
- [x] 复制 `GaleMod_KillIcon\assets\gd656killicon\**`（371 个文件，15.4 MB）→
      `legacy/src/main/resources/assets/gd656killicon/**`
- [x] 资源命名空间保持 `gd656killicon`，未改任何路径字符串

## T2 修正反编译产物（3 处已知坑）

- [x] `client/render/impl/CardBarRenderer.java`：`private static class WaveEffectSystem.Wave` → `static class Wave`
- [x] `compat/BufferBuilder.java`：删除 `net.minecraft.client.renderer.vertex.VertexFormat` import，
      参数改用 `gale.kill.gd.compat.VertexFormat.Mode` + 全限定 `net.minecraft...VertexFormat`
- [x] `client/render/impl/SubtitleRenderer.java`：两处 lambda 各引入 final 局部变量
- [x] 全量搜索新增 Java 源码：无 `gale.module`、`gale.event.bus`、`Gale.LOG`、`import gale.`（除 gale.kill.gd 与 3 个替身）、
      无 `func_\d+_`、`field_\d+_`

## T3 宿主配置模块（common/ui）

- [x] 新建 `common/ui/src/main/kotlin/me/sorihiko/dango/module/impls/music/KillIconHudModule.kt`
      （7 个 `@ModuleValue`：playSound / soundVolume / presetIndex / anchorXPercent / anchorYPercent / size / demoMode）
- [x] 同文件 companion：`@JvmField var presetLabels: Array<String>`（默认 42 个「第 N 套」占位）
- [x] `DangoClient.kt` 模块列表追加 `KillIconHudModule()`

## T4 运行层（legacy）

- [x] 新建 `legacy/src/main/kotlin/me/sorihiko/dango/legacy/KillIconRuntime.kt`
- [x] 构造时 `applyPreset(8)`，并从 `effect.presets()` 填充 `KillIconHudModule.presetLabels`
- [x] `tick()`：`sync()` → `effect.onServerTick()` → 世界变化时 `reset()`
- [x] `sync()`：模块 `enabled` 与 7 个字段写入 `effect` public 字段；关闭时 `reset()`
- [x] `onAttack(target)`：记录最近攻击目标 + tick 时间（200 tick 内有效）
- [x] `onLivingDeath(e)`：自己死亡 → `reset()`；目标死亡且满足归属条件 → `onKill(1, formattedText)`
- [x] `onRenderOverlay(e)`：`e.resolution` 宽高 → `effect.render(width, height, e.partialTicks)`
- [x] 所有入口 try/catch + `DangoLogger.error`，异常不抛进游戏循环

## T5 Forge 事件接线

- [x] `ForgeBridge.onClientTick`：`FpsPortRuntime.tick()` 之后调用 `KillIconRuntime.tick()`
- [x] `ForgeBridge` 新增 `@SubscribeEvent onLivingDeath(e: LivingDeathEvent)` → `KillIconRuntime.onLivingDeath(e)`
- [x] `ForgeBridge.onAttack`：追加 `KillIconRuntime.onAttack(e.target)`
- [x] `ForgeBridge.onRenderGameOverlayPost`：动态模糊之前调用 `KillIconRuntime.onRenderOverlay(e)`

## T6 设置页接入

- [x] `SettingsPage.kt` 增加 `KillIconHudModule` import 与 `Src`
- [x] 格式化：`presetIndex` → 预设名、锚点 → 百分比、尺寸 → px、音量 → 百分比
- [x] 在 `PVP · 打击` 段、`PVP 工具` 组后插入 `Group("击杀图标", "42 套预设 · 屏幕锚点", ..., toggle = true)`
- [x] 不新增控件类型、不新增 Screen、不改动其它分组布局

## T7 构建与验证

- [x] JDK8 离线构建：`BUILD SUCCESSFUL`，EXIT=0
- [x] jar 内含 `assets/gd656killicon/presets/index.json`、380 条资源、全部 42 个预设、116 个 ogg
- [x] 静态自检四条（Gale 残留 / SRG 残留 / 资源路径 / 无新 GUI）
- [x] 游戏内四项：切预设立即生效 / 击杀触发 / 有音效 / 切服清零
      （已按 4 个接线点逐路径审查；实机表现需用户进游戏确认）
- [x] `version.properties` 2.0.1 → 2.0.2
- [x] 备份 `music pvp 2.0.2+1.8.9.jar`（17.84 MB）、`music pvp 2.0.2+1.8.9-src.zip`（16.29 MB），
      删除旧 2.0.1 同名备份

## T8 交付报告

- [x] 变更文件清单（新增 / 修改）
- [x] 4 处接线代码位置（类 + 方法）
- [x] 编译命令、EXIT 码、警告说明
- [x] 自检清单逐条结论
- [x] 未实现项与原因
