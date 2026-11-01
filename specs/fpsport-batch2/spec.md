# fpsport 第二批移植规格（Dango Music 1.4.9 / MC 1.8.9 Forge）

## 1. 目标与边界

把 `C:\Users\xzy18\Desktop\fpsport_export2` 里的 6 个模块移植进宿主：

- `fpsport/render/CleanView`（清洁视角：自己不冒药水粒子）
- `fpsport/render/BlockOverlay`（准星方块高亮，世界空间绘制）
- `fpsport/render/ItemPhysics`（掉落物物理观感）
- `fpsport/utility/BetterChat`（折叠重复消息 + 复制按钮）
- `fpsport/utility/CPSDisplay`（点击速度计数，**只给数据**）
- `fpsport/utility/ArmorDisplay`（护甲数据，**只给数据**，宿主已有同名类）

铁律：**零 UI 代码**（不新建 Screen/HUD、不改主题/字体/排版）；不新增第三方依赖；
宿主没有 Mixin，全部用 coremod ASM transformer 实现。

## 2. 侦察结论（决策表）

| 导出模块 | 宿主落点 | 实现方式 |
|---|---|---|
| `Feature` | 已存在 `fpsport/core/Feature.java` | 直接复用，不改 |
| `ArmorDisplay` | 已存在 `fpsport/utility/ArmorDisplay.java` | 保持数据-only，不新画 HUD |
| `CPSDisplay` | 新建 `fpsport/utility/CPSDisplay.java` | 事件驱动计数，只给数据 |
| `BetterChat` | 新建 + `BetterChatTransformer` | 注入收包处与聊天点击处 |
| `CleanView` | 新建 + `CleanViewTransformer` | 替换 DataWatcher 读取调用 |
| `BlockOverlay` | 新建 + `FpsPortRuntime.onRender3D` | 现成 `RenderWorldLastEvent` |
| `ItemPhysics` | 新建 + `ItemPhysicsRenderer` + `ItemPhysicsTransformer` | 注入 `doRender` HEAD 并接管 |

### 2.1 运行时映射（joined.srg 实测）

| 符号 | srg / 描述符 | 说明 |
|---|---|---|
| `EntityLivingBase#updatePotionEffects` | `func_70679_bo ()V`（混淆 `bi`） | 清洁视角目标方法 |
| `DataWatcher#getWatchableObjectInt` | `func_75679_c (I)I` | 被替换的调用 |
| `RenderEntityItem#doRender` | `func_76986_a (Lnet/minecraft/entity/item/EntityItem;DDDFF)V` | 物品物理目标方法 |
| `RenderEntityItem#func_177078_a` | 私有 `(Lnet/minecraft/item/ItemStack;)I` | 数量：stackSize>48→5、>32→4、>16→3、>1→2、否则 1 |
| `NetHandlerPlayClient#handleChat` | `func_147251_a (Lnet/minecraft/network/play/server/S02PacketChat;)V` | 折叠重复消息的取消点 |
| `GuiScreen#handleComponentClick` | `func_175276_a (Lnet/minecraft/util/IChatComponent;)Z` | 聊天点击复制拦截点 |
| `GuiNewChat.chatLines` | `field_146252_h`（MCP `chatLines`） | 反射读取 |
| `GuiNewChat.drawnChatLines` | `field_146253_i`（MCP `drawnChatLines`） | 反射读取 |

### 2.2 `updatePotionEffects` 的真实字节码（已反汇编确认）

```
126: aload_0
127: getfield dataWatcher
130: bipush 7
132: invokevirtual DataWatcher.getWatchableObjectInt(I)I
135: istore_2                     // int color
...
154: iload_2
155: ifle 368                    // color <= 0 → 直接 return
... 粒子生成块 ...
368: return
```

药水计时在该调用之前已全部完成。对**本地玩家**把该返回值改为 0，即可只跳过粒子生成、
不影响药效时长，且**不需要插入分支 / 栈帧**（栈形完全不变）。

