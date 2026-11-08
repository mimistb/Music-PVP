/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.scoreboard.ScorePlayerTeam
 *  net.minecraft.scoreboard.Team
 *  net.minecraft.util.EnumChatFormatting
 *  net.minecraft.util.ResourceLocation
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
import gale.kill.gd.client.render.impl.ComboIconRenderer;
import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.compat.Axis;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.RenderSystem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

public class CardRenderer
implements IHudRenderer {
    private float configScale = 1.0f;
    private int configXOffset = 0;
    private int configYOffset = 0;
    private String configScreenAnchor = "bottom_center";
    private float animationDuration = 0.75f;
    private String colorTextCt = "9cc1eb";
    private String colorTextT = "d9ac5b";
    private float textScale = 1.0f;
    private String team = "ct";
    private boolean dynamicCardStyle = false;
    private int maxStackCount = 5;
    private boolean enableIconGlow = false;
    private int iconGlowColor = 0xFFFFFF;
    private float iconGlowIntensity = 0.45f;
    private float iconGlowSize = 4.0f;
    private JsonObject currentConfig;
    private static final int CARD_SIZE = 256;
    private static final float MOVE_DISTANCE_MULTIPLIER = 0.9f;
    private final List<CardInstance> activeCards = new ArrayList<CardInstance>();
    private PendingTrigger pendingTrigger;

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "card");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            this.activeCards.clear();
            this.pendingTrigger = null;
            return;
        }
        this.loadConfig(config2);
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float standardX = ScreenAnchor.resolveCenterX(this.configScreenAnchor, this.configXOffset, screenWidth);
        float standardY = ScreenAnchor.resolveCenterY(this.configScreenAnchor, this.configYOffset, screenHeight);
        this.renderInternal(guiGraphics, partialTick, standardX, standardY);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float standardX, float standardY) {
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "card");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            this.activeCards.clear();
            this.pendingTrigger = null;
            return;
        }
        this.loadConfig(config2);
        this.renderInternal(guiGraphics, partialTick, standardX, standardY);
    }

    public void renderPreviewAt(GuiGraphics guiGraphics, float partialTick, float standardX, float standardY, JsonObject config2) {
        if (config2 == null) {
            this.activeCards.clear();
            this.pendingTrigger = null;
            return;
        }
        this.loadConfig(config2);
        this.renderInternal(guiGraphics, partialTick, standardX, standardY);
    }

    private void renderInternal(GuiGraphics guiGraphics, float partialTick, float standardX, float standardY) {
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        long displayDuration = this.resolveDisplayDuration();
        long animDurMs = (long)(this.animationDuration * 1000.0f);
        Minecraft mc = Minecraft.getMinecraft();
        Iterator<CardInstance> it = this.activeCards.iterator();
        while (it.hasNext()) {
            long transitionElapsed;
            long exitElapsed;
            CardInstance card = it.next();
            long age = currentTime - card.spawnTime;
            if (card.state == CardState.ENTERING) {
                if (age >= animDurMs) {
                    card.state = CardState.DISPLAYING;
                }
            } else if (card.state == CardState.EXITING && (exitElapsed = currentTime - card.exitStartTime) > animDurMs) {
                it.remove();
                continue;
            }
            if (!card.isPendingRemoval || (transitionElapsed = currentTime - card.lastLayoutUpdateTime) < animDurMs) continue;
            it.remove();
        }
        if (this.activeCards.isEmpty() && this.pendingTrigger != null) {
            CardInstance newCard = new CardInstance(this.pendingTrigger.killType, this.pendingTrigger.comboCount, currentTime);
            this.activeCards.add(newCard);
            this.pendingTrigger = null;
            this.updateLayout(currentTime);
        }
        if (!this.activeCards.isEmpty()) {
            long newestAge;
            CardInstance newest = this.activeCards.get(this.activeCards.size() - 1);
            if (newest.state == CardState.DISPLAYING && (newestAge = currentTime - newest.spawnTime) > displayDuration) {
                long exitTime = currentTime;
                for (CardInstance card : this.activeCards) {
                    if (card.state == CardState.EXITING) continue;
                    card.startExit(exitTime);
                }
            }
        }
        for (CardInstance card : this.activeCards) {
            this.renderCard(guiGraphics, card, currentTime, standardX, standardY, animDurMs, mc);
        }
    }

    private void renderCard(GuiGraphics guiGraphics, CardInstance card, long currentTime, float standardX, float standardY, long animDurMs, Minecraft mc) {
        Team pt;
        long exitElapsed;
        float progress;
        long elapsed = currentTime - card.spawnTime;
        float maxDist = 256.0f * this.configScale * 0.9f;
        float currentDist = 0.0f;
        if (card.state == CardState.ENTERING) {
            float progress2 = (float)elapsed / (float)animDurMs;
            progress2 = Mth.clamp(progress2, 0.0f, 1.0f);
            float moveProgress = this.calculateSegmentedEaseOut(progress2);
            currentDist = moveProgress * maxDist;
        } else if (card.state == CardState.EXITING) {
            long exitElapsed2 = currentTime - card.exitStartTime;
            float progress3 = (float)exitElapsed2 / (float)animDurMs;
            progress3 = Mth.clamp(progress3, 0.0f, 1.0f);
            float moveProgress = this.calculateSegmentedEaseIn(progress3);
            currentDist = Mth.lerp(moveProgress, maxDist, 0.0f);
        } else {
            currentDist = maxDist;
        }
        float currentAngle = card.targetAngle;
        if (currentTime - card.lastLayoutUpdateTime < animDurMs) {
            float layoutProgress = (float)(currentTime - card.lastLayoutUpdateTime) / (float)animDurMs;
            layoutProgress = Mth.clamp(layoutProgress, 0.0f, 1.0f);
            float angleEase = this.calculateSegmentedEaseOut(layoutProgress);
            currentAngle = Mth.lerp(angleEase, card.startAngle, card.targetAngle);
        }
        card.currentAngle = currentAngle;
        double rad = Math.toRadians(currentAngle);
        float offsetX = (float)Math.sin(rad) * currentDist;
        float offsetY = (float)(-Math.cos(rad)) * currentDist;
        float renderX = standardX + offsetX;
        float renderY = standardY + offsetY;
        float alpha = 1.0f;
        if (card.state == CardState.ENTERING) {
            float fadeDur = (float)animDurMs / 3.0f;
            if ((float)elapsed < fadeDur) {
                alpha = (float)elapsed / fadeDur;
            }
        } else if (card.state == CardState.EXITING && (progress = (float)(exitElapsed = currentTime - card.exitStartTime) / (float)animDurMs) > 0.66f) {
            float fadeP = (progress - 0.66f) / 0.33f;
            alpha = 1.0f - fadeP;
        }
        float lightAlpha = 0.0f;
        float lightScale = 1.0f;
        long lightTotalDur = animDurMs * 5L;
        if (elapsed < lightTotalDur) {
            float scaleP;
            float lightP = (float)elapsed / (float)lightTotalDur;
            lightAlpha = 1.0f - lightP * lightP;
            long scaleUpDur = animDurMs / 3L;
            if (elapsed < scaleUpDur) {
                scaleP = (float)elapsed / (float)scaleUpDur;
                lightScale = Mth.lerp(scaleP, 0.5f, 1.0f);
            } else {
                scaleP = (float)(elapsed - scaleUpDur) / (float)(lightTotalDur - scaleUpDur);
                lightScale = Mth.lerp(scaleP, 1.0f, 0.6f);
            }
        }
        String currentTeam = this.team;
        if (this.dynamicCardStyle && mc.thePlayer != null && (pt = mc.thePlayer.getTeam()) != null) {
            EnumChatFormatting color = ((ScorePlayerTeam)pt).getChatFormat();
            if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA || color == EnumChatFormatting.DARK_BLUE) {
                currentTeam = "ct";
            } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                currentTeam = "t";
            }
        }
        boolean isT = "t".equalsIgnoreCase(currentTeam);
        String cardTextureKey = this.getCardTextureKey(card.killType, isT);
        String lightTextureKey = isT ? "light_t" : "light_ct";
        float cardFocusMultiplier = PreviewTextureFocusContext.alphaMultiplier("kill_icon/card", cardTextureKey);
        float lightFocusMultiplier = PreviewTextureFocusContext.alphaMultiplier("kill_icon/card", lightTextureKey);
        String cardTextureName = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/card", cardTextureKey, this.currentConfig);
        String lightTextureName = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/card", lightTextureKey, this.currentConfig);
        ResourceLocation cardTexture = ExternalTextureManager.getTexture(cardTextureName);
        ResourceLocation lightTexture = ExternalTextureManager.getTexture(lightTextureName);
        if (cardTexture == null) {
            return;
        }
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(0.0, 0.0, this.activeCards.indexOf(card));
        if (lightTexture != null && lightAlpha > 0.01f) {
            float lightWidthRatio = this.resolveFrameRatio(lightTextureKey, "texture_frame_width_ratio");
            float lightHeightRatio = this.resolveFrameRatio(lightTextureKey, "texture_frame_height_ratio");
            float lightW = 256.0f * this.configScale * lightWidthRatio;
            float lightH = 256.0f * this.configScale * lightHeightRatio;
            poseStack.pushPose();
            poseStack.translate(renderX, renderY, 0.0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(currentAngle));
            poseStack.translate(0.0, 256.0f * this.configScale / 2.0f, 0.0);
            poseStack.scale(lightScale, lightScale, 1.0f);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, lightAlpha * alpha * lightFocusMultiplier);
            guiGraphics.blit(lightTexture, (int)(-lightW / 2.0f), (int)(-lightH), (int)lightW, (int)lightH, 0.0f, 0.0f, (int)lightW, (int)lightH, (int)lightW, (int)lightH);
            poseStack.popPose();
        }
        poseStack.pushPose();
        poseStack.translate(renderX, renderY, 0.0);
        poseStack.mulPose(Axis.ZP.rotationDegrees(currentAngle));
        poseStack.scale(this.configScale, this.configScale, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha * cardFocusMultiplier);
        float cardWidthRatio = this.resolveFrameRatio(cardTextureKey, "texture_frame_width_ratio");
        float cardHeightRatio = this.resolveFrameRatio(cardTextureKey, "texture_frame_height_ratio");
        int drawWidth = Math.round(256.0f * cardWidthRatio);
        int drawHeight = Math.round(256.0f * cardHeightRatio);
        if (this.enableIconGlow) {
            IconGlowRenderEffect.drawGlowFrame(guiGraphics, cardTexture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0, 0, drawWidth, drawHeight, drawWidth, drawHeight, alpha * cardFocusMultiplier, this.iconGlowColor, this.iconGlowIntensity, this.iconGlowSize);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha * cardFocusMultiplier);
        }
        guiGraphics.blit(cardTexture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
        float flashAlpha = 0.0f;
        long flashHold = animDurMs / 2L;
        long flashFade = animDurMs * 4L;
        if (elapsed < flashHold) {
            flashAlpha = 1.0f;
        } else {
            long flashElapsed = elapsed - flashHold;
            if (flashElapsed < flashFade) {
                flashAlpha = 1.0f - (float)flashElapsed / (float)flashFade;
            }
        }
        if (flashAlpha > 0.01f) {
            RenderSystem.blendFunc(770, 1);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, flashAlpha * alpha);
            guiGraphics.blit(cardTexture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
            if (flashAlpha > 0.5f) {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, (flashAlpha - 0.5f) * 2.0f * alpha);
                guiGraphics.blit(cardTexture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
            }
            RenderSystem.defaultBlendFunc();
        }
        if (card.comboCount > 0) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            String text = String.valueOf(card.comboCount);
            Font font = Font.INSTANCE;
            String colorHex = isT ? this.colorTextT : this.colorTextCt;
            int color = this.parseColor(colorHex);
            int alphaInt = (int)(alpha * 255.0f);
            int finalColor = color & 0xFFFFFF | alphaInt << 24;
            poseStack.pushPose();
            poseStack.scale(this.textScale, this.textScale, 1.0f);
            int textWidth = font.width(text);
            guiGraphics.drawString(font, text, -textWidth / 2, -font.lineHeight / 2, finalColor, true);
            poseStack.popPose();
        }
        poseStack.popPose();
        poseStack.popPose();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float calculateSegmentedEaseOut(float t) {
        if (t < 0.5f) {
            return t * 1.8f;
        }
        float t2 = (t - 0.5f) * 2.0f;
        return 0.9f + (1.0f - (float)Math.pow(1.0f - t2, 3.0)) * 0.1f;
    }

    private float calculateSegmentedEaseIn(float t) {
        if (t < 0.5f) {
            float t2 = t * 2.0f;
            return t2 * t2 * 0.2f;
        }
        return 0.2f + (t - 0.5f) * 1.6f;
    }

    private void loadConfig(JsonObject config2) {
        this.currentConfig = config2;
        this.configScale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
        this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
        this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 0;
        this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
        this.team = config2.has("team") ? config2.get("team").getAsString() : "ct";
        this.animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 0.75f;
        this.colorTextCt = config2.has("color_text_ct") ? config2.get("color_text_ct").getAsString() : "9cc1eb";
        this.colorTextT = config2.has("color_text_t") ? config2.get("color_text_t").getAsString() : "d9ac5b";
        this.textScale = config2.has("text_scale") ? config2.get("text_scale").getAsFloat() : 1.0f;
        this.dynamicCardStyle = config2.has("dynamic_card_style") && config2.get("dynamic_card_style").getAsBoolean();
        this.maxStackCount = config2.has("max_stack_count") ? config2.get("max_stack_count").getAsInt() : 5;
        this.enableIconGlow = IconGlowRenderEffect.isEnabled(config2);
        this.iconGlowColor = IconGlowRenderEffect.resolveColor(config2);
        this.iconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
        this.iconGlowSize = IconGlowRenderEffect.resolveSize(config2);
    }

    private long resolveDisplayDuration() {
        long serverDuration = ComboIconRenderer.getServerComboWindowMs();
        if (serverDuration > 0L) {
            return serverDuration;
        }
        return 3000L;
    }

    private String getCardTextureKey(int killType, boolean isT) {
        String base;
        switch (killType) {
            case 1: {
                base = "headshot";
                break;
            }
            case 2: {
                base = "explosion";
                break;
            }
            case 3: {
                base = "crit";
                break;
            }
            default: {
                base = "default";
            }
        }
        return isT ? base + "_t" : base + "_ct";
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

    private int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", ""), 16);
        }
        catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        if (context.type() == 4 || context.type() == 5) {
            return;
        }
        if (context.comboCount() == 1 && !this.activeCards.isEmpty()) {
            long now = PreviewRenderTimeContext.currentTimeMillis();
            for (CardInstance card : this.activeCards) {
                if (card.state == CardState.EXITING) continue;
                card.startExit(now);
            }
            this.pendingTrigger = new PendingTrigger(context.type(), context.comboCount());
            return;
        }
        if (!this.activeCards.isEmpty()) {
            CardInstance newest = this.activeCards.get(this.activeCards.size() - 1);
            if (newest.state == CardState.EXITING) {
                this.activeCards.clear();
                this.pendingTrigger = null;
            }
        }
        long now = PreviewRenderTimeContext.currentTimeMillis();
        CardInstance newCard = new CardInstance(context.type(), context.comboCount(), now);
        this.activeCards.add(newCard);
        this.updateLayout(now);
    }

    private void updateLayout(long now) {
        boolean isStackingMode;
        int count = this.activeCards.size();
        if (count == 0) {
            return;
        }
        CardInstance newest = this.activeCards.get(count - 1);
        boolean bl = isStackingMode = newest.comboCount >= this.maxStackCount;
        if (isStackingMode) {
            for (int i = 0; i < count; ++i) {
                CardInstance card = this.activeCards.get(i);
                if (card.targetAngle != 0.0f) {
                    card.startAngle = card.currentAngle;
                    card.targetAngle = 0.0f;
                    card.lastLayoutUpdateTime = now;
                }
                if (i >= count - 1 || card.isPendingRemoval) continue;
                card.isPendingRemoval = true;
                card.lastLayoutUpdateTime = now;
            }
        } else {
            float centerIndex = (float)(count - 1) / 2.0f;
            for (int i = 0; i < count; ++i) {
                CardInstance card = this.activeCards.get(i);
                float newTarget = ((float)i - centerIndex) * 10.0f;
                if (!(Math.abs(card.targetAngle - newTarget) > 0.01f) && (i != count - 1 || card.state != CardState.ENTERING)) continue;
                if (i == count - 1) {
                    card.targetAngle = newTarget;
                    card.currentAngle = newTarget;
                    card.startAngle = newTarget;
                    continue;
                }
                card.startAngle = card.currentAngle;
                card.targetAngle = newTarget;
                card.lastLayoutUpdateTime = now;
            }
        }
    }

    public static void updateServerComboWindowSeconds(double seconds) {
        ComboIconRenderer.updateServerComboWindowSeconds(seconds);
    }

    private static class CardInstance {
        final int killType;
        final int comboCount;
        final long spawnTime;
        CardState state = CardState.ENTERING;
        long exitStartTime = -1L;
        float currentAngle = 0.0f;
        float startAngle = 0.0f;
        float targetAngle = 0.0f;
        long lastLayoutUpdateTime = 0L;
        boolean isPendingRemoval = false;

        CardInstance(int killType, int comboCount, long spawnTime) {
            this.killType = killType;
            this.comboCount = comboCount;
            this.spawnTime = spawnTime;
        }

        void startExit(long currentTime) {
            if (this.state != CardState.EXITING) {
                this.state = CardState.EXITING;
                this.exitStartTime = currentTime;
            }
        }
    }

    private static class PendingTrigger {
        final int killType;
        final int comboCount;

        PendingTrigger(int killType, int comboCount) {
            this.killType = killType;
            this.comboCount = comboCount;
        }
    }

    private static enum CardState {
        ENTERING,
        DISPLAYING,
        EXITING;

    }
}

