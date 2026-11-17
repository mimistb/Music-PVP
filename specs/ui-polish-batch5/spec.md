# UI 精修批次（batch5）

## 1. 用户需求（原文拆解）

1. 将设置中的各个分工不同的模块分割开（上一批未完成）。
2. 界面关闭后再次开启，回到原来的页面与滚动位置。
3. 全面可复制粘贴化（所有文本输入框支持 Ctrl+C / V / X / A）。
4. 方块高亮的填充颜色与描边颜色增加「黑色」「浅灰」两个可选色。
5. 各个信息显示分开，不要粘合在一块。
6. 严格检查 bug 并构建。

## 2. 实现决策（已完成）

| # | 决策 | 落点 |
|---|---|---|
| 1 | 设置列表改为「段标题通栏 + 段内两列瀑布流」；段标题 = 红色竖条 + 13px 粗体 + 通栏分隔线；卡片加 1px 淡描边。段顺序：音乐 → 组件位置 → 信息与 HUD → 网络与对战 → PVP·打击 / 视角与画面 / 自动化 / 性能 / 方块与物品 / 聊天与护甲。不同段绝不并排混排。 | `common/ui/.../musicui/SettingsPage.kt`：`draw()` 分组算法（`sectionGroups` / `Placed` / `sectionTop`）、`drawSectionHeader()`、`drawGroup()` 描边 |
| 2 | 所有滚动状态放进跨界面存活的 `ScreenState`：`homeScroll`、`tracksScroll`、`tracksCover`、`settingsScroll`、`sidebarScroll`；各页面构造时读取、`tick()`/`scroll()`/`draw()` 写回；进入设置页不再重置滚动。页面本身沿用已有 `ScreenState.page`。 | `musicui/Library.kt`、`GridPage.kt`、`TracksPage.kt`、`Sidebar.kt`、`SettingsPage.kt`、`music/MusicScreen.kt`（`toggleSettings` 去掉 `resetScroll()`） |
| 3 | 新增共用单行输入状态 `TextInput`（光标、选区、Ctrl+A/C/X/V、Backspace/Delete/←/→/Home/End、`maxLength`、字符过滤 `accept`、密码掩码），搜索框 / 歌单筛选框 / 房间码 / 设置里文字与数值全部改用该类；剪贴板走 `Platform.clipboard`（游戏自带系统剪贴板）。 | 新增 `musicui/TextInput.kt`；`platform/Keys.kt` 增加 A/F/L/X/C/V 键码；`Sidebar.kt`、`TracksPage.kt`、`PartyPage.kt`、`SettingsPage.kt` |
| 4 | 方块高亮色板由 6 色扩到 8 色：白 / 粉 / 蓝 / 金 / 绿 / 红 / **黑** / **浅灰**；`boFillColor`、`boOutlineColor` 上限 7；深色色块在深色卡片上补 1px 浅描边保证可见。`FpsPortRuntime` 的 `argb()` 天然支持 RGB=0，legacy 无需改。 | `module/impls/music/FpsToolsModule.kt`、`musicui/SettingsPage.kt`（色板绘制） |
| 5 | 信息胶囊不再用一条 `·` 串联：按类别（玩家名 / 帧率 / 延迟 / 时间 / 护甲 / CPS）各自一个小胶囊，横向排开、间距 6px；整体仍由 `posX/posY` 拖动与缩放，关玻璃时每块独立黑色胶囊。 | `module/impls/music/HudInfoModule.kt`：`blockStart/blockEnd`、`addBlock()`、`chipWidth()`、`render()` |
| 6 | 全量 `gradlew build` 通过（`BUILD SUCCESSFUL in 1m 59s`），仅剩既存的 `DangoMusicMod.java` deprecation 提示。自查已修正：筛选框只有内容变化才重置滚动、段内高度/裁剪/滚动上限、`addBlock` 用 `private inline`、黑/浅灰可见性、设置页滚动不再被进入动作重置。 | `legacy` Gradle 构建 |

## 3. 待执行（需批准）

1. `DangoMusic-main/version.properties` → `1.7.0`。
2. 重新构建（JAVA_HOME=jdk-8.0.432.6-hotspot，离线，`--no-daemon build`）。
3. 备份产物：
   - jar → `c:\Users\xzy18\Desktop\TraeCode CN\music pvp\music pvp 1.7.0+1.8.9.jar`
   - 源码 zip → `music pvp 1.7.0+1.8.9-src.zip`（robocopy 排除 `build .gradle .kotlin run out .idea`）
4. 输出中文交付说明（含第 3、5 项的理解假设）。

## 4. 验收标准

- `BUILD SUCCESSFUL`，`legacy/build/libs/DangoMusic-1.7.0+1.8.9.jar` 存在。
- 两个备份文件存在且大小非零。
- 设置页每段有通栏标题、段间不并排；搜索/筛选/房间码/设置输入框 Ctrl+C/V/X/A 均生效；关闭再打开界面回到原页面与原滚动位置；方块高亮色板 8 色可选且黑色块可见；信息胶囊为多个独立小块。

## 5. 风险与边界

- 单行输入框无 Shift 选择，因此「无选区时 Ctrl+C/X」按复制/剪切整段处理（有意设计）。
- `go(page)` 主动切换页面仍会重置该页滚动（属于导航语义，不影响「关闭再打开」恢复）。
- 备份路径若已存在同名文件将覆盖（沿用历史约定）。
