/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.render.effect;

import com.google.gson.JsonObject;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;
import net.minecraft.util.ResourceLocation;

public final class IconGlowRenderEffect {
    private static final float[][] OFFSETS = new float[][]{{-1.0f, -1.0f}, {0.0f, -1.0f}, {1.0f, -1.0f}, {-1.0f, 0.0f}, {1.0f, 0.0f}, {-1.0f, 1.0f}, {0.0f, 1.0f}, {1.0f, 1.0f}};

    private IconGlowRenderEffect() {
    }

    public static boolean isEnabled(JsonObject config2) {
        return config2 != null && config2.has("enable_icon_glow") && config2.get("enable_icon_glow").getAsBoolean();
    }

    public static int resolveColor(JsonObject config2) {
        return IconGlowRenderEffect.parseColor(config2, "color_icon_glow", 0xFFFFFF);
    }

    public static float resolveIntensity(JsonObject config2) {
        if (config2 == null || !config2.has("icon_glow_intensity")) {
            return 0.45f;
        }
        return Mth.clamp(config2.get("icon_glow_intensity").getAsFloat(), 0.0f, 1.0f);
    }

    public static float resolveSize(JsonObject config2) {
        if (config2 == null || !config2.has("icon_glow_size")) {
            return 4.0f;
        }
        return Math.max(0.0f, config2.get("icon_glow_size").getAsFloat());
    }

    public static void drawGlowFrame(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height, int u, int v, int frameWidth, int frameHeight, int totalWidth, int totalHeight, float alpha, int rgb, float intensity, float size) {
        if (texture == null || alpha <= 0.001f || intensity <= 0.001f || size <= 0.01f || width <= 0 || height <= 0) {
            return;
        }
        float glowAlpha = Mth.clamp(alpha * intensity, 0.0f, 1.0f);
        float outerSpread = Math.max(0.5f, size);
        float innerSpread = outerSpread * 0.55f;
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(770, 1);
        float r = (float)(rgb >> 16 & 0xFF) / 255.0f;
        float g = (float)(rgb >> 8 & 0xFF) / 255.0f;
        float b = (float)(rgb & 0xFF) / 255.0f;
        for (float[] offset : OFFSETS) {
            RenderSystem.setShaderColor(r, g, b, glowAlpha * 0.16f);
            guiGraphics.blit(texture, Math.round((float)x + offset[0] * outerSpread), Math.round((float)y + offset[1] * outerSpread), width, height, u, v, frameWidth, frameHeight, totalWidth, totalHeight);
        }
        for (float[] offset : OFFSETS) {
            RenderSystem.setShaderColor(r, g, b, glowAlpha * 0.11f);
            guiGraphics.blit(texture, Math.round((float)x + offset[0] * innerSpread), Math.round((float)y + offset[1] * innerSpread), width, height, u, v, frameWidth, frameHeight, totalWidth, totalHeight);
        }
        RenderSystem.setShaderColor(r, g, b, glowAlpha * 0.09f);
        guiGraphics.blit(texture, x, y, width, height, u, v, frameWidth, frameHeight, totalWidth, totalHeight);
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static int parseColor(JsonObject config2, String key, int fallback) {
        if (config2 == null || !config2.has(key)) {
            return fallback;
        }
        String hex = config2.get(key).getAsString();
        if (hex == null || hex.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF;
        }
        catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

