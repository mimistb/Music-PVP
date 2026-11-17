# 第四批移植：AdOverlay 反作弊 / 聊天数据 / 会话统计 + 聊天透明度

## 1. 目标

1. 按 `PORTING_PROMPT_4.md` 把 `AdOverlay_extract3.zip` 的三个模块（anticheat / chat / session，共 19 个 Java 文件）与自带 89 项单测移植进本工程。
2. 宿主完成接线：配置热更新、告警输出、提示音、自己名字、队友判定、会话开始/结束。
3. 新增「聊天透明度」参数（用户明确要求），作用于原版聊天背景与文字，并顺带修复移植代码中已确认的 bug。
4. 验收：`gradlew build` 通过；`ModuleTestRunner` 输出 `PASSED=89 FAILED=0`；无新增 Screen/HUD/面板类。

## 2. 硬约束

- **UI 冻结**：不新增/修改任何 `Screen`、HUD 组件、面板类、主题、字体、布局常量。
  唯一允许的设置页改动是：在**现有** `SettingsPage` 的「聊天与视角」分组里追加参数行（用户明确要求的参数调节入口），不新增分组卡片。
- 三模块互不 import；模块内不使用全局单例、不读文件、不联网、不绘制。
- 参数只从 `*Config` 传入；`config()` 返副本；`updateConfig` 非法值抛 `IllegalArgumentException` 且不污染运行配置。
- 模块代码保持 Java 8、无外部依赖；`ModuleTestRunner` 必须可 `java -cp` 独立运行。

## 3. 文件改动清单

### 3.1 新增：模块源码（原样移植，不重构）

```
legacy/src/main/java/fpsport/adnin/anticheat/  Alert, AnticheatApi, AnticheatConfig,
                                               AnticheatModule, CheatFlag, FlagType, PlayerLabel
legacy/src/main/java/fpsport/adnin/chat/       ChatApi, ChatConfig, ChatDataModule, ChatEvent,
                                               ChatParser, GameMode, PlayerStats, StatsDecision
legacy/src/main/java/fpsport/adnin/session/    GameSessionMode, SessionApi, SessionConfig,
                                               SessionModule, SessionSnapshot
legacy/src/main/java/fpsport/selftest/         ModuleTestRunner
```

### 3.2 新增：宿主运行层

`legacy/src/main/kotlin/me/sorihiko/dango/legacy/AdninRuntime.kt`

- 持有 `AnticheatModule` / `ChatDataModule` / `SessionModule` 三个实例，配置对象只在本类内构造。
- 初始化：
  - `ChatConfig.teammates` = 读 `world.scoreboard.getPlayersTeam` 与自己的队伍比较（不引入 bedwars 依赖）；
  - `anticheat.setFlagSoundSink` → `mc.thePlayer?.playSound(name, volume, pitch)`；
  - `anticheat.addAlertListener` → `mc.thePlayer?.addChatMessage(ChatComponentText(...))`，文案 `[AC] 玩家 检查项 标签 (置信度/条数)`；
  - `anticheat.setSelfName` 每 tick 刷新。
- `fun sync(m: FpsToolsModule)`：
  - 用参数指纹（`List<Any>`）判断是否变化，变化时才 `updateConfig(config.copy())`；
  - 反作弊：7 开关 + `confirmFlagCount` / `flagWindowMs`(=秒×1000) / `minConfidence` / `legitScaffoldMultiplier`；
  - 聊天：总开关、队友过滤、紧凑黑名单、`minStars` / `minFkdr` / `minSwKdr`、历史容量、单条长度；
  - 会话：总开关、`idleTimeoutMs`(=秒×1000) / `keepWinstreakOnLoss` / `maxSessionMs`(=分钟×60000) / `avgTimeRoundSeconds`。
- `fun tick(nowMs)`：`session.tick(nowMs)`；刷新 selfName。
- `fun onChat(text: String, nowMs: Long)`：`chat.receive` + 识别 `VICTORY`（SYSTEM 行）作为本局胜利标记。
- `fun onMatchActive(active: Boolean, nowMs)`：`false→true` 调 `session.onGameStart(BEDWARS, now)`；`true→false` 调 `session.onGameEnd(win, 0, 0, 0, now)`。
- 会话 HUD **不渲染**：只保留 `session.snapshot()` 数据接口（UI 冻结），交付说明中标注放弃。

