# DangoMusic 1.8.9 Forge 构建 · 中文 UI · 无损性能优化 规格

## 1. 目标与验收

| # | 目标 | 验收标准 |
|---|---|---|
| G1 | 用上传源码构建出 Minecraft 1.8.9 Forge 模组 | `legacy/build/libs/DangoMusic-1.4.1+1.8.9.jar` 生成；`reobfShadowJar` 成功；jar 内含 reobf 后的 srg 名（`func_`/`field_`）、relocate 后的 kotlin/zxing/jlayer；交付到 `c:\Users\xzy18\Desktop\TraeCode CN` |
| G2 | UI 英文改中文 | 第 4 节清单内所有用户可见英文（侧栏、页面标题、空态/加载态、按钮、设置分组与选项标签、模块 `@ModuleValue` 标签/描述、登录与一起听弹窗）全部中文；技术 ID、快捷键名、品牌名 `DANGO MUSIC`、单位 `fps`/`ms` 保留 |
| G3 | 性能优化且视觉零变化 | 仅优化测量缓存、批处理、分配/状态查询；不改布局、坐标、字号、颜色、字形位置、绘制顺序、动画曲线、shader/FBO 流程。验证方式见第 6 节 |

## 2. 现状与构建卡点（已取证）

- 源码：`c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic_src\DangoMusic-main`（zip 解压）。
- 构建链：Gradle 7.0.2 wrapper + Kotlin 2.0.21 + Shadow 7.1.1 + ForgeGradle（`legacy/build.gradle.kts`，`includeBuild("../common")`）。
- JDK8：`C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot`。
- **卡点**：`buildscript.classpath("net.minecraftforge.gradle:ForgeGradle-Modern:2.1")` 无法解析（files.minecraftforge.net/maven、Maven Central、mavenLocal、plugins.gradle.org 均 404；上游 GitHub 同坐标，说明作者本地私有产物）。
- 本地缓存：`~/.gradle/caches/minecraft/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9` 已存在，`modules-2` 仅 `ForgeGradle:2.1-SNAPSHOT`（Gradle 7 不兼容）。
- 网络已验证：`com.anatawa12.forge:ForgeGradle:2.3-1.0.8` 在 Maven Central **返回 200**，其包名仍为 `net.minecraftforge.gradle.*`，因此 `import net.minecraftforge.gradle.user.patcherUser.forge.ForgeExtension` 与 plugin id `net.minecraftforge.gradle.forge` 不变。

### 决策 D1（构建工具链替换）
只改 `legacy/build.gradle.kts` 第 13 行：
```
classpath("net.minecraftforge.gradle:ForgeGradle-Modern:2.1")
→ classpath("com.anatawa12.forge:ForgeGradle:2.3-1.0.8")
```
其余 `forge { version / mappings = "stable_22" / makeObfSourceJar = false }`、shadow、reobf 配置**原样保留**。

失败回退阶梯（按序尝试，不得跳级改项目结构）：
1. `2.3-1.0.8` 若报 Gradle 7 不兼容 → 试 `com.anatawa12.forge:ForgeGradle:2.3-1.0.9`（或该坐标最新 1.0.x）。
2. 若该 fork 不支持 `stable_22` 拉取 → 改用本地已缓存 `stable_20`（仅改 `mappings`），reobf 后运行名不受影响。
3. 若 Gradle 侧彻底不可行 → 降级为手工构建：kotlinc(2.0.21) + javac 对 `srg-merged-1.8.9` 编译 → 手工 shade/relocate → 用 ForgeGradle 的 SpecialSource 做 reobf；此路径需先向用户说明再执行。

## 3. 构建流程（G1）

1. `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot`。
2. 先在线解析新 classpath（首次需联网）：
   `cd legacy; .\gradlew.bat --no-daemon build`（首次不加 `--offline`）。
