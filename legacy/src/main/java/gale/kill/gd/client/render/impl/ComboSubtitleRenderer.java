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
import gale.kill.gd.client.config.FormatDefaultsManager;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.TextFadeEffect;
import gale.kill.gd.client.stats.ClientStatsManager;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.compat.BufferBuilder;
import gale.kill.gd.compat.Component;
import gale.kill.gd.compat.DefaultVertexFormat;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GameRenderer;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.MutableComponent;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.RenderSystem;
import gale.kill.gd.compat.Tesselator;
import gale.kill.gd.compat.VertexFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

public class ComboSubtitleRenderer
implements IHudRenderer {
    private static final long FADE_IN_DURATION = 200L;
    private static final long FADE_OUT_DURATION = 200L;
    private static final long EXIT_ANIMATION_DURATION = 500L;
    private static final long LIGHT_SCAN_DURATION = 400L;
    private static final float LIGHT_SCAN_DISTANCE = 20.0f;
    private static final long LIGHT_STRIP_FADE_DELAY = 200L;
    private static final long LIGHT_STRIP_FADE_OUT_DURATION = 200L;
    private static ComboSubtitleRenderer instance;
    private boolean visible = true;
    private float scale = 1.5f;
    private int xOffset = 0;
    private int yOffset = 150;
    private String screenAnchor = "bottom_center";
    private int colorKillCombo = 0xFF0000;
    private int colorAssistCombo = 16766720;
    private String formatKillSingle = "";
    private String formatKillMulti = "";
    private String formatAssistSingle = "";
    private String formatAssistMulti = "";
    private boolean enableAnimation = true;
    private boolean enableLightEffect = true;
    private boolean enableBold = true;
    private boolean enableTextShadow = true;
    private boolean configBlinkFadeAnimation = false;
    private double lightHeight = 12.0;
    private double lightHoldDuration = 0.2;
    private boolean enableScaleAnimation = false;
    private long displayDuration = 5000L;
    private String resetKillCombo = "death";
    private String resetAssistCombo = "death";
    private float comboResetTimeout = 10.0f;
    private long startTime = -1L;
    private boolean isVisible = false;
    private int currentCombo = 0;
    private boolean isAssist = false;
    private final Deque<ComboItem> pendingQueue = new ArrayDeque<ComboItem>();
    private long lastDequeueTime = 0L;
    private int localKillComboCount = 0;
    private int localAssistComboCount = 0;
    private long lastKillTime = 0L;
    private long lastAssistTime = 0L;
    private float lastScanX = 0.0f;

    private ComboSubtitleRenderer() {
    }

    public static synchronized ComboSubtitleRenderer getInstance() {
        if (instance == null) {
            instance = new ComboSubtitleRenderer();
        }
        return instance;
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        int newCombo;
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "combo");
        if (config2 == null) {
            return;
        }
        this.loadConfig(config2);
        if (!this.visible) {
            this.isVisible = false;
            return;
        }
        long now = PreviewRenderTimeContext.currentTimeMillis();
        if (context.type() == 5) {
            return;
        }
        this.isAssist = context.type() == 4;
        this.checkResetTimeout(now);
        boolean isAssistEvent = this.isAssist;
        if (this.isAssist) {
            if ("server".equals(this.resetAssistCombo)) {
                this.localAssistComboCount = newCombo = context.comboCount();
            } else if ("never".equals(this.resetAssistCombo)) {
                this.localAssistComboCount = newCombo = (int)ClientStatsManager.getTotalAssists() + 1;
            } else {
                ++this.localAssistComboCount;
                newCombo = this.localAssistComboCount;
                this.lastAssistTime = now;
            }
        } else if ("server".equals(this.resetKillCombo)) {
            this.localKillComboCount = newCombo = context.comboCount();
        } else if ("never".equals(this.resetKillCombo)) {
            this.localKillComboCount = newCombo = (int)ClientStatsManager.getTotalKills();
        } else {
            ++this.localKillComboCount;
            newCombo = this.localKillComboCount;
            this.lastKillTime = now;
        }
        if (this.pendingQueue.size() < 10) {
            this.pendingQueue.add(new ComboItem(newCombo, isAssistEvent));
        }
        this.isVisible = true;
    }

    public void resetKillCombo() {
        this.localKillComboCount = 0;
    }

    public void resetAssistCombo() {
        this.localAssistComboCount = 0;
    }

    public void onPlayerDeath() {
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "combo");
        if (config2 != null) {
            this.loadConfig(config2);
        }
        boolean resetKill = "death".equals(this.resetKillCombo);
        boolean resetAssist = "death".equals(this.resetAssistCombo);
        if (resetKill) {
            this.localKillComboCount = 0;
            this.lastKillTime = 0L;
        }
        if (resetAssist) {
            this.localAssistComboCount = 0;
            this.lastAssistTime = 0L;
        }
        if (resetKill || resetAssist) {
            this.pendingQueue.clear();
            this.currentCombo = 0;
            this.isVisible = false;
            this.startTime = -1L;
            this.lastDequeueTime = 0L;
            this.lastScanX = 0.0f;
        }
    }

    public void onPlayerLogout() {
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "combo");
        if (config2 != null) {
            this.loadConfig(config2);
        }
        if (!"never".equals(this.resetKillCombo)) {
            this.localKillComboCount = 0;
            this.lastKillTime = 0L;
        }
        if (!"never".equals(this.resetAssistCombo)) {
            this.localAssistComboCount = 0;
            this.lastAssistTime = 0L;
        }
        this.pendingQueue.clear();
        this.currentCombo = 0;
        this.isVisible = false;
        this.startTime = -1L;
        this.lastDequeueTime = 0L;
        this.lastScanX = 0.0f;
    }

    private void checkResetTimeout(long now) {
        long timeoutMs = (long)(this.comboResetTimeout * 1000.0f);
        if ("time".equals(this.resetKillCombo) && now - this.lastKillTime > timeoutMs) {
            this.localKillComboCount = 0;
        }
        if ("time".equals(this.resetAssistCombo) && now - this.lastAssistTime > timeoutMs) {
            this.localAssistComboCount = 0;
        }
    }

    public void triggerPreview(boolean assist, int combo) {
        JsonObject config2 = ConfigManager.getElementConfig("subtitle", "combo");
        if (config2 == null) {
            return;
        }
        this.loadConfig(config2);
        if (!this.visible) {
            this.isVisible = false;
            return;
        }
        this.isAssist = assist;
        this.currentCombo = combo;
        this.startTime = PreviewRenderTimeContext.currentTimeMillis();
        this.isVisible = true;
        this.pendingQueue.clear();
        if (this.enableLightEffect) {
            this.lastScanX = 0.0f;
        }
    }

    @Override
    public void resetPreview() {
        this.pendingQueue.clear();
        this.isVisible = false;
        this.startTime = -1L;
        this.currentCombo = 0;
        this.isAssist = false;
        this.lastDequeueTime = 0L;
        this.lastScanX = 0.0f;
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        this.processQueue();
        RenderState state = this.resolveRenderState();
        if (state == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        int centerX = ScreenAnchor.resolveCenterX(this.screenAnchor, this.xOffset, screenWidth);
        int centerY = ScreenAnchor.resolveCenterY(this.screenAnchor, this.yOffset, screenHeight);
        this.renderInternal(guiGraphics, partialTick, centerX, centerY, state);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float x, float y) {
        this.processQueue();
        RenderState state = this.resolveRenderState();
        if (state == null) {
            return;
        }
        this.renderInternal(guiGraphics, partialTick, (int)x, (int)y, state);
    }

    private void processQueue() {
        ComboItem item;
        long now = PreviewRenderTimeContext.currentTimeMillis();
        if (this.pendingQueue.isEmpty()) {
            return;
        }
        if (now - this.lastDequeueTime >= 200L && (item = this.pendingQueue.poll()) != null) {
            this.currentCombo = item.combo;
            this.isAssist = item.isAssist;
            this.startTime = now;
            this.lastDequeueTime = now;
            this.isVisible = true;
            if (this.enableLightEffect) {
                this.lastScanX = 0.0f;
            }
        }
    }

    private RenderState resolveRenderState() {
        if (!this.isVisible || this.startTime == -1L) {
            return null;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        long elapsed = currentTime - this.startTime;
        if (elapsed > this.displayDuration + 500L) {
            this.isVisible = false;
            this.startTime = -1L;
            return null;
        }
        float alpha = 1.0f;
        if (this.enableAnimation) {
            if (elapsed < 200L) {
                alpha = (float)elapsed / 200.0f;
            } else if (elapsed > this.displayDuration) {
                alpha = TextFadeEffect.fadeAlpha((float)(elapsed - this.displayDuration) / 500.0f, this.configBlinkFadeAnimation);
            }
        }
        if ((alpha = Mth.clamp(alpha, 0.0f, 1.0f)) <= 0.05f && elapsed > this.displayDuration) {
            this.isVisible = false;
            this.startTime = -1L;
            return null;
        }
        float currentScale = this.scale;
        if (this.enableAnimation && this.enableScaleAnimation && elapsed < 200L) {
            float progress = (float)elapsed / 200.0f;
            float easedProgress = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
            currentScale = Mth.lerp(easedProgress, this.scale * 1.5f, this.scale);
        }
        return new RenderState(elapsed, alpha, currentScale);
    }

    private void renderInternal(GuiGraphics guiGraphics, float partialTick, int centerX, int centerY, RenderState state) {
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        String formatKey = this.isAssist ? (this.currentCombo > 1 ? this.formatAssistMulti : this.formatAssistSingle) : (this.currentCombo > 1 ? this.formatKillMulti : this.formatKillSingle);
        String format = formatKey;
        String text = format.replace("<combo>", String.valueOf(this.currentCombo));
        int color = this.isAssist ? this.colorAssistCombo : this.colorKillCombo;
        int textWidth = font.width(text);
        int textHeight = font.lineHeight;
        float textHalfWidth = (float)textWidth / 2.0f;
        float textHalfHeight = (float)textHeight / 2.0f;
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, 0.0);
        poseStack.scale(state.currentScale, state.currentScale, 1.0f);
        if (this.enableLightEffect) {
            this.renderLightEffect(guiGraphics, poseStack, state.elapsed, textWidth, textHeight);
        }
        int alphaInt = (int)(state.alpha * 255.0f);
        int finalColor = color & 0xFFFFFF | alphaInt << 24;
        if (this.enableBold) {
            MutableComponent comp = Component.literal(text).withStyle(style -> style.withBold(true));
            textWidth = font.width(comp);
            textHalfWidth = (float)textWidth / 2.0f;
            guiGraphics.drawString(font, comp, (int)(-textHalfWidth), (int)(-textHalfHeight), finalColor, this.enableTextShadow);
        } else {
            guiGraphics.drawString(font, text, (int)(-textHalfWidth), (int)(-textHalfHeight), finalColor, this.enableTextShadow);
        }
        poseStack.popPose();
    }

    private void renderLightEffect(GuiGraphics guiGraphics, PoseStack poseStack, long elapsed, int width, int height) {
        long holdDurationMs = (long)(this.lightHoldDuration * 1000.0);
        if (elapsed > 400L + holdDurationMs + 200L) {
            return;
        }
        float currentScanX = 20.0f;
        if (elapsed < 400L) {
            float progress;
            float t = progress = (float)elapsed / 400.0f;
            float ease = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
            currentScanX = ease * 20.0f;
        }
        float baseAlpha = 1.0f;
        if (elapsed > 400L + holdDurationMs) {
            long fadeOutElapsed = elapsed - (400L + holdDurationMs);
            if (fadeOutElapsed < 200L) {
                float fadeOutProgress = (float)fadeOutElapsed / 200.0f;
                baseAlpha = Mth.lerp(fadeOutProgress, 1.0f, 0.0f);
            } else {
                baseAlpha = 0.0f;
            }
        }
        if (baseAlpha <= 0.01f) {
            return;
        }
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float halfHeight = (float)this.lightHeight / 2.0f;
        float yOffset = 1.0f;
        int r = 255;
        int g = 255;
        int b = 255;
        int aCenter = (int)(200.0f * baseAlpha);
        int aEdge = 0;
        buffer.vertex(poseStack.last().pose(), 0.0f, -halfHeight + yOffset, 0.0f).color(r, g, b, aCenter).endVertex();
        buffer.vertex(poseStack.last().pose(), -currentScanX, -halfHeight + yOffset, 0.0f).color(r, g, b, aEdge).endVertex();
        buffer.vertex(poseStack.last().pose(), -currentScanX, halfHeight + yOffset, 0.0f).color(r, g, b, aEdge).endVertex();
        buffer.vertex(poseStack.last().pose(), 0.0f, halfHeight + yOffset, 0.0f).color(r, g, b, aCenter).endVertex();
        buffer.vertex(poseStack.last().pose(), currentScanX, -halfHeight + yOffset, 0.0f).color(r, g, b, aEdge).endVertex();
        buffer.vertex(poseStack.last().pose(), 0.0f, -halfHeight + yOffset, 0.0f).color(r, g, b, aCenter).endVertex();
        buffer.vertex(poseStack.last().pose(), 0.0f, halfHeight + yOffset, 0.0f).color(r, g, b, aCenter).endVertex();
        buffer.vertex(poseStack.last().pose(), currentScanX, halfHeight + yOffset, 0.0f).color(r, g, b, aEdge).endVertex();
        tesselator.end();
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.visible = config2.has("visible") ? config2.get("visible").getAsBoolean() : true;
            this.scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.5f;
            this.xOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.yOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 150;
            this.screenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            this.colorKillCombo = this.parseColor(config2, "color_kill_combo", 0xFF0000);
            this.colorAssistCombo = this.parseColor(config2, "color_assist_combo", 16766720);
            this.formatKillSingle = this.getFormat(config2, "format_kill_single", FormatDefaultsManager.getDefault("subtitle/combo", "format_kill_single"));
            this.formatKillMulti = this.getFormat(config2, "format_kill_multi", FormatDefaultsManager.getDefault("subtitle/combo", "format_kill_multi"));
            this.formatAssistSingle = this.getFormat(config2, "format_assist_single", FormatDefaultsManager.getDefault("subtitle/combo", "format_assist_single"));
            this.formatAssistMulti = this.getFormat(config2, "format_assist_multi", FormatDefaultsManager.getDefault("subtitle/combo", "format_assist_multi"));
            this.enableAnimation = config2.has("enable_animation") ? config2.get("enable_animation").getAsBoolean() : true;
            this.enableLightEffect = config2.has("enable_light_effect") ? config2.get("enable_light_effect").getAsBoolean() : true;
            this.enableBold = config2.has("enable_bold") ? config2.get("enable_bold").getAsBoolean() : true;
            this.enableTextShadow = !config2.has("enable_text_shadow") || config2.get("enable_text_shadow").getAsBoolean();
            this.configBlinkFadeAnimation = config2.has("blink_fade_animation") && config2.get("blink_fade_animation").getAsBoolean();
            this.lightHeight = config2.has("light_height") ? config2.get("light_height").getAsDouble() : 12.0;
            this.lightHoldDuration = config2.has("light_hold_duration") ? config2.get("light_hold_duration").getAsDouble() : 0.2;
            this.enableScaleAnimation = config2.has("enable_scale_animation") ? config2.get("enable_scale_animation").getAsBoolean() : false;
            this.displayDuration = config2.has("display_duration") ? (long)(config2.get("display_duration").getAsFloat() * 1000.0f) : 5000L;
            this.resetKillCombo = config2.has("reset_kill_combo") ? config2.get("reset_kill_combo").getAsString() : "death";
            this.resetAssistCombo = config2.has("reset_assist_combo") ? config2.get("reset_assist_combo").getAsString() : "death";
            this.comboResetTimeout = config2.has("combo_reset_timeout") ? config2.get("combo_reset_timeout").getAsFloat() : 10.0f;
        }
        catch (Exception e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.config_error", new Object[0]);
        }
    }

    private int parseColor(JsonObject config2, String key, int def) {
        if (!config2.has(key)) {
            return def;
        }
        String hex = config2.get(key).getAsString();
        try {
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            return Integer.parseInt(hex, 16);
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    private String getFormat(JsonObject config2, String key, String def) {
        if (!config2.has(key)) {
            return def;
        }
        return config2.get(key).getAsString();
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

    private static class ComboItem {
        int combo;
        boolean isAssist;

        ComboItem(int combo, boolean isAssist) {
            this.combo = combo;
            this.isAssist = isAssist;
        }
    }
}

