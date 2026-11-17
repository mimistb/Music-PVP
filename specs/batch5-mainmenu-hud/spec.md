# 2.0.8 主菜单入口 · 液态模糊 · 自动翻译 · Hyp 自动取数 · HUD 拆分 · 账号切换

## 1. 目标与范围

对 Minecraft 1.8.9 Forge 客户端 mod「DangoMusic / Music PVP」做 8 项改造，全部沿用现有液态玻璃设计语言（深色底、白色低透明度玻璃面、#0A84FF 强调色、圆角 18/10、Inter 字体）。不新增独立设置界面；设置项继续由 `SettingsPage` 的反射式 `Src/Group/Row` 渲染。coremod 只允许 ASM，不引入 mixin。

版本：`2.0.7` → `2.0.8`。构建：`legacy` 目录下 `.\gradlew.bat build --offline`。

## 2. 现状调研结论（已确认）

### 2.1 主菜单替换链
- `legacy/src/main/java/me/sorihiko/dango/core/SplashGlassTransformer.java`
  - 在 `GuiMainMenu.drawScreen(IIF)V` 头注入 `SplashGlassHook.beginMainMenuFrame()V`，每个 RETURN 前注入 `drawMainMenuForeground(II)V`。
  - 在 `GuiButton.drawButton` 的 RETURN 前注入 `recordButton(...)`。
  - 目前**没有**鼠标点击注入。
- `SplashGlassHook.java`
  - `drawMainMenuForeground` 中标题卡片几何：`cardW = min(430, width-36)`，`cardH = min(112, max(54, height*0.185))`，`cardX=(width-cardW)/2`，`cardY=10+(1-ease)*8`（L234-237）。
  - 标题卡片右上角在 `cardX+cardW-38 … cardX+cardW-12`、`cardY+12 … cardY+38` 区域内为空白，可放齿轮入口。
  - 按钮绘制 `drawGlassButton`（L489-525）可复用玻璃样式。
- `LegacyMusicScreen.kt` 构造参数 `(parent: GuiScreen? = null)`，`initGui()` 中调用 `screen.opened()`。
- `Platform.openMusicScreen()` 默认打开普通播放器页。

### 2.2 背景模块引用（需彻底删除）
- `common/ui/.../module/impls/music/BackgroundModule.kt`：整个文件。
- `common/ui/.../musicui/BackgroundImage.kt`：整个文件。
- `common/ui/.../DangoClient.kt`：import + `BackgroundModule(),` 注册（L5、L55）。
- `common/ui/.../music/MusicScreen.kt`：import（L5、L7）、`background` 懒加载字段（L55-62）、`render()` 中 `drawBackground(alpha)`（L190）、`drawBackground`/`drawBackgroundImage`（L236-306）。
- `common/ui/.../musicui/SettingsPage.kt`：import（L9）、`val bg = Src(...)`（L751-756）、`bgRows` 与「选择图片」按钮（L757-771）、`Group("界面背景", ...)`（L775）。
- `common/core/.../platform/Platform.kt`：`fun pickImageFile(): String? = null`（L68）。
- `legacy/.../LegacyPlatform.kt`：`override fun pickImageFile()`（L74-86）。
- 已确认无其它源码引用（`build/` 里的是旧产物，不参与源码修改）。

### 2.3 液态玻璃
- 所有 `drawGlass` 最终进入 `legacy/.../ui/GlassRenderContext.kt#drawGlass`（L204-246）。
- 模糊背景由 `legacy/.../ui/glass/GlassBackdrop.kt` 生成，质量档读 `FpsToolsModule.perfGlassQuality`。
- `GlassRenderContext` 在 `backdrop == null || !backdrop.ready` 时退化画 `style.fallback` 纯色圆角矩形（L209-213），可直接复用为「关闭液态模糊」的绘制分支。

### 2.4 聊天翻译
- 配置模块 `common/ui/.../ChatTranslateSettingsModule.kt`，运行层 `legacy/.../ChatTranslateRuntime.kt`，效果类 `legacy/.../fpsport/chattranslate/ChatTranslateModule.java`。
- 现状：`onChatReceived` 只追加 ✎；注释明确「收到消息不自动翻译」。`translateOutgoing` 默认 `true`（发送前翻译）。
- 因此「自动翻译玩家话语」默认必须为 **false**，与现有产品逻辑一致。

