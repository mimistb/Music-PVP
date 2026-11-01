# fpsport 第二批 6 模块 · 参数补全与 bug 修复

## 1. 目标

为已移植的 6 个模块补齐可在设置页调整、且真正被消费的参数，并修复已知 bug：

| 模块 | 宿主类 |
| --- | --- |
| 护甲显示 | `fpsport/utility/ArmorDisplay.java` |
| 聊天增强 | `fpsport/utility/BetterChat.java` |
| 点击速度 | `fpsport/utility/CPSDisplay.java` |
| 清洁视角 | `fpsport/render/CleanView.java` |
| 方块高亮 | `fpsport/render/BlockOverlay.java` |
| 物品物理 | `fpsport/render/ItemPhysics.java` + `ItemPhysicsRenderer.java` |

HUD 方案已由用户确认：**并入现有「信息胶囊」**（`HudInfoModule`），不新建 HUD 组件、不改主题/字体/排版。

## 2. 约束（沿用上一批铁律）

- 零新 UI：不新建 Screen/HUD；护甲与 CPS 只作为信息胶囊的追加文字段。
- 不新增 Mixin、不新增第三方依赖；ASM 只沿用既有 3 个 transformer（本次不改字节码）。
- 所有参数走 `FpsToolsModule` 的 `@ModuleValue`，由 `FpsPortRuntime.sync()` 写入 fpsport 静态字段，ConfigManager 自动存盘。
- 设置页继续复用 `SettingsPage` 的 Toggle / Slider / Swatch，不新增控件类型。

## 3. Bug 清单与修复

| 编号 | 问题 | 位置 | 修复 |
| --- | --- | --- | --- |
| B1 | `onChatPacket` 是实例方法，但 `BetterChatTransformer` 注入 `INVOKESTATIC`，运行期 VerifyError / NoSuchMethodError | `BetterChat.java` L74 | 方法改为 `static`；`lastMessage`、`counter` 改为静态；`reset()` 改 static，`onDisable()` 调 `reset()` |
| B2 | `CleanView.readPotionColor` 异常分支 `return 0` 与注释「返回真实值」矛盾 | `CleanView.java` L46 | 保留 `return 0`（异常时跳过粒子是安全行为，不影响药效计时），修正注释为「异常时返回 0，只跳过粒子」 |
| B3 | `BlockOverlay` 开启 `enableBlend/enableAlpha` 后未恢复，污染后续渲染 | `BlockOverlay.java` L128-135 | 结尾补 `GlStateManager.disableBlend()`、`disableAlpha()`、`color(1,1,1,1)` |
| B4 | 打开 GUI 时的鼠标点击也计入 CPS，菜单点击虚增 | `FpsPortRuntime.kt` `onMouse()` L287 | 新增 `cpsInGameOnly`；开启时只有 `mc.currentScreen == null` 才计数 |
| B5 | `HudInfoModule.labels` 固定 4 槽，追加护甲/CPS 会越界 | `HudInfoModule.kt` L88 | 重构为容量 24 的段数组（文字/警告/加粗），`parts()` 返回实际段数 |
| B6 | 复制按钮硬编码「长度 > 2」 | `BetterChat.java` L106 | 改为静态 `minCopyLength`（默认 3），条件 `>= minCopyLength` |
| B7 | 折叠固定跳过动作栏（packet type == 2） | `BetterChat.java` L75 | 新增静态 `foldActionBar`（默认 false） |
| B8 | `ArmorDisplay.mode` 是无人消费的死字段 | `ArmorDisplay.java` L20 | 删除 `mode`，改为真正被消费的格式参数（见 §4.1） |
| B9 | `CPSDisplay` 统计窗口硬编码 1000ms | `CPSDisplay.java` L56/L66 | 新增静态 `windowMs`，`onTick` 内夹到 200–5000 |

## 4. 参数清单（全部落在 `FpsToolsModule.kt`）

### 4.1 护甲显示

| 字段 | 类型/范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `armorDisplay` | Boolean | false | 在信息胶囊尾部显示四件护甲 |
| `armorShowSlots` | Boolean | true | 显示部位名（头盔/胸甲/护腿/靴子）；关闭只显示数值 |
| `armorShowEmpty` | Boolean | false | 空槽显示 `–` 占位 |
| `armorPercent` | Int 0..1 | 0 | 0=百分比，1=剩余/最大 |
| `armorOnlyDamaged` | Boolean | false | 满耐久护甲不显示 |
| `armorLowThreshold` | Int 0..100 | 20 | 低耐久阈值（%） |
| `armorLowWarn` | Boolean | true | 低于阈值时文字用告警色 `#FF453A` |

