# Hypixel 模块缺陷修复规格（床警报 / 敌人状态 / 方块高亮）

## 背景
用户要求对 Hypixel 相关模块做全量代码审查与修复：床警报的误报漏报与触发时机、敌人状态卡淡入被重复点击重置、方块高亮导致手部/皮肤变暗，以及三者的全量回归。

本规格基于对以下文件的完整阅读（结论已确定，不再二次探索）：

- `legacy/src/main/kotlin/me/sorihiko/dango/legacy/BedWarsRuntime.kt`
- `common/core/src/main/java/bedwars/BedAlertModule.java`、`MatchInput.java`、`MatchTracker.java`、`OwnBedTracker.java`、`ThreatTracker.java`、`RosterBook.java`、`BedMarker.java`、`Text.java`、`Team.java`
- `common/ui/src/main/kotlin/me/sorihiko/dango/module/impls/music/TargetInfoHudModule.kt`、`common/core/src/main/java/glidehud/feature/TargetInfoState.java`
- `legacy/src/main/java/fpsport/render/BlockOverlay.java`、`legacy/src/main/kotlin/me/sorihiko/dango/legacy/FpsPortRuntime.kt`（渲染调用顺序）

## 一、床警报：根因与修复

### 已确认根因
1. **床的队伍恒为 UNKNOWN**
   `BedWarsRuntime.markerAt()` 用 `Team.UNKNOWN` 构造 `BedMarker`，因此 `OwnBedTracker` 的 `TEAM_WOOL` 分支（唯一精确来源）永不触发；自己床只能退回“比赛确认那一刻玩家位置 36 格内最近的床”。
   而比赛确认依赖胸甲颜色 / 名单证据，常延后到玩家已离开出生岛，锚点可能落在**敌方床**上 → 威胁提示出现在错误的床、“自己床 安全/已失守”全错（误报 + 漏报的根因）。

2. **“被拆”判定与扫描覆盖脱节（误报 + 漏报）**
   - 漏报：`bedCache` 对扫描盒（±40）之外的床**永久保留**，且不对已锁床做任何复核。玩家在 40 格外时床被拆，缓存条目仍在 → 永远不报“已失守”。
   - 误报：若已锁床在扫描盒内但区块未加载（`isBlockLoaded=false`），本轮“没见到”即被删除；`OwnBedTracker` 又用 96 格作为“理应加载”判据，与宿主 40 格扫描半径互相矛盾。

3. **锚点记录时机错误**
   `onMatchStart()` 只在 `justActivated` 时记录锚点并清锁，丢失了“首次看到床时”的出生点位置。

4. **扫描性能**
   每 2 s 在**同一 tick** 内做 81×81×33 ≈ 216k 次 `getBlockState`，造成周期性卡顿；`bedCache` 还会随跨图移动无限增长。

5. **GUI 打开时整段 tick 提前 return**
   `BedWarsRuntime.tick()` 在 `mc.currentScreen != null` 时直接返回：威胁接触不再过期也不再发布 → 开背包/聊天期间灵动岛继续显示**过期威胁**，关闭后又在 500 ms 内集体消失（触发时机错误 + 闪烁）。

6. **死逻辑**
   `MatchTracker.missingCount` 只自增不使用；`justDeactivated` 恒为 false（`matchActive` 只由 `reset()` 清除，而 `reset()` 已把 `wasActive` 归零）→ `BedAlertModule` 里的 `ownBedTracker.onMatchEnd()` 分支永不执行。

### 修复方案（决策已定）

**A. 床 → 队伍推断（`BedWarsRuntime.kt`）**
- 新增 `teamAroundBed(world, headX/Y/Z, footX/Z)`：对床的两个方块各取 4 个水平邻居，在 `dy ∈ {-1, 0, +1}` 三层查 `Block.getIdFromBlock == 35`（羊毛），用 `Team.fromWoolMetadata(meta)` 计票；**最大票数 ≥ 2** 时返回该队伍，否则 `UNKNOWN`（避免单块杂色羊毛误判）。
- `markerAt(...)` 增加 `team` 参数，`BedMarker` 不再恒定 UNKNOWN。
- 缓存合并：新 marker 队伍 UNKNOWN 但同一 key 的旧 marker 有队伍时，沿用旧队伍（防止玩家用黑曜石/木头替换掉防御羊毛后丢失队伍信息）。
- 注释修正：方向表实际是 **HEAD→FOOT 向量**（0→-Z、1→+X、2→+Z、3→-X），与 vanilla `FACING`（FOOT→HEAD）相反；现有坐标计算是正确的，只改注释与变量名，不改算法。

