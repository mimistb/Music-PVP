# Tasks

- [x] T1 `FpsPortRuntime.kt`：解耦 `performance` / `betterChat` 与容器模块 `enabled`
- [x] T2 `FpsPortRuntime.kt`：`applyFpsLimit` 提前到世界判空之前
- [x] T3 `SettingsPage.kt`：`Group.gateOnModule` + 性能/聊天分组不再随容器置灰
- [x] T4 `SettingsPage.kt`：`hitClip` 修复 `HitList` 双重位移
- [x] T5 `SettingsPage.kt`：删除「主界面背景」分组，接入「游戏选项背景」卡片
- [x] T6 `SettingsPage.kt`：`drawBackgroundPicker`（预设缩略图 + 实时预览 + 持久化）
- [x] T7 `SettingsPage.kt`：`draw(page)` 绘制页面背景
- [x] T8 校验主界面背景残留引用（`MenuBackground`）
- [x] T9 全量离线构建 + `:core:bugfixTest` 回归

## 验证结果

- `gradlew build --offline`：BUILD SUCCESSFUL（`DangoMusic-2.1.0+1.8.9.jar`）
- `:core:bugfixTest --rerun-tasks`：ALL 10 CHECKS PASSED
- 产物 jar 内仅含 `ScreenBackground` / `ScreenBackgroundModule`，无 `MenuBackground`
- 产物 jar 内含 `assets/dangomusic/backgrounds/yileina.jpg`（1,035,365 字节），默认预设指向它