3. 通过后复现构建：`.\gradlew.bat --no-daemon --offline build`。
4. 产物校验：
   - `jar tf` 检查：`me/sorihiko/dango/shadow/kotlin/**`、`me/sorihiko/dango/shadow/zxing/**`、`mcmod.info`、`me/sorihiko/dango/forge/DangoMusicMod.class`；
   - `javap -p -c` 抽查 `LegacyGfx` / `ForgeBridge` 内出现 `func_` 调用即 reobf 生效；
   - 条目数记录，与优化后构建对比应仅 class 字节变化。
5. 复制 jar 至 `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-1.4.1+1.8.9.jar`。

**执行顺序硬约束**：先产出基线 jar（翻译前、优化前）→ 再做翻译 → 再优化。便于第 6 节像素对比与问题二分。

## 4. 中文翻译清单（G2，逐文件精确到字符串）

字体链路已具备：`MixedText` + `CjkFont`（系统字体动态光栅化，1024×1024 多页图集），中文可直接渲染；拉丁段仍用 Inter，保持字号与基线不变。翻译仅替换字符串字面量，不改坐标/字号参数。

### legacy 平台层
- `legacy/src/main/java/me/sorihiko/dango/forge/DangoMusicMod.java`：`name = "Dango Music"` 保留（模组技术名）。

### common/ui — 框架与界面
1. `common/ui/src/main/kotlin/me/sorihiko/dango/music/MusicScreen.kt`
   - `Welcome to Dango Music!` → `欢迎使用 Dango Music！`
   - `Your playlists` → `你的歌单`；`Recommended playlists` → `推荐歌单`
   - `Charts` → `排行榜`；`"${c.size} charts"` → `"${c.size} 个榜单"`
2. `.../musicui/Sidebar.kt`
   - `Search songs...` → `搜索歌曲…`；`DANGO MUSIC` 保留
   - `Home` → `首页`；`DISCOVER` → `发现`；`Charts` → `排行榜`；`Now Playing` → `正在播放`；`Listen Together` → `一起听`
   - `MY PLAYLISTS` → `我的歌单`；`Loading...` → `加载中…`；`No playlists` → `暂无歌单`
   - `Log in to see your playlists` → `登录后查看你的歌单`
   - `Settings` → `设置`；`Logged in` → `已登录`；`Log out` → `退出登录`；`Log in to NetEase` → `登录网易云音乐`
3. `.../musicui/PlayerBar.kt`：`Not playing` → `未在播放`；`Shuffle` → `随机播放`；`-` 保留
4. `.../musicui/TracksPage.kt`
   - `Now Playing` → `正在播放`
   - `No songs match "$filterText"` → `没有匹配「$filterText」的歌曲`
   - `Search  "$q"` → `搜索「$q」`；`Searching...` → `搜索中…`
   - `Search failed: ` → `搜索失败：`；`No results` → `无结果`
   - `Nothing in the queue yet` → `播放队列为空`；`Loading...` → `加载中…`
   - `"${shown} of ${total} songs"` → `"${shown} / ${total} 首"`；`"${n} songs"` → `"${n} 首歌"`
   - `Play all` → `播放全部`；`List` → `列表`；`Covers` → `封面`；`Locate` → `定位`
   - `Filter this list...` → `在本歌单内筛选…`；`TITLE` → `歌曲`；`TIME` → `时长`
5. `.../musicui/GridPage.kt`：`Loading...` → `加载中…`
6. `.../musicui/PartyPage.kt`
   - `Listen Together` → `一起听`
   - `Play the same song in sync with friends who also have this mod` → `和同样安装本模组的朋友同步听同一首歌`
   - `You are the host · everyone in the room follows your music` → `你是房主 · 房间内所有人跟随你的音乐`
   - `Following ` → `正在跟随 `（`+ ListenParty.hostName`）
   - `Listen Together is not set up (missing firebase.properties)` → `一起听功能未配置（缺少 firebase.properties）`
   - `Working...` → `处理中…`；`Create a room` → `创建房间`
   - `OR JOIN WITH A ROOM CODE` → `或使用房间码加入`；`ROOM CODE` → `房间码`；`Join` → `加入`
   - `Copied` → `已复制`；`Copy` → `复制`
   - `"IN THIS ROOM · " + n` → `"房间成员 · " + n`
   - `"  (you)"` → `"（你）"`；`HOST` → `房主`
   - `Close room` → `关闭房间`；`Leave room` → `离开房间`；`Resync` → `重新同步`
   - 底部说明 → `每个人使用自己的网易云账号串流，VIP 歌曲需要双方都有 VIP。`