数据生产者：`ArmorDisplay.update(player, showSlots, showEmpty, percent, onlyDamaged, lowThreshold, lowWarn)`，
按「头盔→靴子」写静态快照 `SEGMENTS`（`String[]`）与 `WARN`（`boolean[]`），只读访问器 `segments()` / `warnFlags()`。

### 4.2 点击速度

| 字段 | 类型/范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `cpsDisplay` | Boolean | false | 显示 CPS（信息胶囊） |
| `cpsShowLeft` | Boolean | true | 显示左键 |
| `cpsShowRight` | Boolean | true | 显示右键 |
| `cpsWindow` | Int 500..2000 | 1000 | 统计窗口 ms → `CPSDisplay.windowMs` |
| `cpsInGameOnly` | Boolean | true | 只在游戏内（未打开界面）统计 |

### 4.3 聊天增强

| 字段 | 类型/范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `betterChat` | Boolean | false | 总开关 |
| `chatFold` | Boolean | false | 折叠重复消息 |
| `chatCopy` | Boolean | false | 追加 [C] 复制按钮 |
| `chatMinLength` | Int 0..32 | 3 | 追加 [C] 的最小字符数 → `BetterChat.minCopyLength` |
| `chatFoldActionBar` | Boolean | false | 动作栏消息也折叠 → `BetterChat.foldActionBar` |

### 4.4 清洁视角

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `cleanView` | Boolean | false | 唯一参数：隐藏自己身上的药水粒子（模块本身只有一个行为，不再臆造参数） |

### 4.5 方块高亮

| 字段 | 类型/范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `blockOverlay` | Boolean | false | 总开关 |
| `boFill` / `boOutline` / `boThrough` / `boWidth` | 既有 | 既有 | 不变 |
| `boFillColor` | Int 0..5 色板 | 0 白 | 填充色 → `BlockOverlay.fillColor` 的 RGB |
| `boFillAlpha` | Int 0..100 | 20 | 填充透明度 |
| `boOutlineColor` | Int 0..5 色板 | 0 白 | 描边色 |
| `boOutlineAlpha` | Int 0..100 | 100 | 描边透明度 |

色板（`FpsToolsModule.overlayPalette`）：白 `FFFFFF` / 粉 `FF6E9E` / 蓝 `51A8FF` / 金 `FFBD4C` / 绿 `55E08B` / 红 `FF5A5A`。
`FpsPortRuntime` 按 `(alpha*255/100)<<24 | rgb` 组合成 ARGB 写入 `BlockOverlay.fillColor` / `outlineColor`。

### 4.6 物品物理

| 字段 | 类型/范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `itemPhysics` | Boolean | false | 总开关 |
| `ipRotate` | Float 0..360 | 90 | 初始翻转角 → `ItemPhysics.rotateAngle` |
| `ipScale` | Float 0.2..1.0 | 0.5 | 3D 物品缩放 → `ItemPhysics.scale` |
| `ipRollSpeed` | Float 0..10 | 1.1 | 空中翻滚速度/帧 → `ItemPhysics.rollSpeed` |
| `ipSpread` | Float 0..0.5 | 0.15 | 多数量随机偏移强度 → `ItemPhysics.spread` |
| `ipStackGap` | Float 0.01..0.2 | 0.05375 | 非 3D 物品层叠间距 → `ItemPhysics.stackGap` |
| `ipRespectCount` | Boolean | true | 按堆叠数量渲染 → `ItemPhysics.respectCount` |

`ItemPhysicsRenderer.render` 全部改为读上述静态字段，默认值与当前硬编码完全等值（行为不变）。

## 5. 信息胶囊桥接（common/ui 不能依赖 legacy）

`HudInfoModule` companion 新增 volatile 静态桥字段，由 `FpsPortRuntime` 每 tick 写入：

```kotlin
@JvmStatic @Volatile var armorSegments: Array<String>? = null   // null = 不显示
@JvmStatic @Volatile var armorWarn: BooleanArray? = null
@JvmStatic @Volatile var cpsLeft: Int = -1                       // -1 = 不显示
@JvmStatic @Volatile var cpsRight: Int = -1
```

`HudInfoModule` 渲染改动：
- `labels` → `segText/segWarn/segBold`（容量 24）+ `segN`；
- `parts()` 依次追加：玩家名（加粗）、fps、ping、时间、护甲段（按 `armorWarn` 着色）、CPS 段（`左/右 cps`，单侧时只显示单值，均用缓存避免每帧建串）；
- `pillWidth(n)` 与绘制循环改读段数组，胶囊尺寸/玻璃样式/拖动逻辑不变。

