# 任务清单：Hypixel 模块缺陷修复

> 规格：[spec.md](./spec.md)。按顺序执行，每项完成后更新 TodoWrite。

- [x] **T1 床扫描层重写（`legacy/.../BedWarsRuntime.kt`）**
  - `markerAt()` 增加 `team` 参数，新增 `teamAroundBed()`（羊毛邻居计票，≥2 票），缓存合并保留旧队伍，修正方向表注释为 HEAD→FOOT 向量。
  - `scanBeds()` 改时间切片（`SCAN_BUDGET_BLOCKS=12000`，线性游标，复用地段字段），完成后才删“盒内未见”条目并重建 `bedList`。
  - `bedCache` 增加 `CACHE_RETAIN_RADIUS=128` 距离淘汰。

- [x] **T2 床警报宿主层（同一文件）**
  - 删除 `mc.currentScreen != null` 的整段提前 return。
  - 每 tick 对 `bedAlerts.getOwnBed()` 做 `isBlockLoaded` + 方块 id 26 探测，写 `input.ownBedProbe`。

- [x] **T3 床警报纯逻辑（`common/core/.../bedwars`）**
  - `MatchInput` 新增 `ownBedProbe`；`BedAlertModule.update()` 透传。
  - `OwnBedTracker`：新增 `PROBE_*` 常量与 6 参 `update`（保留 5 参重载）；首次见到床即记锚点；`onMatchStart` 不清锁；被拆判定改为“探测优先 + 40 格保守回退”。
  - `MatchTracker` 删除无用 `missingCount`。

- [x] **T4 敌人状态卡（`TargetInfoHudModule.kt`）**
  - 新增 `shownName`，可见期内同目标点击只延长 `pinUntilNanos`。
  - `fadeOut` 上限钳到显示时长；`coerceIn` 移到 shake 之后；换目标清零抖动；点击判定追加 `platform.inGame`。

- [x] **T5 方块高亮（`BlockOverlay.java`）**
  - 删除裸 `glColor4f`，状态切换全部走 `GlStateManager`。
  - 重写 6 面填充（正确绕序），描边合并为单次 `GL_LINES`。
  - 增加 `typeOfHit == BLOCK` 判定、跳过液体、改用碰撞包围盒（回退静态 bounds），移除 `BlockStairs` 特判；`fill/outline` 全关时提前返回。

- [x] **T6 回归验证**
  - 临时目录编译 `bedwars` 纯逻辑 harness，跑通 T3/T1 相关断言（head/foot key 一致、TEAM_WOOL、PROBE 三态、锚点、ThreatTracker 迟滞/过期/跳过）。
  - `legacy` 离线构建 exit 0，校验 `build/libs` jar。
  - 输出人工验证清单，等待用户游戏内确认。
