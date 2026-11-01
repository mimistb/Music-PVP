# 交付检查清单

## 构建
- [x] `legacy` 用 ForgeGradle 2.3-1.0.8 + JDK8 成功构建，产物 `legacy/build/libs/DangoMusic-1.4.1+1.8.9.jar`
- [x] 绕过 FG 的 1.8.9 版本限制（`setSuppressVersionTest(true)`）与已下线的在线版本检查（`net.minecraftforge.gradle.disableUpdateChecker=true`）
- [x] jar 结构正确：`mcmod.info` / `version.properties` / `assets` / `logo.png`，共 1724 个条目
- [x] `javap` 确认已 reobf：MC 引用为 `func_` / `field_` 混淆名
- [x] 依赖已重定位：`me/sorihiko/dango/shadow/kotlin` 等，不会与其它 mod 冲突

## UI 中文化
- [x] 侧栏、主界面、设置页、歌曲列表、封面墙、一起听、登录弹窗、播放条全部可见文案改为中文
- [x] 模块设置项名称与说明（歌词 / 网易云 / 歌曲卡片 / 灵动岛）改为中文
- [x] 全局复查无残留可见英文 UI 文案（保留 `@ModuleMeta` 名、配置 key、按键名、日志、品牌名）
- [x] 配置 key 与模块名未改，旧配置不会被重置

## 性能优化（视觉不变）
- [x] `UiFont`：ASCII 字形数组化 + 按字号宽度缓存
- [x] `CjkFont`：宽度缓存 + 失败字形集合 + `draw()` 去掉每帧临时 List
- [x] `GlassRenderContext`：单位矩阵复用 + 屏幕尺寸只在窗口变化时重建
- [x] `MixedText`：纯 ASCII 快路径 + `trim`/`wrap2` 二分查找
- [x] `MusicScreen`：pageKey 改为逐字段比较，不再每帧拼字符串
- [x] `SettingsPage`：常用小数位格式串缓存
- [x] `DynamicIslandModule`：时钟文本按分钟缓存

## 视觉等价性
- [x] 与原始 zip 逐文件 diff 审计：改动仅为文案替换或上述等价改写，未触碰布局常量、颜色、动画参数、绘制顺序、浮点累加顺序
- [x] 采用 spec 6.6 降级方案：本环境未做自动化客户端同状态截图，像素对比未执行，等价性由源码级审计保证

## 交付
- [x] 最终 jar 复制到 `c:\Users\xzy18\Desktop\TraeCode CN\DangoMusic-1.4.1+1.8.9.jar`
- [x] 记录 SHA256、文件大小、条目数
