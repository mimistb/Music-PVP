# 任务清单（bedwars-integration）

按顺序执行；每项完成后跑一次对应模块编译，最后统一构建。

## T1 数据桥（common/core）
- [x] 新增 `module/values/StringValue.kt`（@Volatile 值，set 截断 128）
- [x] `config/ConfigManager.kt`：`applyValue` / `serialize` 追加 `StringValue` 分支（不改旧键）
- [x] 新增 `hud/BedWarsData.kt`：比赛态、威胁快照数组、bedLine、统计缓存、API 状态、设置镜像、`teamRgb`
- 验证：`common` 编译通过；`bedwars`/`bedwarsstats` 无改动

## T2 设置模块（common/ui）
- [x] 新增 `module/impls/music/BedWarsModule.kt`（4 个 @ModuleValue + tick 同步到 BedWarsData）
- [x] `DangoClient.kt` 注册该模块
- 验证：模块出现在模块列表且 ConfigManager 能存读新字段

## T3 设置页文本框（common/ui）
- [x] `SettingsPage.kt`：`Ctl.TEXT`、`Row.secret`、StringValue 识别、绘制与编辑、Enter 提交、密文显示
- [x] 新增「床提醒」分组（开关 / 半径 / API Key / 查询玩家）
- 验证：不改变任何现有行布局；数值输入仍正常

## T4 1.8.9 取数运行时（legacy）
- [x] 新增 `legacy/.../legacy/BedWarsRuntime.kt`：sidebar / TAB / 皮革甲 / 实体 / 床扫描缓存 / MatchInput / 发布 / 统计服务 / 手动查询 / addScheduledTask 回调
- [x] `ForgeBridge.kt` tick 调用 `BedWarsRuntime.tick()`（try/catch）
- 验证：编译通过；世界切换清状态；GUI 打开不 update

## T5 灵动岛行（common/ui）
- [x] `DynamicIslandModule.kt`：IDLE 尺寸 +14、可见性放宽、`drawBedRow`、无尽音乐时不画音浪
- 验证：无 BedWars 数据时尺寸/绘制与改前一致

## T6 敌人卡统计行（common/ui）
- [x] `TargetInfoHudModule.kt`：底部 y+41 一行统计，按 statsVersion/name 缓存字符串，trim 到面板宽
- [x] 显示判定改为「瞄准 + 左键点击」，默认停留 1.5 秒，可调淡出时长
- 验证：无数据不画，面板尺寸不变

## T7 自检与构建
- [x] `grep` 无新增 GuiScreen/GuiIngame；`git diff --stat` 确认两个 java 包未改
- [x] JDK 8 执行 `legacy\gradlew.bat --no-daemon --console=plain --offline build`
- [x] 复制 `DangoMusic-*+1.8.9.jar` 到桌面并给出中文交付说明（含人工验收步骤）