### 3.3 修改：宿主接线

| 文件 | 改动 |
|---|---|
| `FpsPortRuntime.kt` | `tick()` 中调用 `AdninRuntime.sync(module)`、`AdninRuntime.tick(nowMs)`；`onChat(event)` 去掉「autoGG 关闭就 return」的早退，先投递 `AdninRuntime.onChat`（`event.type==2` 动作栏跳过），再执行原 AutoGG 逻辑 |
| `BedWarsRuntime.kt` | `publish(now)` 中检测 `active` 跳变，调用 `AdninRuntime.onMatchActive(active, nowMs)`（`now` 为单位毫秒的 `System.currentTimeMillis()`，与模块时钟一致） |

### 3.4 聊天透明度（参数 + 渲染）

| 文件 | 改动 |
|---|---|
| `common/ui/.../impls/music/FpsToolsModule.kt` | 新增 `@ModuleValue("聊天透明度", "0 ~ 100%，作用于聊天背景与文字") val chatOpacity = IntValue(0, 100, 100)` |
| `legacy/.../fpsport/utility/BetterChat.java` | 新增 `public static int opacity = 100` 与 `public static int alpha(int color)`：`!using` 或 `opacity>=100` 原样返回；否则按百分比缩放 ARGB 的 A 通道，保留 RGB，`opacity<=0` 时 A=0 |
| `legacy/.../core/BetterChatTransformer.java` | 新增对 `net.minecraft.client.gui.GuiNewChat#drawChat(I)V`（`drawChat` / `func_146230_a` / `a`）的注入：在方法内每条「同类私有 `drawRect(IIIII)V`」与「`FontRenderer.drawStringWithShadow(String,FF,int)`」调用前插 `INVOKESTATIC BetterChat.alpha(I)I`；失败原样返回 |
| `legacy/.../core/DangoCorePlugin.java` | 注册上述 transformer（沿用现有注册方式） |
| `FpsPortRuntime.kt` | `sync()` 中 `BetterChat.opacity = m.chatOpacity.value.coerceIn(0, 100)` |
| `common/ui/.../musicui/SettingsPage.kt` | `deps` 增 `"chatOpacity" to "betterChat"`；`fmts` 增 `"chatOpacity" to { "${value}%" }`；现有「聊天与视角」组 rows 追加 `chatOpacity`（不新增分组） |

透明度实现要点（来自 1.8.9 字节码核对）：`GuiNewChat.drawChat` 的背景/滚动条走本类私有静态 `drawRect(IIIII)V`，文字走 `FontRenderer.drawStringWithShadow`；在栈顶为颜色 int 时插入缩放调用即可，无需改局部变量与帧。

### 3.5 新增参数（反作弊 / 聊天数据 / 会话）与设置页

全部放进现有 `FpsToolsModule`，并在现有「聊天与视角」分组追加（不新增分组卡片）：

- 反作弊：`acEnabled`、`acSound`、`acAutoBlock`、`acLegitScaffold`、`acScaffoldA`、`acScaffoldB`、`acNoSlow`、`acConfirmCount`(1–10,3)、`acWindowSeconds`(1–60,10)、`acMinConfidence` Float(0–1,0.6,2)、`acLegitMultiplier`(1–5,2)
- 聊天数据：`adChatEnabled`、`adChatIgnoreTeammates`、`adChatCompactBlacklist`、`adChatMinStars`(0–500,0)、`adChatMinFkdr` Float(0–10,0)、`adChatMinSwKdr` Float(0–10,0)、`adChatHistory`(10–1000,200)、`adChatMaxLength`(8–512,256)
- 会话：`adSessionEnabled`、`adSessionIdleSeconds`(0–600,120)、`adSessionKeepWinstreak`、`adSessionMaxMinutes`(0–180,0)、`adSessionAvgRoundSeconds`(1–60,1)

