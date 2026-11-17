# 床提醒 + Hypixel Bed Wars 统计接入（DangoMusic 1.8.9）

状态：待确认（确认后才改代码）

## 1. 现有 UI 清单（侦察结论）

| 类别 | 位置 | 结论 |
| --- | --- | --- |
| HUD 主绘制入口 | `legacy/src/main/kotlin/me/sorihiko/dango/forge/ForgeBridge.kt` | `RenderWorldLastEvent → WorldRenderEndEvent`；`ClientTickEvent(START) → TickEvent`，已有 `HitColorBridge.tick()` 可挂载点 |
| 灵动岛（胶囊） | `common/ui/.../music/DynamicIslandModule.kt` | 玻璃胶囊 + 内容淡入淡出 + 弹簧尺寸；IDLE 高度 32/44，宽度按歌词文本推算；未放歌时整块不画 |
| 敌人状态卡 | `common/ui/.../music/TargetInfoHudModule.kt` | 玻璃圆角面板 150×52；头像 26、名字 y+12、血条 y+31、护甲条 y+37；底部 y+41..52 有空行 |
| 设置页 | `common/ui/.../musicui/SettingsPage.kt` | 两列瀑布流分组卡片；控件 `Ctl{TOGGLE,SLIDER,SWATCH,KEY}`，行高 40；数值输入框已有（仅数字） |
| 主题 | `common/ui/.../ui/Theme.kt` | 玻璃 `GLASS_WINDOW`、文字 `TEXT_PRIMARY/SECONDARY/TERTIARY`、`ACCENT/GREEN/TRACK`、字体 4 档；逻辑像素渲染 |
| 锚点 / 偏移 | `DynamicIslandModule` 的 `HudLayoutModule.islandX/Y`；`TargetInfoHudModule` 的 `posX/posY/follow/anchor` | 不新增锚点系统 |
| 配置 | `config/dangomusic/config.json`，键 = 模块名 → `enabled` + `values.<字段名>` | 只追加新模块名与字段，不改旧键 |
| 事件/模块 | `DangoClient.kt` 的模块 list、`module/Module.kt`、`TickEvent` | 新模块加进 list 即被 ConfigManager / 事件系统接管 |

队伍色：项目内无队伍色表，按已确认决策使用**原版 § 色表** `c/e/9/a/b/f/d/7`（红 #FF5555、黄 #FFFF55、蓝 #5555FF、绿 #55FF55、青 #55FFFF、白 #FFFFFF、粉 #FF55FF、灰 #AAAAAA），不新增自定义颜色。

## 2. 数据 → 现有组件映射

| 数据 | 落点 | 形式 | 颜色 |
| --- | --- | --- | --- |
| 床威胁（敌人进入半径） | 灵动岛 IDLE 内容底部**追加一行**（高度 +1 行，仅灵动岛） | `床威胁 <名字> <距离>格`（距离 1 位小数） | 名字用其队伍 § 色，距离用 `TEXT_SECONDARY` |
| 自己床状态 | 灵动岛同一行（无威胁时） | `床 安全` / `床 已失守` | 安全 `TEXT_SECONDARY`，失守 `TEXT_PRIMARY` |
| 敌人 Bed Wars 统计 | 敌人状态卡底部空行（面板尺寸不变） | `★<星> FKDR <x> WLR <x>`，放不下用现有 `trim` | `TEXT_SECONDARY`，8px |
| 床防御统计 | 本次**不做**（源码标注为可选，项目无现成防御方块数据，避免新增扫描面板） | — | — |
| 设置开关 | 设置页新增一个分组「床提醒」，复用现有分组卡片 / 开关 / 滑条 / 输入框 | 床提醒开关、半径 3..32、API Key 密文、查询玩家名 | 现有控件配色 |

## 3. 改动文件（全部）

### 3.1 common/core（纯数据，无 MC / 无绘制）

1. **新增** `module/values/StringValue.kt`
   - `class StringValue(value: String = "") { @Volatile private var v; fun get(): String; fun set(value: String) }`
   - `set` 截断到 128 字符，去掉首尾空白。
2. **改** `config/ConfigManager.kt`（仅追加分支，不改旧键）
   - `applyValue` 增加 `is StringValue -> value.set(json.asString)`
   - `serialize` 增加 `is StringValue -> values.addProperty(field.name, v.get())`