### 2.5 Hyp 取数
- `legacy/.../BedWarsRuntime.kt`
  - `readRoster()` 每 tick 从 `netHandler.getPlayerInfoMap()` 填充 `roster`。
  - `syncStats(active, ownTeam, now)`（L593-622）当前仅在 `active || TabStatsHudModule.isOpen` 时工作，且只查询比赛对手 / 按住 TAB 时的名单。
- `common/core/.../bedwarsstats/RosterStatsService.java`
  - 已有内存缓存 `statsCache`（默认 6 小时、最多 1024 条）、10 分钟去重、请求间隔 1100ms、每局上限 128 人。
  - 缓存生命周期跨越对局，符合「程序运行期间本地缓存」要求，无需新增磁盘缓存。

### 2.6 信息胶囊
- `common/ui/.../HudInfoModule.kt`：`parts()` 按 name/fps/ping/clock/armor/cps 分块，`render()` 用同一个 `posX/posY` 把全部胶囊横向排成一行；打开聊天栏后可整体拖动。
- 需要拆成「名字+护甲」与「fps / 延迟 / 时间 / CPS」各自独立位置、各自拖动。

### 2.7 账号
- `common/core/.../music/LoginAccounts.kt`：`accounts` 下存 `qq` / `kugou`；**没有** `activePlatform`。
- `common/ui/.../musicui/Sidebar.kt#drawAccount`（L178-220）：只显示一个账号（网易云优先，否则 `firstLoggedIn()`）。
- `LoginDialog` 已支持三平台，已登录平台不会重新走扫码流程。
- 代码中不存在 QQ / 酷狗播放 API，实际播放只有网易云。因此「切换平台账号」实现为：三平台登录态展示 + `activePlatform` 持久化 + 点击已登录平台即切换当前展示/上下文；不伪造不存在的播放能力。

## 3. 方案（逐项）

### 3.1 删除旧主界面背景模块
1. 删除 `BackgroundModule.kt`、`BackgroundImage.kt`。
2. `DangoClient.kt` 去掉 import 与注册项。
3. `MusicScreen.kt` 去掉 import、`background` 字段、`render()` 中的 `drawBackground(alpha)`、`drawBackground`、`drawBackgroundImage`。
4. `SettingsPage.kt` 去掉 `BackgroundModule` import、`bg`/`bgRows`/「选择图片」按钮构造、`Group("界面背景", ...)`。
5. `Platform.kt` 删除 `pickImageFile()`；`LegacyPlatform.kt` 删除对应 override。
6. 清理因此不再使用的 import（`StringValue` 仍被聊天翻译等处使用，按编译结果决定）。

### 3.2 主标题界面右上角功能入口
1. `SplashGlassHook.java`
   - 新增静态字段 `gearX/gearY/gearW/gearH`、`gearHover`。
   - 在标题卡片绘制完成后计算并绘制齿轮入口：26×26，位于 `cardX+cardW-38, cardY+12`，玻璃底 + 8 齿 + 中心圆（纯 GL 绘制，不依赖字体图标）。
   - `gearHover` 按鼠标位置平滑插值，悬停时描边变强调色。
   - 新增 `public static boolean onMainMenuClick(int mouseX, int mouseY, int button)`：命中齿轮且左键时打开设置页并返回 true。
2. `SplashGlassTransformer.java`
   - 新增对 `GuiScreen.mouseClicked(III)V`（MCP `mouseClicked` / srg `func_73864_a`）头部的注入：调用 `SplashGlassHook.onMainMenuClick(III)Z`，返回 true 时直接 `RETURN`，阻止原版继续处理。
3. `LegacyMusicScreen.kt`
   - 构造参数增加 `openSettings: Boolean = false`；`initGui()` 中 `screen.opened()` 后若为 true 调用 `screen.openSettings()`。
4. `MusicScreen.kt`
   - 新增 `fun openSettings()`：`settings.commitEdit(); ScreenState.settingsBack = ScreenState.page; ScreenState.page = Page.SETTINGS`。
5. `SplashGlassHook` 点击时 `mc.displayGuiScreen(new LegacyMusicScreen(null, true))`。

