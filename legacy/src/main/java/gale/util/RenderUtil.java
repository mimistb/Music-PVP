package gale.util;

import net.minecraft.client.gui.Gui;

/**
 * 独立化替身：原 Gale 的渲染工具只被 {@code gale.kill.gd.compat.GuiGraphics#fill} 用到。
 */
public final class RenderUtil {

    private RenderUtil() {
    }

    /** 参数顺序与 GuiGraphics#fill 一致：左上 + 右下 + ARGB。 */
    public static void rect(int x, int y, int x2, int y2, int color) {
        Gui.drawRect(x, y, x2, y2, color);
    }
}
