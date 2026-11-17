package fpsport.pvp;

import me.sorihiko.dango.utils.render.GlCaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * PVP 增强的 2D HUD 绘制工具（正交投影 + 立即提交）。
 *
 * <p>只在宿主 HUD 阶段使用：{@link #begin()} 保存 GL 状态、切到屏幕坐标系，
 * {@link #end()} 原样恢复。所有形状与文字都假设当前处于 begin/end 之间。
 *
 * <p>状态还原走 {@link GlCaps}，避免 MC 的 GlStateManager 缓存与真实 GL 状态脱节
 * （否则后续手部 / 皮肤 / HUD 会被残留的混合、深度或纹理状态污染）。
 */
public final class PvpHud {

    private static GlCaps.Saved caps;
    private static int width;
    private static int height;

    private PvpHud() {
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static void begin() {
        Minecraft mc = Minecraft.getMinecraft();
        caps = GlCaps.INSTANCE.save();
        ScaledResolution sr = new ScaledResolution(mc);
        width = sr.getScaledWidth();
        height = sr.getScaledHeight();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, width, height, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);

        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void end() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        if (caps != null) {
            GlCaps.INSTANCE.restore(caps);
            caps = null;
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---------------------------------------------------------------- 形状

    public static void rect(float x1, float y1, float x2, float y2, int argb) {
        if (x1 == x2 || y1 == y2) {
            return;
        }
        float a = (argb >>> 24 & 255) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer w = tessellator.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        w.pos(x1, y2, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x2, y1, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    public static void line(float x1, float y1, float x2, float y2, int argb, float lineWidth) {
        float a = (argb >>> 24 & 255) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GL11.glLineWidth(Math.max(1.0F, lineWidth));
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer w = tessellator.getWorldRenderer();
        w.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        w.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
        GL11.glLineWidth(1.0F);
    }

    public static void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        float a = (argb >>> 24 & 255) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer w = tessellator.getWorldRenderer();
        w.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        w.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        w.pos(x3, y3, 0.0D).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    // ---------------------------------------------------------------- 文字

    public static void text(String text, float x, float y, int argb, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        if (shadow) {
            mc.fontRendererObj.drawStringWithShadow(text, x, y, argb);
        } else {
            mc.fontRendererObj.drawString(text, (int) x, (int) y, argb, false);
        }
        GlStateManager.disableTexture2D();
    }

    public static void textScaled(String text, float x, float y, float scale, int argb, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        text(text, 0.0F, 0.0F, argb, shadow);
        GL11.glPopMatrix();
    }

    public static float textWidth(String text, float scale) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        return Minecraft.getMinecraft().fontRendererObj.getStringWidth(text) * scale;
    }
}
