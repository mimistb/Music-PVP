# 任务分解（执行结果）

## T1 构建链路修复并产出 jar
- [x] T1.1 改 `legacy/build.gradle.kts` classpath → `com.anatawa12.forge:ForgeGradle:2.3-1.0.8`
- [x] T1.2 `JAVA_HOME=…jdk-8.0.432.6-hotspot`；联网首次构建
  - 追加：`setSuppressVersionTest(true)` 解除 1.12 版本闸门
  - 追加：`gradle.properties` 关闭已下线的 FG 在线版本检查
  - 追加：先单独执行 `deobfMcMCP genSrgs` 生成 `forgeBin`，再执行 `build`
  - 追加：`compileJava` 统一 UTF-8，消除 GBK 不可映射字符告警
- [x] T1.3 未触发回退阶梯（2.3 可用）
- [x] T1.4 校验：`jar tf` 1724 条目、`javap` 见 `func_`、`mcmod.info` / `version.properties` 正确
- [x] T1.5 构建记录留存 `build_legacy.log`（因翻译/优化改动先于首次成功构建落地，未单独产出纯净基线 jar）

## T2 中文翻译
- [x] T2.1 框架层：MusicScreen、Sidebar、PlayerBar、TracksPage、GridPage
- [x] T2.2 弹窗/页：PartyPage、LoginDialog、SettingsPage
- [x] T2.3 模块注解：LyricsModule、DynamicIslandModule、SongCardModule、NeteaseMusicModule
- [x] T2.4 全局复查：仅剩 `@ModuleMeta` 名、配置 key、按键名、日志、品牌名

## T3 产出中文版 jar
- [x] T3.1 `gradlew build` 产出 `DangoMusic-1.4.1+1.8.9.jar`
- [ ] T3.2 未执行：本环境未做自动化 `runClient` 截图（按 spec 6.6 降级为源码审计）

## T4 性能优化
- [x] T4.1 `UiFont`（ASCII 数组 + 宽度缓存）、`CjkFont`（宽度缓存 + 失败集合 + 去临时 List）、`GlassRenderContext`（矩阵复用 + 屏幕尺寸缓存）
  - 未做：`GlassBackdrop` 缓冲/状态复用（收益低、风险高，未纳入）
- [x] T4.2 `MixedText`（ASCII 快路径 + trim/wrap2 二分）
- [x] T4.3 `SettingsPage`（格式串缓存）、`MusicScreen`（pageKey 逐字段比较）
- [x] T4.4 `DynamicIslandModule`（时钟按分钟缓存）

## T5 视觉与回归
- [x] T5.1 构建最终（中文 + 优化）jar
- [ ] T5.2 未执行：自动化同状态截图
- [ ] T5.3 未执行：像素级 diff
- [x] T5.4 静态审计：与原始 zip 全文件 diff，确认无布局/颜色/动画/绘制顺序改动

## T6 交付
- [x] T6.1 复制最终 jar 到 `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-1.4.1+1.8.9.jar`
- [x] T6.2 记录 SHA256、条目数、构建日志
- [x] T6.3 交付说明（替换方式、中文页面、优化点、未验证项）
