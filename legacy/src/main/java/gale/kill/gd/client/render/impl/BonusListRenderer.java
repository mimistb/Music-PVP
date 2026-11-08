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
 *  net.minecraft.util.StatCollector
 */
package gale.kill.gd.client.render.impl;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.ElementConfigManager;
import gale.kill.gd.client.config.FormatDefaultsManager;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.BonusTextBox;
import gale.kill.gd.client.render.effect.DigitalScrollEffect;
import gale.kill.gd.client.render.effect.TextFadeEffect;
import gale.kill.gd.client.render.effect.TextScrambleEffect;
import gale.kill.gd.client.render.impl.ScrollingIconRenderer;
import gale.kill.gd.client.render.impl.SubtitleRenderer;
import gale.kill.gd.client.util.I18nCompat;
import gale.kill.gd.common.bonus.BonusDefinition;
import gale.kill.gd.common.bonus.BonusRegistry;
import gale.kill.gd.compat.Component;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.MutableComponent;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.RenderSystem;
import gale.kill.gd.compat.Style;
import gale.kill.gd.compat.TextColor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

public class BonusListRenderer
implements IHudRenderer {
    private static final float COMBO_ANIMATION_DURATION_MULTIPLIER = 1.25f;
    private static final float STREAK_ANIMATION_DURATION_MULTIPLIER = 5.0f;
    private static final float ALPHA_THRESHOLD = 0.05f;
    private static final float GLOW_OFFSET = 0.3f;
    private static final long KILL_FEED_ENTRY_ANIMATION_DURATION = 350L;
    private static final float KILL_FEED_ENTRY_SCALE_START = 1.8f;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("<(\\w+)>");
    private static final BonusListRenderer INSTANCE = new BonusListRenderer();
    private final List<BonusItem> items = new ArrayList<BonusItem>();
    private final Deque<BonusItem> pendingQueue = new ArrayDeque<BonusItem>();
    private float animationDuration = 0.5f;
    private float animationRefreshRate = 0.01f;
    private boolean enableTextScrolling = false;
    private float textScrollingDurationMultiplier = 1.5f;
    private float textScrollingRefreshRate = 0.05f;
    private long enterAnimationDuration = 200L;
    private boolean enableTextSweepAnimation = false;
    private long mergeWindowDuration = 500L;
    private float animationSpeed = 10.0f;
    private float killBonusScale = 1.0f;
    private boolean enableKillFeed = false;
    private String killFeedFormat = "[<weapon>] <target> +<score>";
    private String killFeedVictimColor = "#FF0000";
    private boolean enableDigitalScroll = true;
    private boolean enableStackMultiplier = false;
    private boolean enableGlowEffect = false;
    private float glowIntensity = 0.5f;
    private float glowSize = 0.3f;
    private int glowColorRgb = 0xFFFFFF;
    private float glowAlphaMultiplier = 1.0f;
    private boolean enableTextBox = false;
    private float textBoxBorderWidth = 1.0f;
    private int textBoxColorRgb = 0xFFFFFF;
    private boolean cleanSubtitleContent = false;
    private long fadeOutDurationMs = 300L;
    private float fadeStartLineRatio = 0.0f;
    private boolean enableQueueLinkage = false;
    private float queueLinkageScrollSpeed = 0.1f;
    private float queueLinkageIconYOffset = 0.0f;
    private boolean linkageConfigLoaded = false;
    private float linkedYOffset = 0.0f;
    private float linkageFromOffset = 0.0f;
    private long linkageStartTime = 0L;
    private float linkageTargetOffset = 0.0f;
    private float linkageBaseY = 0.0f;
    private int normalTextColor = 0xFFFFFF;
    private boolean enableTextShadow = true;
    private boolean configBlinkFadeAnimation = false;
    private long lastProcessTime = 0L;
    private long lastRenderTime = 0L;
    private long nextFadeTriggerTime = 0L;

    private BonusListRenderer() {
    }

    public static BonusListRenderer getInstance() {
        return INSTANCE;
    }

    public static boolean isBonusConfigEmpty(int type, JsonObject config2) {
        BonusDefinition def = BonusRegistry.get(type);
        if (def == null || config2 == null || !config2.has(def.formatConfigKey())) {
            return false;
        }
        return config2.get(def.formatConfigKey()).getAsString().isEmpty();
    }

    public static String getEffectiveFormat(int type, String extraData) {
        JsonObject config2 = ElementConfigManager.getElementConfig(ConfigManager.getCurrentPresetId(), "subtitle/bonus_list");
        return BonusListRenderer.getEffectiveFormat(type, extraData, config2);
    }

    public static String getEffectiveFormat(int type, String extraData, JsonObject config2) {
        BonusDefinition def;
        if (type == 8 && config2 != null && config2.has("enable_special_streak_subtitles") && config2.get("enable_special_streak_subtitles").getAsBoolean()) {
            try {
                String subtitle;
                int combo = Integer.parseInt(extraData);
                def = BonusRegistry.get(type);
                if (def != null && (subtitle = def.streakSubtitle(combo)) != null) {
                    return I18n.format((String)subtitle, (Object[])new Object[0]);
                }
            }
            catch (NumberFormatException combo) {
                // empty catch block
            }
        }
        String configValue = null;
        def = BonusRegistry.get(type);
        if (def != null && config2 != null && config2.has(def.formatConfigKey())) {
            configValue = config2.get(def.formatConfigKey()).getAsString();
        }
        if (def == null) {
            return "";
        }
        if (configValue == null || configValue.isEmpty()) {
            return FormatDefaultsManager.getDefault("subtitle/bonus_list", def.formatConfigKey());
        }
        return configValue;
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        JsonObject config2 = ElementConfigManager.getElementConfig(ConfigManager.getCurrentPresetId(), "subtitle/bonus_list");
        if (config2 == null || !config2.get("visible").getAsBoolean()) {
            return;
        }
        if (!this.linkageConfigLoaded) {
            this.loadConfig(config2);
            this.linkageConfigLoaded = true;
        }
        int xOffset = config2.get("x_offset").getAsInt();
        int yOffset = config2.get("y_offset").getAsInt();
        String screenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float centerX = ScreenAnchor.resolveCenterX(screenAnchor, xOffset, screenWidth);
        float startY = ScreenAnchor.resolveCenterY(screenAnchor, yOffset, screenHeight);
        startY = this.applyQueueLinkage(startY);
        this.renderInternal(guiGraphics, config2, centerX, startY);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        JsonObject config2 = ElementConfigManager.getElementConfig(ConfigManager.getCurrentPresetId(), "subtitle/bonus_list");
        if (config2 == null || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.renderInternal(guiGraphics, config2, centerX, centerY);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        Entity entity;
        JsonObject config2 = ElementConfigManager.getElementConfig(ConfigManager.getCurrentPresetId(), "subtitle/bonus_list");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.loadConfig(config2);
        ParsedData parsed = ParsedData.parse(context.extraData());
        String extraData = parsed.extraData;
        if (BonusListRenderer.isBonusConfigEmpty(context.type(), config2)) {
            return;
        }
        String format = BonusListRenderer.getEffectiveFormat(context.type(), extraData);
        int specialColor = this.parseSpecialColor(config2);
        long now = PreviewRenderTimeContext.currentTimeMillis();
        String weaponName = "";
        String victimName = "";
        Minecraft mc = Minecraft.getMinecraft();
        victimName = parsed.victimName != null && !parsed.victimName.isEmpty() ? (StatCollector.canTranslate((String)parsed.victimName) ? I18n.format((String)parsed.victimName, (Object[])new Object[0]) : parsed.victimName) : (mc.theWorld != null && context.entityId() != -1 ? ((entity = mc.theWorld.getEntityByID(context.entityId())) != null ? entity.getDisplayName().getUnformattedText() : I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0])) : I18n.format((String)"gd656killicon.client.text.unknown", (Object[])new Object[0]));
        if (mc.thePlayer != null) {
            ItemStack held;
            weaponName = mc.thePlayer.ridingEntity != null ? mc.thePlayer.ridingEntity.getDisplayName().getUnformattedText() : ((held = mc.thePlayer.getHeldItem()) == null ? (StatCollector.canTranslate((String)"gd656killicon.client.text.bare_hand") ? I18n.format((String)"gd656killicon.client.text.bare_hand", (Object[])new Object[0]) : "Bare Hand") : held.getDisplayName());
        }
        List<BonusItem> list = this.items;
        synchronized (list) {
            boolean isComboFormat = format.contains("<combo>");
            for (BonusItem item : this.items) {
                if (!this.canMerge(item, format, isComboFormat, extraData, now)) continue;
                item.merge(parsed.score, extraData, isComboFormat, format);
                this.nextFadeTriggerTime = now + (long)(config2.get("display_duration").getAsFloat() * 1000.0f);
                return;
            }
            BonusItem newItem = new BonusItem(format, parsed.score, extraData, context.type(), specialColor, weaponName, victimName);
            this.pendingQueue.add(newItem);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void triggerPreview(int type, float score, String extraData, String weaponName, String victimName, JsonObject config2) {
        String resolvedExtraData;
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.loadConfig(config2);
        String string = resolvedExtraData = extraData != null ? extraData : "";
        if (BonusListRenderer.isBonusConfigEmpty(type, config2)) {
            return;
        }
        String format = BonusListRenderer.getEffectiveFormat(type, resolvedExtraData, config2);
        int specialColor = this.parseSpecialColor(config2);
        long now = PreviewRenderTimeContext.currentTimeMillis();
        List<BonusItem> list = this.items;
        synchronized (list) {
            boolean isComboFormat = format.contains("<combo>");
            for (BonusItem item : this.items) {
                if (!this.canMerge(item, format, isComboFormat, resolvedExtraData, now)) continue;
                item.merge(score, resolvedExtraData, isComboFormat, format);
                this.nextFadeTriggerTime = now + (long)(config2.get("display_duration").getAsFloat() * 1000.0f);
                return;
            }
            BonusItem newItem = new BonusItem(format, score, resolvedExtraData, type, specialColor, weaponName, victimName);
            this.pendingQueue.add(newItem);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void resetPreview() {
        List<BonusItem> list = this.items;
        synchronized (list) {
            this.items.clear();
            this.pendingQueue.clear();
        }
        this.lastProcessTime = 0L;
        this.lastRenderTime = 0L;
        this.nextFadeTriggerTime = 0L;
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 0.5f;
            this.animationRefreshRate = config2.has("animation_refresh_rate") ? config2.get("animation_refresh_rate").getAsFloat() : 0.01f;
            this.enableTextScrolling = config2.has("enable_text_scrolling") && config2.get("enable_text_scrolling").getAsBoolean();
            this.textScrollingDurationMultiplier = config2.has("text_scrolling_duration_multiplier") ? config2.get("text_scrolling_duration_multiplier").getAsFloat() : 1.5f;
            this.textScrollingRefreshRate = config2.has("text_scrolling_refresh_rate") ? config2.get("text_scrolling_refresh_rate").getAsFloat() : 0.05f;
            this.enterAnimationDuration = config2.has("enter_animation_duration") ? (long)(config2.get("enter_animation_duration").getAsFloat() * 1000.0f) : 200L;
            this.enableTextSweepAnimation = config2.has("enable_text_sweep_animation") && config2.get("enable_text_sweep_animation").getAsBoolean();
            this.mergeWindowDuration = config2.has("merge_window_duration") ? (long)(config2.get("merge_window_duration").getAsFloat() * 1000.0f) : 500L;
            this.animationSpeed = config2.has("animation_speed") ? config2.get("animation_speed").getAsFloat() : 10.0f;
            this.killBonusScale = config2.has("kill_bonus_scale") ? config2.get("kill_bonus_scale").getAsFloat() : 1.0f;
            this.enableKillFeed = config2.has("enable_kill_feed") && config2.get("enable_kill_feed").getAsBoolean();
            this.killFeedFormat = config2.has("kill_feed_format") ? config2.get("kill_feed_format").getAsString() : "[<weapon>] <target> +<score>";
            this.killFeedVictimColor = config2.has("kill_feed_victim_color") ? config2.get("kill_feed_victim_color").getAsString() : "#FF0000";
            this.enableDigitalScroll = !config2.has("enable_digital_scroll") || config2.get("enable_digital_scroll").getAsBoolean();
            this.enableStackMultiplier = config2.has("enable_stack_multiplier") && config2.get("enable_stack_multiplier").getAsBoolean();
            this.enableGlowEffect = config2.has("enable_glow_effect") && config2.get("enable_glow_effect").getAsBoolean();
            this.glowIntensity = config2.has("glow_intensity") ? config2.get("glow_intensity").getAsFloat() : 0.5f;
            this.glowSize = config2.has("glow_size") ? config2.get("glow_size").getAsFloat() : 0.3f;
            this.glowColorRgb = this.parseHexColor(config2, "glow_color", 0xFFFFFF);
            this.glowAlphaMultiplier = config2.has("glow_alpha") ? Mth.clamp(config2.get("glow_alpha").getAsFloat(), 0.0f, 1.0f) : 1.0f;
            this.enableTextBox = config2.has("text_box") && config2.get("text_box").getAsBoolean();
            this.textBoxBorderWidth = config2.has("text_box_border_width") ? Math.max(0.0f, config2.get("text_box_border_width").getAsFloat()) : 1.0f;
            this.textBoxColorRgb = this.parseHexColor(config2, "text_box_color", 0xFFFFFF);
            this.cleanSubtitleContent = config2.has("clean_subtitle_content") && config2.get("clean_subtitle_content").getAsBoolean();
            this.fadeOutDurationMs = config2.has("fade_out_duration") ? Math.max(1L, (long)(config2.get("fade_out_duration").getAsFloat() * 1000.0f)) : 300L;
            this.fadeStartLineRatio = config2.has("fade_start_line_ratio") ? Mth.clamp(config2.get("fade_start_line_ratio").getAsFloat(), 0.0f, 1.0f) : 0.0f;
            this.enableQueueLinkage = config2.has("enable_queue_linkage") && config2.get("enable_queue_linkage").getAsBoolean();
            this.queueLinkageScrollSpeed = config2.has("queue_linkage_scroll_speed") ? Math.max(0.01f, config2.get("queue_linkage_scroll_speed").getAsFloat()) : 0.1f;
            this.queueLinkageIconYOffset = config2.has("queue_linkage_icon_y_offset") ? config2.get("queue_linkage_icon_y_offset").getAsFloat() : 0.0f;
            this.enableTextShadow = !config2.has("enable_text_shadow") || config2.get("enable_text_shadow").getAsBoolean();
            this.configBlinkFadeAnimation = config2.has("blink_fade_animation") && config2.get("blink_fade_animation").getAsBoolean();
            this.normalTextColor = this.parseHexColor(config2, "color_normal_text", 0xFFFFFF);
        }
        catch (Exception e) {
            this.animationDuration = 0.5f;
            this.animationRefreshRate = 0.01f;
            this.enableTextScrolling = false;
            this.enterAnimationDuration = 200L;
            this.enableTextSweepAnimation = false;
            this.mergeWindowDuration = 500L;
            this.animationSpeed = 10.0f;
            this.killBonusScale = 1.0f;
            this.enableKillFeed = false;
            this.enableDigitalScroll = true;
            this.enableGlowEffect = false;
            this.glowIntensity = 0.5f;
            this.glowSize = 0.3f;
            this.glowColorRgb = 0xFFFFFF;
            this.glowAlphaMultiplier = 1.0f;
            this.normalTextColor = 0xFFFFFF;
        }
    }

    private void renderInternal(GuiGraphics guiGraphics, JsonObject config2, float baseCenterX, float baseBottomY) {
        this.loadConfig(config2);
        float scale = config2.get("scale").getAsFloat();
        int lineSpacing = config2.get("line_spacing").getAsInt();
        int maxLines = config2.get("max_lines").getAsInt();
        float displayDuration = config2.get("display_duration").getAsFloat() * 1000.0f;
        float fadeOutInterval = config2.get("fade_out_interval").getAsFloat() * 1000.0f;
        Minecraft mc = Minecraft.getMinecraft();
        int centerX = Math.round(baseCenterX);
        int startY = Math.round(baseBottomY);
        long now = PreviewRenderTimeContext.currentTimeMillis();
        if (this.lastRenderTime == 0L) {
            this.lastRenderTime = now;
        }
        float dt = (float)(now - this.lastRenderTime) / 1000.0f;
        this.lastRenderTime = now;
        this.processPendingQueue(now, displayDuration);
        this.processIdleFade(now, fadeOutInterval);
        this.renderItems(guiGraphics, mc, scale, centerX, startY, lineSpacing, maxLines, now, dt);
    }

    private int parseSpecialColor(JsonObject config2) {
        if (config2.has("color_special_placeholder")) {
            String colorStr = config2.get("color_special_placeholder").getAsString();
            try {
                if (colorStr.startsWith("#")) {
                    return Integer.parseInt(colorStr.substring(1), 16);
                }
                return Integer.parseInt(colorStr, 16);
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return 13940736;
    }

    private int parseHexColor(JsonObject config2, String key, int fallback) {
        if (config2.has(key)) {
            String colorStr = config2.get(key).getAsString();
            try {
                String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
                return Integer.parseInt(hex, 16);
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return fallback;
    }

    private static String cleanSubtitleText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == ' ' || c == '+') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static Component stripColor(Component component) {
        Style style = component.getStyle();
        Style stripped = (style == null ? Style.EMPTY : style).withColor(null);
        MutableComponent result = component.copy();
        result.setStyle(stripped);
        for (int i = 0; i < result.getSiblings().size(); ++i) {
            result.getSiblings().set(i, BonusListRenderer.stripColor(result.getSiblings().get(i)));
        }
        return result;
    }

    private boolean canMerge(BonusItem item, String format, boolean isComboFormat, String extraData, long now) {
        boolean extraDataMatches;
        if (item.isFading || !item.formatString.equals(format) || !item.hasPlaceholder) {
            return false;
        }
        if (isComboFormat) {
            return true;
        }
        boolean bl = extraDataMatches = (item.extraData == null || item.extraData.isEmpty()) && (extraData == null || extraData.isEmpty()) || item.extraData != null && item.extraData.equals(extraData);
        if (!extraDataMatches) {
            return false;
        }
        return now - item.spawnTime <= this.mergeWindowDuration;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void processPendingQueue(long now, float displayDuration) {
        if (!this.pendingQueue.isEmpty() && now - this.lastProcessTime >= 100L) {
            List<BonusItem> list = this.items;
            synchronized (list) {
                BonusItem newItem = this.pendingQueue.poll();
                if (newItem != null) {
                    newItem.spawnTime = now;
                    this.items.add(0, newItem);
                }
            }
            this.lastProcessTime = now;
            this.nextFadeTriggerTime = now + (long)displayDuration;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void processIdleFade(long now, float fadeOutInterval) {
        if (this.pendingQueue.isEmpty() && now > this.nextFadeTriggerTime && !this.items.isEmpty()) {
            List<BonusItem> list = this.items;
            synchronized (list) {
                for (int i = this.items.size() - 1; i >= 0; --i) {
                    BonusItem item = this.items.get(i);
                    if (item.isFading) continue;
                    item.isFading = true;
                    item.fadeStartTime = now;
                    this.nextFadeTriggerTime += (long)fadeOutInterval;
                    return;
                }
                this.nextFadeTriggerTime = now + (long)fadeOutInterval;
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void renderItems(GuiGraphics guiGraphics, Minecraft mc, float scale, int centerX, int startY, int lineSpacing, int maxLines, long now, float dt) {
        JsonObject config2 = ElementConfigManager.getElementConfig(ConfigManager.getCurrentPresetId(), "subtitle/bonus_list");
        boolean alignLeft = config2 != null && config2.has("align_left") && config2.get("align_left").getAsBoolean();
        boolean alignRight = config2 != null && config2.has("align_right") && config2.get("align_right").getAsBoolean();
        boolean effectiveAlignLeft = alignLeft && !alignRight;
        boolean effectiveAlignRight = !alignLeft && alignRight;
        boolean horizontal = config2 != null && config2.has("enable_horizontal_layout") && config2.get("enable_horizontal_layout").getAsBoolean() && (effectiveAlignLeft || effectiveAlignRight);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, 1.0f);
        float scaledCenterX = (float)centerX / scale;
        float scaledStartY = (float)startY / scale;
        List<BonusItem> list = this.items;
        synchronized (list) {
            Iterator<BonusItem> iterator = this.items.iterator();
            int index = 0;
            float accumulatedX = 0.0f;
            while (iterator.hasNext()) {
                BonusItem item = iterator.next();
                if (horizontal) {
                    float itemWidth = Font.INSTANCE.width(item.getDisplayComponent());
                    float dir = effectiveAlignRight ? -1.0f : 1.0f;
                    item.updateX(now, dt, dir * accumulatedX);
                    accumulatedX += itemWidth + (float)lineSpacing;
                } else {
                    item.update(now, dt, index * lineSpacing);
                }
                float alpha = this.calculateAlpha(item, now, maxLines, lineSpacing, horizontal, index);
                if (alpha > 0.05f) {
                    item.render(guiGraphics, mc, scaledCenterX, scaledStartY, alpha, effectiveAlignLeft, effectiveAlignRight, (float)new ScaledResolution(mc).getScaledWidth() / scale, scale, horizontal);
                }
                if (this.shouldRemove(item, alpha, now, maxLines, lineSpacing, horizontal, index)) {
                    iterator.remove();
                    continue;
                }
                ++index;
            }
        }
        guiGraphics.pose().popPose();
        RenderSystem.disableBlend();
    }

    private float calculateAlpha(BonusItem item, long now, int maxLines, int lineSpacing, boolean horizontal, int index) {
        float alpha = 1.0f;
        if (!this.enableTextSweepAnimation) {
            long timeSinceSpawn = now - item.spawnTime;
            float fadeInProgress = (float)timeSinceSpawn / (float)this.enterAnimationDuration;
            alpha *= Math.min(1.0f, fadeInProgress);
        }
        float lineIndex = horizontal ? (float)index : item.currentY / (float)lineSpacing;
        float fadeRange = Math.max(1.0f, (float)maxLines - 1.0f);
        float fadeStartLine = this.fadeStartLineRatio * fadeRange;
        float fadeSpan = fadeRange - fadeStartLine;
        float posFadeProgress = fadeSpan <= 0.0f ? 0.0f : (lineIndex - fadeStartLine) / fadeSpan;
        float posAlpha = Math.max(0.0f, 1.0f - Math.max(0.0f, posFadeProgress));
        alpha *= posAlpha;
        if (posAlpha <= 0.0f) {
            return 0.0f;
        }
        if (item.isFading) {
            long fadeElapsed = now - item.fadeStartTime;
            float fadeDuration = this.fadeOutDurationMs;
            float fadeProgress = (float)fadeElapsed / fadeDuration;
            alpha *= TextFadeEffect.fadeAlpha(fadeProgress, this.configBlinkFadeAnimation);
            alpha = Math.max(0.1f, alpha);
        }
        return alpha;
    }

    private boolean shouldRemove(BonusItem item, float alpha, long now, int maxLines, int lineSpacing, boolean horizontal, int index) {
        float lineIndex = horizontal ? (float)index : item.currentY / (float)lineSpacing;
        boolean positionHidden = lineIndex >= (float)maxLines;
        boolean fadeHidden = item.isFading && now - item.fadeStartTime >= this.fadeOutDurationMs;
        return positionHidden || fadeHidden;
    }

    private float applyQueueLinkage(float baseY) {
        if (!this.enableQueueLinkage) {
            this.linkageStartTime = 0L;
            this.linkedYOffset = 0.0f;
            return baseY;
        }
        SubtitleRenderer subtitleRenderer = SubtitleRenderer.getInstance();
        ScrollingIconRenderer iconRenderer = ScrollingIconRenderer.getInstance();
        boolean kfVisible = subtitleRenderer.hasVisibleSubtitle();
        boolean iconsVisible = iconRenderer.hasVisibleIcons();
        float kfBaseY = subtitleRenderer.getBaseTextY();
        float iconY = iconRenderer.getIconsAnchorY();
        float targetY = iconsVisible && kfVisible ? baseY : (kfVisible ? kfBaseY : (iconsVisible ? kfBaseY : iconY + this.queueLinkageIconYOffset));
        if (targetY >= baseY) {
            targetY = baseY;
        }
        return this.smoothLinkedY(baseY, targetY);
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

    private void drawComponentWithGlow(GuiGraphics guiGraphics, Font font, Component component, int x, int y, int alphaInt) {
        if (this.enableGlowEffect) {
            float[][] offsets;
            int glowAlpha = (int)((float)alphaInt * this.glowIntensity * this.glowAlphaMultiplier);
            glowAlpha = Math.max(25, Math.min(255, glowAlpha));
            glowAlpha = Math.max(0, Math.min(255, glowAlpha));
            int glowColor = glowAlpha << 24 | this.glowColorRgb & 0xFFFFFF;
            Component glowComponent = BonusListRenderer.stripColor(component);
            PoseStack poseStack = guiGraphics.pose();
            for (float[] offset : offsets = new float[][]{{-this.glowSize, 0.0f}, {this.glowSize, 0.0f}, {0.0f, -this.glowSize}, {0.0f, this.glowSize}, {-this.glowSize, -this.glowSize}, {this.glowSize, -this.glowSize}, {-this.glowSize, this.glowSize}, {this.glowSize, this.glowSize}}) {
                poseStack.pushPose();
                poseStack.translate(offset[0], offset[1], 0.0);
                guiGraphics.drawString(font, glowComponent, x, y, glowColor, false);
                poseStack.popPose();
            }
        }
        int color = alphaInt << 24 | this.normalTextColor & 0xFFFFFF;
        guiGraphics.drawString(font, component, x, y, color, this.enableTextShadow);
    }

    public static String getLocalizedNumber(int n) {
        boolean isChinese;
        String lang = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
        boolean bl = isChinese = lang != null && lang.startsWith("zh_");
        if (isChinese) {
            switch (n) {
                case 1: {
                    return "\u4e00";
                }
                case 2: {
                    return "\u4e8c";
                }
                case 3: {
                    return "\u4e09";
                }
                case 4: {
                    return "\u56db";
                }
                case 5: {
                    return "\u4e94";
                }
                case 6: {
                    return "\u516d";
                }
                case 7: {
                    return "\u4e03";
                }
                case 8: {
                    return "\u516b";
                }
                case 9: {
                    return "\u4e5d";
                }
                case 10: {
                    return "\u5341";
                }
            }
            return String.valueOf(n);
        }
        return String.valueOf(n);
    }

    private int interpolateColor(int c1, int c2, float t) {
        int r1 = c1 >> 16 & 0xFF;
        int g1 = c1 >> 8 & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = c2 >> 16 & 0xFF;
        int g2 = c2 >> 8 & 0xFF;
        int b2 = c2 & 0xFF;
        int r = (int)((float)r1 + (float)(r2 - r1) * t);
        int g = (int)((float)g1 + (float)(g2 - g1) * t);
        int b = (int)((float)b1 + (float)(b2 - b1) * t);
        return r << 16 | g << 8 | b;
    }

    private class BonusItem {
        final String formatString;
        final boolean hasPlaceholder;
        String extraData;
        int stackCount = 1;
        float currentY;
        float currentXOffset;
        boolean isFading;
        long fadeStartTime;
        long spawnTime;
        final AnimatedStat scoreStat;
        AnimatedStat comboStat;
        AnimatedStat mkStat;
        AnimatedStat distanceStat;
        AnimatedStat streakStat;
        int specialColor;
        final boolean isKillBonus;
        final float itemScale;
        final boolean showKillFeed;
        final String weaponName;
        final String victimName;
        final String killFeedFormatStr;
        final int killFeedVictimColorVal;
        private final List<TextScrambleEffect> scrambleEffects = new ArrayList<TextScrambleEffect>();

        public BonusItem(String format, float initialScore, String extraData, int type, int specialColor, String weaponName, String victimName) {
            this.formatString = format;
            this.extraData = extraData != null ? extraData : "";
            this.currentY = 0.0f;
            this.currentXOffset = 0.0f;
            this.isFading = false;
            this.spawnTime = PreviewRenderTimeContext.currentTimeMillis();
            this.specialColor = specialColor;
            this.isKillBonus = type == 1 || type == 6 || type == 7 || type == 5;
            this.itemScale = this.isKillBonus ? BonusListRenderer.this.killBonusScale : 1.0f;
            this.showKillFeed = this.isKillBonus && BonusListRenderer.this.enableKillFeed;
            this.weaponName = weaponName;
            this.victimName = victimName;
            this.killFeedFormatStr = BonusListRenderer.this.killFeedFormat;
            int vColor = 0xFF0000;
            try {
                String c = BonusListRenderer.this.killFeedVictimColor;
                if (c.startsWith("#")) {
                    c = c.substring(1);
                }
                vColor = Integer.parseInt(c, 16);
            }
            catch (Exception exception) {
                // empty catch block
            }
            this.killFeedVictimColorVal = vColor;
            this.hasPlaceholder = format.contains("<score>");
            this.scoreStat = this.hasPlaceholder ? new AnimatedStat(initialScore, BonusListRenderer.this.animationDuration, BonusListRenderer.this.animationRefreshRate) : null;
            this.initStats(format, extraData);
            this.initScrambleEffects(format);
        }

        private void initScrambleEffects(String format) {
            if (!BonusListRenderer.this.enableTextScrolling) {
                return;
            }
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(format);
            int lastEnd = 0;
            long duration = (long)(BonusListRenderer.this.animationDuration * BonusListRenderer.this.textScrollingDurationMultiplier * 1000.0f);
            long refresh = (long)(BonusListRenderer.this.textScrollingRefreshRate * 1000.0f);
            while (matcher.find()) {
                String staticPart = format.substring(lastEnd, matcher.start());
                this.scrambleEffects.add(new TextScrambleEffect(staticPart, duration, refresh, true));
                lastEnd = matcher.end();
            }
            this.scrambleEffects.add(new TextScrambleEffect(format.substring(lastEnd), duration, refresh, true));
        }

        private void initStats(String format, String extraData) {
            if (extraData == null || extraData.isEmpty()) {
                return;
            }
            if (format.contains("<combo>")) {
                this.comboStat = this.createStat(extraData, 1.25f);
            }
            if (format.contains("<multi_kill>")) {
                this.mkStat = this.createStat(extraData, 5.0f);
            }
            if (format.contains("<distance>")) {
                this.distanceStat = this.createStat(extraData, 5.0f);
            }
            if (format.contains("<streak>")) {
                this.streakStat = this.createStat(extraData, 5.0f);
            }
        }

        private AnimatedStat createStat(String data, float durationMult) {
            try {
                int val = Integer.parseInt(data);
                return new AnimatedStat(val, BonusListRenderer.this.animationDuration * durationMult, BonusListRenderer.this.animationRefreshRate);
            }
            catch (NumberFormatException e) {
                return null;
            }
        }

        public void merge(float score, String newExtraData, boolean isComboFormat, String format) {
            ++this.stackCount;
            if (this.scoreStat != null && !BonusListRenderer.this.enableStackMultiplier) {
                this.scoreStat.add(score);
            }
            if (newExtraData == null) {
                return;
            }
            if (isComboFormat && this.comboStat != null) {
                try {
                    this.comboStat.updateMax(Integer.parseInt(newExtraData));
                    this.extraData = String.valueOf((int)this.comboStat.targetValue);
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            if (format.contains("<multi_kill>") && this.mkStat != null) {
                try {
                    this.mkStat.updateMax(Integer.parseInt(newExtraData));
                    this.extraData = String.valueOf((int)this.mkStat.targetValue);
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            if (format.contains("<distance>") && this.distanceStat != null) {
                try {
                    int dist = Integer.parseInt(newExtraData);
                    if ((float)dist != this.distanceStat.targetValue) {
                        this.distanceStat.targetValue = dist;
                        this.distanceStat.effect.startAnimation(this.distanceStat.effect.getCurrentValue(), dist);
                        this.extraData = String.valueOf(dist);
                    }
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            if (format.contains("<streak>") && this.streakStat != null) {
                try {
                    this.streakStat.updateMax(Integer.parseInt(newExtraData));
                    this.extraData = String.valueOf((int)this.streakStat.targetValue);
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
        }

        public void update(long now, float dt, float targetY) {
            if (this.scoreStat != null) {
                this.scoreStat.update(now, dt);
            }
            if (this.comboStat != null) {
                this.comboStat.update(now, dt);
            }
            if (this.mkStat != null) {
                this.mkStat.update(now, dt);
            }
            if (this.distanceStat != null) {
                this.distanceStat.update(now, dt);
            }
            if (this.streakStat != null) {
                this.streakStat.update(now, dt);
            }
            float smoothFactor = 1.0f - (float)Math.exp(-BonusListRenderer.this.animationSpeed * dt);
            this.currentY += (targetY - this.currentY) * smoothFactor;
        }

        public void updateX(long now, float dt, float targetX) {
            if (this.scoreStat != null) {
                this.scoreStat.update(now, dt);
            }
            if (this.comboStat != null) {
                this.comboStat.update(now, dt);
            }
            if (this.mkStat != null) {
                this.mkStat.update(now, dt);
            }
            if (this.distanceStat != null) {
                this.distanceStat.update(now, dt);
            }
            if (this.streakStat != null) {
                this.streakStat.update(now, dt);
            }
            float smoothFactor = 1.0f - (float)Math.exp(-BonusListRenderer.this.animationSpeed * dt);
            this.currentXOffset += (targetX - this.currentXOffset) * smoothFactor;
        }

        public void render(GuiGraphics guiGraphics, Minecraft mc, float x, float y, float alpha, boolean alignLeft, boolean alignRight, float screenWidth, float globalScale, boolean horizontal) {
            float drawY;
            float drawX;
            long feedStart;
            Component component = this.getDisplayComponent();
            Component killFeedComponent = null;
            if (this.showKillFeed) {
                killFeedComponent = this.buildKillFeedComponent();
            }
            int alphaInt = (int)(alpha * 255.0f);
            alphaInt = Math.max(0, Math.min(255, alphaInt));
            int textColor = alphaInt << 24 | 0xFFFFFF;
            int baseTextWidth = Font.INSTANCE.width(component);
            int feedTextWidth = killFeedComponent != null ? Font.INSTANCE.width(killFeedComponent) : 0;
            long now = PreviewRenderTimeContext.currentTimeMillis();
            long elapsed = now - this.spawnTime;
            long enterDuration = BonusListRenderer.this.enterAnimationDuration;
            boolean sweepEnabled = BonusListRenderer.this.enableTextSweepAnimation;
            guiGraphics.pose().pushPose();
            float scaleOriginX = x;
            Font.INSTANCE.getClass();
            float scaleOriginY = y + this.currentY + 9.0f / 2.0f;
            float currentScale = this.itemScale;
            if (this.showKillFeed && elapsed < 350L) {
                float progress = (float)elapsed / 350.0f;
                float easedProgress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
                float animationScaleMultiplier = Mth.lerp(easedProgress, 1.8f, 1.0f);
                currentScale *= animationScaleMultiplier;
            }
            guiGraphics.pose().translate(scaleOriginX, scaleOriginY, 0.0);
            guiGraphics.pose().scale(currentScale, currentScale, 1.0f);
            guiGraphics.pose().translate(-scaleOriginX, -scaleOriginY, 0.0);
            boolean renderOriginal = true;
            boolean renderFeed = false;
            float feedProgress = 0.0f;
            if (this.showKillFeed && elapsed > (feedStart = enterDuration * 4L)) {
                feedProgress = (float)(elapsed - feedStart) / (float)enterDuration;
                feedProgress = Math.max(0.0f, Math.min(1.0f, feedProgress));
                renderFeed = true;
                if (feedProgress >= 1.0f) {
                    renderOriginal = false;
                }
            }
            float totalScale = globalScale * this.itemScale;
            float screenAnchorX = x * globalScale;
            float pivotYScreen = scaleOriginY * globalScale;
            if (renderOriginal) {
                drawX = this.calculateDrawX(x, baseTextWidth, alignLeft, alignRight);
                if (horizontal) {
                    drawX += this.currentXOffset;
                }
                drawY = y + this.currentY;
                if (sweepEnabled) {
                    float entryProgress = (float)elapsed / (float)enterDuration;
                    entryProgress = Math.max(0.0f, Math.min(1.0f, entryProgress));
                    float screenWidthPx = (float)baseTextWidth * totalScale;
                    float screenLeft = this.calculateScreenLeft(screenAnchorX, screenWidthPx, alignLeft, alignRight);
                    float screenRight = screenLeft + screenWidthPx;
                    float entryLeft = screenRight - screenWidthPx * entryProgress;
                    float exitLeft = screenLeft + screenWidthPx * feedProgress;
                    int scLeft = (int)Math.max(entryLeft, exitLeft);
                    int scRight = (int)screenRight;
                    Font.INSTANCE.getClass();
                    int scY = (int)(pivotYScreen - 9.0f / 2.0f * totalScale);
                    Font.INSTANCE.getClass();
                    int scH = (int)(9.0f * totalScale);
                    if (scRight > scLeft) {
                        guiGraphics.enableScissor(Math.max(0, scLeft), Math.max(0, scY), scRight, scY + scH + 2);
                        guiGraphics.pose().pushPose();
                        guiGraphics.pose().translate(drawX, drawY, 0.0);
                        if (BonusListRenderer.this.enableTextBox) {
                            float f = baseTextWidth;
                            Font.INSTANCE.getClass();
                            BonusTextBox.draw(guiGraphics, f, 9.0f, BonusListRenderer.this.textBoxBorderWidth, BonusListRenderer.this.textBoxColorRgb, alphaInt);
                        }
                        BonusListRenderer.this.drawComponentWithGlow(guiGraphics, Font.INSTANCE, component, 0, 0, alphaInt);
                        guiGraphics.pose().popPose();
                        guiGraphics.disableScissor();
                    }
                } else {
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(drawX, drawY, 0.0);
                    if (BonusListRenderer.this.enableTextBox) {
                        float f = baseTextWidth;
                        Font.INSTANCE.getClass();
                        BonusTextBox.draw(guiGraphics, f, 9.0f, BonusListRenderer.this.textBoxBorderWidth, BonusListRenderer.this.textBoxColorRgb, alphaInt);
                    }
                    BonusListRenderer.this.drawComponentWithGlow(guiGraphics, Font.INSTANCE, component, 0, 0, alphaInt);
                    guiGraphics.pose().popPose();
                }
            }
            if (renderFeed && killFeedComponent != null) {
                drawX = this.calculateDrawX(x, feedTextWidth, alignLeft, alignRight);
                if (horizontal) {
                    drawX += this.currentXOffset;
                }
                drawY = y + this.currentY;
                if (sweepEnabled) {
                    float screenWidthPx = (float)feedTextWidth * totalScale;
                    float screenLeft = this.calculateScreenLeft(screenAnchorX, screenWidthPx, alignLeft, alignRight);
                    float revealRight = screenLeft + screenWidthPx * feedProgress;
                    int scLeft = (int)screenLeft;
                    int scRight = (int)revealRight;
                    Font.INSTANCE.getClass();
                    int scY = (int)(pivotYScreen - 9.0f / 2.0f * totalScale);
                    Font.INSTANCE.getClass();
                    int scH = (int)(9.0f * totalScale);
                    if (scRight > scLeft) {
                        guiGraphics.enableScissor(Math.max(0, scLeft), Math.max(0, scY), scRight, scY + scH + 2);
                        guiGraphics.pose().pushPose();
                        guiGraphics.pose().translate(drawX, drawY, 0.0);
                        if (BonusListRenderer.this.enableTextBox) {
                            float f = feedTextWidth;
                            Font.INSTANCE.getClass();
                            BonusTextBox.draw(guiGraphics, f, 9.0f, BonusListRenderer.this.textBoxBorderWidth, BonusListRenderer.this.textBoxColorRgb, alphaInt);
                        }
                        BonusListRenderer.this.drawComponentWithGlow(guiGraphics, Font.INSTANCE, killFeedComponent, 0, 0, alphaInt);
                        guiGraphics.pose().popPose();
                        guiGraphics.disableScissor();
                    }
                } else {
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(drawX, drawY, 0.0);
                    if (BonusListRenderer.this.enableTextBox) {
                        float f = feedTextWidth;
                        Font.INSTANCE.getClass();
                        BonusTextBox.draw(guiGraphics, f, 9.0f, BonusListRenderer.this.textBoxBorderWidth, BonusListRenderer.this.textBoxColorRgb, alphaInt);
                    }
                    BonusListRenderer.this.drawComponentWithGlow(guiGraphics, Font.INSTANCE, killFeedComponent, 0, 0, alphaInt);
                    guiGraphics.pose().popPose();
                }
            }
            guiGraphics.pose().popPose();
        }

        private float calculateDrawX(float x, int width, boolean alignLeft, boolean alignRight) {
            if (alignLeft) {
                return x;
            }
            if (alignRight) {
                return x - (float)width;
            }
            return x - (float)width / 2.0f;
        }

        private float calculateScreenLeft(float anchorX, float widthPx, boolean alignLeft, boolean alignRight) {
            if (alignLeft) {
                return anchorX;
            }
            if (alignRight) {
                return anchorX - widthPx;
            }
            return anchorX - widthPx / 2.0f;
        }

        private Component buildKillFeedComponent() {
            MutableComponent root = Component.empty();
            String fmt = this.killFeedFormatStr;
            float score = this.scoreStat != null ? this.scoreStat.getValue(BonusListRenderer.this.enableDigitalScroll) : 0.0f;
            Matcher m = Pattern.compile("(<weapon>|<target>|<score>)").matcher(fmt);
            int lastEnd = 0;
            while (m.find()) {
                String staticPart = fmt.substring(lastEnd, m.start());
                if (BonusListRenderer.this.cleanSubtitleContent) {
                    staticPart = BonusListRenderer.cleanSubtitleText(staticPart);
                }
                if (!staticPart.isEmpty()) {
                    root.append(Component.literal(staticPart).withStyle(Style.EMPTY.withColor(BonusListRenderer.this.normalTextColor)));
                }
                String tag = m.group(1);
                if (BonusListRenderer.this.cleanSubtitleContent && "<score>".equals(tag)) {
                    lastEnd = m.end();
                    continue;
                }
                if ("<weapon>".equals(tag)) {
                    root.append(Component.literal(this.weaponName).withStyle(Style.EMPTY.withColor(BonusListRenderer.this.normalTextColor)));
                } else if ("<target>".equals(tag)) {
                    String displayVName = this.victimName;
                    if (I18nCompat.exists(displayVName)) {
                        displayVName = I18n.format((String)displayVName, (Object[])new Object[0]);
                    }
                    root.append(Component.literal(displayVName).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(this.killFeedVictimColorVal))));
                } else if ("<score>".equals(tag)) {
                    String scoreStr = Math.abs(score) < 1.0f && Math.abs(score) > 0.001f ? String.format("%.1f", Float.valueOf(score)) : String.valueOf(Math.round(score));
                    root.append(Component.literal(scoreStr).withStyle(Style.EMPTY.withColor(BonusListRenderer.this.normalTextColor)));
                }
                lastEnd = m.end();
            }
            String tail = fmt.substring(lastEnd);
            if (BonusListRenderer.this.cleanSubtitleContent) {
                tail = BonusListRenderer.cleanSubtitleText(tail);
            }
            if (!tail.isEmpty()) {
                root.append(Component.literal(tail).withStyle(Style.EMPTY.withColor(BonusListRenderer.this.normalTextColor)));
            }
            return root;
        }

        private Component getDisplayComponent() {
            TextScrambleEffect effect;
            MutableComponent root = Component.empty();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(this.formatString);
            int lastEnd = 0;
            int scrambleIdx = 0;
            while (matcher.find()) {
                String staticPart = this.formatString.substring(lastEnd, matcher.start());
                if (BonusListRenderer.this.cleanSubtitleContent) {
                    staticPart = BonusListRenderer.cleanSubtitleText(staticPart);
                }
                if (BonusListRenderer.this.enableTextScrolling && scrambleIdx < this.scrambleEffects.size()) {
                    effect = this.scrambleEffects.get(scrambleIdx);
                    root.append(effect != null ? effect.getCurrentText() : staticPart);
                } else {
                    root.append(staticPart);
                }
                ++scrambleIdx;
                String type = matcher.group(1);
                if (BonusListRenderer.this.cleanSubtitleContent && "score".equals(type)) {
                    lastEnd = matcher.end();
                    continue;
                }
                switch (type) {
                    case "score": {
                        float score;
                        float f = score = this.scoreStat != null ? this.scoreStat.getValue(BonusListRenderer.this.enableDigitalScroll) : 0.0f;
                        if (Math.abs(score) < 1.0f && Math.abs(score) > 0.001f) {
                            root.append(String.format("%.1f", Float.valueOf(score)));
                            break;
                        }
                        root.append(String.valueOf(Math.round(score)));
                        break;
                    }
                    case "combo": {
                        int val = this.comboStat != null ? (int)this.comboStat.getValue(BonusListRenderer.this.enableDigitalScroll) : this.tryParse(this.extraData);
                        root.append(this.createStyledComponent(String.valueOf(val), this.comboStat));
                        break;
                    }
                    case "multi_kill": {
                        int val = this.mkStat != null ? (int)this.mkStat.getValue(BonusListRenderer.this.enableDigitalScroll) : this.tryParse(this.extraData);
                        String text = BonusListRenderer.getLocalizedNumber(val);
                        root.append(this.createStyledComponent(text, this.mkStat));
                        break;
                    }
                    case "distance": {
                        int val = this.distanceStat != null ? (int)this.distanceStat.getValue(BonusListRenderer.this.enableDigitalScroll) : this.tryParse(this.extraData);
                        MutableComponent c = this.createStyledComponent(String.valueOf(val), this.distanceStat);
                        root.append(c);
                        MutableComponent unit = Component.literal("m");
                        if (c.getStyle().getColor() != null) {
                            unit.setStyle(c.getStyle());
                        }
                        root.append(unit);
                        break;
                    }
                    case "streak": {
                        int val = this.streakStat != null ? (int)this.streakStat.getValue(BonusListRenderer.this.enableDigitalScroll) : this.tryParse(this.extraData);
                        root.append(this.createStyledComponent(String.valueOf(val), this.streakStat));
                        break;
                    }
                    case "extra": {
                        root.append(this.extraData);
                    }
                }
                lastEnd = matcher.end();
            }
            String lastPart = this.formatString.substring(lastEnd);
            if (BonusListRenderer.this.cleanSubtitleContent) {
                lastPart = BonusListRenderer.cleanSubtitleText(lastPart);
            }
            if (BonusListRenderer.this.enableTextScrolling && scrambleIdx < this.scrambleEffects.size()) {
                effect = this.scrambleEffects.get(scrambleIdx);
                root.append(effect != null ? effect.getCurrentText() : lastPart);
            } else {
                root.append(lastPart);
            }
            if (BonusListRenderer.this.enableStackMultiplier && this.stackCount > 1) {
                root.append(Component.literal(" \u00d7" + this.stackCount).withStyle(Style.EMPTY.withColor(BonusListRenderer.this.normalTextColor)));
            }
            return root;
        }

        private MutableComponent createStyledComponent(String text, AnimatedStat stat) {
            float flash;
            MutableComponent comp = Component.literal(text);
            int baseColor = this.specialColor != 0 ? this.specialColor : BonusListRenderer.this.normalTextColor;
            float f = flash = stat != null ? stat.flashAlpha : 0.0f;
            if (flash > 0.0f) {
                int color = BonusListRenderer.this.interpolateColor(baseColor, 0xFFFFFF, flash);
                comp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            } else if (baseColor != 0xFFFFFF) {
                comp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(baseColor)));
            }
            return comp;
        }

        private int tryParse(String s) {
            try {
                return Integer.parseInt(s);
            }
            catch (Exception e) {
                return 0;
            }
        }
    }

    private static class AnimatedStat {
        final DigitalScrollEffect effect;
        float targetValue;
        float flashAlpha;
        float lastDisplayed;

        AnimatedStat(float initial, float duration, float refreshRate) {
            this.effect = new DigitalScrollEffect(duration, refreshRate, DigitalScrollEffect.Easing.CUBIC_OUT);
            this.targetValue = initial;
            this.effect.startAnimation(0.0f, initial);
            this.lastDisplayed = 0.0f;
            this.flashAlpha = 0.0f;
        }

        void add(float amount) {
            this.targetValue += amount;
            this.effect.startAnimation(this.effect.getCurrentValue(), this.targetValue);
        }

        void updateMax(float newVal) {
            if (newVal > this.targetValue) {
                this.targetValue = newVal;
                this.effect.startAnimation(this.effect.getCurrentValue(), this.targetValue);
            }
        }

        void update(long now, float dt) {
            this.effect.update(now);
            float current = this.effect.getCurrentValue();
            if (Math.abs(current - this.lastDisplayed) >= 0.1f) {
                this.flashAlpha = 1.0f;
                this.lastDisplayed = current;
            }
            if (this.flashAlpha > 0.0f) {
                this.flashAlpha = Math.max(0.0f, this.flashAlpha - dt * 5.0f);
            }
        }

        float getValue(boolean enableScroll) {
            return enableScroll ? this.effect.getCurrentValue() : this.targetValue;
        }
    }

    private static final class ParsedData {
        private final float score;
        private final String extraData;
        private final String victimName;

        public ParsedData(float score, String extraData, String victimName) {
            this.score = score;
            this.extraData = extraData;
            this.victimName = victimName;
        }

        public float score() {
            return this.score;
        }

        public String extraData() {
            return this.extraData;
        }

        public String victimName() {
            return this.victimName;
        }

        static ParsedData parse(String data) {
            float score = 0.0f;
            String extraData = "";
            String victimName = null;
            if (data != null && !data.isEmpty()) {
                String[] parts = data.split("\\|", -1);
                if (parts.length > 0) {
                    try {
                        score = Float.parseFloat(parts[0]);
                    }
                    catch (NumberFormatException e) {
                        score = ParsedData.parseScore(parts[0]);
                    }
                }
                if (parts.length > 1) {
                    extraData = parts[1];
                }
                if (parts.length > 2) {
                    victimName = parts[2];
                }
            }
            return new ParsedData(score, extraData, victimName);
        }

        private static float parseScore(String data) {
            try {
                Matcher matcher = Pattern.compile("\\+([\\d.]+)").matcher(data);
                if (matcher.find()) {
                    return Float.parseFloat(matcher.group(1));
                }
                matcher = Pattern.compile("([\\d.]+)").matcher(data);
                String lastMatch = null;
                while (matcher.find()) {
                    lastMatch = matcher.group(1);
                }
                if (lastMatch != null) {
                    return Float.parseFloat(lastMatch);
                }
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
            return 0.0f;
        }
    }
}

