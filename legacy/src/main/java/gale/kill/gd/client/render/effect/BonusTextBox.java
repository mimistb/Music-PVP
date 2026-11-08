/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.compat.BufferBuilder;
import gale.kill.gd.compat.BufferUploader;
import gale.kill.gd.compat.DefaultVertexFormat;
import gale.kill.gd.compat.GameRenderer;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Matrix4f;
import gale.kill.gd.compat.RenderSystem;
import gale.kill.gd.compat.Tesselator;
import gale.kill.gd.compat.VertexFormat;

public final class BonusTextBox {
    private static final float PAD_X = 2.0f;
    private static final float PAD_Y = 1.0f;

    private BonusTextBox() {
    }

    public static void draw(GuiGraphics guiGraphics, float textWidth, float lineHeight, float borderWidth, int rgb, int alpha) {
        if (borderWidth <= 0.0f || alpha <= 0) {
            return;
        }
        float x0 = -2.0f;
        float x1 = textWidth + 2.0f;
        float y0 = -1.0f;
        float y1 = lineHeight + 1.0f;
        float halfThickness = borderWidth * 0.5f;
        int a = Math.max(0, Math.min(255, alpha));
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float leftOuter = x0 - halfThickness;
        float leftInner = x0 + halfThickness;
        float rightInner = x1 - halfThickness;
        float rightOuter = x1 + halfThickness;
        float topOuter = y0 - halfThickness;
        float topInner = y0 + halfThickness;
        float bottomInner = y1 - halfThickness;
        float bottomOuter = y1 + halfThickness;
        BonusTextBox.quad(builder, matrix, leftOuter, topOuter, rightOuter, topInner, r, g, b, a);
        BonusTextBox.quad(builder, matrix, leftOuter, bottomInner, rightOuter, bottomOuter, r, g, b, a);
        BonusTextBox.quad(builder, matrix, leftOuter, topInner, leftInner, bottomInner, r, g, b, a);
        BonusTextBox.quad(builder, matrix, rightInner, topInner, rightOuter, bottomInner, r, g, b, a);
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder builder, Matrix4f matrix, float x1, float y1, float x2, float y2, int r, int g, int b, int a) {
        builder.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y1, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y2, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x1, y2, 0.0f).color(r, g, b, a).endVertex();
    }
}