7. `.../musicui/LoginDialog.kt`
   - `Log in to NetEase` → `登录网易云音乐`；`Refresh QR` → `刷新二维码`
   - `x` 保留（关闭图标字形，改字会改布局）
8. `.../musicui/SettingsPage.kt`
   - 标题 `Settings` → `设置`；副标题 `Changes apply instantly and are saved automatically` → `修改即时生效并自动保存`
   - 页签 `Lyrics` → `歌词`；`Music & HUD` → `音乐与 HUD`
   - 分组：`Placement` → `位置`；`In the world` → `世界内显示`；`Glow` → `发光`；`Deep Glow` → `深度发光`；`Motion` → `动画`；`Animation` → `动画效果`；`Now playing` → `正在播放`；`Classic overlay` → `经典悬浮层`；`Other` → `其他`；`More` → `更多`；`Player` → `播放器`；`Playback` → `播放`；`Hotkeys` → `快捷键`；`Click, then press a key · Backspace clears` → `点击后按下按键 · 退格键清除`；`Song card` → `歌曲卡片`；`Overlay` → `悬浮层`；`Dynamic Island` → `灵动岛`
   - 控件 `Press a key` → `请按键`；`None` → `无`
9. `.../module/impls/music/LyricsModule.kt`（`@ModuleValue` 标签/描述）
   - `3D lyrics`→`3D 歌词`；`Now playing`→`正在播放`；`Cover`→`封面`
   - `Scale`→`缩放`；`Distance`→`距离`
   - `Glow`→`发光`；`Glow color`→`发光颜色`；`Glow strength`→`发光强度`；`Glow radius`→`发光半径`
   - `Smooth font`→`平滑字体`；`Wiggle`→`抖动`；`Wiggle fps`→`抖动帧率`；`Wiggle amount`→`抖动幅度`
   - `Text flash`→`文字闪烁`；`Word pop`→`逐词弹出`；`Flash size`→`闪烁尺寸`
   - `Face player`→`面向玩家`；`Keep away`→`保持距离`；`Stay on screen`→`保持在屏幕内`；`Above ground`→`位于地面上方`；`Spread`→`分散`；`Split lines`→`分行`
10. `.../module/impls/music/DynamicIslandModule.kt`
    - `Liquid glass`→`液态玻璃`；`Music`→`音乐`；`Song notify`→`歌曲通知`；`Scale`→`缩放`；`Notify time`→`通知时长`
    - 状态 `PAUSED`→`已暂停`；`LOADING`→`加载中`；`NOW PLAYING`→`正在播放`
11. `.../module/impls/music/SongCardModule.kt`
    - `Scale`→`缩放`；`Show when paused`→`暂停时显示`；`Pos X`→`X 坐标`；`Pos Y`→`Y 坐标`
12. `.../module/impls/music/NeteaseMusicModule.kt`
    - `Volume`→`音量`；`Shuffle`→`随机播放`；`Open key`→`打开快捷键`；`Play / Pause`→`播放 / 暂停`；`Previous`→`上一首`；`Next`→`下一首`；`Volume up`→`音量增加`；`Volume down`→`音量减少`；`Toggle lyrics`→`切换歌词`

