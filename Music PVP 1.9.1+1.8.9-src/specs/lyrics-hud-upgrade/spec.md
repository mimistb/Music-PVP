# Spec：DangoMusic 1.8.9 歌词逐字 / 三选一互斥 / HUD 拆分与 glidehud 模块移植

工程根：`c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-main`
子工程：`common/core`、`common/ui`、`legacy`（1.8.9 Forge，`mappings=stable_22`，reobf → SRG）
构建：`legacy` 下 `gradlew --no-daemon --console=plain build`（JDK 8）

## 0. 硬约束（全量沿用）

* 既有 `@ModuleValue` 键名、默认值、语义一律不改；只允许追加新键，旧配置不重置。

* 不新增 FBO / 全屏 pass / 着色器；不改变渲染顺序与玻璃参数；绘制全部在 `WorldRenderEndEvent` 同一入口内。

* 不引入 glidehud 的 `HudTheme / HudModule / SimpleHudModule / Render2D / VanillaRender2D`，不改 `glidehud.*` 源码逻辑；适配只新增类。

* 工程无 Mixin，受伤红闪用 coremod（`IFMLLoadingPlugin` + `IClassTransformer`，ASM tree），不引入第二套 Mixin 引导。

* 每帧顺序：`update()` → 读值 → 绘制；不产生每帧新对象。

## 1. 已完成（本轮已落盘的现状，无需重做）

1. `common/ui/.../font/KaraokeText.kt`：逐字时间表（yrc words / LRC 按字数均分）、单调修正、二分取已唱字数、`prefix/suffix` 两段，句/字号级宽度缓存。
2. `LyricsModule.LyricView` 扩展：`line / lineEndMs / posMs / fallback`；`lyricView()` 填充 `posMs` 与句尾时间。
3. `LyricsModule.toggleLyrics()`：3D → 灵动岛 → 2D → 3D 循环；`syncLyricModes()` 三路互斥（打开者关另两个，多开时 3D > 灵动岛 > 2D 收敛）。
4. `Lyrics2DModule` 重写：保留旧键，新增 `lyrics / glass / splitRows / vertical`；逐字染色、玻璃底板、分割两排、竖直单字、换句淡入。
5. `DynamicIslandModule`：逐字染色 + 换句 `lineAlpha` 淡入 + 3px 上移；无歌词不再显示歌名；非音乐时不显示（信息已拆分）。
6. `HudInfoModule`（信息胶囊）：玩家名 / FPS / 延迟 / 时间，液态玻璃，缩放与拖动。
7. `HitColorHudModule` + `legacy/.../core/DangoCorePlugin.java` + `HitColorTransformer.java`（补丁 `func_177092_a` 的 1/0/0/0.3 四常量，字段 `dango$hitR/G/B/A`）。
8. `common/core/.../hud/HudData.kt`；`glidehud` 指定 11 个文件已按原包名拷入 `common/core`（api/core/feature/modules）与 `legacy`（mc）。

## 2. 待实现（决定级细节）

### 2.1 歌曲卡片进度条可调（`SongCardModule.kt`）

追加键（默认值 = 现状，视觉零变化）：

| 键           | 类型            | 默认 | 范围      | 说明          |
| ----------- | ------------- | -- | ------- | ----------- |
| `barWidth`  | FloatValue(2) | 1f | 0.3..1  | 进度条占可用宽度比例  |
| `barHeight` | FloatValue(1) | 5f | 2..10   | 进度条高度（逻辑像素） |
| `barOffset` | FloatValue(1) | 0f | -14..14 | 整条进度行的纵向偏移  |

`drawCard()`：

* `rowCy = b.bottom - pad - 5f*k + barOffset.value*k`，时间文字 y 跟着变。

* `availW = b.right - pad - labelW - 9f*k - barX`；`barW = (availW * barWidth.value).coerceIn(barH, availW)`。

* `barH = barHeight.value*k`。

* 拖动命中排除整条进度行底部区域：新增 `private fun progressHit(x,y,w,h,k)` 返回 `LayoutBounds(x + pad, b.bottom - pad - 13f*k, b.width - pad*2, 13f*k + pad)`；仅在卡片内且不在该区域时开始拖动。

### 2.2 按键显示（新建 `KeystrokesHudModule.kt`）

* `glidehud.feature.KeystrokesState`；每帧先 `state.keys = HudData.keys`，再 `state.update()`。

* 键：`glass(true)`、`showSpace(true)`、`unmarked(false)`、`scale(0.6..1.6,1f,2)`、`posX(0..1,0.03,3)`、`posY(0..1,0.55,3)`。

* 布局（逻辑像素）：键盒 `18x18`、间距 `3`；第一行 W 居中，第二行 A S D，第三行空格宽 `60`；内容 `66 x 66`，面板 = 内容 + `8` padding。

* 视觉：面板 `ctx.drawGlass(Theme.GLASS_WINDOW.copy(radius=min(h/2,16f), shadow=ShadowStyle(Color(0,0,0,0.14f),8f,Vec2(0,-2f))))`；键盒 `ctx.drawShape(ShapeStyle(Color(1,1,1,0.055f+0.20f*highlight), 6f))`，`highlight>0.02` 时叠 1px `Theme.ACCENT` 描边（不放大、不改尺寸）。

