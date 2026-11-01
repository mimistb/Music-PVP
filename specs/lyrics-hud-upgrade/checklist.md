# Checklist：歌词逐字 / 三选一互斥 / HUD 拆分 / glidehud 移植

## 歌词
- [x] 灵动岛与 2D 歌词为「逐字」染色：`KaraokeText.prefix/suffix` 两段绘制，唱到字才重切，不是整句变色。
- [x] 换句平滑：灵动岛 `lineAlpha`（0.16s 淡入 + 3px 上移）；2D 歌词 `alpha` 淡入（`淡化平滑` 键控制）。
- [x] 灵动岛在找不到歌词时不再显示歌名（已删除 `fallback` 使用）；2D 歌词保留歌名兜底。
- [x] 3D / 灵动岛 / 2D 三选一互斥：`LyricsModule.syncLyricModes()` 按「刚变成开」单次收敛，多开时 3D > 灵动岛 > 2D。
- [x] 切换歌词快捷键循环：3D → 灵动岛 → 2D → 3D，且只保留一个。
- [x] `noLyrics` 总开关同时压制 3D 与 `lyricView`（灵动岛 / 2D 均取不到内容）。

## 2D 歌词
- [x] 液态玻璃背景可开关（`glass`），圆角 / 阴影取项目现有 `Theme.GLASS_WINDOW`，未自创外观。
- [x] 「分割已唱与未唱」：唱过一排（前）、未唱一排（后），各自居中。
- [x] 「竖直显示」：一字一行，超出屏幕高度截断；前奏也能看到下一句。

## 歌曲卡片
- [x] 进度条宽度 / 高度 / 上下偏移三个追加键可调（默认 1f / 5f / 0f，不影响旧配置）。
- [x] 底部进度行区域不参与拖动；卡片其余区域仍可拖动并保存位置。

## 信息胶囊
- [x] 玩家名 / 帧率 / 延迟 / 时间拆到独立模块 `HudInfoModule`，各有开关。
- [x] 未放歌时灵动岛不再显示这些信息（`sizeOf(IDLE)` 返回 0 且提前 return）。
- [x] 背景为项目液态玻璃胶囊（`GLASS_WINDOW.copy(radius = h/2f)`）。

## glidehud 移植（三模块）
- [x] 只拷入允许的 13 个 `glidehud.*` 文件；未引入 `HudTheme / SimpleHudModule / Render2D / VanillaRender2D / KeystrokesModule / TargetInfoModule`（仅 Javadoc 文字提及，无引用）。
- [x] 按键显示：`KeystrokesState` 只取状态；视觉用项目玻璃面板 + 圆角键盒；高亮只改透明度 / 亮度 + 1px 描边，不放大。
- [x] 按键显示：打开任意界面时高亮归零（`isGuiOpen`）；`unmarked` 时只画键盒不画键名。
- [x] 敌人状态：`TargetInfoState` 只取数据；面板复用玻璃与 `TRACK/GREEN` 配色；淡入淡出用 `introAlpha`，未新增缩放 pass。
- [x] 打击颜色：coremod 只替换 `RendererLivingEntity.func_177092_a` 的 4 个常量；关闭时字段为 1/0/0/0.3。
- [x] 打击颜色离线实测：补丁命中（`input=20491 → output=20679`），只在该方法出现 4 个 `GETSTATIC`，其余方法指令等价。
- [x] 反射桥失败时只记录一次并停用，不抛到游戏主循环。

## 渲染与配置约束
- [x] 所有新 HUD 都挂项目现有 `WorldRenderEndEvent` + `DangoClient.gfx` 批次，没有第二个事件 / 钩子。
- [x] 未新增 FBO、全屏 pass、着色器，未改现有玻璃材质与绘制顺序。
- [x] 新配置全部为追加 `@ModuleValue` 键，未重命名 / 改默认值既有键。
- [x] 新增代码每帧无临时对象：fps/延迟/玩家名/时钟字符串缓存、键名缓存、首字符缓存、`TargetData` 快照缓存。
- [x] 每帧绘制前先 `update()`（Keystrokes / TargetInfo / HitColor）。

## 构建
- [x] JDK8 `legacy` 构建通过（`BUILD SUCCESSFUL`）。
- [x] jar MANIFEST 含 `FMLCorePlugin: me.sorihiko.dango.core.DangoCorePlugin` 与 `FMLCorePluginContainsFMLMod: true`。
- [x] jar 含 coremod、桥、5 个新模块、`glidehud/**`；14 个类加载校验全部 OK。
- [x] 交付副本已放到 `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-1.4.1+1.8.9.jar`，大小 3,771,753 B，SHA256 `113C4D5BAA63CD79C6298AAA2BEFE65C91A8B5418065DCAA574994BA161DBE8F`。

## 说明（需实机确认）
- 本次为静态 / 离线 / 构建级验证，未启动游戏。6.7.1 的「改动前后同场景截图逐像素对比」与 FPS 对比需要你在游戏内确认；代码层面已保证除新模块区域外不触碰任何现有绘制参数。
- 敌人状态的头像位：现有渲染管线没有皮肤 UV 子区组件，按 6.2.5 用「玻璃圆角方块 + 名字首字符」占位；血条 / 护甲条复用现有 `TRACK/GREEN` 细条。
