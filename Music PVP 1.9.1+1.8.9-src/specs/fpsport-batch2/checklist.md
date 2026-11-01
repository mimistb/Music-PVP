# fpsport 第二批移植验收清单

- [x] 三个新 transformer 已注册进 `DangoCorePlugin`，类名常量用点分形式（与 LaunchClassLoader 一致）
- [x] ASM 自检：`EntityLivingBase / RenderEntityItem / NetHandlerPlayClient / GuiScreen`
      打补丁后 `CheckClassAdapter` 全部通过，注入点各 1 处，`BasicVerifier` 全通过
- [x] CleanView：只对本地玩家把药水颜色读成 0，其它实体/关闭开关时返回真实值，不改药效计时
- [x] ItemPhysics：`using == false` 时 `render` 返回 false，原版渲染照常
- [x] BetterChat 折叠：`onChatPacket` 返回 true 时 `handleChat` 直接 RETURN（不进入聊天栏）
- [x] BetterChat 复制：`handleClick` 命中 `\u0000#COPY` 时写剪贴板并返回 true，不发包给服务器
- [x] BlockOverlay：`FpsPortRuntime.onRender3D` 每帧调用，世界空间绘制，无 UI 代码
- [x] CPSDisplay：1 秒窗口计数，只暴露 `leftCps/rightCps` 数据，不画 HUD
- [x] FpsPortRuntime 接线完整（实例 / sync / tick / onMouse / onRender3D）
- [x] 设置页新增「方块与物品」「聊天与视角」两组，依赖与 `boWidth` 格式正确
- [x] 版本号 1.4.9，`gradlew build` 成功（BUILD SUCCESSFUL）
- [x] 备份 `music pvp 1.4.9+1.8.9.jar` 与 `music pvp 1.4.9+1.8.9-src.zip` 到 `music pvp`
- [x] 未新增任何 Screen/HUD，未改主题/字体/排版（仅设置数据层）