### 3.3 液态模糊开关
1. `NeteaseMusicModule.kt`
   - 新增 `@ModuleValue("液态模糊", "关闭后所有液态玻璃面板改为半透明纯色，降低 GPU 占用") val liquidBlur = BooleanValue(true)`。
   - 增加 `init()` 与 companion `instance/of()`（与其它模块一致）。
2. `legacy/.../ui/GlassRenderContext.kt#drawGlass`
   - 在阴影绘制后读取 `NeteaseMusicModule.of()?.liquidBlur?.get() ?: true`；为 false 时走现有 `style.fallback` 纯色分支并 return。
3. `SettingsPage.kt`
   - 新增 `Group("界面", "液态模糊 · 显示效果", player.m, true, player.take(listOf("liquidBlur")), section = "界面")`，替换被删除的「界面背景」组。

### 3.4 自动翻译玩家话语
1. `ChatTranslateSettingsModule.kt`
   - 新增 `@ModuleValue("自动翻译玩家话语", "收到其他玩家聊天时自动补一条译文（默认关闭）") val autoTranslateIncoming = BooleanValue(false)`。
2. `ChatTranslateRuntime.kt#sync`
   - `effect.autoTranslateIncoming = module.autoTranslateIncoming.get()`。
3. `ChatTranslateModule.java`
   - 新增 `public boolean autoTranslateIncoming = false;`。
   - `onChatReceived`：
     - 保留原有 ✎ 逻辑；
     - 当 `autoTranslateIncoming && chatType == 0`（玩家聊天）时：先打印原文（按 `showTranslateButton` 决定是否带 ✎），再异步翻译 `raw` 到 `incomingLanguage`；译文非空且与原文不同才打印 `§7[译] §f...`；失败静默；
     - 复用现有单线程池与 64 条 LRU 缓存，避免刷屏与重复请求；
     - 自动翻译不受 `showTranslateButton` 影响（两者独立）。
4. `SettingsPage.kt`
   - 在「聊天翻译」分组中显式加入 `autoTranslateIncoming` 行（按现有 `chatTranslate.take(...)` 调用处调整）。

### 3.5 Hyp 数据自动获取
1. `BedWarsRuntime.kt`
   - 新增常量 `MAX_AUTO_STATS_PLAYERS = 45`。
   - `syncStats(active, ownTeam, now)` 改为：
     - `working = mc.theWorld != null && roster.size <= MAX_AUTO_STATS_PLAYERS`；
     - 不再依赖 `TabStatsHudModule.isOpen`，也不再只查比赛对手；
     - `working` 为 true 且 `hypStats && hypAutoQuery` 时，为 `roster` 中所有有效玩家入队（队伍未知按 `Team.WHITE`）；
     - `working` 变为 false 时 `rosterService.setMatchActive(false)` 并清空 `enqueued`；
     - 世界切换 / 名单人数从 >45 降到 ≤45 时清空 `enqueued`，允许重新入队（服务层 10 分钟去重 + 6 小时缓存仍生效）。
   - 保留 `handleQuery()` 手动查询不受 45 人限制影响。
   - `setMatchActive` 的语义改为「自动取数管线是否工作」，注释同步更新。

### 3.6 FPS / 时间 / CPS / 延迟拆分
1. `HudInfoModule.kt`
   - 新增 8 个位置项：`fpsX/fpsY`、`pingX/pingY`、`clockX/clockY`、`cpsX/cpsY`（`FloatValue(0f,1f,默认值,3)`）。
   - `posX/posY` 保留给「名字+护甲」块，默认值由 `0.5f` 改为 `0.40f`。
   - 默认位置：fps `0.50/0`、ping `0.56/0`、clock `0.61/0`、cps `0.66/0`（1280 逻辑宽度下约等于原来的连续横排）。
   - `parts()` 增加 `blockKind` 记录每个块类型；`addBlock(kind) {}`。
   - `render()`：每个块按自己的位置计算 `x/y`；聊天栏打开且左键按下时，命中哪个块就只拖动哪个块，写回对应位置项；其余块不动。
   - 文本裁剪从「整行矩形」改为全屏逻辑矩形，避免独立位置被裁掉。
2. `SettingsPage.kt`
   - `percentKeys` 加入 `fpsX/fpsY/pingX/pingY/clockX/clockY/cpsX/cpsY`，显示为百分比。

