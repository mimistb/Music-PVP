# 验收清单：Hypixel 模块缺陷修复

## 床警报
- [x] 床标记不再恒为 `Team.UNKNOWN`：`BedWarsRuntime.recordMarker()` 通过羊毛邻居计票（≥2 票）推断队伍，并在本轮推断不出时沿用旧队伍
- [x] `BedMarker.fromBlock()` 对 HEAD / FOOT 两种 metadata 归一化到同一个 key 与同一 head 坐标（4 种朝向 × head/foot/center 共 12 项断言通过）
- [x] 自己床锁定优先 `TEAM_WOOL`，且可覆盖原先的 `MATCH_SPAWN` 锁
- [x] 锚点在“未确认阶段首次看到床”时记录，`onMatchStart()` 不再清除已建立的锁
- [x] 已锁床消失时按宿主探测判定：`PROBE_ABSENT` → 报被拆；`PROBE_UNLOADED` / `PROBE_PRESENT` → 保留；`PROBE_NONE` 回退 40 格（不再用 96 格误判）
- [x] 本局确认被拆后不再重新锁定其它床（`destroyed` 标志）
- [x] 宿主每 tick 对已锁床做 `isBlockLoaded` + id 26 直接探测并写入 `MatchInput.ownBedProbe`
- [x] 床扫描改为时间切片（`SCAN_BUDGET_BLOCKS=12000`/tick），扫完整轮才回收“盒内未见”条目并重建 `bedList`
- [x] `bedCache` 距玩家 > 128 格的条目被淘汰（缓存不再无限增长）
- [x] GUI / 聊天打开时不再整段跳过 tick（威胁能正常过期，不再残留过期提示）
- [x] `MatchTracker.missingCount` 死逻辑已删除，编译通过

## 敌人状态卡
- [x] 可见期内（淡入/停留/淡出）同目标再次左键只延长停留，不重播淡入动画（`shownName` + `stillShown`）
- [x] 换目标（或已完全消失后再次点击）才重置 `shownAtNanos` 并重新淡入
- [x] `holdTime < fadeTime` 时淡出起点不再接近透明（`fade` 钳到显示时长）
- [x] 抖动叠加后再夹取面板位置，不会把面板推出屏幕
- [x] 换目标时清零 `hitNanos` / `hitPower`，不沿用上一个目标的抖动
- [x] 左键触发追加 `platform.inGame`，开背包/聊天时点击不再弹面板

## 方块高亮
- [x] 删除裸 `GL11.glColor4f`，颜色只经由 `GlStateManager.color` 设置与复位（皮肤/手部不再被高亮色污染变暗）
- [x] 混合、纹理、深度测试、`depthMask` 全部经 `GlStateManager` 切换；`GL_LINE_SMOOTH` / `glLineWidth` 成对恢复
- [x] 6 个填充面为正确绕序的四边形，无退化面 / 缺面
- [x] 描边合并为单次 `GL_LINES` 的 12 条边
- [x] 仅 `typeOfHit == BLOCK` 时渲染，对准实体不再误高亮方块
- [x] 跳过液体与 `isReplaceable` 方块
- [x] 使用 `getCollisionBoundingBox`（世界坐标减 viewer 位置），删除 `BlockStairs` 特判
- [x] `fill` 与 `outline` 均关闭时提前返回，不触碰任何 GL 状态

## 回归验证
- [x] 纯逻辑 harness：31 PASS / 0 FAIL（`BedMarker`、`OwnBedTracker`、`ThreatTracker`）
- [x] `legacy` 离线构建 `BUILD SUCCESSFUL`（exit 0），生成 `DangoMusic-1.9.1+1.8.9.jar`
- [ ] 用户在游戏内按人工清单确认（床警报 / 威胁 / 状态卡 / 方块高亮）