## 3. 新增 / 修改文件

### 新增（legacy/src/main/java/fpsport/…）

1. `fpsport/render/CleanView.java`
   - `static boolean using`
   - `public static int readPotionColor(DataWatcher watcher, int id, Object self)`：
     `using && self == Minecraft.getMinecraft().thePlayer` → 返回 0，否则返回
     `watcher.getWatchableObjectInt(id)`。异常兜底返回真实值，绝不吞药效。
2. `fpsport/render/BlockOverlay.java`：照抄导出实现（`fill/outline/throughBlock/width/
   fillColor/outlineColor/using` + `onRender3D()`）。
3. `fpsport/render/ItemPhysics.java`：开关类（`using`）。
4. `fpsport/render/ItemPhysicsRenderer.java`：
   - `public static boolean render(RenderEntityItem renderer, EntityItem entity,
     double x, double y, double z, float entityYaw, float partialTicks)`；
   - `ItemPhysics.using == false` → 返回 false（原版继续跑）；
   - 渲染体从导出 mixin 搬入（贴图 blur 成对、`GlStateManager` 变换、`GL11.glRotatef`、
     空中翻滚、落地回正、多数量随机偏移）；
   - 数量改为反射调用 `RenderEntityItem` 的私有 `(Lnet/minecraft/item/ItemStack;)I`
     （按描述符查找，不依赖方法名；失败回退 1），结果缓存 `Method`。
5. `fpsport/utility/BetterChat.java`：照抄导出实现，另加：
   - 反射字段名同时兼容 `chatLines`/`field_146252_h`、`drawnChatLines`/`field_146253_i`；
   - `public static boolean handleClick(IChatComponent component)`：命中
     `ClickEvent.Action.RUN_COMMAND` 且值以 `\u0000#COPY` 开头时写剪贴板
     （`GuiScreen.setClipboardString`）并返回 true；否则 false。
6. `fpsport/utility/CPSDisplay.java`：照抄导出实现（1 秒窗口）。
7. `me/sorihiko/dango/core/CleanViewTransformer.java`
   - 目标：`EntityLivingBase`，方法 `()V` 且名字 ∈ `{updatePotionEffects, func_70679_bo, bi}`；
   - 找到 `INVOKEVIRTUAL net/minecraft/entity/DataWatcher.<getWatchableObjectInt>(I)I`
     （名字 ∈ `{getWatchableObjectInt, func_75679_c, c}`，owner+描述符精确匹配）；
   - 在其前插入 `ALOAD 0`，并把该节点替换为
     `INVOKESTATIC fpsport/render/CleanView.readPotionColor
     (Lnet/minecraft/entity/DataWatcher;ILjava/lang/Object;)I`；
   - 无新增跳转 → 不需要 FrameNode；`ClassWriter(COMPUTE_MAXS)`；失败原样返回。
8. `me/sorihiko/dango/core/ItemPhysicsTransformer.java`
   - 目标：`RenderEntityItem`，描述符 `(Lnet/minecraft/entity/item/EntityItem;DDDFF)V`，
     名字 ∈ `{doRender, func_76986_a, a}`；
   - HEAD 插入：`ALOAD 0, ALOAD 1, DLOAD 2, DLOAD 4, DLOAD 6, FLOAD 8, FLOAD 9,
     INVOKESTATIC fpsport/render/ItemPhysicsRenderer.render
     (Lnet/minecraft/client/renderer/entity/RenderEntityItem;
      Lnet/minecraft/entity/item/EntityItem;DDDFF)Z`，
     `IFEQ skip`、`RETURN`、`F_SAME`；
   - 失败原样返回。
