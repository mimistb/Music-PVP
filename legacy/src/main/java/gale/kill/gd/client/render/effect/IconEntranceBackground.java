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
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;
import gale.kill.gd.compat.Tesselator;
import gale.kill.gd.compat.VertexFormat;

public final class IconEntranceBackground {
    private IconEntranceBackground() {
    }

    public static void drawRect(GuiGraphics guiGraphics, long elapsedMs, float centerX, float centerY, float size, int color, long fadeInMs, long fadeOutMs, float peakTransparency) {
        float scale;
        float alpha;
        if (elapsedMs < 0L || fadeInMs <= 0L || fadeOutMs <= 0L || elapsedMs >= fadeInMs + fadeOutMs) {
            return;
        }
        float peakAlpha = Mth.clamp(1.0f - peakTransparency, 0.0f, 1.0f);
        if (elapsedMs < fadeInMs) {
            alpha = peakAlpha * ((float)elapsedMs / (float)fadeInMs);
            scale = 1.0f;
        } else {
            float progress = (float)(elapsedMs - fadeInMs) / (float)fadeOutMs;
            alpha = peakAlpha * (1.0f - progress);
            scale = 1.0f - progress;
        }
        if (alpha <= 0.001f || scale <= 0.001f) {
            return;
        }
        int argb = color & 0xFFFFFF | (int)(Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f) << 24;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerX, centerY, 0.0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.pose().translate(-size / 2.0f, -size / 2.0f, 0.0);
        guiGraphics.fill(0, 0, Math.round(size), Math.round(size), argb);
        guiGraphics.pose().popPose();
    }

    public static void drawBorder(GuiGraphics guiGraphics, long elapsedMs, float centerX, float centerY, float size, int color, long fadeInMs, long fadeOutMs, float borderWidth, float peakTransparency) {
        float twoThirdsZ;
        float outProgress;
        float scale;
        float alpha;
        if (borderWidth <= 0.0f) {
            return;
        }
        if (elapsedMs < 0L || fadeInMs <= 0L || fadeOutMs <= 0L || elapsedMs >= fadeInMs + fadeOutMs) {
            return;
        }
        float peakAlpha = Mth.clamp(1.0f - peakTransparency, 0.0f, 1.0f);
        if (elapsedMs < fadeInMs) {
            alpha = peakAlpha * ((float)elapsedMs / (float)fadeInMs);
            scale = 1.0f;
        } else {
            float progress = (float)(elapsedMs - fadeInMs) / (float)fadeOutMs;
            alpha = peakAlpha * (1.0f - progress);
            scale = 1.0f - progress;
        }
        if (alpha <= 0.001f || scale <= 0.001f) {
            return;
        }
        float half = size * scale / 2.0f;
        float maxDiag = half * 1.4142135f;
        float quarterX = (float)fadeInMs / 4.0f;
        float diagLen = (float)elapsedMs < quarterX ? maxDiag * (1.0f - (float)elapsedMs / quarterX) : (elapsedMs < fadeInMs ? maxDiag * (((float)elapsedMs - quarterX) / ((float)fadeInMs - quarterX)) : ((outProgress = (float)(elapsedMs - fadeInMs)) < (twoThirdsZ = (float)fadeOutMs * 2.0f / 3.0f) ? maxDiag * (1.0f - outProgress / twoThirdsZ) : 0.0f));
        IconEntranceBackground.drawBorderQuads(guiGraphics, centerX, centerY, half, color, borderWidth, alpha, diagLen);
    }

    private static void drawBorderQuads(GuiGraphics guiGraphics, float centerX, float centerY, float half, int color, float borderWidth, float alpha, float diagLen) {
        float x1 = centerX - half;
        float x2 = centerX + half;
        float y1 = centerY - half;
        float y2 = centerY + half;
        float halfThickness = borderWidth * 0.5f;
        int a = (int)(Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f);
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        float leftOuter = x1 - halfThickness;
        float leftInner = x1 + halfThickness;
        float rightInner = x2 - halfThickness;
        float rightOuter = x2 + halfThickness;
        float topOuter = y1 - halfThickness;
        float topInner = y1 + halfThickness;
        float bottomInner = y2 - halfThickness;
        float bottomOuter = y2 + halfThickness;
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        IconEntranceBackground.quad(builder, matrix, leftOuter, topOuter, rightOuter, topInner, r, g, b, a);
        IconEntranceBackground.quad(builder, matrix, leftOuter, bottomInner, rightOuter, bottomOuter, r, g, b, a);
        IconEntranceBackground.quad(builder, matrix, leftOuter, topInner, leftInner, bottomInner, r, g, b, a);
        IconEntranceBackground.quad(builder, matrix, rightInner, topInner, rightOuter, bottomInner, r, g, b, a);
        float rayProj = diagLen * 0.70710677f;
        IconEntranceBackground.lineQuad(builder, matrix, centerX, centerY, centerX - rayProj, centerY - rayProj, halfThickness, r, g, b, a);
        IconEntranceBackground.lineQuad(builder, matrix, centerX, centerY, centerX + rayProj, centerY - rayProj, halfThickness, r, g, b, a);
        IconEntranceBackground.lineQuad(builder, matrix, centerX, centerY, centerX + rayProj, centerY + rayProj, halfThickness, r, g, b, a);
        IconEntranceBackground.lineQuad(builder, matrix, centerX, centerY, centerX - rayProj, centerY + rayProj, halfThickness, r, g, b, a);
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void lineQuad(BufferBuilder builder, Matrix4f matrix, float ax, float ay, float bx, float by, float halfThickness, int r, int g, int b, int a) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float)Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4f) {
            return;
        }
        float nx = -dy / len * halfThickness;
        float ny = dx / len * halfThickness;
        builder.vertex(matrix, ax + nx, ay + ny, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, ax - nx, ay - ny, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, bx - nx, by - ny, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, bx + nx, by + ny, 0.0f).color(r, g, b, a).endVertex();
    }

    private static void quad(BufferBuilder builder, Matrix4f matrix, float x1, float y1, float x2, float y2, int r, int g, int b, int a) {
        builder.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y1, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y2, 0.0f).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x1, y2, 0.0f).color(r, g, b, a).endVertex();
    }

    public static int parseColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String value = hex.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }
}

