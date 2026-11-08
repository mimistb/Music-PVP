/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.client.resources.IResource
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
import gale.kill.gd.client.render.effect.IconRingEffect;
import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.compat.BufferBuilder;
import gale.kill.gd.compat.DefaultVertexFormat;
import gale.kill.gd.compat.GameRenderer;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.RenderSystem;
import gale.kill.gd.compat.Tesselator;
import gale.kill.gd.compat.VertexFormat;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.IResource;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

public class CardBarRenderer
implements IHudRenderer {
    private static final Map<ResourceLocation, Float> ASPECT_RATIO_CACHE = new ConcurrentHashMap<ResourceLocation, Float>();
    private static final float DEFAULT_ASPECT_RATIO = 1.0f;
    private static final int BASE_LOGICAL_HEIGHT = 40;
    private static final float CENTER_FLASH_SPEED_MULTIPLIER = 3.0f;
    private long flashStartTime = -1L;
    private final WaveEffectSystem waveSystem = new WaveEffectSystem();
    private final IconRingEffect ringEffect = new IconRingEffect();
    private JsonObject currentConfig;

    public CardBarRenderer() {
        this.ringEffect.setScale(1.0f);
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        Team pt;
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "card_bar");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.currentConfig = config2;
        float scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
        int xOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
        int yOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 0;
        String screenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
        String team = config2.has("team") ? config2.get("team").getAsString() : "ct";
        boolean dynamicCardStyle = config2.has("dynamic_card_style") && config2.get("dynamic_card_style").getAsBoolean();
        float animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 0.2f;
        Minecraft mc = Minecraft.getMinecraft();
        if (dynamicCardStyle && mc.thePlayer != null && (pt = mc.thePlayer.getTeam()) != null) {
            EnumChatFormatting color = ((ScorePlayerTeam)pt).getChatFormat();
            if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA || color == EnumChatFormatting.DARK_BLUE) {
                team = "ct";
            } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                team = "t";
            }
        }
        boolean showLight = config2.has("show_light") && config2.get("show_light").getAsBoolean();
        float lightWidth = config2.has("light_width") ? config2.get("light_width").getAsFloat() : 300.0f;
        float lightHeight = config2.has("light_height") ? config2.get("light_height").getAsFloat() : 20.0f;
        String lightColorCt = config2.has("color_light_ct") ? config2.get("color_light_ct").getAsString() : "9cc1eb";
        String lightColorT = config2.has("color_light_t") ? config2.get("color_light_t").getAsString() : "d9ac5b";
        boolean enableIconGlow = IconGlowRenderEffect.isEnabled(config2);
        int iconGlowColor = IconGlowRenderEffect.resolveColor(config2);
        float iconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
        float iconGlowSize = IconGlowRenderEffect.resolveSize(config2);
        boolean isT = "t".equalsIgnoreCase(team);
        String textureKey = isT ? "bar_t" : "bar_ct";
        float focusMultiplier = PreviewTextureFocusContext.alphaMultiplier("kill_icon/card_bar", textureKey);
        String textureName = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/card_bar", textureKey, config2);
        ResourceLocation texture = ExternalTextureManager.getTexture(textureName);
        if (texture == null) {
            return;
        }
        float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
        float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
        int drawHeight = Math.round(40.0f * frameHeightRatio);
        int drawWidth = Math.round(40.0f * frameWidthRatio);
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float centerX = ScreenAnchor.resolveCenterX(screenAnchor, xOffset, screenWidth);
        float centerY = ScreenAnchor.resolveCenterY(screenAnchor, yOffset, screenHeight);
        this.renderInternal(guiGraphics, partialTick, centerX, centerY, scale, isT, texture, drawWidth, drawHeight, showLight, lightWidth, lightHeight, lightColorCt, lightColorT, animationDuration, enableIconGlow, iconGlowColor, iconGlowIntensity, iconGlowSize, focusMultiplier);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        ResourceLocation texture;
        Team pt;
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "card_bar");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.currentConfig = config2;
        float scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
        String team = config2.has("team") ? config2.get("team").getAsString() : "ct";
        boolean dynamicCardStyle = config2.has("dynamic_card_style") && config2.get("dynamic_card_style").getAsBoolean();
        float animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 0.2f;
        Minecraft mc = Minecraft.getMinecraft();
        if (dynamicCardStyle && mc.thePlayer != null && (pt = mc.thePlayer.getTeam()) != null) {
            EnumChatFormatting color = ((ScorePlayerTeam)pt).getChatFormat();
            if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA || color == EnumChatFormatting.DARK_BLUE) {
                team = "ct";
            } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                team = "t";
            }
        }
        boolean showLight = config2.has("show_light") && config2.get("show_light").getAsBoolean();
        float lightWidth = config2.has("light_width") ? config2.get("light_width").getAsFloat() : 300.0f;
        float lightHeight = config2.has("light_height") ? config2.get("light_height").getAsFloat() : 20.0f;
        String lightColorCt = config2.has("color_light_ct") ? config2.get("color_light_ct").getAsString() : "9cc1eb";
        String lightColorT = config2.has("color_light_t") ? config2.get("color_light_t").getAsString() : "d9ac5b";
        boolean enableIconGlow = IconGlowRenderEffect.isEnabled(config2);
        int iconGlowColor = IconGlowRenderEffect.resolveColor(config2);
        float iconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
        float iconGlowSize = IconGlowRenderEffect.resolveSize(config2);
        String textureName = "killicon_card_bar_ct.png";
        boolean isT = "t".equalsIgnoreCase(team);
        if (isT) {
            textureName = "killicon_card_bar_t.png";
        }
        if ((texture = ExternalTextureManager.getTexture(textureName)) == null) {
            return;
        }
        String textureKey = isT ? "bar_t" : "bar_ct";
        float focusMultiplier = PreviewTextureFocusContext.alphaMultiplier("kill_icon/card_bar", textureKey);
        float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
        float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
        int drawHeight = Math.round(40.0f * frameHeightRatio);
        int drawWidth = Math.round(40.0f * frameWidthRatio);
        this.renderInternal(guiGraphics, partialTick, centerX, centerY, scale, isT, texture, drawWidth, drawHeight, showLight, lightWidth, lightHeight, lightColorCt, lightColorT, animationDuration, enableIconGlow, iconGlowColor, iconGlowIntensity, iconGlowSize, focusMultiplier);
    }

    public void renderPreviewAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY, JsonObject config2) {
        ResourceLocation texture;
        Team pt;
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        this.currentConfig = config2;
        float scale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
        String team = config2.has("team") ? config2.get("team").getAsString() : "ct";
        boolean dynamicCardStyle = config2.has("dynamic_card_style") && config2.get("dynamic_card_style").getAsBoolean();
        float animationDuration = config2.has("animation_duration") ? config2.get("animation_duration").getAsFloat() : 0.2f;
        Minecraft mc = Minecraft.getMinecraft();
        if (dynamicCardStyle && mc.thePlayer != null && (pt = mc.thePlayer.getTeam()) != null) {
            EnumChatFormatting color = ((ScorePlayerTeam)pt).getChatFormat();
            if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA || color == EnumChatFormatting.DARK_BLUE) {
                team = "ct";
            } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                team = "t";
            }
        }
        boolean showLight = config2.has("show_light") && config2.get("show_light").getAsBoolean();
        float lightWidth = config2.has("light_width") ? config2.get("light_width").getAsFloat() : 300.0f;
        float lightHeight = config2.has("light_height") ? config2.get("light_height").getAsFloat() : 20.0f;
        String lightColorCt = config2.has("color_light_ct") ? config2.get("color_light_ct").getAsString() : "9cc1eb";
        String lightColorT = config2.has("color_light_t") ? config2.get("color_light_t").getAsString() : "d9ac5b";
        boolean enableIconGlow = IconGlowRenderEffect.isEnabled(config2);
        int iconGlowColor = IconGlowRenderEffect.resolveColor(config2);
        float iconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
        float iconGlowSize = IconGlowRenderEffect.resolveSize(config2);
        String textureName = "killicon_card_bar_ct.png";
        boolean isT = "t".equalsIgnoreCase(team);
        if (isT) {
            textureName = "killicon_card_bar_t.png";
        }
        if ((texture = ExternalTextureManager.getTexture(textureName)) == null) {
            return;
        }
        String textureKey = isT ? "bar_t" : "bar_ct";
        float focusMultiplier = PreviewTextureFocusContext.alphaMultiplier("kill_icon/card_bar", textureKey);
        float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
        float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
        int drawHeight = Math.round(40.0f * frameHeightRatio);
        int drawWidth = Math.round(40.0f * frameWidthRatio);
        this.renderInternal(guiGraphics, partialTick, centerX, centerY, scale, isT, texture, drawWidth, drawHeight, showLight, lightWidth, lightHeight, lightColorCt, lightColorT, animationDuration, enableIconGlow, iconGlowColor, iconGlowIntensity, iconGlowSize, focusMultiplier);
    }

    private void renderInternal(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY, float scale, boolean isT, ResourceLocation texture, int drawWidth, int drawHeight, boolean showLight, float lightWidth, float lightHeight, String lightColorCt, String lightColorT, float animationDuration, boolean enableIconGlow, int iconGlowColor, float iconGlowIntensity, float iconGlowSize, float focusMultiplier) {
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, 10.0);
        poseStack.scale(scale, scale, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        float flashAlpha = 0.0f;
        long animDurMs = (long)(animationDuration * 1000.0f);
        long flashAnimDurMs = Math.max(1L, (long)((float)animDurMs / 3.0f));
        if (this.flashStartTime != -1L) {
            long elapsed = PreviewRenderTimeContext.currentTimeMillis() - this.flashStartTime;
            long flashHold = flashAnimDurMs / 2L;
            long flashFade = flashAnimDurMs * 4L;
            if (elapsed < flashHold) {
                flashAlpha = 1.0f;
            } else {
                long flashElapsed = elapsed - flashHold;
                if (flashElapsed < flashFade) {
                    flashAlpha = 1.0f - (float)flashElapsed / (float)flashFade;
                } else {
                    this.flashStartTime = -1L;
                }
            }
        }
        if (showLight) {
            int baseColorInt = this.parseColor(isT ? lightColorT : lightColorCt);
            int br = baseColorInt >> 16 & 0xFF;
            int bg = baseColorInt >> 8 & 0xFF;
            int bb = baseColorInt & 0xFF;
            int mr = (int)((float)br + (float)(255 - br) * flashAlpha);
            int mg = (int)((float)bg + (float)(255 - bg) * flashAlpha);
            int mb = (int)((float)bb + (float)(255 - bb) * flashAlpha);
            String mixedColorHex = String.format("%02X%02X%02X", mr, mg, mb);
            this.renderLightEffect(guiGraphics, lightWidth, lightHeight, mixedColorHex, 1.0f);
            this.ringEffect.render(guiGraphics, 0.0f, 0.0f, PreviewRenderTimeContext.currentTimeMillis());
        }
        if (showLight) {
            this.waveSystem.updateAndRender(guiGraphics, lightWidth, isT ? lightColorT : lightColorCt, animationDuration, flashAlpha);
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        if (enableIconGlow) {
            IconGlowRenderEffect.drawGlowFrame(guiGraphics, texture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0, 0, drawWidth, drawHeight, drawWidth, drawHeight, focusMultiplier, iconGlowColor, iconGlowIntensity, iconGlowSize);
        }
        guiGraphics.blit(texture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
        if (flashAlpha > 0.01f) {
            RenderSystem.blendFunc(770, 1);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, flashAlpha * focusMultiplier);
            guiGraphics.blit(texture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
            if (flashAlpha > 0.5f) {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, (flashAlpha - 0.5f) * 2.0f * focusMultiplier);
                guiGraphics.blit(texture, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight, 0.0f, 0.0f, drawWidth, drawHeight, drawWidth, drawHeight);
            }
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private float getTextureAspectRatio(ResourceLocation texture) {
        block18: {
            if (ASPECT_RATIO_CACHE.containsKey(texture)) {
                return ASPECT_RATIO_CACHE.get(texture).floatValue();
            }
            try {
                IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(texture);
                if (resource == null) break block18;
                try (InputStream inputStream = resource.getInputStream();){
                    BufferedImage image = ImageIO.read(inputStream);
                    if (image != null && image.getHeight() > 0) {
                        float ratio = (float)image.getWidth() / (float)image.getHeight();
                        ASPECT_RATIO_CACHE.put(texture, Float.valueOf(ratio));
                        float f = ratio;
                        return f;
                    }
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        ASPECT_RATIO_CACHE.put(texture, Float.valueOf(1.0f));
        return 1.0f;
    }

    private void renderLightEffect(GuiGraphics guiGraphics, float width, float height, String colorHex, float alphaMultiplier) {
        int color = this.parseColor(colorHex);
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        float halfWidth = width / 2.0f;
        float halfHeight = height / 2.0f;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int segments = 50;
        float coreRatio = 0.5f;
        for (int i = 0; i <= segments; ++i) {
            float alphaVal;
            float t = (float)i / (float)segments;
            float x = (t - 0.5f) * 2.0f * halfWidth;
            float dist = Math.abs(x) / halfWidth;
            if (dist <= coreRatio) {
                alphaVal = 1.0f;
            } else {
                float decayProgress = (dist - coreRatio) / (1.0f - coreRatio);
                alphaVal = (float)Math.pow(1.0f - decayProgress, 2.0);
            }
            int a = (int)(alphaVal * 255.0f * alphaMultiplier);
            buffer.vertex(guiGraphics.pose().last().pose(), x, -halfHeight, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(guiGraphics.pose().last().pose(), x, halfHeight, 0.0f).color(r, g, b, a).endVertex();
        }
        tesselator.end();
    }

    private int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", ""), 16);
        }
        catch (NumberFormatException e) {
            return 0xFFFFFF;
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

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "card_bar");
        if (config2 == null || !config2.has("visible") || !config2.get("visible").getAsBoolean()) {
            return;
        }
        if (context.type() == 4) {
            return;
        }
        this.flashStartTime = PreviewRenderTimeContext.currentTimeMillis();
        if (config2 != null) {
            boolean isT;
            Team pt;
            boolean dynamicCardStyle;
            String lightColorCt = config2.has("color_light_ct") ? config2.get("color_light_ct").getAsString() : "9cc1eb";
            String lightColorT = config2.has("color_light_t") ? config2.get("color_light_t").getAsString() : "d9ac5b";
            Minecraft mc = Minecraft.getMinecraft();
            String team = config2.has("team") ? config2.get("team").getAsString() : "ct";
            boolean bl = dynamicCardStyle = config2.has("dynamic_card_style") && config2.get("dynamic_card_style").getAsBoolean();
            if (dynamicCardStyle && mc.thePlayer != null && (pt = mc.thePlayer.getTeam()) != null) {
                EnumChatFormatting color = ((ScorePlayerTeam)pt).getChatFormat();
                if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA || color == EnumChatFormatting.DARK_BLUE) {
                    team = "ct";
                } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                    team = "t";
                }
            }
            int color = this.parseColor((isT = "t".equalsIgnoreCase(team)) ? lightColorT : lightColorCt);
            int r = color >> 16 & 0xFF;
            int g = color >> 8 & 0xFF;
            int b = color & 0xFF;
            int r2 = (r + 255) / 2;
            int g2 = (g + 255) / 2;
            int b2 = (b + 255) / 2;
            int explosionColor2 = r2 << 16 | g2 << 8 | b2;
            this.ringEffect.trigger(PreviewRenderTimeContext.currentTimeMillis(), true, context.type(), color, explosionColor2, color);
        }
        int combo = context.comboCount();
        int waveCount = Math.min(combo, 5);
        if (combo >= 6) {
            waveCount = 1;
        }
        if (waveCount > 0) {
            this.waveSystem.trigger(waveCount);
        }
    }

    private static class WaveEffectSystem {
        private final List<Wave> waves = new ArrayList<Wave>();
        private final Queue<Long> pendingSpawns = new LinkedList<Long>();
        private long lastSpawnTime = 0L;
        private static final float WAVE_RADIUS_RATIO = 0.15f;
        private static final float MAX_STRETCH_PIXELS = 25.0f;

        private WaveEffectSystem() {
        }

        public void trigger(int pairCount) {
            long now = PreviewRenderTimeContext.currentTimeMillis();
            for (int i = 0; i < pairCount; ++i) {
                this.pendingSpawns.offer(now);
            }
        }

        public void updateAndRender(GuiGraphics guiGraphics, float width, String colorHex, float animationDuration, float flashAlpha) {
            long now = PreviewRenderTimeContext.currentTimeMillis();
            long interval = (long)(animationDuration * 1000.0f / 2.0f);
            if (!this.pendingSpawns.isEmpty()) {
                if (now - this.lastSpawnTime >= interval) {
                    this.pendingSpawns.poll();
                    this.waves.add(new Wave(now, -1));
                    this.waves.add(new Wave(now, 1));
                    this.lastSpawnTime = now;
                }
            } else if (now - this.lastSpawnTime > interval * 2L) {
                this.lastSpawnTime = now - interval;
            }
            long waveLifeTime = (long)(animationDuration * 1000.0f * 4.0f);
            Iterator<Wave> it = this.waves.iterator();
            while (it.hasNext()) {
                if (now - it.next().startTime <= waveLifeTime) continue;
                it.remove();
            }
            if (this.waves.isEmpty()) {
                return;
            }
            this.renderWaves(guiGraphics, width, colorHex, animationDuration, flashAlpha, now, waveLifeTime);
        }

        private void renderWaves(GuiGraphics guiGraphics, float width, String colorHex, float animationDuration, float flashAlpha, long now, long lifeTime) {
            int color = this.parseColor(colorHex);
            int r = color >> 16 & 0xFF;
            int g = color >> 8 & 0xFF;
            int b = color & 0xFF;
            int fr = 255;
            int fg = 255;
            int fb = 255;
            float halfWidth = width / 2.0f;
            float waveRadius = width * 0.15f;
            float speed = halfWidth / (float)lifeTime;
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder buffer = tesselator.getBuilder();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.disableDepthTest();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (float x = -halfWidth; x <= halfWidth; x += 1.0f) {
                float distFromCenter = Math.abs(x);
                float maxDistRatio = distFromCenter / halfWidth;
                float totalInfluence = 0.0f;
                for (Wave w : this.waves) {
                    long elapsed = now - w.startTime;
                    float wavePos = (float)w.direction * (speed * (float)elapsed);
                    float distToWave = Math.abs(x - wavePos);
                    if (!(distToWave < waveRadius)) continue;
                    float influence = 1.0f - distToWave / waveRadius;
                    influence = (float)Math.pow(influence, 2.0);
                    totalInfluence += influence;
                }
                if (totalInfluence <= 0.001f) continue;
                float fadeMask = 1.0f - maxDistRatio * 0.99f;
                float baseAlpha = totalInfluence;
                boolean isSpecial = Math.abs(x) % 20.0f < 1.0f;
                float alphaMultiplier = isSpecial ? 1.5f : 1.0f;
                float lengthMultiplier = isSpecial ? 1.5f : 1.0f;
                float finalAlpha = baseAlpha * fadeMask * alphaMultiplier;
                finalAlpha = Math.min(finalAlpha, 1.0f);
                float randomLen = (float)(Math.sin(x * 123.456f) * 2.5 + 2.5);
                float stretch = totalInfluence * 25.0f * fadeMask * lengthMultiplier + randomLen;
                if (finalAlpha < 0.01f) continue;
                int aBottom = (int)(finalAlpha * 255.0f);
                int aTop = 0;
                float yBottom = 0.5f;
                float yTop = -0.5f - stretch;
                float xLeft = x;
                float xRight = x + 1.0f;
                buffer.vertex(guiGraphics.pose().last().pose(), xRight, yBottom, 0.0f).color(r, g, b, aBottom).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xRight, yTop, 0.0f).color(r, g, b, aTop).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xLeft, yTop, 0.0f).color(r, g, b, aTop).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xLeft, yBottom, 0.0f).color(r, g, b, aBottom).endVertex();
                if (!(flashAlpha > 0.01f)) continue;
                int faBottom = (int)(finalAlpha * flashAlpha * 255.0f);
                int faTop = 0;
                if (faBottom <= 0) continue;
                buffer.vertex(guiGraphics.pose().last().pose(), xRight, yBottom, 0.0f).color(fr, fg, fb, faBottom).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xRight, yTop, 0.0f).color(fr, fg, fb, faTop).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xLeft, yTop, 0.0f).color(fr, fg, fb, faTop).endVertex();
                buffer.vertex(guiGraphics.pose().last().pose(), xLeft, yBottom, 0.0f).color(fr, fg, fb, faBottom).endVertex();
            }
            tesselator.end();
            RenderSystem.enableDepthTest();
        }

        private int parseColor(String hex) {
            try {
                return Integer.parseInt(hex.replace("#", ""), 16);
            }
            catch (NumberFormatException e) {
                return 0xFFFFFF;
            }
        }

        static class Wave {
            long startTime;
            int direction;

            Wave(long startTime, int direction) {
                this.startTime = startTime;
                this.direction = direction;
            }
        }
    }
}

