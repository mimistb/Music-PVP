# 2.0.8 任务拆解

## T1 删除旧主界面背景模块
- [x] 删除 `common/ui/src/main/kotlin/me/sorihiko/dango/module/impls/music/BackgroundModule.kt`
- [x] 删除 `common/ui/src/main/kotlin/me/sorihiko/dango/musicui/BackgroundImage.kt`
- [x] `DangoClient.kt`：移除 import 与 `BackgroundModule()` 注册
- [x] `MusicScreen.kt`：移除 import、`background` 字段、`drawBackground`/`drawBackgroundImage`、render 调用
- [x] `SettingsPage.kt`：移除 BackgroundModule import、`bg`/`bgRows`/浏览按钮/「界面背景」分组
- [x] `Platform.kt`：删除 `pickImageFile()` 默认实现
- [x] `LegacyPlatform.kt`：删除 `pickImageFile()` override
- [x] 验证：全局搜索 `BackgroundModule|BackgroundImage|pickImageFile` 无源码命中

## T2 主菜单右上角齿轮入口
- [x] `SplashGlassHook.java`：新增齿轮几何/悬停字段与绘制
- [x] `SplashGlassHook.java`：新增 `onMainMenuClick(III)Z`
- [x] `SplashGlassTransformer.java`：注入 `GuiScreen.mouseClicked`，命中时提前 RETURN
- [x] `LegacyMusicScreen.kt`：新增 `openSettings` 参数并在 `initGui` 调用
- [x] `MusicScreen.kt`：新增 `openSettings()`
- [x] 验证：编译通过；静态检查命中坐标与提前返回分支

## T3 液态模糊开关
- [x] `NeteaseMusicModule.kt`：新增 `liquidBlur` 与 `of()`
- [x] `GlassRenderContext.kt#drawGlass`：关闭时走 fallback 纯色分支
- [x] `SettingsPage.kt`：新增「界面」分组展示 `liquidBlur`
- [x] 验证：编译通过；开关读取无异常兜底

## T4 自动翻译玩家话语
- [x] `ChatTranslateSettingsModule.kt`：新增 `autoTranslateIncoming = false`
- [x] `ChatTranslateRuntime.kt#sync`：同步新字段
- [x] `ChatTranslateModule.java`：新增字段与 `onChatReceived` 自动翻译分支
- [x] `SettingsPage.kt`：聊天翻译分组加入新行
- [x] 验证：默认 false；译文与原文相同不打印；异常静默

## T5 Hyp 自动取数
- [x] `BedWarsRuntime.kt`：新增 45 人上限常量
- [x] `BedWarsRuntime.kt#syncStats`：改为进世界且 ≤45 人即自动入队，移除 TAB/对局前置条件
- [x] 世界切换 / 人数变化时清空 `enqueued`
- [x] 验证：>45 人零入队；≤45 人全量入队；手动查询不受影响

## T6 HUD 拆分
- [x] `HudInfoModule.kt`：新增 8 个位置项与默认值
- [x] `HudInfoModule.kt`：`blockKind` + 分块独立布局
- [x] `HudInfoModule.kt`：命中块独立拖动并写回对应位置
- [x] `HudInfoModule.kt`：裁剪区域改为全屏
- [x] `SettingsPage.kt`：`percentKeys` 加入 8 个新键
- [x] 验证：编译通过；四个胶囊位置互不影响

## T7 三平台账号展示与切换
- [x] `LoginAccounts.kt`：新增 `activePlatform` 读写与持久化
- [x] `Sidebar.kt#drawAccount`：三行登录态 + 切换 + 退出
- [x] `Sidebar.kt`：`footerH` 调整，避免与歌单列表重叠
- [x] `LoginDialog.kt`：登录成功后设置 activePlatform
- [x] 验证：已登录平台点击不触发扫码；未登录平台打开登录弹窗

## T8 版本、构建、备份
- [x] 版本号改为 `2.0.8`
- [x] `legacy` 下 `.\gradlew.bat build --offline` 成功
- [x] 产物复制到 `c:\Users\xzy18\Desktop\TraeCode CN\music pvp`
- [x] 输出中文完成报告与限制说明
