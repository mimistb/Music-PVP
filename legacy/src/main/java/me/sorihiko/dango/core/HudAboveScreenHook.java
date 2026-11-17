package me.sorihiko.dango.core;

import me.sorihiko.dango.forge.LegacyHudPass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * EntityRenderer.updateCameraAndRender 末尾（currentScreen 已画完）的 HUD 补绘钩子。
 *
 * 只处理原版界面：mod 自己的界面（me.sorihiko.dango.*）本身就画液态玻璃，重复补绘会叠一层。
 * 任何异常都吞掉，渲染钩子绝不能让游戏崩。
 */
public final class HudAboveScreenHook {

    private HudAboveScreenHook() {
    }

    /** ForgeBridge 用来决定本帧是否把 HUD 推迟到补绘阶段（注入失败时永远不推迟） */
    public static boolean shouldDefer() {
        if (!HudAboveScreenTransformer.patched) return false;
        try {
            return needsOverlayAfterScreen(Minecraft.getMinecraft());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 注入点：EntityRenderer.updateCameraAndRender 的每个 RETURN 之前 */
    public static void render() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!needsOverlayAfterScreen(mc)) return;
            // 界面绘制可能改过投影 / 矩阵，补绘前重新进入原版 HUD 的正交空间
            mc.entityRenderer.setupOverlayRendering();
            LegacyHudPass.renderAboveScreen();
        } catch (Throwable t) {
            // 绝不影响游戏渲染；只报第一次，避免刷屏
            if (!loggedError) {
                loggedError = true;
                System.err.println("[Dango Music] HUD-above-screen render failed: " + t);
            }
        }
    }

    private static boolean loggedError = false;

    private static boolean needsOverlayAfterScreen(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.skipRenderWorld) return false;
        GuiScreen screen = mc.currentScreen;
        if (screen == null) return false;
        return !screen.getClass().getName().startsWith("me.sorihiko.dango");
    }
}
