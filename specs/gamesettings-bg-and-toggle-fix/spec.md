# 游戏选项背景 + 开关失效修复（Spec）

## 目标

1. 修复「性能」开关无法生效：开关状态必须直接驱动 `fpsport` 的 `Performance` 特性。
2. 修复「聊天增强」开关无法生效：开关状态必须直接驱动 `BetterChat` 特性及其子项。
3. 移除主界面（主菜单）原有背景展示模块与渲染逻辑。
4. 在游戏选项（设置页）右侧内容区新增背景选择：预设背景、自定义图片上传、实时预览、持久化。
5. 全量离线构建 + 回归用例验证。

## 根因

- `FpsPortRuntime.sync()` 中 `performance.setEnabled(on && m.performance.get())`、
  `val perfOn = on && m.performance.get()`、`betterChat.setEnabled(on && m.betterChat.get())`
  都被 `FpsToolsModule.enabled`（容器模块总开关）二次门控。
- 设置页中「性能」各分组与「聊天与视角」分组的 `owner` 都是 `FpsToolsModule`，
  `drawGroup` 里 `moduleOff = !g.owner.enabled` 会把整组置灰并丢弃点击热区，
  玩家直接拨动 `performance` / `betterChat` 时既看不到效果、也无法在容器关闭时操作。
- 结果：开关值已保存，但对应优化逻辑永远读不到 `true`。

## 变更点

### 1. `legacy/src/main/kotlin/me/sorihiko/dango/legacy/FpsPortRuntime.kt`

| 位置 | 修改 |
| --- | --- |
| `sync()` 性能段 | `performance.setEnabled(m.performance.get())` |
| `sync()` 性能段 | `val perfOn = m.performance.get()` |
| `sync()` 聊天段 | `betterChat.setEnabled(m.betterChat.get())` |
| `onRender3D()` | `Performance.applyFpsLimit()` 移到 `mc.theWorld == null` 判断之前 |

### 2. `common/ui/src/main/kotlin/me/sorihiko/dango/musicui/SettingsPage.kt`

- `Group` 新增 `gateOnModule: Boolean = true`；`drawGroup` 改为
  `val moduleOff = g.gateOnModule && !g.owner.enabled`。
- 「性能」四个分组与「聊天与视角」分组传 `gateOnModule = false`，容器关闭时仍可操作。
- `drawGroup` 新增未平移的 `hitClip`（`b.x, bodyTop, b.width, bodyH`）传给 `drawRow` 与
  `custom`，`beginClip` 继续用已平移的 `bodyClip`，修复 `HitList` 双重位移。
- 删除旧「主界面背景」分组（`MenuBackgroundModule`），改为「游戏选项背景」：
  - `ScreenBackgroundModule` + 行：选择图片（`pickImageFileAsync`）/ 不透明度 / 填充 / 暗色遮罩；
  - `customH = 156f` 的自定义区域 `drawBackgroundPicker`：7 个预设缩略图（关闭 + 6 渐变）、
    选中描边、底部实时预览、失败提示；
  - 点击预设：`preset.setValue(i)`、`path.set("")`、`persist()`。
- `draw(page)` 开头调用 `ScreenBackground.drawPageBackground(ui, page)`。

### 3. 默认背景资源（yileina.jpg）

- 图片导入 `common/ui/src/main/resources/assets/dangomusic/backgrounds/yileina.jpg`，随 shadowJar 打包。
- 预设列表改为：0 关闭 / **1 插画（默认选中）** / 2 ~ 7 内置渐变。
- `ScreenBackground` 通过 `getResourceAsStream("/assets/dangomusic/backgrounds/yileina.jpg")`
  在后台线程解码，渲染线程上传；解码结果按最长边 2048 预缩放，控制显存。
- 填充模式 0 改为「铺满裁切」：用 `drawImageRegion` 的 UV 裁切等比覆盖，不拉伸；
  1 适应（完整显示）、2 平铺。
- `LegacyTexture.bind()` 设置 `GL_LINEAR`，避免 1.8.9 `DynamicTexture` 默认最近邻采样
  在大图缩放时产生锯齿。
- 资源缺失 / 解码失败时回退为纯色占位，不影响设置页其他内容。

### 4. 已完成的移除（校验项）

- 删除 `MenuBackgroundModule.kt`、`legacy/.../core/MenuBackground.java`。
- `SplashGlassHook.java` 中两处调用与 import 已删除。
- `DangoClient` 注册改为 `ScreenBackgroundModule`。

## 验收

- `legacy` 全量离线构建成功（`gradlew build --offline`）。
- `:core:bugfixTest` 全部用例通过。
- 源码中不再存在 `MenuBackground` 引用（`build/` 产物除外）。
- 设置页切换「性能」/「聊天增强」后，`FpsPortRuntime.sync` 直接写入对应 `Feature`。
