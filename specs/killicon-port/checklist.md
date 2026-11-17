# 击杀图标移植验收清单

## 约束合规

- [x] 未引入 Gale 框架：新增源码无 `Module` 基类、`Setting`、事件总线、`ConfigManager`（Gale 版）、HUD 管理器、`Gale.LOG`
- [x] 仅保留 3 个替身：`gale.core.Scheduler`、`gale.event.impl.Render2DEvent`、`gale.util.RenderUtil`
- [x] 资源命名空间保持 `gd656killicon`，落在 `assets/gd656killicon/`
- [x] 未新增 Screen / GuiScreen / HUD 面板 / 设置控件
- [x] 未写 mixin、未 hook 渲染管线
- [x] 所有参数通过 `KillIconModule` public 字段 / `KillIconHudModule` 配置项暴露
- [x] 锚点为屏幕百分比、缩放为 `size / 96`，无 GUI Scale / 分辨率分支

## 编译与产物

- [x] JDK8 离线构建 `BUILD SUCCESSFUL`（EXIT=0）
- [x] 唯一编译告警为既有的 JavaCompile deprecation note，无新增错误 / 警告
- [x] jar 内包含 `fpsport/killicon/KillIconModule.class`
- [x] jar 内包含 `gale/kill/gd/**` 与 3 个替身类
- [x] jar 内包含 `assets/gd656killicon/**`（380 条）与 `presets/index.json`
- [x] jar 内包含 `sounds.json` 与 116 个 ogg
- [x] 源码包含 `common/ui/.../KillIconHudModule.kt` 与 `legacy/.../KillIconRuntime.kt`

## 接线

- [x] tick：`ForgeBridge.onClientTick` → `KillIconRuntime.tick()`（在 `FpsPortRuntime.tick()` 之后）
- [x] 击杀：`ForgeBridge.onLivingDeath` + `ForgeBridge.onAttack` 兜底
- [x] 渲染：`ForgeBridge.onRenderGameOverlayPost(ALL)` → `KillIconRuntime.onRenderOverlay`（动态模糊之前）
- [x] 重置：世界变化 / 本地玩家死亡 / 模块关闭
- [x] 初始化只做一次 `applyPreset(8)`（对应原 `00009` Valorant Prime）

## UI

- [x] 设置页「PVP · 打击」段出现「击杀图标」分组
- [x] 分组含 7 项：音效、音效音量、预设、横向锚点、纵向锚点、图标尺寸、预览循环
- [x] 预设行显示 index.json 中的真实预设名（42 条）
- [x] 组标题总开关绑定模块 `enabled`
- [x] 未新增控件类型，未改动其它分组

## 版本与备份

- [x] `version.properties` = 2.0.2（符合 2.0.1 → 2.0.2 规则）
- [x] 备份 `music pvp 2.0.2+1.8.9.jar`
- [x] 备份 `music pvp 2.0.2+1.8.9-src.zip`
- [x] 旧 `music pvp 2.0.1+1.8.9.jar` / `-src.zip` 已删除

## 待实机确认

- [ ] 游戏内切预设立即生效
- [ ] 击杀实体后图标播放且锚点位置正确
- [ ] 音效音量生效且可关闭
- [ ] 切服 / 死亡后图标清空