3. **新增** `hud/BedWarsData.kt`（object，线程安全桥）
   - 比赛态：`@Volatile matchActive`、`ownTeam`（Team.index，-1 未知）、`ownBedKnown`、`ownBedBroken`、`rosterCount`
   - 威胁快照（tick 写一次）：预分配数组 `names/teams/distances/invisibles` + `threatCount`；`bedLine: String`（tick 内拼好，渲染不拼串）、`bedLineTeam`、`bedLineWarn`
   - 统计缓存：`ConcurrentHashMap<String, BedWarsStats>`（key 小写名字）、`statsVersion` 自增、`putStats/clearStats/statsFor(name)`
   - API 状态：`@Volatile apiState/apiMessage/apiPendingName`
   - 设置镜像：`@Volatile apiKey`、`@Volatile queryRequest: String?`
   - `fun teamRgb(index: Int): Int`（原版 § 色表 0xRRGGBB）
   - `fun clearMatch()`（世界切换清状态，不清统计缓存）
   - 写入方：legacy tick 线程；读取方：渲染线程（同为主线程），无锁。

`bedwars` / `bedwarsstats` 两个包**零改动**。

### 3.2 common/ui

4. **新增** `module/impls/music/BedWarsModule.kt`
   - `@ModuleMeta(Category.MUSIC, "BedWars")`（不新增 Category）
   - `@ModuleValue("床提醒", "敌人靠近自己床时在灵动岛提示") val bedAlerts = BooleanValue(true)`
   - `@ModuleValue("提醒半径", "3 ~ 32 格") val bedRadius = IntValue(3, 32, 8)`
   - `@ModuleValue("Hypixel API Key", "在 Hypixel 开发者面板申请；留空则统计不可用") val apiKey = StringValue("")`
   - `@ModuleValue("查询玩家", "输入名字后按回车查询 Bed Wars 统计") val queryName = StringValue("")`
   - `@EventHandler fun onTick(e: TickEvent)`：`BedWarsData.apiKey = apiKey.get()`；`queryName` 变化时写 `BedWarsData.queryRequest`；不打印 Key、不刷聊天栏。
5. **改** `DangoClient.kt`：模块 list 追加 `BedWarsModule()`。
6. **改** `DynamicIslandModule.kt`（只增内容，不新增面板）
   - IDLE 尺寸：在原有音乐内容尺寸上，若 `BedWarsData.matchActive && (threatCount>0 || ownBedKnown || ownBedBroken)`，高度 +14、宽度取 `max(原宽, min(床行宽, 138))`；未进 BedWars 时尺寸/绘制逐像素不变。
   - 可见性：原 `if (want == IDLE && !musicIdle()) return` 改为「无音乐且无床行时才 return」。
   - `drawMusicIdle` 之后追加 `drawBedRow`：贴底一行，9px，`mt.trim` 到宽度上限；有威胁用队伍色，否则 `TEXT_SECONDARY/TEXT_PRIMARY`；只有 `bedLine` 非空才画。
   - 音浪 `bars` 仅在有音乐时画；无音乐时仅文本 + 底部横条（沿用 3px TRACK 形状，不新增样式）。
7. **改** `TargetInfoHudModule.kt`
   - 面板尺寸、头像、名字、两条血条**不动**。
   - 底部 y+41 画一行统计（8px，`TEXT_SECONDARY`），仅当 `BedWarsData.statsFor(name)` 非空。
   - 缓存：`statsName + statsVersion + statsText`，名字/版本不变不重建字符串；超出宽度用 `mt.trim`。
8. **改** `SettingsPage.kt`
   - `Ctl` 追加 `TEXT`；`Row` 追加 `secret: Boolean = false`（apiKey 为 true）。
   - `Src.take` 识别 `StringValue → Ctl.TEXT`；`secret = key == "apiKey"`。
   - 新分组：`Group("床提醒", "灵动岛 · Hypixel 统计", bw, false, bw.take(listOf("bedAlerts","bedRadius","apiKey","queryName")))`，放在「敌人状态」之后。
   - 绘制 `Ctl.TEXT`：复用数值输入框外观（`CARD_HOVER` + 1px `RED` 边 + 闪烁光标），宽 166；空 Key 显示「未配置」，Key 编辑/展示均为 `*` 等长密文（列表里以 `••••` 截断显示）；空查询名显示「输入名字」。
   - 编辑逻辑：`startEdit` 对 StringValue 取当前值；`commitEdit` 对 StringValue 写入并（`queryName`）发起 `BedWarsData.queryRequest`；`keyTyped` 对 StringValue 接受可打印 ASCII（≤96 字符），Enter 提交 / Esc 取消 / Ctrl+A 清空。
   - 不改任何现有键名、默认值、控件样式参数。

### 3.3 legacy（1.8.9 取数）

