# 任务清单

## T1 模块源码落地
- [x] 复制 `_adoverlay_tmp/java/fpsport/adnin/{anticheat,chat,session}` 到 `legacy/src/main/java/fpsport/adnin/`。
- [x] 复制 `fpsport/selftest/ModuleTestRunner.java` 到 `legacy/src/main/java/fpsport/selftest/`。
- [x] 检查三个子包互不 import（grep `import fpsport.adnin` 无命中）。

## T2 模块 bug 修复（spec §4 的 11 条）
- [x] `AnticheatConfig`：copy 判空、validate 补 pitch/name/NaN。
- [x] `AnticheatModule`：prune 全量清理、键淘汰（`sweepStale`）、listener 隔离。
- [x] `ChatConfig`：copy/validate 判空与 NaN。
- [x] `ChatParser`：公聊判定改用 `body.startsWith("<")`。
- [x] `ChatDataModule`：TeamLookup 异常隔离、listener 隔离。
- [x] `SessionConfig`：scale 有限性校验。
- [x] `SessionModule`：自动清空走 reset 通知路径（复核为误报，`reset()` 已通知，未重复发）。

## T3 宿主运行层
- [x] 新建 `AdninRuntime.kt`：三模块实例、队友 TeamLookup、提示音 sink、告警输出、selfName、sync 指纹、tick、onChat、onMatchActive。
- [x] `FpsPortRuntime.tick/onChat` 接线（修正 onChat 早退）。
- [x] `BedWarsRuntime.publish` 接 match active 跳变。

## T4 聊天透明度
- [x] `FpsToolsModule` 新增 `chatOpacity`。
- [x] `BetterChat` 新增 `opacity` + `alpha(color)`。
- [x] `BetterChatTransformer` 新增 `GuiNewChat#drawChat` 注入并注册到 `DangoCorePlugin`。
- [x] `FpsPortRuntime.sync` 写入 `BetterChat.opacity`。
- [x] `SettingsPage` 增加该行与 deps/fmts。

## T5 模块参数暴露与设置页
- [x] `FpsToolsModule` 追加 ac*/adChat*/adSession* 参数。
- [x] `AdninRuntime.sync` 指纹热更新三个 Config。
- [x] `SettingsPage` 现有「聊天与视角」组追加行，补 deps/fmts（不新增分组卡片）。

## T6 验收
- [x] javac 独立编译 21 文件 + 跑 `ModuleTestRunner`（`PASSED=89 FAILED=0`）。
- [x] 离线 `gradlew build` 通过（含 `AdninRuntime` 队伍名修正后二次构建）。
- [x] UI 冻结检查：无新增 Screen/HUD/面板/主题类，设置页仅追加参数行（`git` 不可用，改用文件清单人工核对）。
- [x] 版本 1.9.3 → 1.9.4，备份 jar 与 src.zip，输出交付报告（改动清单 / 接线路径 / 单测 / 验收 / 放弃项）。
