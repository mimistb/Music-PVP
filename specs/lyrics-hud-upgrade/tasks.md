# Tasks：歌词逐字 / 三选一互斥 / HUD 拆分 / glidehud 移植

> 依赖顺序：先修缺陷 → 再补未实现模块 → 接线 → 设置页 → 自检 → 构建。

## T1 修复既有缺陷（common/ui）
- [x] `Lyrics2DModule.render()`：有下一句时当前句截断宽上限 `maxW*0.6f`；竖直模式补 `vertNext`。
- [x] `DynamicIslandModule.sizeOf()`：IDLE 文本宽三元表达式清理为确定值。
- 验证：字号 36、超长句 + 提前歌词不越界；竖直模式前奏可见。

## T2 歌曲卡片进度条可调（`SongCardModule.kt`）
- [x] 追加 `barWidth / barHeight / barOffset` 三个 FloatValue（默认 1f / 5f / 0f）。
- [x] `drawCard()` 用新键计算行位置、条高、条长；时间文字跟随 `barOffset`。
- [x] 拖动命中排除底部进度行区域（新 helper）。
- 验证：三键在设置页可调；拖卡片不误触进度条。

## T3 按键显示模块（新建 `KeystrokesHudModule.kt`）
- [x] `KeystrokesState` + `HudData.keys`，每帧 `update()`。
- [x] 玻璃面板 + W/A S D/空格 键盒；`highlight` 驱动亮度与 1px 描边，不放大。
- [x] `showSpace / unmarked / scale / posX / posY`；聊天栏拖动写回位置。

## T4 敌人状态模块（新建 `TargetInfoHudModule.kt`）
- [x] `TargetInfoState` + `HudData.targets`，每帧 `update()`。
- [x] 玻璃面板 + 首字符头像占位 + 生命/护甲两条；`introAlpha` 淡入淡出（不加缩放 pass）。
- [x] `glass / scale / posX / posY`；聊天栏拖动。

## T5 打击颜色桥接（新建 `legacy/.../forge/HitColorBridge.kt`）
- [x] 每 tick 调 `HitColorHudModule.update()`，反射写 `RendererLivingEntity.dango$hitR/G/B/A`。
- [x] 反射失败只报警一次并停用，不影响其它功能。

## T6 接线
- [x] `DangoClient.start()` 注册 4 个新模块。
- [x] `LegacyDango.start()` 注入 `HudData.keys/targets`。
- [x] `ForgeBridge.onClientTick` 调 `HitColorBridge.tick()`。
- [x] `legacy/build.gradle.kts` shadowJar 增加 `FMLCorePlugin` 与 `FMLCorePluginContainsFMLMod`。

## T7 设置页
- [x] `percentKeys` 加 `barWidth`；`card` 的 `fmts` 补 3 项。
- [x] 新增「信息胶囊 / 按键显示 / 敌人状态 / 打击颜色」四组；`color` 显示 `#RRGGBB`。

## T8 自检
- [x] 扫描源码无 `HudTheme|HudModule|SimpleHudModule|Render2D|VanillaRender2D` 引用（仅 Javadoc 文字，无 import / 调用）。
- [x] 新增键全部是追加键，旧键名/默认值逐项对照未变。
- [x] 三路互斥无振荡（连续多帧只开一个）；`noLyrics` 总开关生效。
- [x] 每帧无新增对象分配（缓存/复用）；`update()` 在绘制前。
- [x] 删除临时文件 `c:\Users\xzy18\Desktop\TraeCode CN\_tmp_rle.txt`（若存在）。
- [x] 受伤红闪补丁离线实测命中：`input=20491 output=20679`，4 个 `GETSTATIC dango$hitR/G/B/A`，仅该方法常量与分支偏移变化。
- [x] 编译期错误修复：`Lyrics2DModule` 竖直模式 `Int != Float` 比较；`HitColorTransformer` 补 `isAload0` / `VarInsnNode`。
- [x] 新类加载校验：14 个类（coremod / 桥 / 5 个模块 / glidehud）全部 OK。

## T9 构建与交付
- [x] `legacy` 下 JDK8 `gradlew --no-daemon --console=plain build` 一次通过（必要时先修编译错误再整体重跑）。
- [x] 校验 jar：条目含 `me/sorihiko/dango/core/DangoCorePlugin.class`、`HitColorTransformer.class`、`glidehud/**`、4 个新模块类；MANIFEST 含 `FMLCorePlugin`；记录大小与 SHA256。
- [x] 中文交付说明：功能、按键循环、限制（头像占位）、配置为追加键。