**不做**：`@ModuleMeta` 的模块名（`NeteaseMusic` 等）与配置 key、`Keys.*` 常量名、`firebase.properties`、日志文本。理由：模块名可能参与配置持久化与模块查找，改字有重置用户设置/破坏引用的风险；如需中文化模块列表名，作为可选后续项单独确认。

**翻译后必查**：每个改动字符串所在一行的 `ui.width(...)`/`ui.trim(...)` 调用保持原参数（宽度参数不变），仅字符串变短/变长由测量自适应；任何硬编码宽度（如 `190f`、`320f`）不得改。

## 5. 性能优化清单（G3，逐文件逐函数）

原则：**只减少重复计算、堆分配、JNI/GL 状态调用与逐顶点提交次数；不改任何影响像素的量**（坐标、字号、颜色、alpha、绘制顺序、裁剪、动画参数、blur pass 次数、shader 分支）。

1. `legacy/.../ui/GlassRenderContext.kt`
   - `getTransform()`：栈空时返回**单例复用** `Matrix4f`（渲染单线程），消除每个 shape 的 `Matrix4f()` 分配；`pushTransform/popTransform` 用对象池复用副本。
   - `drawText()`：把逐字符 `uiFont.getGlyph()` 循环改为从 `UiFont` 取**预编排字形序列**（位置/UV 与原循环逐项相同），仍走同一 `glBegin/glEnd`。
2. `legacy/.../font/UiFont.kt`
   - `glyphMap` → ASCII 32..126 直索引数组（保留 map 兜底非常用字）。
   - 新增 `widthOf(text, size, scale)` 结果缓存：`HashMap<Key, Float>`，上限 1024 条，超限整表清空；Key 含 size/scale，命中返回克隆语义的相同 float。
3. `legacy/.../font/CjkFont.kt`
   - `glyph()`：`containsKey`+`get` 二次查表 → 单次 `get` + null 判断。
   - `draw()`：去掉 `s.map{...}` 中间 list；按 page 归组，**每 page 一次 bindPage + 一次 glBegin/glEnd**，顶点顺序与原逐字提交完全一致。
   - `drawCentered()`：同上批处理，中心对齐表达式不变。
4. `legacy/.../ui/glass/GlassBackdrop.kt`
   - 复用预分配的顶点/纹理坐标缓冲与 uniform 局部变量；去掉每帧冗余 GL 状态查询（`glGetInteger` 等），改为帧内缓存；**降采样级数、高斯迭代次数（soft 2 / frost 3）保持不变**。
5. `common/ui/.../font/MixedText.kt`
   - 段记录由 `substring` 改为 `(start, end)` 索引；`width` 用一次前缀宽度扫描。
   - `trim()`：由「每次循环重算 `width(substring+"...")`」的 O(n²) 改为单次前缀宽度 + 省略号宽度判定，返回同一字符串。
   - `wrap2()`：单次累加宽度分行；复用行列表容器（`clear()` 而非新建）。
6. `common/ui/.../musicui/Ui.kt`
   - `text/width/trim/wrap2` 走带上限的测量缓存（键：字符串+字号+字重）。
   - `pageKey` 拼接：仅在 `page/opened.id/settingsTab/coverView/lastQuery` 变化时重算并缓存。
   - `itemAnim/headerAnim/ease*` 数学不变。
7. `common/ui/.../musicui/SettingsPage.kt`
   - `"%.${d}f".format(v)`：格式串按小数位预建表；数值-小数位→字符串缓存（滑块拖动时同值不重格式化）。
8. `common/ui/.../module/impls/music/LyricsModule.kt`（热路径）
   - `drawAnimated`：`Glyph` 列表与 `charW` 数组改为成员复用（`clear()`），避免每帧新建。
   - 字符宽度：接入 `UiFont`/`MixedText` 宽度缓存。
   - 平滑字体 `drawGlyph`：`c.toString()` → 预建 0..127 单字符串表。
   - `wrap()`：单次累加宽度，去掉 `strW(sb.toString())` 每字符重建。
   - `withWiggle` 的 push/translate/rotate/scale/pop 序列、`keepOnScreen` 弹簧步长 1/240 **不变**。
