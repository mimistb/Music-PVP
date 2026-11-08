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

public final class IconRingEffect {
    public static final boolean DEFAULT_ENABLE_ICON_EFFECT = true;
    private static final long EFFECT_DELAY_MS = 100L;
    private static final long EFFECT_DURATION_MS = 300L;
    private static final long EXPLOSION_SECOND_RING_DELAY_MS = 100L;
    private static final float BASE_RADIUS_PX = 10.0f;
    private static final float RADIUS_GROWTH_PX = 32.0f;
    private static final float BASE_THICKNESS_PX = 3.0f;
    private float scale = 1.0f;
    private static final int SEGMENTS = 72;
    private static final float EXPLOSION_SECOND_RING_THICKNESS_MULTIPLIER = 1.8f;
    private static final float CRIT_RING_THICKNESS_MULTIPLIER = 0.6f;
    private int killType = 0;
    private long effectStartTimeMs = -1L;
    private int headshotRgb = 0;
    private int explosionRgb = 0;
    private int critRgb = 0;
    private float normalMaxRadius = 42.0f;
    private float normalThickness = 1.8000001f;
    private float headshotMaxRadius = 42.0f;
    private float headshotThickness = 3.0f;
    private float explosionMaxRadius = 42.0f;
    private float explosionThickness = 5.3999996f;

    public void setScale(float scale) {
        this.scale = scale;
    }

    public void setRingParams(float normalMaxRadius, float normalThickness, float headshotMaxRadius, float headshotThickness, float explosionMaxRadius, float explosionThickness) {
        this.normalMaxRadius = normalMaxRadius;
        this.normalThickness = normalThickness;
        this.headshotMaxRadius = headshotMaxRadius;
        this.headshotThickness = headshotThickness;
        this.explosionMaxRadius = explosionMaxRadius;
        this.explosionThickness = explosionThickness;
    }

    public void trigger(long triggerTimeMs, boolean enabled, int killType, int headshotRgb, int explosionRgb, int critRgb) {
        this.killType = killType;
        this.headshotRgb = headshotRgb & 0xFFFFFF;
        this.explosionRgb = explosionRgb & 0xFFFFFF;
        this.critRgb = critRgb & 0xFFFFFF;
        if (!enabled || killType != 1 && killType != 2 && killType != 3) {
            this.effectStartTimeMs = -1L;
            return;
        }
        this.effectStartTimeMs = triggerTimeMs + 100L;
    }

    public void render(GuiGraphics guiGraphics, float centerX, float centerY, long currentTimeMs) {
        long maxEffectDuration;
        if (this.effectStartTimeMs <= 0L) {
            return;
        }
        long effectElapsed = currentTimeMs - this.effectStartTimeMs;
        long l = maxEffectDuration = this.killType == 2 ? 400L : 300L;
        if (effectElapsed < 0L || effectElapsed > maxEffectDuration) {
            return;
        }
        float t = Mth.clamp((float)effectElapsed / 300.0f, 0.0f, 1.0f);
        float eased = 1.0f - (float)Math.pow(1.0f - t, 3.0);
        float effectAlpha = 1.0f - t;
        effectAlpha *= effectAlpha;
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        if (this.killType == 1) {
            float radius = this.resolveRadius(this.headshotMaxRadius, eased);
            float thickness = this.headshotThickness * (1.0f - t) * this.scale;
            IconRingEffect.drawRing(guiGraphics, centerX, centerY, radius, thickness, this.headshotRgb, effectAlpha);
            return;
        }
        if (this.killType == 2) {
            float radius = this.resolveRadius(this.headshotMaxRadius, eased);
            float thickness1 = this.headshotThickness * (1.0f - t) * this.scale;
            IconRingEffect.drawRing(guiGraphics, centerX, centerY, radius, thickness1, this.headshotRgb, effectAlpha);
            long ring2Elapsed = effectElapsed - 100L;
            if (ring2Elapsed < 0L || ring2Elapsed > 300L) {
                return;
            }
            float t2 = (float)ring2Elapsed / 300.0f;
            float eased2 = 1.0f - (float)Math.pow(1.0f - t2, 3.0);
            float radius2 = this.resolveRadius(this.explosionMaxRadius, eased2);
            float alpha2 = 1.0f - t2;
            alpha2 *= alpha2;
            float thickness2 = this.explosionThickness * (1.0f - t2) * this.scale;
            IconRingEffect.drawRing(guiGraphics, centerX, centerY, radius2, thickness2, this.explosionRgb, alpha2);
            return;
        }
        if (this.killType == 3) {
            float radius = this.resolveRadius(this.normalMaxRadius, eased);
            float thickness = this.normalThickness * (1.0f - t) * this.scale;
            IconRingEffect.drawRing(guiGraphics, centerX, centerY, radius, thickness, this.critRgb, effectAlpha);
        }
    }

    private float resolveRadius(float maxRadius, float eased) {
        float baseRatio = 0.23809524f;
        float minRadius = maxRadius * baseRatio;
        return (minRadius + (maxRadius - minRadius) * eased) * this.scale;
    }

    private static void drawRing(GuiGraphics guiGraphics, float centerX, float centerY, float radius, float thickness, int rgb, float alpha) {
        if (thickness <= 0.0f || alpha <= 0.0f || radius <= 0.0f) {
            return;
        }
        float rOuter = radius + thickness * 0.5f;
        float rInner = Math.max(0.0f, radius - thickness * 0.5f);
        int a = Mth.clamp((int)(alpha * 255.0f), 0, 255);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= 72; ++i) {
            float angle = (float)(Math.PI * 2 * (double)i / 72.0);
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            float xo = centerX + cos * rOuter;
            float yo = centerY + sin * rOuter;
            float xi = centerX + cos * rInner;
            float yi = centerY + sin * rInner;
            builder.vertex(matrix, xo, yo, 0.0f).color(red, green, blue, a).endVertex();
            builder.vertex(matrix, xi, yi, 0.0f).color(red, green, blue, a).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }
}

