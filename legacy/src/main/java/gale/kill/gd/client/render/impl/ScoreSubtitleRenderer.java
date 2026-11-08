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
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.DigitalScrollEffect;
import gale.kill.gd.client.render.effect.SubtitleEntranceBackground;
import gale.kill.gd.client.render.effect.TextFadeEffect;
import gale.kill.gd.client.sounds.SoundTriggerManager;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

public class ScoreSubtitleRenderer
implements IHudRenderer {
    private static final long FADE_IN_DURATION = 250L;
    private static final long FADE_OUT_DURATION = 300L;
    private static final long SCALE_ANIMATION_PHASE_DURATION = 100L;
    private static final float SCALE_ANIMATION_MAX_MULTIPLIER = 1.2f;
    private static final float GLOW_OFFSET = 0.3f;
    private static final long BACKGROUND_TRIGGER_COOLDOWN_MS = 100L;
    private static ScoreSubtitleRenderer instance;
    private int configXOffset = 0;
    private int configYOffset = 80;
    private String configScreenAnchor = "bottom_center";
    private long displayDuration = 3500L;
    private String scoreFormat = "<score>";
    private int scoreThreshold = 1000;
    private String highScoreColor = "#D4B800";
    private String flashColor = "#D0D0D0";
    private float scale = 2.0f;
    private float animationDuration = 1.25f;
    private float animationRefreshRate = 0.01f;
    private boolean enableNumberSegmentation = false;
    private boolean enableFlash = true;
    private boolean alignLeft = false;
    private boolean alignRight = false;
    private boolean enableScoreScalingEffect = false;
    private boolean enableDigitalScroll = true;
    private boolean enableGlowEffect = false;
    private float glowIntensity = 0.5f;
    private float glowSize = 0.3f;
    private int glowColorRgb = 0xFFFFFF;
    private float glowAlphaMultiplier = 1.0f;
    private int normalTextColor = -1;
    private boolean enableTextShadow = true;
    private boolean configBlinkFadeAnimation = false;
    private boolean configEntranceBackground = false;
    private long backgroundTriggerTime = -1L;
    private float configEntranceBgPeakTransparency = 0.2f;
    private long configEntranceBgFadeInMs = 66L;
    private long configEntranceBgSweepMs = 100L;
    private long configEntranceBgFadeOutMs = 133L;
    private int configEntranceBgColor = 0xFFFFFF;
    private boolean visible = false;
    private float currentScore = 0.0f;
    private long lastScoreTime = -1L;
    private long startTime = -1L;
    private long textHideTime = -1L;
    private boolean isFadingOut = false;
    private DigitalScrollEffect scrollEffect;
    private long scaleAnimationStartTime = -1L;

    private ScoreSubtitleRenderer() {
    }

    public static synchronized ScoreSubtitleRenderer getInstance() {
        if (instance == null) {
            instance = new ScoreSubtitleRenderer();
        }
        return instance;
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        RenderState state = this.resolveRenderState();
        if (state == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        int centerX = ScreenAnchor.resolveCenterX(this.configScreenAnchor, this.configXOffset, screenWidth);
        int textY = ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
        this.renderInternal(guiGraphics, font, centerX, textY, state);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        RenderState state = this.resolveRenderState();
        if (state == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        int resolvedCenterX = Math.round(centerX);
        int resolvedTextY = Math.round(centerY);
        this.renderInternal(guiGraphics, font, resolvedCenterX, resolvedTextY, state);
    }

    @Override
    public void resetPreview() {
        this.resetState();
    }

    private RenderState resolveRenderState() {
        long scaleElapsed;
        float alpha;
        if (!this.visible || this.startTime == -1L) {
            return null;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        if (!this.isFadingOut && currentTime > this.textHideTime) {
            this.isFadingOut = true;
        }
        if ((alpha = this.calculateAlpha(currentTime)) <= 0.05f || this.textHideTime > 0L && currentTime >= this.textHideTime + 300L) {
            this.visible = false;
            this.resetState();
            return null;
        }
        this.updateAnimatedScore(currentTime);
        float currentScale = this.scale;
        long elapsed = currentTime - this.startTime;
        if (elapsed < 250L) {
            float progress = (float)elapsed / 250.0f;
            float easedProgress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
            currentScale = Mth.lerp(easedProgress, 1.5f, this.scale);
        }
        if (this.enableScoreScalingEffect && this.scaleAnimationStartTime != -1L && (scaleElapsed = currentTime - this.scaleAnimationStartTime) < 200L) {
            float animationScaleMultiplier;
            if (scaleElapsed < 100L) {
                float t = (float)scaleElapsed / 100.0f;
                float progress = 1.0f - (float)Math.pow(1.0f - t, 3.0);
                animationScaleMultiplier = 1.0f + 0.20000005f * progress;
            } else {
                float t = (float)(scaleElapsed - 100L) / 100.0f;
                float progress = 1.0f - (float)Math.pow(1.0f - t, 3.0);
                animationScaleMultiplier = 1.2f - 0.20000005f * progress;
            }
            currentScale *= animationScaleMultiplier;
        }
        return new RenderState(currentTime, currentScale, alpha);
    }

    private void renderInternal(GuiGraphics guiGraphics, Font font, int centerX, int textY, RenderState state) {
        float displayScore = this.enableDigitalScroll ? (this.scrollEffect != null ? this.scrollEffect.getCurrentValue() : 0.0f) : this.currentScore;
        String scoreStr = this.formatNumberWithSegmentation(displayScore, this.enableNumberSegmentation);
        String prefix = "";
        String suffix = "";
        boolean hasPlaceholder = this.scoreFormat.contains("<score>");
        if (hasPlaceholder) {
            int placeholderIndex = this.scoreFormat.indexOf("<score>");
            prefix = this.scoreFormat.substring(0, placeholderIndex);
            suffix = this.scoreFormat.substring(placeholderIndex + 7);
        } else {
            prefix = this.scoreFormat;
        }
        int prefixWidth = font.width(prefix);
        int scoreWidth = hasPlaceholder ? font.width(scoreStr) : 0;
        int suffixWidth = font.width(suffix);
        int totalWidth = prefixWidth + scoreWidth + suffixWidth;
        int defaultColorWithAlpha = this.normalTextColor & 0xFFFFFF | (int)(state.alpha * 255.0f) << 24;
        int scoreColorWithAlpha = this.calculateScoreColor(displayScore, hasPlaceholder, state.alpha, state.currentTime, defaultColorWithAlpha);
        if (this.enableFlash && scoreColorWithAlpha != defaultColorWithAlpha && this.isFlickering(state.currentTime)) {
            defaultColorWithAlpha = scoreColorWithAlpha;
        }
        int textX = this.alignLeft && !this.alignRight ? centerX : (this.alignRight && !this.alignLeft ? centerX - totalWidth : centerX - totalWidth / 2);
        int currentX = textX;
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        float f = textY;
        font.getClass();
        float pivotY = f + 9.0f / 2.0f;
        float pivotX = this.alignLeft && !this.alignRight ? (float)textX : (this.alignRight && !this.alignLeft ? (float)(textX + totalWidth) : (float)textX + (float)totalWidth / 2.0f);
        if (this.configEntranceBackground && this.backgroundTriggerTime >= 0L) {
            long elapsed = state.currentTime - this.backgroundTriggerTime;
            float scale = state.currentScale;
            float textLeftScreen = pivotX + ((float)textX - pivotX) * scale;
            float textRightScreen = pivotX + ((float)(textX + totalWidth) - pivotX) * scale;
            float midYScreen = pivotY;
            font.getClass();
            float textHeightScreen = 9.0f * scale;
            SubtitleEntranceBackground.draw(guiGraphics, elapsed, textLeftScreen, textRightScreen, midYScreen, textHeightScreen, this.configEntranceBgFadeInMs, this.configEntranceBgSweepMs, this.configEntranceBgFadeOutMs, this.configEntranceBgPeakTransparency, this.configEntranceBgColor, false);
        }
        poseStack.translate(pivotX, pivotY, 0.0);
        poseStack.scale(state.currentScale, state.currentScale, 1.0f);
        poseStack.translate(-pivotX, -pivotY, 0.0);
        if (!prefix.isEmpty()) {
            this.drawTextWithGlow(guiGraphics, font, prefix, currentX, textY, defaultColorWithAlpha, this.enableTextShadow);
            currentX += prefixWidth;
        }
        if (hasPlaceholder) {
            this.drawTextWithGlow(guiGraphics, font, scoreStr, currentX, textY, scoreColorWithAlpha, this.enableTextShadow);
            currentX += scoreWidth;
        }
        if (!suffix.isEmpty()) {
            this.drawTextWithGlow(guiGraphics, font, suffix, currentX, textY, defaultColorWithAlpha, this.enableTextShadow);
        }
        poseStack.popPose();
    }

    private void drawTextWithGlow(GuiGraphics guiGraphics, Font font, String text, int x, int y, int color, boolean dropShadow) {
        if (this.enableGlowEffect) {
            float[][] offsets;
            int alpha = color >> 24 & 0xFF;
            int glowAlpha = (int)((float)alpha * this.glowIntensity * this.glowAlphaMultiplier);
            glowAlpha = Math.max(25, Math.min(255, glowAlpha));
            glowAlpha = Math.max(0, Math.min(255, glowAlpha));
            int glowColor = this.glowColorRgb & 0xFFFFFF | glowAlpha << 24;
            PoseStack poseStack = guiGraphics.pose();
            for (float[] offset : offsets = new float[][]{{-this.glowSize, 0.0f}, {this.glowSize, 0.0f}, {0.0f, -this.glowSize}, {0.0f, this.glowSize}, {-this.glowSize, -this.glowSize}, {this.glowSize, -this.glowSize}, {-this.glowSize, this.glowSize}, {this.glowSize, this.glowSize}}) {
                poseStack.pushPose();
                poseStack.translate(offset[0], offset[1], 0.0);
                guiGraphics.drawString(font, text, x, y, glowColor, false);
                poseStack.popPose();
            }
        }
        guiGraphics.drawString(font, text, x, y, color, dropShadow);
    }

    public void addScore(float score) {
        boolean configVisible;
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "score");
        if (config2 == null) {
            return;
        }
        boolean bl = configVisible = !config2.has("visible") || config2.get("visible").getAsBoolean();
        if (!configVisible) {
            this.visible = false;
            return;
        }
        this.loadConfig(config2);
        if (this.displayDuration < 250L) {
            this.displayDuration = 250L;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        boolean shouldReset = false;
        if (this.isFadingOut) {
            shouldReset = true;
        } else if (this.textHideTime > 0L && currentTime > this.textHideTime) {
            shouldReset = true;
        }
        if (shouldReset) {
            this.resetState();
            this.currentScore = score;
        } else {
            this.currentScore += score;
        }
        if (this.scrollEffect != null) {
            this.scrollEffect.startAnimation(this.scrollEffect.getCurrentValue(), this.currentScore);
        }
        this.lastScoreTime = currentTime;
        this.scaleAnimationStartTime = currentTime;
        if (this.backgroundTriggerTime < 0L || currentTime - this.backgroundTriggerTime >= 100L) {
            this.backgroundTriggerTime = currentTime;
        }
        if (this.startTime == -1L) {
            this.startTime = currentTime;
            this.visible = true;
            this.isFadingOut = false;
        }
        this.textHideTime = currentTime + this.displayDuration;
        SoundTriggerManager.playScoreSound();
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 40;
            this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            this.displayDuration = config2.has("display_duration") ? (long)(config2.get("display_duration").getAsFloat() * 1000.0f) : 4000L;
            this.scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 2.0f;
            this.scoreFormat = config2.has("format_score") ? config2.get("format_score").getAsString() : "<score>";
            this.scoreThreshold = config2.has("score_threshold") ? config2.get("score_threshold").getAsInt() : 1000;
            this.highScoreColor = config2.has("color_high_score") ? config2.get("color_high_score").getAsString() : "#D4B800";
            this.flashColor = config2.has("color_flash") ? config2.get("color_flash").getAsString() : "#D0D0D0";
            this.animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 1.25f;
            this.animationRefreshRate = config2.has("animation_refresh_rate") ? config2.get("animation_refresh_rate").getAsFloat() : 0.01f;
            this.enableNumberSegmentation = config2.has("enable_number_segmentation") ? config2.get("enable_number_segmentation").getAsBoolean() : false;
            this.enableFlash = config2.has("enable_flash") ? config2.get("enable_flash").getAsBoolean() : true;
            this.alignLeft = config2.has("align_left") ? config2.get("align_left").getAsBoolean() : false;
            this.alignRight = config2.has("align_right") ? config2.get("align_right").getAsBoolean() : false;
            this.enableScoreScalingEffect = config2.has("enable_score_scaling_effect") ? config2.get("enable_score_scaling_effect").getAsBoolean() : false;
            this.enableDigitalScroll = config2.has("enable_digital_scroll") ? config2.get("enable_digital_scroll").getAsBoolean() : true;
            this.enableGlowEffect = config2.has("enable_glow_effect") && config2.get("enable_glow_effect").getAsBoolean();
            this.glowIntensity = config2.has("glow_intensity") ? config2.get("glow_intensity").getAsFloat() : 0.5f;
            this.glowSize = config2.has("glow_size") ? config2.get("glow_size").getAsFloat() : 0.3f;
            this.glowColorRgb = this.hexColorToInt(config2.has("glow_color") ? config2.get("glow_color").getAsString() : "#FFFFFF");
            this.glowAlphaMultiplier = config2.has("glow_alpha") ? Mth.clamp(config2.get("glow_alpha").getAsFloat(), 0.0f, 1.0f) : 1.0f;
            this.enableTextShadow = !config2.has("enable_text_shadow") || config2.get("enable_text_shadow").getAsBoolean();
            this.configBlinkFadeAnimation = config2.has("blink_fade_animation") && config2.get("blink_fade_animation").getAsBoolean();
            this.configEntranceBackground = config2.has("entrance_background") && config2.get("entrance_background").getAsBoolean();
            this.configEntranceBgPeakTransparency = config2.has("entrance_background_alpha") ? Mth.clamp(config2.get("entrance_background_alpha").getAsFloat(), 0.0f, 1.0f) : 0.2f;
            this.configEntranceBgFadeInMs = config2.has("entrance_background_fade_in") ? Math.max(1L, (long)(config2.get("entrance_background_fade_in").getAsFloat() * 1000.0f)) : 66L;
            this.configEntranceBgSweepMs = config2.has("entrance_background_sweep_duration") ? Math.max(1L, (long)(config2.get("entrance_background_sweep_duration").getAsFloat() * 1000.0f)) : 100L;
            this.configEntranceBgFadeOutMs = config2.has("entrance_background_fade_out") ? Math.max(1L, (long)(config2.get("entrance_background_fade_out").getAsFloat() * 1000.0f)) : 133L;
            this.configEntranceBgColor = config2.has("entrance_background_color") ? SubtitleEntranceBackground.parseColor(config2.get("entrance_background_color").getAsString(), 0xFFFFFF) : 0xFFFFFF;
            this.normalTextColor = this.hexColorToInt(config2.has("color_normal_text") ? config2.get("color_normal_text").getAsString() : "#FFFFFF");
            if (this.scrollEffect == null) {
                this.scrollEffect = new DigitalScrollEffect(this.animationDuration, this.animationRefreshRate, DigitalScrollEffect.Easing.QUINTIC_OUT);
            } else {
                this.scrollEffect.setAnimationDuration(this.animationDuration);
                this.scrollEffect.setAnimationRefreshRate(this.animationRefreshRate);
            }
        }
        catch (Exception e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.subtitle.config_error", new Object[0]);
            this.configXOffset = 0;
            this.configYOffset = 80;
            this.configScreenAnchor = "bottom_center";
            this.displayDuration = 4000L;
            this.scale = 2.0f;
            this.scoreFormat = "<score>";
            this.scoreThreshold = 1000;
            this.highScoreColor = "#D4B800";
            this.flashColor = "#D0D0D0";
            this.animationDuration = 1.25f;
            this.animationRefreshRate = 0.01f;
            this.enableNumberSegmentation = false;
            this.enableFlash = true;
            this.alignLeft = false;
            this.alignRight = false;
            this.enableScoreScalingEffect = false;
            this.normalTextColor = -1;
            if (this.scrollEffect == null) {
                this.scrollEffect = new DigitalScrollEffect(this.animationDuration, this.animationRefreshRate, DigitalScrollEffect.Easing.QUINTIC_OUT);
            }
            this.scrollEffect.setAnimationDuration(this.animationDuration);
            this.scrollEffect.setAnimationRefreshRate(this.animationRefreshRate);
        }
    }

    private float calculateAlpha(long currentTime) {
        if (this.textHideTime > 0L) {
            if (currentTime < this.textHideTime) {
                return 1.0f;
            }
            long fadeElapsed = currentTime - this.textHideTime;
            float fadeProgress = (float)fadeElapsed / 300.0f;
            return TextFadeEffect.fadeAlpha(fadeProgress, this.configBlinkFadeAnimation);
        }
        return 1.0f;
    }

    private void resetState() {
        this.currentScore = 0.0f;
        this.startTime = -1L;
        this.lastScoreTime = -1L;
        this.textHideTime = -1L;
        this.isFadingOut = false;
        this.visible = false;
        this.scrollEffect = null;
        this.backgroundTriggerTime = -1L;
    }

    private int roundToInteger(float value) {
        return Math.round(value);
    }

    private void updateAnimatedScore(long currentTime) {
        if (this.scrollEffect != null) {
            this.scrollEffect.update(currentTime);
        }
    }

    private boolean isFlickering(long currentTime) {
        long timeRemaining;
        if (this.textHideTime > 0L && (timeRemaining = this.textHideTime - currentTime) > 0L && timeRemaining <= 2000L) {
            return (timeRemaining - 1L) / 500L % 2L == 1L;
        }
        return false;
    }

    private int calculateScoreColor(float roundedScore, boolean hasPlaceholder, float alpha, long currentTime, int defaultColorWithAlpha) {
        int scoreColorWithAlpha;
        if (roundedScore >= (float)this.scoreThreshold && hasPlaceholder) {
            int highScoreColorInt = this.hexColorToInt(this.highScoreColor);
            scoreColorWithAlpha = highScoreColorInt & 0xFFFFFF | (int)(alpha * 255.0f) << 24;
        } else {
            scoreColorWithAlpha = defaultColorWithAlpha;
        }
        if (this.enableFlash && this.isFlickering(currentTime)) {
            int flashColorWithAlpha;
            int flashColorInt = this.hexColorToInt(this.flashColor);
            scoreColorWithAlpha = flashColorWithAlpha = flashColorInt & 0xFFFFFF | (int)(alpha * 255.0f) << 24;
        }
        return scoreColorWithAlpha;
    }

    private int hexColorToInt(String hexColor) {
        if (hexColor == null || hexColor.isEmpty()) {
            return -1;
        }
        String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
        try {
            if (hex.length() == 6) {
                return Integer.parseInt(hex, 16) | 0xFF000000;
            }
            if (hex.length() == 8) {
                return (int)Long.parseLong(hex, 16);
            }
            if (hex.length() == 3) {
                int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                return r << 16 | g << 8 | b | 0xFF000000;
            }
        }
        catch (NumberFormatException e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.score_subtitle.invalid_color", hexColor);
        }
        return -1;
    }

    private String formatNumberWithSegmentation(float number, boolean enabled) {
        String formattedNumber;
        boolean isNegative = number < 0.0f;
        float absNumber = Math.abs(number);
        if (absNumber < 1.0f) {
            formattedNumber = String.format("%.1f", Float.valueOf(absNumber));
        } else {
            int roundedNumber = Math.round(absNumber);
            formattedNumber = String.valueOf(roundedNumber);
        }
        if (isNegative) {
            formattedNumber = "-" + formattedNumber;
        }
        if (enabled) {
            String[] parts = formattedNumber.split("\\.");
            String integerPart = parts[0];
            String decimalPart = parts.length > 1 ? parts[1] : "";
            StringBuilder result = new StringBuilder();
            int length = integerPart.length();
            for (int i = 0; i < length; ++i) {
                if (i > 0 && (length - i) % 3 == 0 && integerPart.charAt(i) != '-') {
                    result.append(",");
                }
                result.append(integerPart.charAt(i));
            }
            if (!decimalPart.isEmpty()) {
                result.append(".").append(decimalPart);
            }
            return result.toString();
        }
        return formattedNumber;
    }

    private static final class RenderState {
        private final long currentTime;
        private final float currentScale;
        private final float alpha;

        private RenderState(long currentTime, float currentScale, float alpha) {
            this.currentTime = currentTime;
            this.currentScale = currentScale;
            this.alpha = alpha;
        }
    }
}

