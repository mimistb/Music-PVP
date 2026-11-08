/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;

public final class SubtitleEntranceBackground {
    private static final float INITIAL_WIDTH = 2.0f;
    private static final float EDGE_GAP = 2.0f;

    private SubtitleEntranceBackground() {
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

    public static void draw(GuiGraphics guiGraphics, long elapsedMs, float textLeft, float textRight, float midY, float textHeight, long fadeInMs, long sweepMs, long fadeOutMs, float peakTransparency, int rgb, boolean mirror) {
        float left;
        float right;
        float width;
        float alpha;
        if (elapsedMs < 0L || fadeInMs <= 0L || sweepMs <= 0L || fadeOutMs <= 0L || elapsedMs >= fadeInMs + sweepMs + fadeOutMs) {
            return;
        }
        float maxWidth = textRight - textLeft + 4.0f;
        float peakAlpha = Mth.clamp(1.0f - peakTransparency, 0.0f, 1.0f);
        if (elapsedMs < fadeInMs) {
            alpha = peakAlpha * ((float)elapsedMs / (float)fadeInMs);
            width = 2.0f;
        } else if (elapsedMs < fadeInMs + sweepMs) {
            float t = (float)(elapsedMs - fadeInMs) / (float)sweepMs;
            float eased = 1.0f - (float)Math.pow(1.0f - Mth.clamp(t, 0.0f, 1.0f), 3.0);
            alpha = peakAlpha;
            width = Mth.lerp(eased, 2.0f, maxWidth);
        } else {
            float p = (float)(elapsedMs - fadeInMs - sweepMs) / (float)fadeOutMs;
            alpha = peakAlpha * (1.0f - Mth.clamp(p, 0.0f, 1.0f));
            width = maxWidth;
        }
        if (alpha <= 0.001f || width <= 0.001f) {
            return;
        }
        if (mirror) {
            right = textRight + 2.0f;
            left = right - width;
        } else {
            left = textLeft - 2.0f;
            right = left + width;
        }
        float top = midY - (textHeight + 2.0f) / 2.0f;
        float bottom = midY + (textHeight + 2.0f) / 2.0f;
        int argb = rgb & 0xFFFFFF | (int)(Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f) << 24;
        int l = Math.round(left);
        int r = Math.round(right);
        int t = Math.round(top);
        int b = Math.round(bottom);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.fill(l, t, r, b, argb);
        RenderSystem.disableBlend();
    }
}