* 文字：`state.label(k)` 为 null 时不画键名；字号 `8.5`，居中。

* 聊天栏打开时可拖动，写回 `posX/posY`。

### 2.3 敌人状态（新建 `TargetInfoHudModule.kt`）

* `glidehud.feature.TargetInfoState`；每帧 `state.provider = HudData.targets` → `state.update()`。

* 显示条件：`state.name() != null && state.introAlpha() > 0.01f`（不依赖 `head()`，避免 skin 为空时不显示）。

* 键：`glass(true)`、`scale(0.7..1.6,1f,2)`、`posX(0..1,0.5,3)`、`posY(0..1,0.28,3)`。

* 面板：宽 `max(150, 12+26+8+名称宽+12)`，高 `52`；`pushOpacity(introAlpha)`。

* 头像位：现有管线无 skin UV 子区，按 6.2.5 用「圆角玻璃方块 + 名字首字符」占位（不使用 glide 渲染类）；血条/护甲条用 `Theme.TRACK` 底 + `Theme.ACCENT`（血）/ `Theme.TEXT_SECONDARY`（甲），比例取 `healthRatio()/armorRatio()`。

* 不使用 `introScale`（禁止额外缩放 pass）。聊天栏打开时可拖动。

### 2.4 打击颜色桥接（新建 `legacy/.../forge/HitColorBridge.kt`）

* 每 tick：`HitColorHudModule.of()?.update()`；`Class.forName("net.minecraft.client.renderer.entity.RendererLivingEntity", false, 本类classLoader)` 取静态字段 `dango$hitR/G/B/A` 并写入 `rgba()`；失败一次即 `failed=true` 不再重试，打印一次错误。

### 2.5 接线

* `DangoClient.start()` 追加 `HudInfoModule() / HitColorHudModule() / KeystrokesHudModule() / TargetInfoHudModule()`。

* `LegacyDango.start()`：先 `HudData.keys = McKeys()`、`HudData.targets = McTargetTracker()`，再 `DangoClient.start`。

* `ForgeBridge.onClientTick`：`LegacyGl.init()` 之后、`callEvent(TickEvent)` 之前调 `HitColorBridge.tick()`（try/catch 包裹）。

* `legacy/build.gradle.kts` 的 `shadowJar` 加 manifest：
  `FMLCorePlugin=me.sorihiko.dango.core.DangoCorePlugin`、`FMLCorePluginContainsFMLMod=true`。

### 2.6 设置页（`musicui/SettingsPage.kt`）

* `percentKeys` 追加 `"barWidth"`。

* `card` 的 `fmts` 追加：`barWidth`→百分比、`barHeight`→`"%.1f px"`、`barOffset`→`"%.1f px"`。

* 新增 4 组（`Music & HUD` 页签，带模块总开关）：

  * 「信息胶囊」`HudInfoModule.take()`

  * 「按键显示」`KeystrokesHudModule.take()`

  * 「敌人状态」`TargetInfoHudModule.take()`

  * 「打击颜色」`HitColorHudModule.take()`，`color` 格式化为 `#RRGGBB`

* `2D 歌词` 组继续 `l2d.take()`（自动带上 `lyrics/glass/splitRows/vertical`）。

## 3. 已发现并需在实现时修掉的缺陷

1. `Lyrics2DModule` 横排：当前句 + 下一句同时显示时总宽可超屏 → 当前句宽上限改为 `maxW*0.6f`（有下一句时）。
2. `Lyrics2DModule` 竖直模式在前奏（无 current）时什么都不显示 → 补充 `vertNext`（`curShown ?: 下一句`）。
3. `DynamicIslandModule` 的 `sizeOf()` 中 `else -> if (cur == null) 0f else kara.width` 恒为 0 → 清理为 `0f`（`coerceAtLeast(56f)` 仍生效）。

## 4. 验收（逐条自查）

* 逐字：已唱前缀/未唱后缀颜色分明；换句淡入平滑；灵动岛无歌名兜底。

* 三选一：任一时刻最多一个歌词显示；快捷键循环 3D→灵动岛→2D→3D；从设置页直接打开也收敛。

* 2D：玻璃底板、分割两排（已唱在上）、竖直单字三种版式均正确，可拖动且位置保存。

* 卡片：进度条宽/高/偏移可调；拖卡片时不会误触进度条区域。

* 信息胶囊 / 按键 / 敌人状态：玻璃底、可拖动、关闭不影响其它模块与玻璃层。

* 打击颜色：关闭时 1/0/0/0.3 与原版一致；开启生效；HUD 无变化。

* 静态检查：源码不出现 `HudTheme|HudModule|SimpleHudModule|Render2D|VanillaRender2D`；无新增 FBO/着色器。

* 构建：`gradlew build` 成功；jar 含 `me/sorihiko/dango/core/DangoCorePlugin.class`、`HitColorTransformer.class`、`glidehud/*`、4 个新模块类；MANIFEST 含 `FMLCorePlugin`；记录 SHA256。

## 5. 限制说明（需在交付时告知）

* 1.8.9 现有 UI 无「玩家皮肤头像 UV 子区」渲染组件，敌人状态头像按玻璃方块 + 首字符占位，不改现有渲染管线（符合 6.2.5，不新增皮肤渲染通道）。

