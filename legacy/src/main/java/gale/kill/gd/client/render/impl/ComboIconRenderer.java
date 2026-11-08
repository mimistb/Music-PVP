/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 */
package gale.kill.gd.client.render.impl;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.ElementTextureDefinition;
import gale.kill.gd.client.gui.tabs.PreviewTextureFocusContext;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.IconGlowRenderEffect;
import gale.kill.gd.client.render.effect.IconRingEffect;
import gale.kill.gd.client.textures.ModTextures;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

public class ComboIconRenderer
implements IHudRenderer {
    private static final long DEFAULT_DISPLAY_DURATION = 1500L;
    private static final long ANIMATION_DURATION = 300L;
    private static final float START_SCALE = 2.0f;
    private static final int DEFAULT_HEADSHOT_COLOR = 13940736;
    private static final int DEFAULT_EXPLOSION_COLOR = 0xF77F00;
    private static final int DEFAULT_CRIT_COLOR = 10275941;
    private static volatile long serverComboWindowMs = -1L;
    private float configScale = 1.0f;
    private int configXOffset = 0;
    private int configYOffset = 0;
    private String configScreenAnchor = "bottom_center";
    private long displayDuration = 1500L;
    private boolean enableCritRing = true;
    private boolean enableHeadshotRing = true;
    private boolean enableExplosionRing = true;
    private float ringCritRadius = 42.0f;
    private float ringCritThickness = 1.8f;
    private float ringHeadshotRadius = 42.0f;
    private float ringHeadshotThickness = 3.0f;
    private float ringExplosionRadius = 42.0f;
    private float ringExplosionThickness = 5.4f;
    private boolean configIconGlowEnabled = false;
    private int configIconGlowColor = 0xFFFFFF;
    private float configIconGlowIntensity = 0.45f;
    private float configIconGlowSize = 4.0f;
    private JsonObject currentConfig;
    private long startTime = -1L;
    private boolean isVisible = false;
    private long effectiveDisplayDuration = 1500L;
    private int comboCount = 0;
    private int currentKillType = 0;
    private final IconRingEffect ringEffect = new IconRingEffect();

    public static void updateServerComboWindowSeconds(double seconds) {
        if (seconds <= 0.0) {
            return;
        }
        serverComboWindowMs = Math.max(100L, (long)(seconds * 1000.0));
    }

    public static long getServerComboWindowMs() {
        return serverComboWindowMs;
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        boolean visible;
        this.currentKillType = context.type();
        this.comboCount = context.comboCount();
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "combo");
        if (config2 == null) {
            return;
        }
        boolean bl = visible = !config2.has("visible") || config2.get("visible").getAsBoolean();
        if (!visible) {
            this.isVisible = false;
            return;
        }
        this.loadConfig(config2);
        if (this.displayDuration < 300L) {
            this.displayDuration = 300L;
        }
        this.effectiveDisplayDuration = this.displayDuration + 300L;
        this.startTime = PreviewRenderTimeContext.currentTimeMillis();
        this.isVisible = true;
        this.triggerRingEffect();
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        if (!this.isVisible || this.startTime == -1L) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float centerX = ScreenAnchor.resolveCenterX(this.configScreenAnchor, this.configXOffset, screenWidth);
        float centerY = ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
        this.renderAt(guiGraphics, partialTick, centerX, centerY);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        if (!this.isVisible || this.startTime == -1L) {
            return;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        long elapsed = currentTime - this.startTime;
        if (elapsed > this.effectiveDisplayDuration) {
            this.isVisible = false;
            this.startTime = -1L;
            return;
        }
        float endScale = 1.0f * this.configScale;
        float initialScale = 2.0f * this.configScale;
        float currentScale = endScale;
        float alpha = 1.0f;
        if (elapsed < 300L) {
            float progress = (float)elapsed / 300.0f;
            progress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
            currentScale = Mth.lerp(progress, initialScale, endScale);
            alpha = Mth.clamp(progress, 0.0f, 1.0f);
        }
        if (elapsed > this.displayDuration) {
            long fadeElapsed = elapsed - this.displayDuration;
            float fadeProgress = (float)fadeElapsed / 300.0f;
            float fadeAlpha = 1.0f - fadeProgress;
            alpha = Mth.clamp(alpha * fadeAlpha, 0.0f, 1.0f);
        }
        int displayCombo = Mth.clamp(this.comboCount, 1, 6);
        String textureKey = "combo_" + displayCombo;
        String texturePath = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/combo", textureKey, this.currentConfig);
        float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
        float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
        float drawWidth = 64.0f * frameWidthRatio;
        float drawHeight = 64.0f * frameHeightRatio;
        float focusedAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/combo", textureKey);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
        try {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, centerY, 0.0);
            guiGraphics.pose().scale(currentScale, currentScale, 1.0f);
            guiGraphics.pose().translate(-drawWidth / 2.0f, -drawHeight / 2.0f, 0.0);
            if (this.configIconGlowEnabled) {
                IconGlowRenderEffect.drawGlowFrame(guiGraphics, ModTextures.get(texturePath), 0, 0, (int)drawWidth, (int)drawHeight, 0, 0, (int)drawWidth, (int)drawHeight, (int)drawWidth, (int)drawHeight, focusedAlpha, this.configIconGlowColor, this.configIconGlowIntensity, this.configIconGlowSize);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
            }
            guiGraphics.blit(ModTextures.get(texturePath), 0, 0, 0.0f, 0.0f, (int)drawWidth, (int)drawHeight, (int)drawWidth, (int)drawHeight);
            guiGraphics.pose().popPose();
            this.ringEffect.render(guiGraphics, centerX, centerY, currentTime);
        }
        finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.currentConfig = config2;
            this.configScale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
            this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 0;
            this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            this.displayDuration = this.resolveDisplayDuration(config2);
            boolean defaultRingEnable = !config2.has("enable_icon_effect") || config2.get("enable_icon_effect").getAsBoolean();
            this.enableCritRing = config2.has("enable_ring_effect_crit") ? config2.get("enable_ring_effect_crit").getAsBoolean() : defaultRingEnable;
            this.enableHeadshotRing = config2.has("enable_ring_effect_headshot") ? config2.get("enable_ring_effect_headshot").getAsBoolean() : defaultRingEnable;
            this.enableExplosionRing = config2.has("enable_ring_effect_explosion") ? config2.get("enable_ring_effect_explosion").getAsBoolean() : defaultRingEnable;
            this.ringCritRadius = this.resolveRingFloat(config2, "ring_effect_crit_radius", "ring_effect_normal_radius", 42.0f);
            this.ringCritThickness = this.resolveRingFloat(config2, "ring_effect_crit_thickness", "ring_effect_normal_thickness", 1.8f);
            this.ringHeadshotRadius = config2.has("ring_effect_headshot_radius") ? config2.get("ring_effect_headshot_radius").getAsFloat() : 42.0f;
            this.ringHeadshotThickness = config2.has("ring_effect_headshot_thickness") ? config2.get("ring_effect_headshot_thickness").getAsFloat() : 3.0f;
            this.ringExplosionRadius = config2.has("ring_effect_explosion_radius") ? config2.get("ring_effect_explosion_radius").getAsFloat() : 42.0f;
            this.ringExplosionThickness = config2.has("ring_effect_explosion_thickness") ? config2.get("ring_effect_explosion_thickness").getAsFloat() : 5.4f;
            this.configIconGlowEnabled = IconGlowRenderEffect.isEnabled(config2);
            this.configIconGlowColor = IconGlowRenderEffect.resolveColor(config2);
            this.configIconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
            this.configIconGlowSize = IconGlowRenderEffect.resolveSize(config2);
        }
        catch (Exception e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.combo.config_error", new Object[0]);
            this.currentConfig = null;
            this.configScale = 1.0f;
            this.configXOffset = 0;
            this.configYOffset = 0;
            this.configScreenAnchor = "bottom_center";
            this.displayDuration = this.resolveDisplayDuration(null);
            this.enableCritRing = true;
            this.enableHeadshotRing = true;
            this.enableExplosionRing = true;
            this.ringCritRadius = 42.0f;
            this.ringCritThickness = 1.8f;
            this.ringHeadshotRadius = 42.0f;
            this.ringHeadshotThickness = 3.0f;
            this.ringExplosionRadius = 42.0f;
            this.ringExplosionThickness = 5.4f;
            this.configIconGlowEnabled = false;
            this.configIconGlowColor = 0xFFFFFF;
            this.configIconGlowIntensity = 0.45f;
            this.configIconGlowSize = 4.0f;
        }
    }

    private float resolveFrameRatio(String textureKey, String suffixKey) {
        if (this.currentConfig == null || textureKey == null) {
            return 1.0f;
        }
        String key = "anim_" + textureKey + "_" + suffixKey;
        if (!this.currentConfig.has(key)) {
            return 1.0f;
        }
        float value = this.currentConfig.get(key).getAsFloat();
        return value > 0.0f ? value : 1.0f;
    }

    private long resolveDisplayDuration(JsonObject config2) {
        long serverDuration = serverComboWindowMs;
        if (serverDuration > 0L) {
            return serverDuration;
        }
        if (config2 != null && config2.has("display_duration")) {
            return (long)(config2.get("display_duration").getAsFloat() * 1000.0f);
        }
        return 1500L;
    }

    private void triggerRingEffect() {
        if (this.isRingEnabledForKillType(this.currentKillType)) {
            this.ringEffect.setRingParams(this.ringCritRadius, this.ringCritThickness, this.ringHeadshotRadius, this.ringHeadshotThickness, this.ringExplosionRadius, this.ringExplosionThickness);
            this.ringEffect.trigger(this.startTime, true, this.currentKillType, this.resolveHeadshotEffectRgb(), this.resolveExplosionEffectRgb(), this.resolveCritEffectRgb());
        } else {
            this.ringEffect.trigger(this.startTime, false, this.currentKillType, 0, 0, 0);
        }
    }

    private int resolveHeadshotEffectRgb() {
        return this.resolveEffectRgb("ring_effect_headshot_color", 13940736);
    }

    private int resolveExplosionEffectRgb() {
        return this.resolveEffectRgb("ring_effect_explosion_color", 0xF77F00);
    }

    private int resolveCritEffectRgb() {
        if (this.currentConfig != null) {
            if (this.currentConfig.has("ring_effect_crit_color")) {
                return this.resolveEffectRgb("ring_effect_crit_color", 10275941);
            }
            if (this.currentConfig.has("ring_effect_normal_color")) {
                return this.resolveEffectRgb("ring_effect_normal_color", 10275941);
            }
        }
        return 10275941;
    }

    private int resolveEffectRgb(String key, int defaultValue) {
        if (this.currentConfig == null) {
            return defaultValue;
        }
        String hex = this.currentConfig.has(key) ? this.currentConfig.get(key).getAsString() : null;
        return ComboIconRenderer.parseRgbHexOrDefault(hex, defaultValue);
    }

    private float resolveRingFloat(JsonObject config2, String key, String legacyKey, float defaultValue) {
        if (config2 == null) {
            return defaultValue;
        }
        if (config2.has(key)) {
            return config2.get(key).getAsFloat();
        }
        if (legacyKey != null && config2.has(legacyKey)) {
            return config2.get(legacyKey).getAsFloat();
        }
        return defaultValue;
    }

    private boolean isRingEnabledForKillType(int type) {
        switch (type) {
            case 1: {
                return this.enableHeadshotRing;
            }
            case 2: {
                return this.enableExplosionRing;
            }
            case 3: {
                return this.enableCritRing;
            }
        }
        return false;
    }

    private static int parseRgbHexOrDefault(String hex, int fallbackRgb) {
        if (hex == null || hex.isEmpty()) {
            return fallbackRgb;
        }
        try {
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            return rgb & 0xFFFFFF;
        }
        catch (NumberFormatException e) {
            return fallbackRgb;
        }
    }
}