`SettingsPage` 同步补 `deps`（各子项挂到对应总开关）、`fmts`（秒/分钟/百分数）与 rows 顺序。

## 4. 已确认 bug 与修复（严格检查结果）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| 1 | `AnticheatConfig.copy()` | 关键词集合被置 null 时 NPE | 拷贝前判空，空集合兜底 |
| 2 | `AnticheatConfig.validate()` | 未校验 `flagSoundPitch`、`flagSoundName`；`minConfidence=NaN` 可绕过 | 新增音量/音高范围、名称非空、NaN 拒绝 |
| 3 | `AnticheatModule.submit()` | `prune` 用 `break`，时间戳倒退时旧信号永久保留 | 改为遍历清理全部过期项 |
| 4 | `AnticheatModule` | `signals` / `lastAlertMs` 键永不淘汰，长会话内存增长 | 每次提交顺带清理空桶与过期告警时间戳（以 `flagWindowMs` 为界，无新增魔法数） |
| 5 | `AnticheatModule` / `ChatDataModule` | 监听器抛异常会打断游戏循环 | 逐个 listener try/catch 忽略 |
| 6 | `ChatConfig.copy()` / `validate()` | `nickMask`、`teammates` 为 null 时运行期 NPE；`minFkdr=NaN` 绕过校验 | 判空兜底 + 非空校验 + NaN 拒绝 |
| 7 | `ChatParser.parse()` | 用 `text.indexOf('<')==0` 判公聊，原始串带 `§` 颜色码时误判为 SYSTEM | 改用已剥色的 `body.startsWith("<")` |
| 8 | `ChatDataModule.receive()` | `TeamLookup` 抛异常会中断收包 | try/catch 后按「非队友」放行 |
| 9 | `SessionConfig.validate()` | `scale=NaN` 通过校验 | 增加有限性校验 |
| 10 | `SessionModule.onGameEnd()` | 达到 `maxSessionMs` 自动清空时未通知监听者（`reset` 已通知，避免双发） | 统一走 `reset()` 的既有通知路径，返回新快照 |
| 11 | `BetterChat.alpha` | 需保证 100% 时零开销、0 时全透明、RGB 不变 | 显式分支 + 夹取 |

修复不得改变公开 API 签名，89 项单测保持原样全部通过。

## 5. 验收与验证步骤

1. 独立编译 + 单测：
   `javac -proc:none -encoding UTF-8 -source 1.8 -target 1.8 legacy/src/main/java/fpsport/adnin/**/*.java legacy/src/main/java/fpsport/selftest/ModuleTestRunner.java -d _out`
   `java -cp _out fpsport.selftest.ModuleTestRunner` → `PASSED=89 FAILED=0`。
2. 离线构建（cwd=`legacy`）：
   `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot'; .\gradlew.bat --no-daemon --console=plain --offline build`
3. 静态检查：
   - `grep` 确认 `fpsport/adnin/{chat,session}` 不 import 其它两个模块；
   - `git diff --stat` 无新增 UI 类、无布局常量改动；设置页仅新增参数行（用户要求的入口）。
4. 版本从 `1.9.3` bump 到 `1.9.4`，产物备份到 `c:\Users\xzy18\Desktop\TraeCode CN\music pvp\`：
   `Music PVP 1.9.4+1.8.9.jar`、`music pvp 1.9.4+1.8.9-src.zip`。

## 6. 明确放弃 / 替换

- **不实现检测算法**：Scaffold/Autoblock/NoSlow 的角度与时序判定不复刻（逆向报告 §7.1），只交付「信号 → 聚合 → 确认 → 标签」；告警/提示音/自己名字已接线。
- **会话统计不渲染**：UI 冻结，不新增统计 HUD；只提供 `snapshot()` 数据。
- **统计查询与 Hypixel API 不接**：`PlayerStats` / `GameMode` 由宿主填充；`decideStats` 只作为可调用 API，不新造聊天统计输出（避免与现有 TAB 统计双份显示）。
- **不新增设置分组卡片**：所有新参数追加进现有「聊天与视角」分组。
