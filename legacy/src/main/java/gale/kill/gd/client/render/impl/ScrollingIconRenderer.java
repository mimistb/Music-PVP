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
import gale.kill.gd.client.render.effect.IconEntranceBackground;
import gale.kill.gd.client.render.effect.IconGlowRenderEffect;
import gale.kill.gd.client.render.effect.IconRingEffect;
import gale.kill.gd.client.textures.IconTextureAnimationManager;
import gale.kill.gd.client.textures.ModTextures;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.common.killtype.KillTypeDefinition;
import gale.kill.gd.common.killtype.KillTypeRegistry;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

public class ScrollingIconRenderer
implements IHudRenderer {
    private static final ScrollingIconRenderer INSTANCE = new ScrollingIconRenderer();
    private boolean linkageConfigLoaded = false;
    private static final long DEFAULT_DISPLAY_DURATION = 3000L;
    private static final long DEFAULT_ANIMATION_DURATION = 300L;
    private static final long DEFAULT_FADE_OUT_DURATION_MS = 100L;
    private static final long DEFAULT_POSITION_ANIMATION_DURATION = 300L;
    private static final float DEFAULT_START_SCALE = 2.0f;
    private static final int BASE_ICON_SIZE = 64;
    private static final float DEFAULT_ICON_SPACING = 8.0f;
    private static final int DEFAULT_MAX_VISIBLE_ICONS = 7;
    private static final int DEFAULT_MAX_PENDING_ICONS = 30;
    private static final long DEFAULT_DISPLAY_INTERVAL_MS = 100L;
    private static final int DEFAULT_HEADSHOT_COLOR = 13940736;
    private static final int DEFAULT_EXPLOSION_COLOR = 0xF77F00;
    private static final int DEFAULT_CRIT_COLOR = 10275941;
    private float configScale = 1.0f;
    private int configXOffset = 0;
    private int configYOffset = 0;
    private String configScreenAnchor = "bottom_center";
    private int configScrollDirection = 1;
    private boolean configPinNewestIcon = false;
    private boolean configBlinkFadeAnimation = false;
    private boolean configEntranceBackground = false;
    private long configIconDelayMs = 0L;
    private float configEntranceBgSize = 64.0f;
    private long configEntranceBgFadeInMs = 200L;
    private long configEntranceBgFadeOutMs = 200L;
    private int configEntranceBgColor = 0xFFFFFF;
    private int configEntranceBgHeadshotColor = 0xFF5000;
    private float configEntranceBgPeakTransparency = 0.2f;
    private float configEntranceBgBorder = 0.5f;
    private int configEntranceBgBorderColor = 0xFFFFFF;
    private int configEntranceBgHeadshotBorderColor = 16728832;
    private float configEntranceBgBorderAlpha = 0.2f;
    private long displayDuration = 3000L;
    private final Map<String, Boolean> ringEnableFlags = new HashMap<String, Boolean>();
    private long animationDuration = 300L;
    private long fadeOutDurationMs = 100L;
    private long positionAnimationDuration = 300L;
    private float startScale = 2.0f;
    private float iconSpacing = 8.0f;
    private int maxVisibleIcons = 7;
    private long displayIntervalMs = 100L;
    private int maxPendingIcons = 30;
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
    private boolean isVisible = false;
    private final List<ScrollingIcon> activeIcons = new ArrayList<ScrollingIcon>();
    private final List<ScrollingIcon> pendingIcons = new ArrayList<ScrollingIcon>();
    private long lastIconDisplayTime = 0L;
    private boolean hasCustomCenter = false;
    private float lastCustomCenterX = 0.0f;
    private JsonObject currentConfig;

    public static ScrollingIconRenderer getInstance() {
        return INSTANCE;
    }

    private void ensureConfigLoaded() {
        if (!this.linkageConfigLoaded) {
            JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "scrolling");
            if (config2 != null) {
                this.loadConfig(config2);
            }
            this.linkageConfigLoaded = true;
        }
    }

    public boolean hasVisibleIcons() {
        return !this.activeIcons.isEmpty() || !this.pendingIcons.isEmpty();
    }

    public float getIconsAnchorY() {
        this.ensureConfigLoaded();
        Minecraft mc = Minecraft.getMinecraft();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        return ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        boolean visible;
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "scrolling");
        if (config2 == null) {
            return;
        }
        boolean bl = visible = !config2.has("visible") || config2.get("visible").getAsBoolean();
        if (!visible) {
            this.isVisible = false;
            this.activeIcons.clear();
            return;
        }
        this.loadConfig(config2);
        if (this.displayDuration < this.animationDuration) {
            this.displayDuration = this.animationDuration;
        }
        this.isVisible = true;
        ScrollingIcon icon = new ScrollingIcon(context.type(), 0L, this.displayDuration);
        this.pendingIcons.add(icon);
        if (this.pendingIcons.size() > this.maxPendingIcons) {
            this.pendingIcons.remove(0);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        if (!this.isVisible || this.activeIcons.isEmpty() && this.pendingIcons.isEmpty()) {
            this.isVisible = false;
            return;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        float centerX = this.resolveCenterX();
        this.processPendingIcons(currentTime, centerX);
        if (this.activeIcons.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        int centerY = ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
        boolean removedAny = false;
        Iterator<ScrollingIcon> iterator = this.activeIcons.iterator();
        while (iterator.hasNext()) {
            float drawHeight;
            float drawWidth;
            boolean animEnabled;
            ScrollingIcon icon = iterator.next();
            long elapsed = currentTime - icon.startTime;
            long animElapsed = Math.max(0L, elapsed - this.configIconDelayMs);
            this.updatePosition(icon, currentTime);
            float currentScale = this.resolveScale(animElapsed);
            float alpha = this.resolveAlpha(icon, currentTime, animElapsed);
            if (this.shouldRemoveIcon(icon, currentTime, animElapsed)) {
                iterator.remove();
                removedAny = true;
                continue;
            }
            String texturePath = this.getTexturePath(icon.killType);
            String textureKey = this.getTextureKey(icon.killType);
            IconTextureAnimationManager.TextureFrame frame = IconTextureAnimationManager.getTextureFrame(ConfigManager.getCurrentPresetId(), "kill_icon/scrolling", textureKey, texturePath, icon.startTime, this.currentConfig);
            String prefix = "anim_" + textureKey + "_";
            boolean bl = animEnabled = this.currentConfig != null && this.currentConfig.has(prefix + "enable_texture_animation") && this.currentConfig.get(prefix + "enable_texture_animation").getAsBoolean();
            if (animEnabled) {
                float aspectRatio = (float)frame.height / (float)Math.max(1, frame.width);
                drawWidth = 64.0f;
                drawHeight = 64.0f * aspectRatio;
            } else {
                float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
                float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
                drawWidth = 64.0f * frameWidthRatio;
                drawHeight = 64.0f * frameHeightRatio;
            }
            if (this.configEntranceBackground) {
                boolean headshot = icon.killType == 1;
                int rectColor = headshot ? this.configEntranceBgHeadshotColor : this.configEntranceBgColor;
                int borderColor = headshot ? this.configEntranceBgHeadshotBorderColor : this.configEntranceBgBorderColor;
                IconEntranceBackground.drawRect(guiGraphics, elapsed, icon.currentX, centerY, this.configEntranceBgSize, rectColor, this.configEntranceBgFadeInMs, this.configEntranceBgFadeOutMs, this.configEntranceBgPeakTransparency);
                IconEntranceBackground.drawBorder(guiGraphics, elapsed, icon.currentX, centerY, this.configEntranceBgSize, borderColor, this.configEntranceBgFadeInMs, this.configEntranceBgFadeOutMs, this.configEntranceBgBorder, this.configEntranceBgBorderAlpha);
            }
            float focusedAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/scrolling", textureKey);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(icon.currentX, centerY, 0.0);
            guiGraphics.pose().scale(currentScale, currentScale, 1.0f);
            guiGraphics.pose().translate(-drawWidth / 2.0f, -drawHeight / 2.0f, 0.0);
            if (this.configIconGlowEnabled) {
                float glowFactor = this.configBlinkFadeAnimation ? focusedAlpha * focusedAlpha : focusedAlpha;
                IconGlowRenderEffect.drawGlowFrame(guiGraphics, ModTextures.get(texturePath), 0, 0, (int)drawWidth, (int)drawHeight, frame.u, frame.v, frame.width, frame.height, frame.totalWidth, frame.totalHeight, glowFactor, this.configIconGlowColor, this.configIconGlowIntensity, this.configIconGlowSize);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
            }
            guiGraphics.blit(ModTextures.get(texturePath), 0, 0, (int)drawWidth, (int)drawHeight, frame.u, frame.v, frame.width, frame.height, frame.totalWidth, frame.totalHeight);
            guiGraphics.pose().popPose();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            icon.ringEffect.render(guiGraphics, icon.currentX, centerY, currentTime);
        }
        if (removedAny) {
            this.updateAllIconTargetPositions(currentTime, centerX);
        }
        if (this.activeIcons.isEmpty() && this.pendingIcons.isEmpty()) {
            this.isVisible = false;
        }
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float originX, float originY) {
        if (!this.isVisible || this.activeIcons.isEmpty() && this.pendingIcons.isEmpty()) {
            this.isVisible = false;
            return;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        this.syncCustomCenter(originX);
        this.processPendingIcons(currentTime, originX);
        if (this.activeIcons.isEmpty()) {
            return;
        }
        this.updateAllIconTargetPositions(currentTime, originX);
        boolean removedAny = false;
        Iterator<ScrollingIcon> iterator = this.activeIcons.iterator();
        while (iterator.hasNext()) {
            float drawHeight;
            float drawWidth;
            boolean animEnabled;
            ScrollingIcon icon = iterator.next();
            long elapsed = currentTime - icon.startTime;
            long animElapsed = Math.max(0L, elapsed - this.configIconDelayMs);
            this.updatePosition(icon, currentTime);
            float currentScale = this.resolveScale(animElapsed);
            float alpha = this.resolveAlpha(icon, currentTime, animElapsed);
            if (this.shouldRemoveIcon(icon, currentTime, animElapsed)) {
                iterator.remove();
                removedAny = true;
                continue;
            }
            String texturePath = this.getTexturePath(icon.killType);
            String textureKey = this.getTextureKey(icon.killType);
            IconTextureAnimationManager.TextureFrame frame = IconTextureAnimationManager.getTextureFrame(ConfigManager.getCurrentPresetId(), "kill_icon/scrolling", textureKey, texturePath, icon.startTime, this.currentConfig);
            String prefix = "anim_" + textureKey + "_";
            boolean bl = animEnabled = this.currentConfig != null && this.currentConfig.has(prefix + "enable_texture_animation") && this.currentConfig.get(prefix + "enable_texture_animation").getAsBoolean();
            if (animEnabled) {
                float aspectRatio = (float)frame.height / (float)Math.max(1, frame.width);
                drawWidth = 64.0f;
                drawHeight = 64.0f * aspectRatio;
            } else {
                float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
                float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
                drawWidth = 64.0f * frameWidthRatio;
                drawHeight = 64.0f * frameHeightRatio;
            }
            if (this.configEntranceBackground) {
                boolean headshot = icon.killType == 1;
                int rectColor = headshot ? this.configEntranceBgHeadshotColor : this.configEntranceBgColor;
                int borderColor = headshot ? this.configEntranceBgHeadshotBorderColor : this.configEntranceBgBorderColor;
                IconEntranceBackground.drawRect(guiGraphics, elapsed, icon.currentX, originY, this.configEntranceBgSize, rectColor, this.configEntranceBgFadeInMs, this.configEntranceBgFadeOutMs, this.configEntranceBgPeakTransparency);
                IconEntranceBackground.drawBorder(guiGraphics, elapsed, icon.currentX, originY, this.configEntranceBgSize, borderColor, this.configEntranceBgFadeInMs, this.configEntranceBgFadeOutMs, this.configEntranceBgBorder, this.configEntranceBgBorderAlpha);
            }
            float focusedAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/scrolling", textureKey);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(icon.currentX, originY, 0.0);
            guiGraphics.pose().scale(currentScale, currentScale, 1.0f);
            guiGraphics.pose().translate(-drawWidth / 2.0f, -drawHeight / 2.0f, 0.0);
            if (this.configIconGlowEnabled) {
                float glowFactor = this.configBlinkFadeAnimation ? focusedAlpha * focusedAlpha : focusedAlpha;
                IconGlowRenderEffect.drawGlowFrame(guiGraphics, ModTextures.get(texturePath), 0, 0, (int)drawWidth, (int)drawHeight, frame.u, frame.v, frame.width, frame.height, frame.totalWidth, frame.totalHeight, glowFactor, this.configIconGlowColor, this.configIconGlowIntensity, this.configIconGlowSize);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
            }
            guiGraphics.blit(ModTextures.get(texturePath), 0, 0, (int)drawWidth, (int)drawHeight, frame.u, frame.v, frame.width, frame.height, frame.totalWidth, frame.totalHeight);
            guiGraphics.pose().popPose();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            icon.ringEffect.render(guiGraphics, icon.currentX, originY, currentTime);
        }
        if (removedAny) {
            this.updateAllIconTargetPositions(currentTime, originX);
        }
        if (this.activeIcons.isEmpty() && this.pendingIcons.isEmpty()) {
            this.isVisible = false;
        }
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.currentConfig = config2;
            this.configScale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
            this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 0;
            this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            this.configScrollDirection = config2.has("scroll_direction") && "right".equals(config2.get("scroll_direction").getAsString()) ? -1 : 1;
            this.configPinNewestIcon = config2.has("pin_newest_icon") && config2.get("pin_newest_icon").getAsBoolean();
            this.configBlinkFadeAnimation = config2.has("blink_fade_animation") && config2.get("blink_fade_animation").getAsBoolean();
            this.configEntranceBackground = config2.has("entrance_background") && config2.get("entrance_background").getAsBoolean();
            this.configIconDelayMs = config2.has("icon_delay") ? Math.max(0L, (long)(config2.get("icon_delay").getAsFloat() * 1000.0f)) : 0L;
            this.configEntranceBgSize = config2.has("entrance_background_size") ? config2.get("entrance_background_size").getAsFloat() : 64.0f;
            this.configEntranceBgFadeInMs = config2.has("entrance_background_fade_in") ? Math.max(1L, (long)(config2.get("entrance_background_fade_in").getAsFloat() * 1000.0f)) : 200L;
            this.configEntranceBgFadeOutMs = config2.has("entrance_background_fade_out") ? Math.max(1L, (long)(config2.get("entrance_background_fade_out").getAsFloat() * 1000.0f)) : 200L;
            this.configEntranceBgColor = config2.has("entrance_background_color") ? IconEntranceBackground.parseColor(config2.get("entrance_background_color").getAsString(), 0xFFFFFF) : 0xFFFFFF;
            this.configEntranceBgPeakTransparency = config2.has("entrance_background_alpha") ? Mth.clamp(config2.get("entrance_background_alpha").getAsFloat(), 0.0f, 1.0f) : 0.2f;
            this.configEntranceBgBorder = config2.has("entrance_background_border") ? Math.max(0.0f, config2.get("entrance_background_border").getAsFloat()) : 0.5f;
            this.configEntranceBgBorderColor = config2.has("entrance_background_border_color") ? IconEntranceBackground.parseColor(config2.get("entrance_background_border_color").getAsString(), 0xFFFFFF) : 0xFFFFFF;
            this.configEntranceBgBorderAlpha = config2.has("entrance_background_border_alpha") ? Mth.clamp(config2.get("entrance_background_border_alpha").getAsFloat(), 0.0f, 1.0f) : 0.2f;
            this.configEntranceBgHeadshotColor = config2.has("entrance_background_headshot_color") ? IconEntranceBackground.parseColor(config2.get("entrance_background_headshot_color").getAsString(), 0xFF5000) : 0xFF5000;
            this.configEntranceBgHeadshotBorderColor = config2.has("entrance_background_headshot_border_color") ? IconEntranceBackground.parseColor(config2.get("entrance_background_headshot_border_color").getAsString(), 16728832) : 16728832;
            this.displayDuration = config2.has("display_duration") ? (long)(config2.get("display_duration").getAsFloat() * 1000.0f) : 3000L;
            boolean defaultRingEnable = !config2.has("enable_icon_effect") || config2.get("enable_icon_effect").getAsBoolean();
            this.ringEnableFlags.put("enable_ring_effect_crit", config2.has("enable_ring_effect_crit") ? config2.get("enable_ring_effect_crit").getAsBoolean() : defaultRingEnable);
            this.ringEnableFlags.put("enable_ring_effect_headshot", config2.has("enable_ring_effect_headshot") ? config2.get("enable_ring_effect_headshot").getAsBoolean() : defaultRingEnable);
            this.ringEnableFlags.put("enable_ring_effect_explosion", config2.has("enable_ring_effect_explosion") ? config2.get("enable_ring_effect_explosion").getAsBoolean() : defaultRingEnable);
            this.animationDuration = config2.has("animation_duration") ? (long)(config2.get("animation_duration").getAsFloat() * 1000.0f) : 300L;
            this.fadeOutDurationMs = config2.has("fade_out_duration") ? Math.max(1L, (long)(config2.get("fade_out_duration").getAsFloat() * 1000.0f)) : 100L;
            this.positionAnimationDuration = config2.has("position_animation_duration") ? (long)(config2.get("position_animation_duration").getAsFloat() * 1000.0f) : 300L;
            this.startScale = config2.has("start_scale") ? config2.get("start_scale").getAsFloat() : 2.0f;
            this.iconSpacing = config2.has("icon_spacing") ? config2.get("icon_spacing").getAsFloat() : 8.0f;
            this.maxVisibleIcons = config2.has("max_visible_icons") ? config2.get("max_visible_icons").getAsInt() : 7;
            this.displayIntervalMs = config2.has("display_interval_ms") ? (long)config2.get("display_interval_ms").getAsInt() : 100L;
            this.maxPendingIcons = config2.has("max_pending_icons") ? config2.get("max_pending_icons").getAsInt() : 30;
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
            ClientMessageLogger.chatWarn("gd656killicon.client.scrolling.config_error", new Object[0]);
            this.currentConfig = null;
            this.configScale = 1.0f;
            this.configXOffset = 0;
            this.configYOffset = 0;
            this.configScreenAnchor = "bottom_center";
            this.displayDuration = 3000L;
            this.ringEnableFlags.clear();
            this.ringEnableFlags.put("enable_ring_effect_crit", true);
            this.ringEnableFlags.put("enable_ring_effect_headshot", true);
            this.ringEnableFlags.put("enable_ring_effect_explosion", true);
            this.animationDuration = 300L;
            this.positionAnimationDuration = 300L;
            this.startScale = 2.0f;
            this.iconSpacing = 8.0f;
            this.maxVisibleIcons = 7;
            this.displayIntervalMs = 100L;
            this.maxPendingIcons = 30;
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

    private void processPendingIcons(long currentTime, float centerX) {
        while (!this.pendingIcons.isEmpty() && currentTime - this.lastIconDisplayTime >= this.displayIntervalMs) {
            ScrollingIcon nextIcon = this.pendingIcons.remove(0);
            nextIcon.startTime = currentTime;
            this.lastIconDisplayTime = currentTime;
            boolean ringEnabled = this.isRingEnabledForKillType(nextIcon.killType);
            if (ringEnabled) {
                nextIcon.ringEffect.setRingParams(this.ringCritRadius, this.ringCritThickness, this.ringHeadshotRadius, this.ringHeadshotThickness, this.ringExplosionRadius, this.ringExplosionThickness);
                nextIcon.ringEffect.trigger(currentTime, true, nextIcon.killType, this.resolveHeadshotEffectRgb(), this.resolveExplosionEffectRgb(), this.resolveCritEffectRgb());
            } else {
                nextIcon.ringEffect.trigger(currentTime, false, nextIcon.killType, 0, 0, 0);
            }
            this.addIcon(nextIcon, currentTime, centerX);
        }
    }

    private void addIcon(ScrollingIcon icon, long currentTime, float centerX) {
        this.activeIcons.add(icon);
        this.updateAllIconTargetPositions(currentTime, centerX);
        icon.prevX = icon.targetX;
        icon.currentX = icon.targetX;
        icon.positionAnimationStart = currentTime;
    }

    private void updateAllIconTargetPositions(long currentTime, float centerX) {
        ScrollingIcon icon;
        int i;
        if (this.activeIcons.isEmpty()) {
            return;
        }
        int direction = this.configScrollDirection;
        float spacing = this.resolveIconSpacing();
        int size = this.activeIcons.size();
        int visibleStart = Math.max(0, size - this.maxVisibleIcons);
        int visibleCount = size - visibleStart;
        if (this.configPinNewestIcon) {
            ScrollingIcon icon2;
            int i2;
            float newestFixedX = centerX;
            int newestIndex = size - 1;
            for (i2 = 0; i2 < visibleStart; ++i2) {
                icon2 = this.activeIcons.get(i2);
                float overflowX = newestFixedX + (float)(direction * (newestIndex - i2)) * spacing;
                this.updateTarget(icon2, overflowX, currentTime);
                if (icon2.forcedFadeStartTime >= 0L) continue;
                icon2.forcedFadeStartTime = currentTime;
            }
            for (i2 = visibleStart; i2 < size; ++i2) {
                icon2 = this.activeIcons.get(i2);
                float newTargetX = newestFixedX + (float)(direction * (newestIndex - i2)) * spacing;
                this.updateTarget(icon2, newTargetX, currentTime);
            }
            return;
        }
        float rightmostSlotX = centerX + (float)direction * ((float)(visibleCount - 1) / 2.0f) * spacing;
        for (i = 0; i < visibleStart; ++i) {
            icon = this.activeIcons.get(i);
            float overflowX = rightmostSlotX + (float)(direction * (visibleStart - i)) * spacing;
            this.updateTarget(icon, overflowX, currentTime);
            if (icon.forcedFadeStartTime >= 0L) continue;
            icon.forcedFadeStartTime = currentTime;
        }
        for (i = visibleStart; i < size; ++i) {
            icon = this.activeIcons.get(i);
            float position = (float)(i - visibleStart) - (float)(visibleCount - 1) / 2.0f;
            float newTargetX = centerX - (float)direction * position * spacing;
            this.updateTarget(icon, newTargetX, currentTime);
        }
    }

    private void updateTarget(ScrollingIcon icon, float newTargetX, long currentTime) {
        if (Math.abs(icon.targetX - newTargetX) > 0.1f) {
            icon.prevX = icon.currentX;
            icon.targetX = newTargetX;
            icon.positionAnimationStart = currentTime;
        }
    }

    private float resolveCenterX() {
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        return ScreenAnchor.resolveCenterX(this.configScreenAnchor, this.configXOffset, screenWidth);
    }

    private float resolveIconSpacing() {
        float baseSize = 64.0f * this.configScale;
        return this.iconSpacing * baseSize;
    }

    private void updatePosition(ScrollingIcon icon, long currentTime) {
        if (Math.abs(icon.currentX - icon.targetX) <= 0.1f) {
            return;
        }
        long moveElapsed = currentTime - icon.positionAnimationStart;
        float progress = Math.min((float)moveElapsed / (float)this.positionAnimationDuration, 1.0f);
        float easedProgress = 1.0f - (1.0f - progress) * (1.0f - progress);
        icon.currentX = Mth.lerp(easedProgress, icon.prevX, icon.targetX);
    }

    private float resolveScale(long elapsed) {
        float endScale = 1.0f * this.configScale;
        if (elapsed >= this.animationDuration) {
            return endScale;
        }
        float initialScale = this.startScale * this.configScale;
        float progress = (float)elapsed / (float)this.animationDuration;
        progress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
        return Mth.lerp(progress, initialScale, endScale);
    }

    private float resolveAlpha(ScrollingIcon icon, long currentTime, long elapsed) {
        long fadeInDuration = Math.max(1L, this.animationDuration);
        float fadeInProgress = Math.min((float)elapsed / (float)fadeInDuration, 1.0f);
        float easedIn = 1.0f - (float)Math.pow(1.0f - fadeInProgress, 3.0);
        float baseAlpha = Mth.clamp(easedIn, 0.0f, 1.0f);
        if (icon.forcedFadeStartTime >= 0L) {
            long fadeElapsed = currentTime - icon.forcedFadeStartTime;
            float fadeProgress = (float)fadeElapsed / (float)this.fadeOutDurationMs;
            float alpha = this.resolveFadeAlpha(fadeProgress);
            return Mth.clamp(baseAlpha * alpha, 0.0f, 1.0f);
        }
        if (elapsed <= icon.displayDuration) {
            return baseAlpha;
        }
        long fadeElapsed = elapsed - icon.displayDuration;
        float fadeProgress = (float)fadeElapsed / (float)this.fadeOutDurationMs;
        float alpha = this.resolveFadeAlpha(fadeProgress);
        return Mth.clamp(baseAlpha * alpha, 0.0f, 1.0f);
    }

    private float resolveFadeAlpha(float fadeProgress) {
        if (this.configBlinkFadeAnimation) {
            if (fadeProgress < 0.33333334f) {
                return 1.0f - 2.4f * fadeProgress;
            }
            if (fadeProgress < 0.6666667f) {
                return 2.4f * fadeProgress - 0.6f;
            }
            return 3.0f * (1.0f - fadeProgress);
        }
        return 1.0f - fadeProgress;
    }

    private boolean shouldRemoveIcon(ScrollingIcon icon, long currentTime, long elapsed) {
        if (icon.forcedFadeStartTime >= 0L) {
            long fadeElapsed = currentTime - icon.forcedFadeStartTime;
            return fadeElapsed >= this.fadeOutDurationMs;
        }
        return elapsed >= icon.displayDuration + this.fadeOutDurationMs;
    }

    private String getTexturePath(int killType) {
        String textureKey = this.getTextureKey(killType);
        return ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/scrolling", textureKey, this.currentConfig);
    }

    private String getTextureKey(int killType) {
        return KillTypeRegistry.get(killType).textureKey();
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
        return ScrollingIconRenderer.parseRgbHexOrDefault(hex, defaultValue);
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

    private boolean isRingEnabledForKillType(int killType) {
        KillTypeDefinition def = KillTypeRegistry.get(killType);
        if (def == null || def.ringEnableKey() == null) {
            return false;
        }
        return this.ringEnableFlags.getOrDefault(def.ringEnableKey(), false);
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

    private void syncCustomCenter(float centerX) {
        if (!this.hasCustomCenter) {
            this.hasCustomCenter = true;
            this.lastCustomCenterX = centerX;
            return;
        }
        float delta = centerX - this.lastCustomCenterX;
        if (Math.abs(delta) <= 0.1f) {
            return;
        }
        this.lastCustomCenterX = centerX;
        if (this.activeIcons.isEmpty()) {
            return;
        }
        Iterator<ScrollingIcon> iterator = this.activeIcons.iterator();
        while (iterator.hasNext()) {
            ScrollingIcon icon;
            ScrollingIcon scrollingIcon = icon = iterator.next();
            scrollingIcon.prevX = scrollingIcon.prevX + delta;
            scrollingIcon = icon;
            scrollingIcon.currentX = scrollingIcon.currentX + delta;
            scrollingIcon = icon;
            scrollingIcon.targetX = scrollingIcon.targetX + delta;
        }
    }

    private static final class ScrollingIcon {
        private final int killType;
        private long startTime;
        private final long displayDuration;
        private final IconRingEffect ringEffect = new IconRingEffect();
        private float prevX;
        private float currentX;
        private float targetX;
        private long positionAnimationStart;
        private long forcedFadeStartTime = -1L;

        private ScrollingIcon(int killType, long startTime, long displayDuration) {
            this.killType = killType;
            this.startTime = startTime;
            this.displayDuration = displayDuration;
        }
    }
}

