# 验收清单

- [x] 21 个 Java 文件全部落在 `legacy/src/main/java/fpsport/{adnin,selftest}`，包名与目录一致。
- [x] `chat` 不 import `anticheat`/`session`；`session` 不 import 其它两个；无静态注册表。
- [x] `ModuleTestRunner` 独立运行 `PASSED=89 FAILED=0`。
- [x] `updateConfig` 非法值抛异常且不改变运行中配置（单测已覆盖，保持通过）。
- [x] 聊天透明度 100% 时与原生完全一致；0% 时背景与文字全透明；RGB 不变。
- [x] 透明度滑条挂在「聊天增强」开关下（关闭时置灰）。
- [x] AC/Chat/Session 参数改动后下一 tick 生效（指纹热更新），非法组合不会让模块抛异常。
- [x] 告警只输出一份（`[AC]` 聊天行），无第二套告警 UI。
- [x] 会话仅数据、无新增 HUD；`BedWarsRuntime` 跳变只驱动模块状态机。
- [x] 设置页无新增分组卡片、无布局常量改动、无新增 Screen/HUD 类。
- [x] `gradlew build` 离线通过。
- [x] `version.properties` = 1.9.4；`Music PVP 1.9.4+1.8.9.jar` 与 `music pvp 1.9.4+1.8.9-src.zip` 已备份。
- [x] 交付报告写明放弃项：检测算法、会话 HUD、Hypixel 统计输出、新设置分组。