9. **新增** `legacy/.../legacy/BedWarsRuntime.kt`（object）
   - `private val bedAlerts = BedAlertModule()`，`setEnabled/setRadius` 由 BedWarsModule 的 `@ModuleValue` 驱动（读 ModuleManager，缺省 true/8）。
   - `fun tick()`（每客户端 tick，由 ForgeBridge 调用）：
     1. 世界 / 维度变化 → `bedAlerts.onWorldChange()` + `BedWarsData.clearMatch()` + 清 roster 去重 / 床缓存 / 扫描计时。
     2. `mc.currentScreen != null`（打开任意 GUI）→ 只处理待查询，不 `update`。
     3. 组装 `MatchInput`：sidebar（`getObjectiveInDisplaySlot(1)` → `getSortedScores()` → `Scoreboard.getPlayersTeam` + `PlayerTeam.formatPlayerName`，含 §）、TAB（`netHandler.playerInfoMap` → `GameProfile.getName()` 原名 + `getId()` 去 `-` 32 位 hex + `displayName.formattedText`，`PlayerIdentity.fromFormattedName(uuid, raw, formatted)` 三参）、本地皮革胸甲（`getEquipmentInSlot(3)` → `ItemArmor.hasColor/getColor` → `Team.fromLeatherRgb`）、场上玩家胸甲队伍集合、`MatchTracker.armorMask`、玩家坐标 / entityId、实体列表、床列表；`bedAlerts.update(input)`。
     4. 床扫描：每 2000ms 一次，以玩家为中心 ±32 格、y ±16 扫描 `Block.getIdFromBlock(state.block) == 26`，meta bit 0x8=HEAD，朝向低 2 位推 foot 坐标；`bedCache` 保留扫描盒外已知床（避免 `OwnBedTracker` 96 格内误判被拆），盒内本次未扫到才移除。
     5. 发布 `BedWarsData`：比赛态、队伍、床锁定、床被拆（曾锁定且本轮变未知且比赛仍激活）、威胁快照（`activeThreats()` 已按距离升序，取第一条 + 总数）、`bedLine` 在这里拼好。
     6. 自动统计：`RosterStatsService`，`setMatchActive` 随比赛态变化；名单新增的敌方玩家 `enqueuePlayer(name, team, now)`（`HashSet` 去重，世界切换清空）。
     7. 手动查询：消费 `BedWarsData.queryRequest` → 无 Key 时写「未配置」且不发请求；有 Key 则 `hypixelClient.query(name, now)`。
   - 两个回调都在后台线程，全部 `Minecraft.getMinecraft().addScheduledTask { ... }` 切回主线程写 `BedWarsData`（统计 / API 文案）；`onStatsFailed` 只更新状态文案，不刷屏。
   - `ApiKeyProvider` 读 `BedWarsData.apiKey`（不硬编码、不打印）。
   - 使用 `bedAlerts.activeThreats()` / `contacts()` / `roster()` / `isMatchActive()` / `getOwnTeam()` / `getOwnBed()`，不改其算法。
10. **改** `ForgeBridge.kt`：`onClientTick` 中 `HitColorBridge.tick()` 之后追加 `try { BedWarsRuntime.tick() } catch (t: Throwable) { DangoLogger.error(...) }`。

## 4. 视觉与约束核对

- 不新增 GuiScreen / HUD 类 / 渲染批次 / 面板 / 主题常量；灵动岛与敌人卡沿用原绘制入口与玻璃样式。
- 未进 BedWars 或未配置 Key：所有现有像素不变（新行不画）。
- 所有新增文本使用现有字体 / 字号 / 颜色（队伍色为原版 § 色表）。
- 新增文本一律 `trim` 到现有面板/胶囊宽度，GUI Scale 1/2/3 不溢出、不改变其他元素位置。
- 不新增第三方依赖；Gson 仍为 1.8.9 自带 2.2.4；Java 8 目标。

## 5. 验收方式与边界

自动：`legacy` 目录 Gradle `build` 通过（JDK 8），产物 `legacy/build/libs/DangoMusic-*.jar` 复制到桌面。

静态检查：`grep` 确认无新增 `GuiScreen`/`GuiIngame`；`bedwars`/`bedwarsstats` 目录 diff 为空；旧配置键未改名。

需要游戏内人工确认（说明限制：本环境无法启动 1.8.9 客户端截图）：
1. 大厅（名单为空）灵动岛无床行；2. 队友不提示；3. 敌人进半径出现、离开 1.25 格消失；4. 切换世界状态清零；5. 无 Key 显示「未配置」且无请求；6. 429/403 文案正常且不含 Key；7. GUI Scale 1/2/3 无重叠溢出；8. 原有 HUD 在无 BedWars 数据时逐像素不变。