9. `me/sorihiko/dango/core/BetterChatTransformer.java`（两个 HEAD 注入，均 `F_SAME`）
   - `NetHandlerPlayClient.handleChat(S02PacketChat)V`：`ALOAD 1` →
     `BetterChat.onChatPacket(S02PacketChat)Z` → true 则 `RETURN`；
   - `GuiScreen.handleComponentClick(IChatComponent)Z`：`ALOAD 1` →
     `BetterChat.handleClick(IChatComponent)Z` → true 则 `ICONST_1 / IRETURN`。

### 修改

10. `me/sorihiko/dango/core/DangoCorePlugin.java`：注册上述 3 个 transformer
    （CleanView / ItemPhysics / BetterChat），排在现有 4 个之后。
11. `legacy/src/main/kotlin/me/sorihiko/dango/legacy/FpsPortRuntime.kt`
    - 新增实例：`cleanView`、`blockOverlay`、`itemPhysics`、`betterChat`、`cpsDisplay`；
    - `sync()` 写入开关与可调项（`BlockOverlay.fill/outline/throughBlock/width`、
      `BetterChat.foldMessage/copyMessage`），`cpsDisplay` 跟随 PVP 工具总开关；
    - `tick()` 调 `CPSDisplay.onTick()`；
    - `onMouse()` 在 `event.buttonstate && button ∈ 0..1` 时调 `CPSDisplay.onMouseClick(button)`；
    - `onRender3D()` 调 `BlockOverlay.onRender3D()`。
12. `common/ui/.../module/impls/music/FpsToolsModule.kt`：新增配置字段
    - `cleanView`、`blockOverlay`、`boFill`、`boOutline`、`boThrough`、
      `boWidth = FloatValue(0.5f, 5f, 1f, 1)`、`itemPhysics`、
      `betterChat`、`chatFold`、`chatCopy`（全部默认关闭，行为安全的除外）。
13. `common/ui/.../musicui/SettingsPage.kt`：新增两个分组
    - 「方块与物品」：`blockOverlay, boFill, boOutline, boThrough, boWidth, itemPhysics`
    - 「聊天与视角」：`betterChat, chatFold, chatCopy, cleanView`
    - `deps`：`boFill/boOutline/boThrough/boWidth→blockOverlay`、
      `chatFold/chatCopy→betterChat`；`boWidth` 格式 `"%.1f px"`。
14. `DangoMusic-main/version.properties`：`1.4.8` → `1.4.9`。

## 4. 明确不做（附原因）

- **不新建护甲 HUD / CPS HUD**：铁律 UI 冻结；宿主现有 HUD 无这两项，本批只保留
  `ArmorDisplay` / `CPSDisplay` 的可读数据（`visibleStacks/leftCps/rightCps`），
  不放进设置页（没有可调项、也无可切换行为）。
- 不改 `ForgeBridge` 的既有签名（只通过 `FpsPortRuntime` 增加调用）。
- 不改任何 `ui/` 主题、字体、面板、坐标。

## 5. 验收

1. `gradlew.bat --offline build` 通过。
2. ASM 自检：用 asm-util `CheckClassAdapter` + `BasicVerifier` 对
   `EntityLivingBase / RenderEntityItem / NetHandlerPlayClient / GuiScreen` 打补丁后
   的字节码做校验，全部 `All OK`；确认注入点数量正确。
3. `git diff --stat` 不含 `ui/`（SettingsPage/FpsToolsModule 属于设置数据层，
   但无视觉改动）、`theme/`、新 Screen/HUD 类。
4. 逻辑烟测点：护甲顺序（头盔→靴子）、0%/100% 耐久；CPS 1 秒回落；
   折叠 `[x2]`；复制写剪贴板且不向服务器发包；清洁视角药效时长不变；
   方块高亮填充+描边、穿墙、楼梯不悬空；掉落物翻滚落地回正。
5. 构建成功后备份 `music pvp 1.4.9+1.8.9.jar` 与 `...-src.zip` 到
   `C:\Users\xzy18\Desktop\TraeCode CN\music pvp`。