**B. `OwnBedTracker.java`**
- 新增探测常量 `PROBE_NONE=0 / PROBE_PRESENT=1 / PROBE_ABSENT=2 / PROBE_UNLOADED=3`。
- 新签名 `update(matchActive, ownTeam, beds, playerX, playerZ, int probe)`；保留旧 5 参重载（内部传 `PROBE_NONE`）。
- 锚点：`update()` 内若 `!anchorValid && beds 非空` → 记录当前玩家位置（首次看到床即锚点，早于比赛确认）。
- `onMatchStart()` 改为**只补锚点、不再清锁**（避免比赛确认瞬间把已 TEAM_WOOL 锁定的床清掉而闪出“已失守”）。
- “被拆”判定：
  - 已锁床不在 `beds` 中时：`PROBE_ABSENT` → 清锁（被拆）；`PROBE_UNLOADED` / `PROBE_PRESENT` → 保留锁；`PROBE_NONE` → 退回“水平距离 ≤ 40（与宿主扫描半径一致）”的保守判据，替换原 96。
- `beds == null` 维持原语义（清锁），宿主保证非 null（注释说明）。

**C. `MatchInput.java` / `BedAlertModule.java`**
- `MatchInput` 新增 `public int ownBedProbe = OwnBedTracker.PROBE_NONE;`
- `BedAlertModule.update()` 透传 `input.ownBedProbe`。

**D. `BedWarsRuntime.kt` 宿主层**
- `tick()` 删除 `if (mc.currentScreen != null) return`，改为始终 `scanBeds → fillInput → bedAlerts.update → publish`（GUI/聊天期间威胁正常过期、自己床状态正常刷新）。
- 每 tick 对上一次的 `bedAlerts.getOwnBed()` 做直接探测：`!world.isBlockLoaded(...)` → `PROBE_UNLOADED`；已加载但方块 id != 26 → `PROBE_ABSENT`；否则 `PROBE_PRESENT`；无锁 → `PROBE_NONE`。
- `scanBeds` 改为**时间切片**：保留 `nextScanAt`（2 s 周期），一次扫描用线性游标分摊，每 tick 处理 `SCAN_BUDGET_BLOCKS = 12000` 个方块（≈216k/18 tick ≈ 0.9 s 完成），扫完才做“盒内未见即删除 → 重建 bedList”。游标/边界/seen 集合复用字段，避免每轮分配。
- `bedCache` 增加距离淘汰：距玩家 > `CACHE_RETAIN_RADIUS = 128` 的条目丢弃（自己床由探测复核，不依赖缓存）。

**E. `MatchTracker.java`**
- 删除 `missingCount`（无用），`justDeactivated` 保留字段但注释标明仅 `reset()` 语义；`BedAlertModule` 的 `justDeactivated` 分支保留（无害）。

## 二、敌人状态卡：根因与修复（`TargetInfoHudModule.kt`）

1. **淡入重播（用户指定 bug）**
   `if (clicked && state.hasTarget()) { shownAtNanos = now; ... }` 无条件重置淡入起点。可见期内（含淡入、停留、淡出）再次左键 → 0.12 s 淡入从 0 重播。
   → 新增 `shownName`；点击时若 `now < pinUntilNanos` 且 `state.name()` 与 `shownName` 相同：**只刷新 `pinUntilNanos`**；否则（新目标 / 已过期）才更新 `shownAtNanos` 并重新淡入。

2. **淡出起点透明**
   `holdTime` 最小 0.2 s、`fadeTime` 最大 2 s → `fadeOut = holdLeft/fade` 初始 0.1，面板一出现就近乎透明。
   → `fadeOut = min(1, holdLeft / fade.coerceAtMost(holdSeconds))`。

3. **抖动越界**
   先 `coerceIn` 再叠加 `shake` → 面板可被推出屏幕。→ 把 `coerceIn` 移到 shake 之后（仅 `following` 分支）。

4. **换目标残留抖动**
   `target.name != lastHitName` 时只重置血量基线，不清 `hitNanos/hitPower` → 新目标沿用上一个目标的抖动。→ 切换时清零。

5. **GUI 内误触发**
   `chatOpen` 只覆盖 `GuiChat`；开背包（`GuiInventory`）时左键会触发面板。→ 点击判定追加 `platform.inGame`（`mc.currentScreen == null`）。

## 三、方块高亮：根因与修复（`BlockOverlay.java`）

1. **皮肤变暗根因（用户指定 bug）**
   `setColor()` 同时调用 `GlStateManager.color(...)` 与裸 `GL11.glColor4f(...)`。`GlStateManager` 缓存颜色状态，裸调用绕开缓存 → 结尾 `GlStateManager.color(1,1,1,1)` 被判定“无变化”不下发，GL 当前色残留为高亮色（含 alpha）；`RenderWorldLastEvent` 之后紧接绘制的手部/皮肤被染色变暗。视角向下时手正对镜头 → 症状最明显。
   → 删除裸 `glColor4f`，只走 `GlStateManager.color`。