`FpsPortRuntime` 在 `CPSDisplay.onTick()` 之后调用新方法 `publishHud(module)`：
- `armorDisplay` 开启且 `mc.thePlayer != null` → `ArmorDisplay.update(...)` 并写入桥字段；否则写 `null`；
- `cpsDisplay` 开启 → 写入 `leftCps/rightCps`（按 `cpsShowLeft/Right` 决定 -1）；否则 -1。

## 6. 设置页改动（`SettingsPage.kt`）

- 色板识别改为 `k.endsWith("Color")` → `Ctl.SWATCH`（覆盖既有 `fogColor/fontColor/glowColor`，行为不变）。
- `swatchesFor` 新增 `"boFillColor"、"boOutlineColor" -> FpsToolsModule.overlaySwatches()`。
- `fps.deps` 新增：`armorShowSlots/armorShowEmpty/armorPercent/armorOnlyDamaged/armorLowThreshold/armorLowWarn → armorDisplay`；`cpsShowLeft/cpsShowRight/cpsWindow/cpsInGameOnly → cpsDisplay`；`chatMinLength → chatCopy`；`chatFoldActionBar → chatFold`；`boFillColor/boFillAlpha → boFill`；`boOutlineColor/boOutlineAlpha → boOutline`；`ip* → itemPhysics`。
- `fps.fmts` 新增：`cpsWindow → "$v ms"`、`chatMinLength → "$v 字"`、`armorPercent → 百分比/数值`、`armorLowThreshold → "$v%"`、`boFillAlpha/boOutlineAlpha → "$v%"`、`ipRotate → "%.0f°"`、`ipScale → "%.2f"`、`ipRollSpeed → "%.1f"`、`ipSpread → "%.2f"`、`ipStackGap → "%.3f"`。
- 分组：
  - 「方块与物品」追加 `boFillColor、boFillAlpha、boOutlineColor、boOutlineAlpha、ipRotate、ipScale、ipRollSpeed、ipSpread、ipStackGap、ipRespectCount`；
  - 「聊天与视角」追加 `chatMinLength、chatFoldActionBar`；
  - 新增「护甲与点击速度 · 显示在信息胶囊尾部」分组，含 §4.1 与 §4.2 全部字段。

## 7. 文件改动清单

1. `legacy/.../fpsport/utility/BetterChat.java`：静态化 + `minCopyLength` + `foldActionBar`。
2. `legacy/.../fpsport/utility/CPSDisplay.java`：`windowMs`。
3. `legacy/.../fpsport/utility/ArmorDisplay.java`：删除 `mode`，新增 `update/segments/warnFlags`。
4. `legacy/.../fpsport/render/CleanView.java`：注释修正（逻辑不变）。
5. `legacy/.../fpsport/render/BlockOverlay.java`：GL 状态恢复。
6. `legacy/.../fpsport/render/ItemPhysics.java`：新增 6 个静态参数（默认值与硬编码等值）。
7. `legacy/.../fpsport/render/ItemPhysicsRenderer.java`：改用静态参数。
8. `legacy/.../me/sorihiko/dango/legacy/FpsPortRuntime.kt`：sync 接线、`publishHud`、CPS 菜单过滤、导入 `ArmorDisplay`/`HudInfoModule`。
9. `common/ui/.../module/impls/music/FpsToolsModule.kt`：新增 23 个 `@ModuleValue` 与 `overlayPalette/overlaySwatches`。
10. `common/ui/.../module/impls/music/HudInfoModule.kt`：段数组重构 + 桥字段 + 护甲/CPS 渲染。
11. `common/ui/.../musicui/SettingsPage.kt`：依赖、格式、SWATCH 判定与分组。
12. `DangoMusic-main/version.properties`：1.4.9 → 1.5.0。

## 8. 验证

1. `legacy` 下执行离线构建（JAVA_HOME 指向 JDK 8）通过。
2. jar 内确认 `HudInfoModule` / `FpsToolsModule` / 6 个 fpsport 类均为最新编译结果。
3. 设置页人工检查：新分组可见、滑条/色板/开关可改、被依赖项变暗逻辑正确。
4. 游戏内检查：护甲与 CPS 出现在胶囊尾部且不串行、不越界；方块高亮颜色/透明度即时生效；物品物理 6 项可调；聊天折叠与复制正常；清洁视角生效；打开背包点鼠标不计 CPS。
5. 备份 `music pvp 1.5.0+1.8.9.jar` 与 `music pvp 1.5.0+1.8.9-src.zip` 到 `TraeCode CN/music pvp`。

## 9. 本次不改

- 任何 ASM transformer 字节码逻辑（B1 只改被调用方法的静态性，描述符不变）。
- 主题、字体、设置页控件外观、信息胶囊玻璃样式与拖动逻辑。
- 不新增第三方依赖与 Mixin。
