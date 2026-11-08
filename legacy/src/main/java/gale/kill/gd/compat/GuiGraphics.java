/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.client.renderer.GlStateManager
 *  net.minecraft.client.renderer.Tessellator
 *  net.minecraft.client.renderer.WorldRenderer
 *  net.minecraft.client.renderer.vertex.DefaultVertexFormats
 *  net.minecraft.util.ResourceLocation
 *  org.lwjgl.BufferUtils
 *  org.lwjgl.opengl.GL11
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.Component;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.PoseStack;
import gale.util.RenderUtil;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public final class GuiGraphics {
    private final PoseStack pose = new PoseStack();
    private final Deque<int[]> clips = new ArrayDeque<int[]>();
    private final float offsetX;
    private final float offsetY;
    private final float outerScale;

    public GuiGraphics() {
        this(0.0f, 0.0f, 1.0f);
    }

    public GuiGraphics(float x, float y, float scale) {
        this.offsetX = x;
        this.offsetY = y;
        this.outerScale = scale;
        if (GL11.glIsEnabled((int)3089)) {
            IntBuffer b = BufferUtils.createIntBuffer((int)16);
            GL11.glGetInteger((int)3088, (IntBuffer)b);
            this.clips.push(new int[]{b.get(0), b.get(1), b.get(2), b.get(3)});
        }
    }

    public PoseStack pose() {
        return this.pose;
    }

    private void prepareText() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
    }

    public int drawString(Font font, String s, int x, int y, int color, boolean shadow) {
        this.prepareText();
        return Minecraft.getMinecraft().fontRendererObj.drawString(s, (float)x, (float)y, color, shadow);
    }

    public int drawString(Font font, Component s, int x, int y, int color, boolean shadow) {
        this.prepareText();
        return this.drawComponent(s, x, y, color, shadow);
    }

    private int drawComponent(Component c, int x, int y, int color, boolean shadow) {
        int tint = c.getStyle().getColor() == null ? color : color & 0xFF000000 | c.getStyle().getColor().getValue();
        String text = (c.getStyle().isBold() ? "\u00a7l" : "") + c.text;
        Minecraft mc = Minecraft.getMinecraft();
        mc.fontRendererObj.drawString(text, (float)x, (float)y, tint, shadow);
        int end = x + mc.fontRendererObj.getStringWidth(text);
        for (Component child : c.getSiblings()) {
            end = this.drawComponent(child, end, y, color, shadow);
        }
        return end;
    }

    public void fill(int x, int y, int x2, int y2, int color) {
        RenderUtil.rect(x, y, x2, y2, color);
    }

    public void blit(ResourceLocation texture, int x, int y, int w, int h, float u, float v, int regionW, int regionH, int texW, int texH) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.enableTexture2D();
        WorldRenderer out = Tessellator.getInstance().getWorldRenderer();
        out.begin(7, DefaultVertexFormats.POSITION_TEX);
        out.pos((double)x, (double)(y + h), 0.0).tex((double)(u / (float)texW), (double)((v + (float)regionH) / (float)texH)).endVertex();
        out.pos((double)(x + w), (double)(y + h), 0.0).tex((double)((u + (float)regionW) / (float)texW), (double)((v + (float)regionH) / (float)texH)).endVertex();
        out.pos((double)(x + w), (double)y, 0.0).tex((double)((u + (float)regionW) / (float)texW), (double)(v / (float)texH)).endVertex();
        out.pos((double)x, (double)y, 0.0).tex((double)(u / (float)texW), (double)(v / (float)texH)).endVertex();
        Tessellator.getInstance().draw();
    }

    public void blit(ResourceLocation t, int x, int y, float u, float v, int w, int h, int tw, int th) {
        this.blit(t, x, y, w, h, u, v, w, h, tw, th);
    }

    public void blit(ResourceLocation t, int x, int y, int z, float u, float v, int w, int h, int tw, int th) {
        this.blit(t, x, y, w, h, u, v, w, h, tw, th);
    }

    public void enableScissor(int x, int y, int x2, int y2) {
        x = Math.round((float)x * this.outerScale + this.offsetX);
        x2 = Math.round((float)x2 * this.outerScale + this.offsetX);
        y = Math.round((float)y * this.outerScale + this.offsetY);
        y2 = Math.round((float)y2 * this.outerScale + this.offsetY);
        int s = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        int height = Minecraft.getMinecraft().displayHeight;
        int[] clip = new int[]{x * s, height - y2 * s, (x2 - x) * s, (y2 - y) * s};
        if (!this.clips.isEmpty()) {
            int[] old = this.clips.peek();
            int r = Math.min(old[0] + old[2], clip[0] + clip[2]);
            int top = Math.min(old[1] + old[3], clip[1] + clip[3]);
            clip[0] = Math.max(old[0], clip[0]);
            clip[1] = Math.max(old[1], clip[1]);
            clip[2] = Math.max(0, r - clip[0]);
            clip[3] = Math.max(0, top - clip[1]);
        }
        this.clips.push(clip);
        this.apply(clip);
    }

    private void apply(int[] c) {
        GL11.glEnable((int)3089);
        GL11.glScissor((int)c[0], (int)c[1], (int)c[2], (int)c[3]);
    }

    public void disableScissor() {
        if (!this.clips.isEmpty()) {
            this.clips.pop();
        }
        if (this.clips.isEmpty()) {
            GL11.glDisable((int)3089);
        } else {
            this.apply(this.clips.peek());
        }
    }
}