2. **状态泄漏**
   裸 `glBlendFunc` / `glDisable(GL_TEXTURE_2D)` / `glEnable|glDisable(GL_DEPTH_TEST)` / `glDepthMask` 绕过 `GlStateManager` 缓存，缓存与 GL 实际状态不一致。
   → 全部替换为 `GlStateManager.tryBlendFuncSeparate` / `disableTexture2D` / `enableTexture2D` / `enableDepth` / `disableDepth` / `depthMask`；`GL_LINE_SMOOTH` 与 `glLineWidth` 非缓存项保留裸调用并成对恢复。不新增对 cull / lighting 的假设（保持原有行为）。

3. **填充几何错误**
   `drawFaces()` 把 `sideEdges()`（8 顶点）当 `GL_QUADS` 用 → 两组“蝴蝶结”退化面；`topLoop/bottomLoop`（5 顶点）混入 quad → 缺面、扭曲。
   → 重写为 6 个正确绕序的四边形（外法线方向 = 面朝向）：
   - `-X`: (minX,minY,minZ)(minX,minY,maxZ)(minX,maxY,maxZ)(minX,maxY,minZ)
   - `+X`: (maxX,minY,minZ)(maxX,maxY,minZ)(maxX,maxY,maxZ)(maxX,minY,maxZ)
   - `-Z`: (minX,minY,minZ)(minX,maxY,minZ)(maxX,maxY,minZ)(maxX,minY,minZ)
   - `+Z`: (minX,minY,maxZ)(maxX,minY,maxZ)(maxX,maxY,maxZ)(minX,maxY,maxZ)
   - `-Y`: (minX,minY,maxZ)(minX,minY,minZ)(maxX,minY,minZ)(maxX,minY,maxZ)
   - `+Y`: (minX,maxY,minZ)(minX,maxY,maxZ)(maxX,maxY,maxZ)(maxX,maxY,minZ)

4. **描边 3 次 draw**
   → 合并为单次 `GL_LINES` 的 12 条边（24 顶点）。

5. **命中类型未校验**
   `objectMouseOver` 为实体时也会高亮该实体所在方块。→ 要求 `typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK`。

6. **液体/无碰撞方块**
   只跳过 `isReplaceable`，水面/岩浆会被高亮；`BlockStairs` 特判多余。→ 跳过 `material.isLiquid()`；包围盒改用 `block.getCollisionBoundingBox(world, pos, state)`（世界坐标，减 viewer 位置），为 null 时回退 `block.getSelectedBoundingBox` / 静态 bounds；删除 `BlockStairs` 特判。

7. **性能**
   `fill && outline` 时共 6~9 次独立 `begin/end`。→ 修复后 fill 1 次、outline 1 次；`fill/outline` 均关闭时提前返回，不改任何 GL 状态。

## 四、验证方案

1. **纯逻辑回归**（临时目录，工作区外，不新增仓库文件）
   `java` + `javac` harness 直接编译 `common/core/src/main/java/bedwars/*.java`，覆盖：
   - 床 head/foot：扫到 head 与扫到 foot 必须产生同一个 marker key / 相同的 head 坐标（方向表回归）。
   - `OwnBedTracker`：① 单张本队床（TEAM_WOOL）即使远离锚点也锁定且坐标正确；② 锁定后 `PROBE_ABSENT` → 清锁（60 格远也报被拆）；③ `PROBE_UNLOADED` / `PROBE_PRESENT` → 保留；④ `PROBE_NONE` 且 40 格外 → 保留（不再 96 误判）。
   - 锚点：`matchActive=false` 时首次看到床即记录；随后激活不覆盖已有锚点、不清 TEAM_WOOL 锁。
   - `ThreatTracker`：队友跳过、名单查不到跳过、迟滞 enter/leave、500 ms 过期、entityId 复用防护。
2. **构建**：`legacy` 离线构建
   `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot'; .\gradlew.bat --no-daemon --console=plain --offline build`（exit 0）。
3. **人工清单**（需用户在游戏内确认，无法自动化）
   - 自己床：锁对床、被拆立刻提示、远离 40 格被拆也能提示、不再误报失守。
   - 威胁：进入半径提示、离开消失、队友/隐身不误报、开背包期间不残留过期提示。
   - 状态卡：淡入过程中连点不再重播淡入；换目标才重新淡入；淡出起点不透明；抖动不出屏；开背包点击不弹面板。
   - 方块高亮：视角向下时手部/皮肤亮度正常；6 个面完整、描边闭合；水面/岩浆/实体不触发；F3 高频开关无卡顿。

## 五、约束
- 不修改 Forge `MOD_ID`、资源命名空间与配置目录。
- 不引入新依赖；`bedwars` 保持纯逻辑（无 Minecraft/UI 依赖），探测结果由宿主以整型上报。
- 不改动与本次缺陷无关的模块（音乐、统计管线、HUD 外观）。