9. `common/ui/.../module/impls/music/DynamicIslandModule.kt`
   - `SimpleDateFormat("HH:mm")` 提为静态单例 + 按秒缓存结果。
   - `"${fps} fps"` / `"$ping ms"` / 状态文案仅在数值或状态变化时重建。
10. `common/ui/.../module/impls/music/SongCardModule.kt`
    - 时间与进度文本按「显示秒」缓存，避免每帧格式化。
11. `common/ui/.../musicui/TracksPage.kt` / `GridPage.kt`
    - 副标题、行内文本的字符串插值按状态缓存；`lines.forEachIndexed` → 索引 `for`（不产生迭代器闭包）。
    - `shown()` 既有缓存保留不动。

不改动项（明确排除）：`Theme.kt` 常量与颜色、`RenderContext`/`Gfx` 接口签名、shader 源、FBO 尺寸、动画缓动常数、所有 `LayoutBounds` 数值。

## 6. 视觉零变化的验证方案

1. **构建三连**：基线 jar（B）→ 翻译后 jar（T）→ 优化后 jar（T+O）。三份都保留。
2. **像素对比（主验证）**：`cd legacy; .\gradlew.bat runClient` 启动开发客户端，用同一窗口尺寸（1280×720，界面缩放 2）依次截图：
   - 设置页（无需登录，可覆盖分组/控件/滑块/页签）
   - 侧栏 + 一起听页 + 登录弹窗（未登录态）
   - HUD 模块：歌词/歌曲卡片/灵动岛（本地播放 `music` 目录内音频，或暂停态）
   对 T 与 T+O 截图用 PowerShell + .NET `Bitmap` 逐像素比对：要求差异像素数 = 0（同状态、同上电时刻；动画元素先暂停/静止后截图）。
3. **静态审计（辅助）**：对每个改动函数，确认顶点/颜色/顺序来源表达式未变；`javap` 检查无新增字段导致序列化差异。
4. **功能回归**：按键开合界面、搜索、列表滚动、歌单墙滚动、快捷键绑定、一起听房间码输入、二维码刷新、拖动音量/进度、退出重进（配置保持）。
5. **性能取证**：记录优化前后同场景 FPS 与 `-Xmx` 分配（`jcmd GC.class_histogram` 或 JFR 简采），确认字符串/矩阵分配下降，且无 FPS 回退。
6. runClient 无法启动时：退化为「静态审计 + 用户实机确认」，并在交付说明中标注未做像素对比。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| ForgeGradle fork 与 Gradle 7.0.2 / `stable_22` 不兼容 | 第 2 节回退阶梯；必要时先向用户说明再走手工构建 |
| 中文字形缺字/宽度溢出 | `CjkFont` 已有多页图集；翻译后逐页截图检查截断；`trim` 参数不改；缺字回退方块时改用等价中文表述 |
| 翻译改变字符串长度导致关键文案换行 | 仅检查是否被 `trim/wrap` 截断，不改布局常量；必要时缩短用词（如「在歌单内筛选…」） |
| 优化误改绘制顺序/合批跨 page 导致视觉差异 | 合批严格同 page、同图集、同 z 序；像素对比为放行门槛 |
| 配置/模块名改动导致用户设置重置 | 本次不动 `@ModuleMeta` 名与配置 key |
| 一次构建耗时长、在线依赖拉取失败 | 先联网解析一次后转 `--offline`；保留构建日志 `build_legacy.log` |

## 8. 交付物

1. `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-1.4.1+1.8.9.jar`（最终，中文 UI + 优化）
2. 源码改动保留在 `DangoMusic_src\DangoMusic-main`（`legacy/build.gradle.kts` 1 行 + 第 4/5 节所列文件）
3. 构建与验证记录（构建日志、像素对比结果、jar 条目/哈希）