### 3.7 三平台账号展示与切换
1. `LoginAccounts.kt`
   - 新增 `activePlatform(): String` / `setActivePlatform(provider: String)`，持久化到根节点 `activePlatform`（`netease` / `qq` / `kugou`）。
   - 默认：文件里没有时返回 `netease`；`setActivePlatform` 忽略未知值。
2. `Sidebar.kt#drawAccount`
   - 改为三行平台列表（每行 26px）：
     - `N` 网易云：`NeteaseApi.loggedIn` 为已登录，显示昵称；
     - `Q` QQ 音乐：`LoginAccounts.qqAccount() != null`；
     - `K` 酷狗：`LoginAccounts.kugouAccount() != null`。
   - 已登录：显示「已登录」与脱敏 ID；未登录：显示「未登录 · 点击登录」。
   - 当前 `activePlatform` 行用强调色描边 + 左侧短竖条标记。
   - 点击已登录平台：`setActivePlatform(provider)`，**不触发扫码**。
   - 点击未登录平台：`screen.login.open()`（`LoginDialog` 已登录平台不会重新扫码）。
   - 保留当前活跃平台的「退出登录」小按钮（网易云走 `NeteaseApi.logout()`，第三方走 `LoginAccounts.logout(provider)`）。
   - `footerH` 由 92 调整为 150，保证歌单列表不重叠。
3. `LoginDialog`
   - 登录成功后调用 `LoginAccounts.setActivePlatform(provider)`（网易云为 `netease`）。

### 3.8 验证与交付
1. `gradle.properties` / `version.properties` 中版本改为 `2.0.8`。
2. 离线构建：`cwd=legacy`，`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot`，`.\gradlew.bat build --offline`。
3. 编译错误逐个修复，直到 BUILD SUCCESSFUL。
4. 备份产物到 `c:\Users\xzy18\Desktop\TraeCode CN\music pvp`（jar + src zip）。
5. 输出中文完成报告，明确说明：
   - 主菜单齿轮入口打开的是播放器设置页；
   - 液态模糊开关影响所有 `drawGlass` 面板（播放器 UI / HUD），主菜单为静态 GL 绘制不受影响；
   - QQ/酷狗当前只有登录态与切换，没有播放 API，不会伪造播放能力；
   - 无法实机启动游戏，验证以编译 + 静态检查为准。

## 4. 兼容性与风险

- 所有新增 `@ModuleValue` 都会被 `ConfigManager` 自动持久化；旧配置缺键时使用默认值，不破坏现有配置。
- 删除 `BackgroundModule` 后旧配置里的 `Background` 段会被忽略，不影响启动。
- `HudInfoModule` 默认位置变化只影响从未拖动过的用户；已有 `posX/posY` 配置继续生效（名字块）。
- ASM 点击注入失败时只影响齿轮入口，不影响原版按钮；所有 hook 入口吞异常打日志。
- Hyp 自动取数在 >45 人时完全停止入队，避免请求风暴；≤45 人时按 1100ms 间隔串行请求，配合 6 小时缓存。
- 自动翻译默认关闭，开启后仅处理 `chatType == 0` 的玩家聊天，译文与原文相同则不打印。

## 5. 验收清单

- [ ] 源码中不再存在 `BackgroundModule` / `BackgroundImage` / `pickImageFile` 引用。
- [ ] 主菜单标题卡片右上角出现齿轮；点击打开播放器设置页；未命中时原版按钮行为不变。
- [ ] 设置页「界面」段有「液态模糊」开关；关闭后播放器/HUD 玻璃变为纯色半透明，重新开启恢复。
- [ ] 「聊天翻译」段有「自动翻译玩家话语」，默认关闭；开启后玩家聊天自动出现译文，关闭后恢复只有 ✎。
- [ ] 进入 ≤45 人的游戏世界自动开始取数并写入缓存；>45 人时不产生任何自动请求；TAB 面板不再是取数前置条件。
- [ ] FPS / 延迟 / 时间 / CPS 四个胶囊可分别拖动，互不影响；设置页可分别微调位置。
- [ ] 侧栏同时显示网易云 / QQ / 酷狗三行登录态；点击已登录平台切换当前平台且不重新扫码；点击未登录平台打开登录弹窗。
- [ ] `2.0.8` 离线构建成功，jar 与源码包已备份。
