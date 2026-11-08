/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.entity.Entity
 *  net.minecraft.item.ItemStack
 */
package gale.kill.gd.client.render.impl;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.FormatDefaultsManager;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.SubtitleEntranceBackground;
import gale.kill.gd.client.render.effect.TextFadeEffect;
import gale.kill.gd.client.render.impl.ScrollingIconRenderer;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.client.util.I18nCompat;
import gale.kill.gd.common.killtype.KillTypeDefinition;
import gale.kill.gd.common.killtype.KillTypeRegistry;
import gale.kill.gd.compat.Component;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.MutableComponent;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.Style;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;

public class SubtitleRenderer
implements IHudRenderer {
    private static final SubtitleRenderer INSTANCE = new SubtitleRenderer();
    private static final long FADE_IN_DURATION = 200L;
    private static final long FADE_OUT_DURATION = 300L;
    private static final int DEFAULT_PLACEHOLDER_COLOR = -16741493;
    private static final int DEFAULT_EMPHASIS_COLOR = -1;
    private static final int PREVIEW_SCORE_VICTIM_ID = -9999;
    private static final String RUSH_BOMB_PLANTED_CAPTURE_FORMAT = "format_rush_bomb_planted_capture";
    private static final String RUSH_BOMB_DEFUSED_CAPTURE_FORMAT = "format_rush_bomb_defused_capture";
    private static final String RUSH_OBJECTIVE_DESTROYED_CAPTURE_FORMAT = "format_rush_objective_destroyed_capture";
    private int configXOffset = 0;
    private int configYOffset = 20;
    private String configScreenAnchor = "bottom_center";
    private long displayDuration = 3000L;
    private String format = "";
    private int placeholderColor = -16741493;
    private boolean enablePlaceholderBold = false;
    private float scale = 1.0f;
    private int emphasisColor = -1;
    private final Map<String, Boolean> killTypeEnableFlags = new HashMap<String, Boolean>();
    private boolean enableStacking = false;
    private boolean enableTextShadow = true;
    private boolean configBlinkFadeAnimation = false;
    private boolean enableGlowEffect = false;
    private float glowIntensity = 0.5f;
    private float glowSize = 0.3f;
    private int glowColorRgb = 0xFFFFFF;
    private int glowColorRescueRgb = 6541384;
    private float glowAlphaMultiplier = 1.0f;
    private boolean alignLeft = false;
    private boolean alignRight = false;
    private boolean configEnableFlashIn = true;
    private long fadeInDurationMs = 0L;
    private boolean enableQueueLinkage = false;
    private float queueLinkageScrollSpeed = 0.1f;
    private float queueLinkageIconYOffset = 0.0f;
    private boolean linkageConfigLoaded = false;
    private float linkedYOffset = 0.0f;
    private float linkageFromOffset = 0.0f;
    private long linkageStartTime = 0L;
    private float linkageTargetOffset = 0.0f;
    private float linkageBaseY = 0.0f;
    private boolean configEntranceBackground = false;
    private float configEntranceBgPeakTransparency = 0.2f;
    private long configEntranceBgFadeInMs = 66L;
    private long configEntranceBgSweepMs = 100L;
    private long configEntranceBgFadeOutMs = 133L;
    private int configEntranceBgColor = 0xFFFFFF;
    private int maxLines = 5;
    private int lineSpacing = 12;
    private int normalTextColor = -1;
    private long startTime = -1L;
    private boolean isVisible = false;
    private boolean isPreview = false;
    private long textHideTime = -1L;
    private int currentKillType = 0;
    private int victimId = -1;
    private int currentVictimId = -1;
    private String currentScoreOverride = null;
    private String victimName = "";
    private ItemStack heldItem = null;
    private String currentWeaponName = "";
    private String rawFormat = "";
    private float currentDistance = 0.0f;
    private final List<SubtitleItem> stackedItems = new ArrayList<SubtitleItem>();
    private final Deque<SubtitleItem> pendingQueue = new ArrayDeque<SubtitleItem>();
    private long lastDequeueTime = 0L;
    private boolean enableScaleAnimation = false;

    public static SubtitleRenderer getInstance() {
        return INSTANCE;
    }

    public boolean hasVisibleSubtitle() {
        return this.isVisible;
    }

    public float getBaseTextY() {
        if (!this.linkageConfigLoaded) {
            JsonObject cfg = ConfigManager.getElementConfig("subtitle", "kill_feed");
            if (cfg != null) {
                this.loadConfig(cfg);
            }
            this.linkageConfigLoaded = true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        return ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        float dist;
        String resolvedFormat;
        String wName;
        String vName;
        String bonusScoreStr;
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "kill_feed");
        if (config2 == null) {
            return;
        }
        this.isPreview = false;
        this.loadConfig(config2);
        if (!config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            this.isVisible = false;
            return;
        }
        if (!this.isKillTypeEnabled(context.type())) {
            return;
        }
        int type = context.type();
        int entityId = context.entityId();
        Minecraft mc = Minecraft.getMinecraft();
        String rawExtra = context.extraData() == null ? "" : context.extraData();
        String captureWeaponToken = "";
        String captureFormatOverride = null;
        String captureScoreOverride = null;
        String string = bonusScoreStr = context.bonusScale() > 0.0f ? SubtitleRenderer.formatScore(context.bonusScale() * context.bonusMultiplier()) : null;
        if (!rawExtra.isEmpty()) {
            String[] parts;
            String extra = rawExtra;
            if (type == 6) {
                String captureDisplayTarget;
                parts = extra.split("\\|", 3);
                captureWeaponToken = parts.length > 0 ? parts[0].trim() : "";
                String captureTarget = parts.length > 1 ? parts[1].trim() : "";
                captureFormatOverride = SubtitleRenderer.resolveRushCaptureFormat(captureWeaponToken);
                if (captureFormatOverride != null) {
                    captureScoreOverride = SubtitleRenderer.isNumericToken(captureTarget) ? captureTarget : null;
                    captureWeaponToken = "";
                }
                String string2 = captureDisplayTarget = parts.length > 2 ? parts[2].trim() : captureTarget;
                vName = !captureDisplayTarget.isEmpty() && !SubtitleRenderer.isNumericToken(captureDisplayTarget) ? captureDisplayTarget : (!captureTarget.isEmpty() && !SubtitleRenderer.isNumericToken(captureTarget) ? captureTarget : (!captureWeaponToken.isEmpty() ? captureWeaponToken : I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0])));
            } else if (type == 5) {
                if (extra.contains("|")) {
                    parts = extra.split("\\|", 2);
                    vName = parts[0];
                } else {
                    vName = extra;
                }
            } else {
                vName = extra;
            }
        } else {
            Entity entity;
            vName = mc.theWorld != null && entityId != -1 ? ((entity = mc.theWorld.getEntityByID(entityId)) != null ? entity.getDisplayName().getUnformattedText() : I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0])) : I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0]);
        }
        ItemStack itemStack = null;
        if (type == 6) {
            itemStack = null;
            wName = captureWeaponToken == null || captureWeaponToken.isEmpty() ? I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0]) : (I18nCompat.exists(captureWeaponToken) ? I18n.format((String)captureWeaponToken, (Object[])new Object[0]) : captureWeaponToken);
        } else if (mc.thePlayer != null) {
            if (mc.thePlayer.ridingEntity != null) {
                itemStack = null;
                wName = mc.thePlayer.ridingEntity.getDisplayName().getUnformattedText();
            } else {
                itemStack = mc.thePlayer.getHeldItem();
                wName = itemStack == null ? I18n.format((String)"gd656killicon.client.text.bare_hand", (Object[])new Object[0]) : itemStack.getDisplayName();
            }
        } else {
            itemStack = null;
            wName = "Unknown";
        }
        String formatKey = SubtitleRenderer.formatKeyForType(type);
        if (SubtitleRenderer.isKillFeedFormatEmpty(config2, formatKey)) {
            return;
        }
        String colorKey = SubtitleRenderer.placeholderColorKeyForType(type);
        String emphasisColorKey = SubtitleRenderer.emphasisColorKeyForType(type);
        String normalFormat = config2.has("format_normal") ? config2.get("format_normal").getAsString() : FormatDefaultsManager.getDefault("subtitle/kill_feed", KillTypeRegistry.get(type).formatKey());
        String string3 = resolvedFormat = config2.has(formatKey) ? config2.get(formatKey).getAsString() : normalFormat;
        if (captureFormatOverride != null && config2.has(captureFormatOverride)) {
            resolvedFormat = config2.get(captureFormatOverride).getAsString();
        }
        String normalColorHex = config2.has("color_normal_placeholder") ? config2.get("color_normal_placeholder").getAsString() : "#008B8B";
        String chosenColorHex = config2.has(colorKey) ? config2.get(colorKey).getAsString() : normalColorHex;
        int pColor = SubtitleRenderer.parseColorHexOrDefault(chosenColorHex, -16741493);
        String emphasisHex = config2.has(emphasisColorKey) ? config2.get(emphasisColorKey).getAsString() : "#FFFFFF";
        int eColor = SubtitleRenderer.parseColorHexOrDefault(emphasisHex, -1);
        float f = dist = this.isNormalKillType(type) ? context.distance() : 0.0f;
        if (this.enableStacking) {
            this.addItemToStack(resolvedFormat, pColor, eColor, wName, vName, this.displayDuration, dist, entityId, type == 10 ? bonusScoreStr : (captureScoreOverride != null ? captureScoreOverride : bonusScoreStr), type);
        } else {
            this.currentKillType = type;
            this.victimId = entityId;
            this.currentVictimId = entityId;
            this.currentScoreOverride = type == 10 ? bonusScoreStr : (captureScoreOverride != null ? captureScoreOverride : bonusScoreStr);
            this.victimName = vName;
            this.heldItem = itemStack;
            this.currentWeaponName = wName;
            this.format = resolvedFormat;
            this.placeholderColor = pColor;
            this.emphasisColor = eColor;
            this.currentDistance = dist;
            if (this.displayDuration < 200L) {
                this.displayDuration = 200L;
            }
            this.startTime = PreviewRenderTimeContext.currentTimeMillis();
            this.textHideTime = this.startTime + this.displayDuration;
            this.isVisible = true;
        }
    }

    public void triggerPreview(int killType, String weaponName, String victimName) {
        float dist;
        this.currentKillType = killType;
        this.victimId = -9999;
        this.currentVictimId = -9999;
        this.isPreview = true;
        this.victimName = victimName != null ? victimName : "";
        this.heldItem = null;
        this.currentWeaponName = weaponName != null ? weaponName : "Unknown";
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "kill_feed");
        if (config2 == null) {
            return;
        }
        this.loadConfig(config2);
        if (!config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            this.isVisible = false;
            return;
        }
        if (!this.isKillTypeEnabled(killType)) {
            return;
        }
        String formatKey = SubtitleRenderer.formatKeyForType(killType);
        if (SubtitleRenderer.isKillFeedFormatEmpty(config2, formatKey)) {
            return;
        }
        String colorKey = SubtitleRenderer.placeholderColorKeyForType(killType);
        String emphasisColorKey = SubtitleRenderer.emphasisColorKeyForType(killType);
        String normalFormat = config2.has("format_normal") ? config2.get("format_normal").getAsString() : FormatDefaultsManager.getDefault("subtitle/kill_feed", KillTypeRegistry.get(killType).formatKey());
        String resolvedFormat = config2.has(formatKey) ? config2.get(formatKey).getAsString() : normalFormat;
        String normalColorHex = config2.has("color_normal_placeholder") ? config2.get("color_normal_placeholder").getAsString() : "#008B8B";
        String chosenColorHex = config2.has(colorKey) ? config2.get(colorKey).getAsString() : normalColorHex;
        int pColor = SubtitleRenderer.parseColorHexOrDefault(chosenColorHex, -16741493);
        String emphasisHex = config2.has(emphasisColorKey) ? config2.get(emphasisColorKey).getAsString() : "#FFFFFF";
        int eColor = SubtitleRenderer.parseColorHexOrDefault(emphasisHex, -1);
        float f = dist = this.isNormalKillType(killType) ? 50.0f : 0.0f;
        if (this.enableStacking) {
            this.addItemToStack(resolvedFormat, pColor, eColor, this.currentWeaponName, this.victimName, this.displayDuration, dist, -9999, "20", killType);
        } else {
            this.format = resolvedFormat;
            this.placeholderColor = pColor;
            this.emphasisColor = eColor;
            this.currentDistance = dist;
            if (this.displayDuration < 200L) {
                this.displayDuration = 200L;
            }
            this.startTime = PreviewRenderTimeContext.currentTimeMillis();
            this.textHideTime = this.startTime + this.displayDuration;
            this.isVisible = true;
        }
    }

    private void addItemToStack(String format, int pColor, int eColor, String wName, String vName, long duration, float distance, int victimId, String scoreOverride, int killType) {
        SubtitleItem newItem = new SubtitleItem(format, pColor, eColor, wName, vName, 0L, duration, distance, victimId, scoreOverride, killType);
        if (this.pendingQueue.size() >= 10) {
            return;
        }
        this.pendingQueue.add(newItem);
        this.isVisible = true;
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        float baseTextY;
        if (!this.isVisible) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        int centerX = ScreenAnchor.resolveCenterX(this.configScreenAnchor, this.configXOffset, screenWidth);
        float textY = baseTextY = (float)ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
        if (!this.linkageConfigLoaded) {
            JsonObject cfg = ConfigManager.getElementConfig("subtitle", "kill_feed");
            if (cfg != null) {
                this.loadConfig(cfg);
            }
            this.linkageConfigLoaded = true;
        }
        if (this.enableQueueLinkage) {
            ScrollingIconRenderer iconRenderer = ScrollingIconRenderer.getInstance();
            boolean iconsVisible = iconRenderer.hasVisibleIcons();
            float iconY = iconRenderer.getIconsAnchorY();
            float targetY = iconsVisible ? baseTextY : iconY + this.queueLinkageIconYOffset;
            textY = this.smoothLinkedY(baseTextY, targetY);
        } else {
            this.linkageStartTime = 0L;
            this.linkedYOffset = 0.0f;
        }
        if (this.enableStacking) {
            this.renderStacked(guiGraphics, font, centerX, textY);
        } else {
            RenderState state = this.resolveRenderState();
            if (state == null) {
                return;
            }
            this.renderInternal(guiGraphics, font, centerX, textY, state, this.format, this.placeholderColor, this.emphasisColor, this.currentWeaponName, this.victimName, this.currentDistance, this.currentVictimId, this.currentScoreOverride, this.currentKillType);
        }
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        if (!this.isVisible) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        int resolvedCenterX = Math.round(centerX);
        int resolvedTextY = Math.round(centerY);
        if (this.enableStacking) {
            this.renderStacked(guiGraphics, font, resolvedCenterX, resolvedTextY);
        } else {
            RenderState state = this.resolveRenderState();
            if (state == null) {
                return;
            }
            this.renderInternal(guiGraphics, font, resolvedCenterX, resolvedTextY, state, this.format, this.placeholderColor, this.emphasisColor, this.currentWeaponName, this.victimName, this.currentDistance, this.currentVictimId, this.currentScoreOverride, this.currentKillType);
        }
    }

    private float smoothLinkedY(float baseY, float targetY) {
        float targetOffset = targetY - baseY;
        long now = System.currentTimeMillis();
        long durationMs = Math.max(1L, (long)(this.queueLinkageScrollSpeed * 1000.0f));
        if (this.linkageStartTime != 0L && this.linkageTargetOffset == targetOffset && this.linkageBaseY == baseY) {
            float progress = (float)(now - this.linkageStartTime) / (float)durationMs;
            progress = Math.min(1.0f, Math.max(0.0f, progress));
            float eased = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
            this.linkedYOffset = this.linkageFromOffset + (targetOffset - this.linkageFromOffset) * eased;
            if (progress >= 1.0f) {
                this.linkageFromOffset = targetOffset;
            }
            return baseY + this.linkedYOffset;
        }
        this.linkageFromOffset = this.linkedYOffset;
        this.linkageStartTime = now;
        this.linkageTargetOffset = targetOffset;
        this.linkageBaseY = baseY;
        if (Math.abs(targetOffset - this.linkedYOffset) < 0.001f) {
            this.linkageFromOffset = targetOffset;
            this.linkedYOffset = targetOffset;
        }
        return baseY + this.linkedYOffset;
    }

    private void renderStacked(GuiGraphics guiGraphics, Font font, int centerX, float startY) {
        SubtitleItem newItem;
        long now = PreviewRenderTimeContext.currentTimeMillis();
        if (!this.pendingQueue.isEmpty() && now - this.lastDequeueTime >= 200L && (newItem = this.pendingQueue.poll()) != null) {
            newItem.spawnTime = now;
            this.stackedItems.add(newItem);
            while (this.stackedItems.size() > this.maxLines) {
                this.stackedItems.remove(0);
            }
            this.lastDequeueTime = now;
        }
        if (this.stackedItems.isEmpty()) {
            this.isVisible = false;
            return;
        }
        boolean hasVisibleItems = false;
        Iterator<SubtitleItem> iterator = this.stackedItems.iterator();
        while (iterator.hasNext()) {
            SubtitleItem item = iterator.next();
            long hideTime = item.spawnTime + item.duration;
            if (now >= hideTime + 300L) {
                iterator.remove();
                continue;
            }
            hasVisibleItems = true;
        }
        if (!hasVisibleItems && this.stackedItems.isEmpty()) {
            this.isVisible = false;
            return;
        }
        this.renderStackItems(guiGraphics, font, centerX, startY);
    }

    private void renderStackItems(GuiGraphics guiGraphics, Font font, int centerX, float startY) {
        long now = PreviewRenderTimeContext.currentTimeMillis();
        for (int i = 0; i < this.stackedItems.size(); ++i) {
            long elapsed;
            SubtitleItem item = this.stackedItems.get(i);
            int posFromBottom = this.stackedItems.size() - 1 - i;
            float targetRelY = -(posFromBottom * this.lineSpacing);
            float smooth = 0.2f;
            item.currentRelY = Mth.lerp(smooth, item.currentRelY, targetRelY);
            if (Math.abs(item.currentRelY - targetRelY) < 0.5f) {
                item.currentRelY = targetRelY;
            }
            float itemAlpha = 1.0f;
            long hideTime = item.spawnTime + item.duration;
            if (now >= hideTime) {
                long fadeElapsed = now - hideTime;
                itemAlpha = Math.max(0.0f, 1.0f - (float)fadeElapsed / 300.0f);
            }
            if (i == this.stackedItems.size() - 1 && (elapsed = now - item.spawnTime) < 200L) {
                float fadeIn = (float)elapsed / 200.0f;
                itemAlpha = Math.min(itemAlpha, fadeIn);
            }
            if (this.maxLines > 1) {
                float posAlpha = Math.max(0.0f, 1.0f - (float)posFromBottom / (float)(this.maxLines - 1));
                itemAlpha *= posAlpha;
            }
            if (itemAlpha <= 0.05f) continue;
            float drawY = startY + item.currentRelY;
            RenderState state = new RenderState(now - item.spawnTime, itemAlpha, this.scale);
            this.renderInternal(guiGraphics, font, centerX, drawY, state, item.format, item.pColor, item.eColor, item.wName, item.vName, item.distance, item.victimId, item.scoreOverride, item.killType);
        }
    }

    private RenderState resolveRenderState() {
        boolean fadingIn;
        if (!this.isVisible || this.startTime == -1L) {
            return null;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        long elapsed = currentTime - this.startTime;
        float alpha = this.calculateAlpha(currentTime);
        boolean bl = fadingIn = this.startTime >= 0L && this.fadeInDurationMs > 0L && currentTime - this.startTime < this.fadeInDurationMs;
        if (alpha <= 0.05f && !fadingIn || this.textHideTime > 0L && currentTime >= this.textHideTime + 300L) {
            this.isVisible = false;
            this.startTime = -1L;
            return null;
        }
        float currentScale = this.scale;
        return new RenderState(elapsed, alpha, currentScale);
    }

    private void renderInternal(GuiGraphics guiGraphics, Font font, int centerX, float textY, RenderState state, String fmt, int pColor, int eColor, String wName, String vName, float distance, int victimId, String scoreOverride, int killType) {
        float textYFrac = textY - (float)Math.floor(textY);
        int textYInt = (int)Math.floor(textY);
        float colorProgress = this.configEnableFlashIn ? this.getColorProgress(state.elapsed) : 1.0f;
        String scoreStr = scoreOverride == null || scoreOverride.trim().isEmpty() ? "" : scoreOverride;
        Component fullText = this.buildFullText(fmt, pColor, eColor, wName, vName, scoreStr, colorProgress, distance);
        int textWidth = font.width(fullText);
        int textX = this.alignLeft && !this.alignRight ? centerX : (this.alignRight && !this.alignLeft ? centerX - textWidth : centerX - textWidth / 2);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        float pivotX = this.alignLeft && !this.alignRight ? (float)textX : (this.alignRight && !this.alignLeft ? (float)(textX + textWidth) : (float)textX + (float)textWidth / 2.0f);
        font.getClass();
        float pivotY = textY + 9.0f / 2.0f;
        if (this.configEntranceBackground) {
            float scale = state.currentScale;
            float textLeftScreen = pivotX + ((float)textX - pivotX) * scale;
            float textRightScreen = pivotX + ((float)(textX + textWidth) - pivotX) * scale;
            float midYScreen = pivotY;
            font.getClass();
            float textHeightScreen = 9.0f * scale;
            boolean mirror = this.alignRight && !this.alignLeft;
            SubtitleEntranceBackground.draw(guiGraphics, state.elapsed, textLeftScreen, textRightScreen, midYScreen, textHeightScreen, this.configEntranceBgFadeInMs, this.configEntranceBgSweepMs, this.configEntranceBgFadeOutMs, this.configEntranceBgPeakTransparency, this.configEntranceBgColor, mirror);
        }
        poseStack.translate(pivotX, pivotY, 0.0);
        float s = state.currentScale;
        if (this.enableScaleAnimation && state.elapsed < 200L) {
            float progress = (float)state.elapsed / 200.0f;
            float easedProgress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
            s = Mth.lerp(easedProgress, this.scale * 1.5f, this.scale);
        } else {
            s = this.scale;
        }
        poseStack.scale(s, s, 1.0f);
        poseStack.translate(-pivotX, -pivotY, 0.0);
        if (textYFrac > 0.001f) {
            poseStack.translate(0.0, textYFrac, 0.0);
        }
        int alphaInt = (int)(state.alpha * 255.0f) << 24;
        int colorWithAlpha = this.normalTextColor & 0xFFFFFF | alphaInt;
        if (this.enableGlowEffect) {
            float[][] offsets;
            int glowAlpha = (int)(state.alpha * this.glowIntensity * this.glowAlphaMultiplier * 255.0f);
            glowAlpha = Math.max(25, Math.min(255, glowAlpha));
            glowAlpha = Math.max(0, Math.min(255, glowAlpha));
            int glowRgb = killType == 10 ? this.glowColorRescueRgb : this.glowColorRgb;
            int glowColor = glowRgb & 0xFFFFFF | glowAlpha << 24;
            Component glowComponent = SubtitleRenderer.stripColor(fullText);
            for (float[] offset : offsets = new float[][]{{-this.glowSize, 0.0f}, {this.glowSize, 0.0f}, {0.0f, -this.glowSize}, {0.0f, this.glowSize}, {-this.glowSize, -this.glowSize}, {this.glowSize, -this.glowSize}, {-this.glowSize, this.glowSize}, {this.glowSize, this.glowSize}}) {
                poseStack.pushPose();
                poseStack.translate(offset[0], offset[1], 0.0);
                guiGraphics.drawString(font, glowComponent, textX, textYInt, glowColor, false);
                poseStack.popPose();
            }
        }
        guiGraphics.drawString(font, fullText, textX, textYInt, colorWithAlpha, this.enableTextShadow);
        poseStack.popPose();
    }

    private static String formatScore(float score) {
        if (score < 1.0f && score > 0.0f) {
            return String.format("%.1f", Float.valueOf(score));
        }
        return String.valueOf(Math.round(score));
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 100;
            this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            this.displayDuration = config2.has("display_duration") ? (long)(config2.get("display_duration").getAsFloat() * 1000.0f) : 3000L;
            this.scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
            this.enableScaleAnimation = !config2.has("enable_scale_animation") || config2.get("enable_scale_animation").getAsBoolean();
            this.killTypeEnableFlags.clear();
            this.killTypeEnableFlags.put("enable_normal_kill", !config2.has("enable_normal_kill") || config2.get("enable_normal_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_headshot_kill", !config2.has("enable_headshot_kill") || config2.get("enable_headshot_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_explosion_kill", !config2.has("enable_explosion_kill") || config2.get("enable_explosion_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_crit_kill", !config2.has("enable_crit_kill") || config2.get("enable_crit_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_assist_kill", !config2.has("enable_assist_kill") || config2.get("enable_assist_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_destroy_vehicle_kill", !config2.has("enable_destroy_vehicle_kill") || config2.get("enable_destroy_vehicle_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_capture_kill", !config2.has("enable_capture_kill") || config2.get("enable_capture_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_spot_assist_kill", !config2.has("enable_spot_assist_kill") || config2.get("enable_spot_assist_kill").getAsBoolean());
            this.killTypeEnableFlags.put("enable_rescue_kill", !config2.has("enable_rescue_kill") || config2.get("enable_rescue_kill").getAsBoolean());
            this.enableStacking = config2.has("enable_stacking") && config2.get("enable_stacking").getAsBoolean();
            this.maxLines = config2.has("max_lines") ? config2.get("max_lines").getAsInt() : 5;
            this.lineSpacing = config2.has("line_spacing") ? config2.get("line_spacing").getAsInt() : 12;
            String normalFormat = config2.has("format_normal") ? config2.get("format_normal").getAsString() : FormatDefaultsManager.getDefault("subtitle/kill_feed", KillTypeRegistry.get(0).formatKey());
            String normalColorHex = config2.has("color_normal_placeholder") ? config2.get("color_normal_placeholder").getAsString() : "#008B8B";
            this.format = normalFormat;
            this.placeholderColor = SubtitleRenderer.parseColorHexOrDefault(normalColorHex, -16741493);
            this.enablePlaceholderBold = config2.has("enable_placeholder_bold") && config2.get("enable_placeholder_bold").getAsBoolean();
            this.enableTextShadow = !config2.has("enable_text_shadow") || config2.get("enable_text_shadow").getAsBoolean();
            this.configBlinkFadeAnimation = config2.has("blink_fade_animation") && config2.get("blink_fade_animation").getAsBoolean();
            this.enableGlowEffect = config2.has("enable_glow_effect") && config2.get("enable_glow_effect").getAsBoolean();
            this.glowIntensity = config2.has("glow_intensity") ? config2.get("glow_intensity").getAsFloat() : 0.5f;
            this.glowSize = config2.has("glow_size") ? config2.get("glow_size").getAsFloat() : 0.3f;
            this.glowColorRgb = SubtitleRenderer.parseColorHexOrDefault(config2.has("glow_color") ? config2.get("glow_color").getAsString() : "#FFFFFF", 0xFFFFFF) & 0xFFFFFF;
            this.glowColorRescueRgb = SubtitleRenderer.parseColorHexOrDefault(config2.has("glow_color_rescue") ? config2.get("glow_color_rescue").getAsString() : "#63D048", 6541384) & 0xFFFFFF;
            this.glowAlphaMultiplier = config2.has("glow_alpha") ? Mth.clamp(config2.get("glow_alpha").getAsFloat(), 0.0f, 1.0f) : 1.0f;
            this.alignLeft = config2.has("align_left") ? config2.get("align_left").getAsBoolean() : false;
            this.alignRight = config2.has("align_right") ? config2.get("align_right").getAsBoolean() : false;
            this.configEnableFlashIn = !config2.has("enable_flash_in") || config2.get("enable_flash_in").getAsBoolean();
            this.fadeInDurationMs = config2.has("fade_in_duration") ? Math.max(0L, (long)(config2.get("fade_in_duration").getAsFloat() * 1000.0f)) : 0L;
            this.enableQueueLinkage = config2.has("enable_queue_linkage") && config2.get("enable_queue_linkage").getAsBoolean();
            this.queueLinkageScrollSpeed = config2.has("queue_linkage_scroll_speed") ? Math.max(0.01f, config2.get("queue_linkage_scroll_speed").getAsFloat()) : 0.1f;
            this.queueLinkageIconYOffset = config2.has("queue_linkage_icon_y_offset") ? config2.get("queue_linkage_icon_y_offset").getAsFloat() : 0.0f;
            this.configEntranceBackground = config2.has("entrance_background") && config2.get("entrance_background").getAsBoolean();
            this.configEntranceBgPeakTransparency = config2.has("entrance_background_alpha") ? Mth.clamp(config2.get("entrance_background_alpha").getAsFloat(), 0.0f, 1.0f) : 0.2f;
            this.configEntranceBgFadeInMs = config2.has("entrance_background_fade_in") ? Math.max(1L, (long)(config2.get("entrance_background_fade_in").getAsFloat() * 1000.0f)) : 66L;
            this.configEntranceBgSweepMs = config2.has("entrance_background_sweep_duration") ? Math.max(1L, (long)(config2.get("entrance_background_sweep_duration").getAsFloat() * 1000.0f)) : 100L;
            this.configEntranceBgFadeOutMs = config2.has("entrance_background_fade_out") ? Math.max(1L, (long)(config2.get("entrance_background_fade_out").getAsFloat() * 1000.0f)) : 133L;
            this.configEntranceBgColor = config2.has("entrance_background_color") ? SubtitleEntranceBackground.parseColor(config2.get("entrance_background_color").getAsString(), 0xFFFFFF) : 0xFFFFFF;
            this.normalTextColor = SubtitleRenderer.parseColorHexOrDefault(config2.has("color_normal_text") ? config2.get("color_normal_text").getAsString() : "#FFFFFF", -1);
        }
        catch (Exception e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.subtitle.config_error", new Object[0]);
            this.configXOffset = 0;
            this.configYOffset = 100;
            this.configScreenAnchor = "bottom_center";
            this.displayDuration = 3000L;
            this.scale = 1.0f;
            this.format = FormatDefaultsManager.getDefault("subtitle/kill_feed", KillTypeRegistry.get(0).formatKey());
            this.placeholderColor = -16741493;
            this.enablePlaceholderBold = false;
            this.enableScaleAnimation = true;
            this.killTypeEnableFlags.clear();
            this.killTypeEnableFlags.put("enable_normal_kill", true);
            this.killTypeEnableFlags.put("enable_capture_kill", true);
            this.enableStacking = false;
            this.normalTextColor = -1;
        }
    }

    private boolean isKillTypeEnabled(int type) {
        KillTypeDefinition def = KillTypeRegistry.get(type);
        if (def == null || def.enableKey() == null) {
            return true;
        }
        return this.killTypeEnableFlags.getOrDefault(def.enableKey(), true);
    }

    private float calculateAlpha(long currentTime) {
        long elapsed;
        if (this.startTime >= 0L && this.fadeInDurationMs > 0L && (elapsed = currentTime - this.startTime) < this.fadeInDurationMs) {
            return Math.max(0.0f, (float)elapsed / (float)this.fadeInDurationMs);
        }
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

    private Component buildFullText(String fmt, int pColor, int eColor, String wName, String vName, String scoreStr, float colorProgress, float distance) {
        MutableComponent fullText = Component.empty();
        String tempFormat = fmt;
        while (!tempFormat.isEmpty()) {
            int interpolatedColor;
            int weaponIdx = tempFormat.indexOf("<weapon>");
            int targetIdx = tempFormat.indexOf("<target>");
            int distanceIdx = tempFormat.indexOf("<distance>");
            int scoreIdx = tempFormat.indexOf("<score>");
            int emphasisStart = tempFormat.indexOf("/");
            int emphasisEnd = -1;
            if (emphasisStart != -1 && (emphasisEnd = tempFormat.indexOf("\\", emphasisStart + 1)) == -1) {
                emphasisStart = -1;
            }
            int firstIdx = -1;
            String type = "";
            if (weaponIdx != -1) {
                firstIdx = weaponIdx;
                type = "weapon";
            }
            if (targetIdx != -1 && (firstIdx == -1 || targetIdx < firstIdx)) {
                firstIdx = targetIdx;
                type = "target";
            }
            if (distanceIdx != -1 && (firstIdx == -1 || distanceIdx < firstIdx)) {
                firstIdx = distanceIdx;
                type = "distance";
            }
            if (scoreIdx != -1 && (firstIdx == -1 || scoreIdx < firstIdx)) {
                firstIdx = scoreIdx;
                type = "score";
            }
            if (emphasisStart != -1 && (firstIdx == -1 || emphasisStart < firstIdx)) {
                firstIdx = emphasisStart;
                type = "emphasis";
            }
            if (firstIdx == -1) {
                int targetColor = this.normalTextColor;
                int interpolatedColor2 = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                fullText.getSiblings().add(Component.literal(tempFormat).withStyle(style -> style.withColor(interpolatedColor2 & 0xFFFFFF)));
                break;
            }
            if (firstIdx > 0) {
                String prefix = tempFormat.substring(0, firstIdx);
                int targetColor = this.normalTextColor;
                interpolatedColor = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                final int prefixColor = interpolatedColor;
                fullText.getSiblings().add(Component.literal(prefix).withStyle(style -> style.withColor(prefixColor & 0xFFFFFF)));
            }
            if (type.equals("weapon")) {
                int targetColor = pColor & 0xFFFFFF;
                int interpolatedColor3 = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                fullText.getSiblings().add(Component.literal(wName).withStyle(style -> style.withColor(interpolatedColor3 & 0xFFFFFF).withBold(this.enablePlaceholderBold)));
                tempFormat = tempFormat.substring(firstIdx + "<weapon>".length());
                continue;
            }
            if (type.equals("target")) {
                int targetColor = pColor & 0xFFFFFF;
                int interpolatedColor4 = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                String translatedVName = I18nCompat.exists(vName) ? I18n.format((String)vName, (Object[])new Object[0]) : vName;
                fullText.getSiblings().add(Component.literal(translatedVName).withStyle(style -> style.withColor(interpolatedColor4 & 0xFFFFFF).withBold(this.enablePlaceholderBold)));
                tempFormat = tempFormat.substring(firstIdx + "<target>".length());
                continue;
            }
            if (type.equals("distance")) {
                if (distance >= 20.0f) {
                    String meterText = I18n.format((String)"gd656killicon.client.text.meter", (Object[])new Object[0]);
                    String content = String.format("(%d%s)", (int)distance, meterText);
                    int targetColor = pColor & 0xFFFFFF;
                    int interpolatedColor5 = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                    fullText.getSiblings().add(Component.literal(content).withStyle(style -> style.withColor(interpolatedColor5 & 0xFFFFFF).withBold(this.enablePlaceholderBold)));
                }
                tempFormat = tempFormat.substring(firstIdx + "<distance>".length());
                continue;
            }
            if (type.equals("score")) {
                int targetColor = pColor & 0xFFFFFF;
                int interpolatedColor6 = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
                fullText.getSiblings().add(Component.literal(scoreStr).withStyle(style -> style.withColor(interpolatedColor6 & 0xFFFFFF).withBold(this.enablePlaceholderBold)));
                tempFormat = tempFormat.substring(firstIdx + "<score>".length());
                continue;
            }
            if (!type.equals("emphasis")) continue;
            String content = tempFormat.substring(emphasisStart + 1, emphasisEnd);
            int targetColor = eColor & 0xFFFFFF;
            interpolatedColor = SubtitleRenderer.interpolateFromWhite(targetColor, colorProgress);
            final int emphasisColor = interpolatedColor;
            fullText.getSiblings().add(Component.literal(content).withStyle(style -> style.withColor(emphasisColor & 0xFFFFFF)));
            tempFormat = tempFormat.substring(emphasisEnd + 1);
        }
        return fullText;
    }

    private static boolean isKillFeedFormatEmpty(JsonObject config2, String formatKey) {
        return formatKey != null && config2 != null && config2.has(formatKey) && config2.get(formatKey).getAsString().isEmpty();
    }

    private static String formatKeyForType(int killType) {
        return KillTypeRegistry.get(killType).formatKey();
    }

    private static String resolveRushCaptureFormat(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        switch (token) {
            case "rush_bomb_planted": {
                return RUSH_BOMB_PLANTED_CAPTURE_FORMAT;
            }
            case "rush_bomb_defused": {
                return RUSH_BOMB_DEFUSED_CAPTURE_FORMAT;
            }
            case "rush_objective_destroyed": {
                return RUSH_OBJECTIVE_DESTROYED_CAPTURE_FORMAT;
            }
        }
        return null;
    }

    private static boolean isNumericToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(token);
            return true;
        }
        catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String placeholderColorKeyForType(int killType) {
        return KillTypeRegistry.get(killType).placeholderColorKey();
    }

    private static String emphasisColorKeyForType(int killType) {
        return KillTypeRegistry.get(killType).emphasisColorKey();
    }

    private boolean isNormalKillType(int type) {
        return type == 0 || type == 1 || type == 2 || type == 3;
    }

    private static Component stripColor(Component component) {
        Style style = component.getStyle();
        Style stripped = (style == null ? Style.EMPTY : style).withColor(null);
        MutableComponent result = component.copy();
        result.setStyle(stripped);
        for (int i = 0; i < result.getSiblings().size(); ++i) {
            result.getSiblings().set(i, SubtitleRenderer.stripColor(result.getSiblings().get(i)));
        }
        return result;
    }

    private static int parseColorHexOrDefault(String hex, int fallbackArgb) {
        if (hex == null || hex.isEmpty()) {
            return fallbackArgb;
        }
        try {
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            return rgb & 0xFFFFFF | 0xFF000000;
        }
        catch (NumberFormatException e) {
            return fallbackArgb;
        }
    }

    private float getColorProgress(long elapsed) {
        if (elapsed < 200L) {
            return (float)elapsed / 200.0f;
        }
        return 1.0f;
    }

    private static int interpolateFromWhite(int targetColor, float progress) {
        if (progress >= 1.0f) {
            return targetColor;
        }
        int white = 0xFFFFFF;
        int targetRGB = targetColor & 0xFFFFFF;
        int alpha = targetColor & 0xFF000000;
        int r1 = white >> 16 & 0xFF;
        int g1 = white >> 8 & 0xFF;
        int b1 = white & 0xFF;
        int r2 = targetRGB >> 16 & 0xFF;
        int g2 = targetRGB >> 8 & 0xFF;
        int b2 = targetRGB & 0xFF;
        int r = (int)((float)r1 + (float)(r2 - r1) * progress);
        int g = (int)((float)g1 + (float)(g2 - g1) * progress);
        int b = (int)((float)b1 + (float)(b2 - b1) * progress);
        return alpha | r << 16 | g << 8 | b;
    }

    private static class SubtitleItem {
        String format;
        int pColor;
        int eColor;
        String wName;
        String vName;
        long spawnTime;
        long duration;
        float currentRelY;
        float distance;
        int victimId;
        String scoreOverride;
        int killType;

        public SubtitleItem(String format, int pColor, int eColor, String wName, String vName, long spawnTime, long duration, float distance, int victimId, String scoreOverride, int killType) {
            this.format = format;
            this.pColor = pColor;
            this.eColor = eColor;
            this.wName = wName;
            this.vName = vName;
            this.spawnTime = spawnTime;
            this.duration = duration;
            this.currentRelY = 0.0f;
            this.distance = distance;
            this.victimId = victimId;
            this.scoreOverride = scoreOverride;
            this.killType = killType;
        }
    }

    private static final class RenderState {
        private final long elapsed;
        private final float alpha;
        private final float currentScale;

        private RenderState(long elapsed, float alpha, float currentScale) {
            this.elapsed = elapsed;
            this.alpha = alpha;
            this.currentScale = currentScale;
        }
    }
}

