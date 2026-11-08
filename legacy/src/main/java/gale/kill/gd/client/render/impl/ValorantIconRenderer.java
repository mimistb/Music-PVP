/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.render.impl;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.ElementTextureDefinition;
import gale.kill.gd.client.config.ValorantStyleCatalog;
import gale.kill.gd.client.gui.tabs.PreviewTextureFocusContext;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.render.ScreenAnchor;
import gale.kill.gd.client.render.effect.IconGlowProcessor;
import gale.kill.gd.client.render.effect.ValorantMotion;
import gale.kill.gd.client.render.impl.ComboIconRenderer;
import gale.kill.gd.client.textures.IconTextureAnimationManager;
import gale.kill.gd.client.textures.ModTextures;
import gale.kill.gd.compat.Axis;
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
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;

public class ValorantIconRenderer
implements IHudRenderer {
    private static final long DEFAULT_DISPLAY_DURATION = 2600L;
    private static final long FIVE_PLUS_FADE_IN_DURATION_MS = 300L;
    private static final long PARTICLE_BASE_DURATION_MS = 620L;
    private static final long BAR_BOUNCE_START_MS = 20L;
    private static final long BAR_BOUNCE_PEAK_MS = 180L;
    private static final long BAR_BOUNCE_HOLD_END_MS = 270L;
    private static final long BAR_BOUNCE_END_MS = 620L;
    private static final long BAR_SPIN_START_MS = 750L;
    private static final long BAR_SPIN_DURATION_180_MS = 700L;
    private static final long BAR_SPIN_DURATION_360_MS = 1000L;
    private static final long FADE_OUT_DURATION_MS = 220L;
    private static final float DEFAULT_ICON_ENTRY_OFFSET_PX = -16.0f;
    private static final float DEFAULT_ICON_ENTRY_DURATION_SECONDS = 0.1f;
    private static final float BAR_BOUNCE_DISTANCE_PX = 9.0f;
    private static final float VISUAL_CENTER_Y_OFFSET_PX = 6.0f;
    private static final float GLOBAL_VISUAL_SCALE = 0.95f;
    private static final float ICON_BASE_SIZE = 116.0f;
    private static final float BAR_BASE_SIZE = 32.0f;
    private static final float HEADSHOT_SIZE_MULTIPLIER = 0.16f;
    private static final float PARTICLE_BASE_SIZE = 112.0f;
    private static final float PARTICLE_FRAME_ANCHOR_Y_RATIO = 0.8046875f;
    private static final float BAR_RING_RADIUS = 36.0f;
    private static final int HALO_SEGMENTS = 72;
    private static final float GAIA_DEFAULT_BRIGHTNESS = 1.3f;
    private static final float GAIA_DEFAULT_CONTRAST = 1.1f;
    private static final int DEFAULT_ICON_FLASH_COLOR = 16722486;
    private static final int MAX_ICON_FLASH_COUNT = 12;
    private static final int PRIME_PARTICLE_SMALL_COLOR = 16765240;
    private static final int GAIA_PARTICLE_SMALL_COLOR = 14831708;
    private static final int DEFAULT_ACCENT_COLOR = 14831708;
    private static final float DEFAULT_ICON_FLASH_HOLD_DURATION_SECONDS = 0.08f;
    private static final float ICON_FLASH_MAX_ALPHA = 0.9f;
    private static final float ICON_TEXTURE_SOURCE_SIZE = 1729.0f;
    private static final float DEFAULT_HERO_FLAME_SOURCE_SCALE = 8.0f;
    private static final float DEFAULT_LARGE_SPARKS_SOURCE_SCALE = 6.8f;
    private static final float DEFAULT_X_SPARKS_SOURCE_SCALE = 11.0f;
    private static final float HERO_FLAME_Y_OFFSET_PX = -30.0f;
    private static final float LARGE_SPARKS_Y_OFFSET_PX = 2.0f;
    private static final float X_SPARKS_Y_OFFSET_PX = -8.0f;
    private static final ParticleOverlaySpec HERO_FLAME_PARTICLE = new ParticleOverlaySpec("hero_flame", 0.4934375f, 0.49079242f, 8.0f, 0.0f, -30.0f, false);
    private static final ParticleOverlaySpec LARGE_SPARKS_PARTICLE = new ParticleOverlaySpec("large_sparks", 0.49052733f, 0.5185547f, 6.8f, 0.0f, 2.0f, true);
    private static final ParticleOverlaySpec X_SPARKS_PARTICLE = new ParticleOverlaySpec("x_sparks", 0.48015872f, 0.56722003f, 11.0f, 0.0f, -8.0f, false);
    private static final float[][] BAR_LAYOUT_ANGLES = new float[][]{{0.0f}, {-90.0f, 90.0f}, {0.0f, -120.0f, 120.0f}, {0.0f, -90.0f, 90.0f, 180.0f}, {0.0f, -72.0f, 72.0f, -144.0f, 144.0f}, {0.0f, -60.0f, 60.0f, -120.0f, 120.0f, 180.0f}};
    private static final float[][] GLOW_OFFSETS = new float[][]{{-1.0f, 0.0f}, {1.0f, 0.0f}, {0.0f, -1.0f}, {0.0f, 1.0f}, {-0.70710677f, -0.70710677f}, {-0.70710677f, 0.70710677f}, {0.70710677f, -0.70710677f}, {0.70710677f, 0.70710677f}};
    private float configScale = 1.0f;
    private float configXOffset = 0.0f;
    private float configYOffset = 80.0f;
    private String configScreenAnchor = "bottom_center";
    private float configBarXOffset = 0.0f;
    private float configBarYOffset = 0.0f;
    private float configBarRadiusOffset = 0.0f;
    private float configIconScale = 1.0f;
    private float configHeadshotAnimInitialScale = 1.8f;
    private float configHeadshotAnimDuration = 0.25f;
    private float configBarEntryInitialScale = 1.6f;
    private float configBarEntryDuration = 0.18f;
    private float configBrightness = 1.0f;
    private boolean configIconGlowEnabled = false;
    private int configIconGlowColor = 0xFFFFFF;
    private float configIconGlowIntensity = 0.45f;
    private float configIconGlowSize = 4.0f;
    private boolean configHaloRingEnabled = false;
    private float configHaloRingRadius = 30.0f;
    private float configHaloRingWidth = 1.5f;
    private int configHaloRingColor = 0xFFFFFF;
    private boolean configBladeEffect = true;
    private boolean configBladeRotationEffect = true;
    private float configBladeDecelerationWindowSec = 1.5f;
    private float configMathParticleDensity = 1.0f;
    private float configMathParticleSpread = 1.0f;
    private float configMathParticleSize = 1.0f;
    private JsonObject currentConfig;
    private ValorantMotion motion = new ValorantMotion(null);
    private boolean configMathParticleEffect = false;
    private long startTime = -1L;
    private boolean visible = false;
    private int comboCount = 1;
    private boolean headshotTrigger = false;
    private float spinDirection = 1.0f;
    private SmallParticle[] smallParticles = new SmallParticle[0];

    public static void prepareTextures(String presetId, JsonObject config2) {
        if (config2 == null) {
            return;
        }
        String style = ValorantStyleCatalog.resolveStyleId(presetId, config2);
        boolean gaia = style != null && style.contains("gaia");
        float brightness = (config2.has("brightness") ? config2.get("brightness").getAsFloat() : 1.0f) * (gaia ? 1.3f : 1.0f);
        float contrast = gaia ? 1.1f : 1.0f;
        boolean tint = config2.has("enable_accent_tint") && config2.get("enable_accent_tint").getAsBoolean();
        int color = 0xFFFFFF;
        try {
            if (config2.has("color_accent")) {
                color = Integer.parseInt(config2.get("color_accent").getAsString().replace("#", ""), 16);
            }
        }
        catch (NumberFormatException numberFormatException) {
            // empty catch block
        }
        for (String key : new String[]{"emblem", "frame", "bar", "base_particle", "hero_flame", "large_sparks", "x_sparks"}) {
            boolean applyTint;
            boolean particle;
            boolean bl = particle = key.endsWith("particle") || key.endsWith("flame") || key.endsWith("sparks");
            if (particle && (!config2.has("enable_" + key) || !config2.get("enable_" + key).getAsBoolean())) continue;
            boolean bl2 = applyTint = tint && !particle;
            if (!applyTint && Math.abs(brightness - 1.0f) <= 0.001f && Math.abs(contrast - 1.0f) <= 0.001f) continue;
            String texture = ElementTextureDefinition.getSelectedTextureFileName(presetId, "kill_icon/valorant", key, config2);
            IconGlowProcessor.prepare(presetId, texture, applyTint, color, brightness, contrast);
        }
    }

    public static void clearProcessedTextureCache() {
        IconGlowProcessor.clearCache();
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        boolean enabled;
        if (context.comboCount() <= 0) {
            return;
        }
        if (context.type() == 4 || context.type() == 5) {
            return;
        }
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "valorant");
        if (config2 == null) {
            return;
        }
        boolean bl = enabled = !config2.has("visible") || config2.get("visible").getAsBoolean();
        if (!enabled) {
            this.visible = false;
            return;
        }
        this.loadConfig(config2);
        this.comboCount = Mth.clamp(context.comboCount(), 1, 6);
        this.headshotTrigger = context.type() == 1;
        float f = this.spinDirection = ThreadLocalRandom.current().nextBoolean() ? 1.0f : -1.0f;
        if (this.configMathParticleEffect) {
            this.generateParticleState();
        } else {
            this.smallParticles = new SmallParticle[0];
        }
        this.startTime = PreviewRenderTimeContext.currentTimeMillis();
        this.visible = true;
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        if (!this.visible || this.startTime < 0L) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float centerX = ScreenAnchor.resolveCenterX(this.configScreenAnchor, Math.round(this.configXOffset), screenWidth);
        float centerY = (float)ScreenAnchor.resolveCenterY(this.configScreenAnchor, Math.round(this.configYOffset), screenHeight) + 6.0f;
        this.renderAt(guiGraphics, partialTick, centerX, centerY);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        long displayDuration;
        long elapsed;
        if (!this.visible || this.startTime < 0L) {
            return;
        }
        JsonObject liveConfig = ConfigManager.getElementConfig("kill_icon", "valorant");
        if (liveConfig != null) {
            this.loadConfig(liveConfig);
        }
        if ((elapsed = PreviewRenderTimeContext.currentTimeMillis() - this.startTime) > (displayDuration = this.resolveDisplayDuration())) {
            this.visible = false;
            this.startTime = -1L;
            return;
        }
        float alpha = this.resolveAlpha(elapsed, displayDuration);
        float iconOffsetY = this.resolveIconOffsetY(elapsed);
        float barTravel = this.resolveBarTravel(elapsed);
        float barRotation = this.resolveBarRotation(elapsed);
        FlashState flash = this.resolveFlashState(elapsed);
        String presetId = ConfigManager.getCurrentPresetId();
        String emblemTexturePath = this.resolveSelectedTexture(presetId, "emblem");
        String frameTexturePath = this.resolveSelectedTexture(presetId, "frame");
        String bladeTexturePath = this.resolveSelectedTexture(presetId, "blade");
        String barTexturePath = this.resolveSelectedTexture(presetId, "bar");
        if (this.motion.usesFoxFan() && this.comboCount >= 6 && this.currentConfig.has("texture_style_bar_six") && (!this.currentConfig.has("texture_mode_bar") || "official".equals(this.currentConfig.get("texture_mode_bar").getAsString()))) {
            barTexturePath = this.currentConfig.get("texture_style_bar_six").getAsString();
        }
        String headshotTexturePath = this.resolveSelectedTexture(presetId, "headshot");
        String baseParticleTexturePath = this.resolveSelectedTexture(presetId, "base_particle");
        String heroFlameTexturePath = this.resolveSelectedTexture(presetId, "hero_flame");
        String largeSparksTexturePath = this.resolveSelectedTexture(presetId, "large_sparks");
        String xSparksTexturePath = this.resolveSelectedTexture(presetId, "x_sparks");
        String styleId = this.resolveStyleId();
        boolean gaiaSkin = this.isGaiaSkin(styleId, emblemTexturePath, barTexturePath);
        boolean bladeEnabled = this.configBladeEffect;
        String activeBladeTexturePath = bladeEnabled ? this.resolveBladeTexturePath(styleId, bladeTexturePath) : null;
        float emblemWidth = 116.0f * this.configIconScale * this.resolveTextureScale("emblem") * this.resolveFrameRatio("emblem", "texture_frame_width_ratio");
        float emblemHeight = 116.0f * this.configIconScale * this.resolveTextureScale("emblem") * this.resolveFrameRatio("emblem", "texture_frame_height_ratio");
        float frameWidth = 116.0f * this.resolveTextureScale("frame") * this.resolveFrameRatio("frame", "texture_frame_width_ratio");
        float frameHeight = 116.0f * this.resolveTextureScale("frame") * this.resolveFrameRatio("frame", "texture_frame_height_ratio");
        float bladeWidth = 116.0f * this.resolveTextureScale("blade") * this.resolveFrameRatio("blade", "texture_frame_width_ratio");
        float bladeHeight = 116.0f * this.resolveTextureScale("blade") * this.resolveFrameRatio("blade", "texture_frame_height_ratio");
        float headshotWidth = 18.56f * this.resolveTextureScale("headshot") * this.resolveFrameRatio("headshot", "texture_frame_width_ratio");
        float headshotHeight = 18.56f * this.resolveTextureScale("headshot") * this.resolveFrameRatio("headshot", "texture_frame_height_ratio");
        float barBaseSize = 32.0f;
        float barWidth = barBaseSize * this.resolveTextureScale("bar") * this.resolveFrameRatio("bar", "texture_frame_width_ratio");
        float barHeight = barBaseSize * this.resolveTextureScale("bar") * this.resolveFrameRatio("bar", "texture_frame_height_ratio");
        float effectiveBrightness = this.resolveEffectiveBrightness(gaiaSkin);
        float effectiveContrast = this.resolveEffectiveContrast(gaiaSkin);
        boolean accentTintEnabled = this.isAccentTintEnabled();
        int accentColor = this.resolveAccentColor();
        int defaultParticleColor = this.resolveDefaultParticleColor(accentTintEnabled, accentColor, gaiaSkin);
        int xSparksDefaultColor = this.resolveConfiguredColor("color_icon_flash", 16722486);
        int headshotOverlayColor = this.resolveConfiguredColor("color_headshot_overlay", 16722486);
        boolean frameEntryLocked = this.isEntryMotionFrameLocked(styleId);
        float rootMotionOffsetY = frameEntryLocked ? 0.0f : iconOffsetY;
        float emblemMotionOffsetY = frameEntryLocked ? iconOffsetY : 0.0f;
        float resolvedHeadshotXOffset = (float)ValorantStyleCatalog.getHeadshotOffsetX(styleId) + this.resolveTextureOffset("headshot", "texture_x_offset");
        float resolvedHeadshotYOffset = (float)ValorantStyleCatalog.getHeadshotOffsetY(styleId) + this.resolveTextureOffset("headshot", "texture_y_offset");
        float barRadiusOffset = this.motion.barRadiusOffset(this.comboCount, this.configBarRadiusOffset);
        float barCenterXOffset = this.configBarXOffset + this.resolveTextureOffset("bar", "texture_x_offset");
        float barCenterYOffset = this.configBarYOffset + this.resolveTextureOffset("bar", "texture_y_offset");
        float[] barLayoutAngles = this.motion.barLayout(this.comboCount, BAR_LAYOUT_ANGLES[Mth.clamp(this.comboCount, 1, 6) - 1]);
        try {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, centerY + rootMotionOffsetY, 0.0);
            float renderScale = this.configScale * 0.95f;
            guiGraphics.pose().scale(renderScale, renderScale, 1.0f);
            if (this.configMathParticleEffect) {
                int mathParticleColor = this.resolveLayerParticleColor("base_particle", defaultParticleColor);
                this.renderSmallParticles(guiGraphics, elapsed, alpha, mathParticleColor);
            } else {
                this.renderBaseParticles(guiGraphics, baseParticleTexturePath, elapsed, alpha, effectiveBrightness, effectiveContrast, defaultParticleColor);
            }
            this.renderGradientHaloRing(guiGraphics, elapsed, alpha);
            if (!this.motion.usesFoxFan()) {
                this.renderBarRing(guiGraphics, barTexturePath, barWidth, barHeight, barTravel, alpha, barRotation, barRadiusOffset, barCenterXOffset, barCenterYOffset, barLayoutAngles, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast, elapsed);
            }
            RenderSystem.defaultBlendFunc();
            guiGraphics.pose().pushPose();
            float frameScale = this.motion.frameScale(elapsed);
            if (Math.abs(frameScale - 1.0f) > 0.001f) {
                guiGraphics.pose().scale(frameScale, frameScale, 1.0f);
            }
            this.drawColorizableTexture(guiGraphics, "frame", frameTexturePath, frameWidth, frameHeight, alpha * this.motion.frameAlpha(elapsed), 0xFFFFFF, false, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast);
            if (activeBladeTexturePath != null && !activeBladeTexturePath.isEmpty()) {
                guiGraphics.pose().pushPose();
                float bladeRotation = this.resolveFrameBladeRotation(styleId, elapsed);
                if (Math.abs(bladeRotation) > 0.01f) {
                    guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(bladeRotation));
                }
                this.drawColorizableTexture(guiGraphics, "blade", activeBladeTexturePath, bladeWidth, bladeHeight, alpha, 0xFFFFFF, false, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast);
                guiGraphics.pose().popPose();
            }
            guiGraphics.pose().popPose();
            if (this.motion.usesFoxFan()) {
                this.renderBarRing(guiGraphics, barTexturePath, barWidth, barHeight, barTravel, alpha, barRotation, barRadiusOffset, barCenterXOffset, barCenterYOffset, barLayoutAngles, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast, elapsed);
            }
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0, emblemMotionOffsetY, 0.0);
            float emblemScale = this.motion.emblemScale(elapsed);
            if (Math.abs(emblemScale - 1.0f) > 0.001f) {
                guiGraphics.pose().scale(emblemScale, emblemScale, 1.0f);
            }
            this.drawColorizableTexture(guiGraphics, "emblem", emblemTexturePath, emblemWidth, emblemHeight, alpha, 0xFFFFFF, false, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast);
            if (flash.alpha() > 0.0f) {
                this.drawColorizableTexture(guiGraphics, "emblem", emblemTexturePath, emblemWidth, emblemHeight, alpha * flash.alpha(), flash.color(), false, accentTintEnabled, accentColor, effectiveBrightness, effectiveContrast);
            }
            if (this.headshotTrigger) {
                HeadshotAnimState hsAnim = this.resolveHeadshotAnimState(elapsed, headshotOverlayColor);
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(resolvedHeadshotXOffset, resolvedHeadshotYOffset, 0.0);
                if (Math.abs(hsAnim.scale() - 1.0f) > 0.001f) {
                    guiGraphics.pose().scale(hsAnim.scale(), hsAnim.scale(), 1.0f);
                }
                this.drawColorizableTexture(guiGraphics, "headshot", headshotTexturePath, headshotWidth, headshotHeight, alpha, hsAnim.color(), false, false, 0xFFFFFF, 1.0f, 1.0f);
                guiGraphics.pose().popPose();
            }
            guiGraphics.pose().popPose();
            RenderSystem.defaultBlendFunc();
            if (!this.configMathParticleEffect) {
                this.renderOverlayParticles(guiGraphics, HERO_FLAME_PARTICLE, heroFlameTexturePath, elapsed, alpha, emblemWidth, emblemHeight, effectiveBrightness, effectiveContrast, defaultParticleColor, emblemMotionOffsetY);
                if (this.comboCount >= 5) {
                    this.renderOverlayParticles(guiGraphics, LARGE_SPARKS_PARTICLE, largeSparksTexturePath, elapsed, alpha, emblemWidth, emblemHeight, effectiveBrightness, effectiveContrast, defaultParticleColor, emblemMotionOffsetY);
                }
                if (this.headshotTrigger) {
                    this.renderOverlayParticles(guiGraphics, X_SPARKS_PARTICLE, xSparksTexturePath, elapsed, alpha, emblemWidth, emblemHeight, effectiveBrightness, effectiveContrast, xSparksDefaultColor, emblemMotionOffsetY);
                }
            }
            guiGraphics.pose().popPose();
        }
        finally {
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private void loadConfig(JsonObject config2) {
        if (this.currentConfig != config2) {
            this.motion = new ValorantMotion(config2);
        }
        this.currentConfig = config2;
        this.configScale = config2.has("scale") ? config2.get("scale").getAsFloat() : 1.0f;
        this.configXOffset = config2.has("x_offset") ? config2.get("x_offset").getAsFloat() : 0.0f;
        this.configYOffset = config2.has("y_offset") ? config2.get("y_offset").getAsFloat() : 80.0f;
        this.configScreenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
        this.configBarXOffset = config2.has("bar_x_offset") ? config2.get("bar_x_offset").getAsFloat() : 0.0f;
        this.configBarYOffset = config2.has("bar_y_offset") ? config2.get("bar_y_offset").getAsFloat() : 0.0f;
        this.configBarRadiusOffset = config2.has("bar_radius_offset") ? config2.get("bar_radius_offset").getAsFloat() : 0.0f;
        this.configIconScale = config2.has("icon_scale") ? config2.get("icon_scale").getAsFloat() : 1.0f;
        this.configHeadshotAnimInitialScale = Mth.clamp(config2.has("headshot_anim_initial_scale") ? config2.get("headshot_anim_initial_scale").getAsFloat() : 1.8f, 1.0f, 5.0f);
        this.configHeadshotAnimDuration = Math.max(0.0f, config2.has("headshot_anim_duration") ? config2.get("headshot_anim_duration").getAsFloat() : 0.25f);
        this.configBarEntryInitialScale = Mth.clamp(config2.has("bar_entry_initial_scale") ? config2.get("bar_entry_initial_scale").getAsFloat() : 1.6f, 1.0f, 5.0f);
        this.configBarEntryDuration = Math.max(0.0f, config2.has("bar_entry_duration") ? config2.get("bar_entry_duration").getAsFloat() : 0.18f);
        this.configBrightness = config2.has("brightness") ? config2.get("brightness").getAsFloat() : 1.0f;
        this.configIconGlowEnabled = config2.has("enable_icon_glow") && config2.get("enable_icon_glow").getAsBoolean();
        this.configIconGlowColor = this.resolveConfiguredColor("color_icon_glow", 0xFFFFFF);
        this.configIconGlowIntensity = Mth.clamp(config2.has("icon_glow_intensity") ? config2.get("icon_glow_intensity").getAsFloat() : 0.45f, 0.0f, 1.0f);
        this.configIconGlowSize = Math.max(0.0f, config2.has("icon_glow_size") ? config2.get("icon_glow_size").getAsFloat() : 4.0f);
        this.configHaloRingEnabled = config2.has("enable_halo_ring") && config2.get("enable_halo_ring").getAsBoolean();
        this.configHaloRingRadius = Math.max(4.0f, config2.has("halo_ring_radius") ? config2.get("halo_ring_radius").getAsFloat() : 30.0f);
        this.configHaloRingWidth = Math.max(0.1f, config2.has("halo_ring_width") ? config2.get("halo_ring_width").getAsFloat() : 1.5f);
        this.configHaloRingColor = this.resolveConfiguredColor("color_halo_ring", 0xFFFFFF);
        this.configBladeEffect = !config2.has("enable_blade_effect") || config2.get("enable_blade_effect").getAsBoolean();
        this.configBladeRotationEffect = !config2.has("enable_blade_rotation_effect") || config2.get("enable_blade_rotation_effect").getAsBoolean();
        this.configBladeDecelerationWindowSec = Mth.clamp(config2.has("blade_deceleration_window") ? config2.get("blade_deceleration_window").getAsFloat() : 1.5f, 0.2f, 5.0f);
        this.configMathParticleEffect = config2.has("enable_math_particle_effect") && config2.get("enable_math_particle_effect").getAsBoolean();
        this.configMathParticleDensity = Mth.clamp(config2.has("math_particle_density") ? config2.get("math_particle_density").getAsFloat() : 1.0f, 0.2f, 4.0f);
        this.configMathParticleSpread = Mth.clamp(config2.has("math_particle_spread") ? config2.get("math_particle_spread").getAsFloat() : 1.0f, 0.25f, 3.0f);
        this.configMathParticleSize = Mth.clamp(config2.has("math_particle_size") ? config2.get("math_particle_size").getAsFloat() : 1.0f, 0.25f, 3.0f);
    }

    private long resolveDisplayDuration() {
        long serverDuration = ComboIconRenderer.getServerComboWindowMs();
        if (serverDuration > 0L) {
            return serverDuration;
        }
        return this.motion.duration();
    }

    private long resolveDurationMillis(String key, float fallbackSeconds) {
        float seconds = this.getFloatConfig(key, fallbackSeconds);
        return Math.max(0L, (long)Math.round(seconds * 1000.0f));
    }

    private float resolveAlpha(long elapsed, long displayDuration) {
        return this.motion.alpha(elapsed, displayDuration, this.comboCount);
    }

    private String resolveSelectedTexture(String presetId, String textureKey) {
        return ElementTextureDefinition.getSelectedTextureFileName(presetId, "kill_icon/valorant", textureKey, this.currentConfig);
    }

    private float resolveIconOffsetY(long elapsed) {
        return this.motion.entryY(elapsed);
    }

    private float resolveBarTravel(long elapsed) {
        return this.motion.barTravel(elapsed);
    }

    private float resolveBarRotation(long elapsed) {
        return this.motion.barRotation(elapsed, this.comboCount, this.spinDirection);
    }

    private FlashState resolveFlashState(long elapsed) {
        int flashCount = Mth.clamp(this.getIntConfig("icon_flash_count", 4), 0, 12);
        if (flashCount <= 0) {
            return FlashState.none();
        }
        long holdDurationMs = this.resolveDurationMillis("icon_flash_hold_duration", 0.08f);
        if (holdDurationMs <= 0L) {
            return FlashState.none();
        }
        long gapDurationMs = Math.max(20L, (long)Math.round((float)holdDurationMs * 0.75f));
        long cycleDurationMs = holdDurationMs + gapDurationMs;
        long totalDurationMs = holdDurationMs + cycleDurationMs * ((long)flashCount - 1L);
        if (elapsed >= totalDurationMs) {
            return FlashState.none();
        }
        long cycleElapsed = elapsed % cycleDurationMs;
        if (cycleElapsed >= holdDurationMs) {
            return FlashState.none();
        }
        float holdMs = holdDurationMs;
        float localElapsed = cycleElapsed;
        float edgeDurationMs = Math.min(35.0f, holdMs * 0.25f);
        float pulseAlpha = 1.0f;
        if (edgeDurationMs > 1.0f) {
            if (localElapsed < edgeDurationMs) {
                pulseAlpha = localElapsed / edgeDurationMs;
            } else if (holdMs - localElapsed < edgeDurationMs) {
                pulseAlpha = (holdMs - localElapsed) / edgeDurationMs;
            }
        }
        int color = this.resolveConfiguredColor("color_icon_flash", 16722486);
        return new FlashState(color, Mth.clamp(pulseAlpha, 0.0f, 1.0f) * 0.9f);
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

    private float resolveTextureScale(String textureKey) {
        return this.resolveTextureFloat(textureKey, "texture_scale", 1.0f, 0.01f);
    }

    private float resolveTextureFinalOpacity(String textureKey) {
        return Mth.clamp(this.resolveTextureFloat(textureKey, "texture_final_opacity", 1.0f, 0.0f), 0.0f, 1.0f);
    }

    private float resolveTextureOffset(String textureKey, String suffixKey) {
        return this.resolveTextureFloat(textureKey, suffixKey, 0.0f, -3.4028235E38f);
    }

    private float resolveTextureFloat(String textureKey, String suffixKey, float fallback, float minimum) {
        if (this.currentConfig == null || textureKey == null) {
            return fallback;
        }
        String key = "anim_" + textureKey + "_" + suffixKey;
        if (!this.currentConfig.has(key)) {
            return fallback;
        }
        float value = this.currentConfig.get(key).getAsFloat();
        if (value < minimum) {
            return fallback;
        }
        return value;
    }

    private void generateParticleState() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        this.smallParticles = this.createSmallParticles(random);
    }

    private SmallParticle[] createSmallParticles(ThreadLocalRandom random) {
        int baseParticleCount = this.comboCount <= 1 ? 28 : 22 + this.comboCount * 3;
        int particleCount = Math.max(1, Math.round((float)baseParticleCount * this.configMathParticleDensity));
        float visibilityScale = 0.95f;
        float directionOffset = 0.0f;
        float spreadScale = this.configMathParticleSpread;
        float sizeScale = this.configMathParticleSize;
        SmallParticle[] particles = new SmallParticle[particleCount];
        for (int index = 0; index < particleCount; ++index) {
            boolean rightSide = (index & 1) == 0;
            float angleBase = rightSide ? 90.0f : -90.0f;
            float sideSpread = (this.comboCount <= 1 ? 44.0f : 56.0f) * spreadScale;
            float angle = angleBase + directionOffset + (float)random.nextDouble(-sideSpread, sideSpread);
            if (index % 4 == 0) {
                angle += rightSide ? (float)random.nextDouble(-24.0f * spreadScale, -8.0f * spreadScale) : (float)random.nextDouble(8.0f * spreadScale, 24.0f * spreadScale);
            }
            float startRadius = (float)random.nextDouble(8.0, 16.0) * spreadScale;
            float endRadius = (this.comboCount <= 1 ? (float)random.nextDouble(54.0, 82.0) : (float)random.nextDouble(62.0, 92.0)) * spreadScale;
            float width = (float)random.nextDouble(7.0, 13.5) * visibilityScale * sizeScale;
            float height = (float)random.nextDouble(1.8f, 4.0) * visibilityScale * sizeScale;
            float tilt = angle + (float)random.nextDouble(-28.0, 28.0);
            float alphaScale = (float)random.nextDouble(0.95f, 1.45f) * visibilityScale;
            float jagScale = (float)random.nextDouble(0.0, 1.0);
            float bendScale = (float)random.nextDouble(0.0, 1.0);
            float lateralBoost = (float)random.nextDouble(1.35f, 1.75);
            long delayMs = random.nextLong(0L, 30L);
            float gravityPx = (float)random.nextDouble(50.0, 82.0);
            particles[index] = new SmallParticle(angle, tilt, startRadius, endRadius, width, height, alphaScale, jagScale, bendScale, lateralBoost, gravityPx, delayMs);
        }
        return particles;
    }

    private void renderSmallParticles(GuiGraphics guiGraphics, long elapsed, float alpha, int particleRgb) {
        if (alpha <= 0.0f || elapsed >= 620L) {
            return;
        }
        for (SmallParticle particle : this.smallParticles) {
            long particleElapsed = elapsed - particle.delayMs();
            if (particleElapsed < 0L || particleElapsed > 620L) continue;
            float progress = Mth.clamp((float)particleElapsed / 620.0f, 0.0f, 1.0f);
            float move = ValorantIconRenderer.easeOutCubic(progress);
            float distance = Mth.lerp(move, particle.startRadius(), particle.endRadius());
            float angleRad = (float)Math.toRadians(particle.angleDeg());
            float horizontalFactor = Mth.sin(angleRad) * distance * particle.lateralBoost();
            float verticalFactor = -Mth.cos(angleRad) * distance * (2.15f - particle.lateralBoost());
            float x = horizontalFactor;
            float y = verticalFactor + particle.gravityPx() * ValorantIconRenderer.easeInQuad(progress);
            float particleAlpha = alpha * particle.alphaScale() * (1.0f - ValorantIconRenderer.easeInQuart(progress));
            int color = ValorantIconRenderer.applyAlpha(particleRgb, particleAlpha);
            if (color >>> 24 <= 0) continue;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x, y, 0.0);
            guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(particle.tiltDeg()));
            this.drawSmallShard(guiGraphics, particle.width(), particle.height(), particle.jagScale(), particle.bendScale(), color);
            guiGraphics.pose().popPose();
        }
    }

    private void drawSmallShard(GuiGraphics guiGraphics, float length, float thickness, float jagScale, float bendScale, int color) {
        float halfLength = Math.max(1.5f, length * 0.5f);
        float halfThickness = Math.max(0.8f, thickness * 0.5f);
        float topFlare = halfThickness * (1.25f + jagScale * 0.45f);
        float bottomFlare = halfThickness * (1.05f + (1.0f - jagScale) * 0.35f);
        float tailShoulderX = -halfLength * (0.22f - bendScale * 0.08f);
        float upperKinkX = halfLength * (0.34f + jagScale * 0.12f);
        float lowerKinkX = halfLength * (0.18f + bendScale * 0.08f);
        float tailLowerX = -halfLength * (0.58f - bendScale * 0.1f);
        float skewY = (bendScale - 0.5f) * thickness * 0.55f;
        float[] xs = new float[]{-halfLength, tailShoulderX, upperKinkX, halfLength, lowerKinkX, tailLowerX};
        float[] ys = new float[]{halfThickness * 0.38f + skewY * 0.18f, topFlare + skewY, halfThickness * 0.22f + skewY * 0.3f, 0.0f, -bottomFlare + skewY * 0.08f, -halfThickness * 0.62f + skewY * 0.45f};
        this.drawConvexPolygon(guiGraphics, xs, ys, color & 0xFFFFFF, (float)(color >>> 24 & 0xFF) / 255.0f);
    }

    private void drawConvexPolygon(GuiGraphics guiGraphics, float[] xs, float[] ys, int rgb, float alpha) {
        if (xs.length < 3 || ys.length < 3 || alpha <= 0.0f) {
            return;
        }
        int a = Mth.clamp((int)(alpha * 255.0f), 0, 255);
        if (a <= 0) {
            return;
        }
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int index = 1; index < xs.length - 1; ++index) {
            builder.vertex(matrix, xs[0], ys[0], 0.0f).color(red, green, blue, a).endVertex();
            builder.vertex(matrix, xs[index], ys[index], 0.0f).color(red, green, blue, a).endVertex();
            builder.vertex(matrix, xs[index + 1], ys[index + 1], 0.0f).color(red, green, blue, a).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private int resolveAccentColor() {
        if (this.currentConfig != null) {
            if (this.currentConfig.has("color_accent")) {
                return this.resolveConfiguredColor("color_accent", 14831708);
            }
            if (this.currentConfig.has("color_gaia_accent")) {
                return this.resolveConfiguredColor("color_gaia_accent", 14831708);
            }
        }
        return 14831708;
    }

    private int resolveDefaultParticleColor(boolean accentTintEnabled, int accentColor, boolean gaiaSkin) {
        return accentTintEnabled ? accentColor : (gaiaSkin ? 14831708 : 16765240);
    }

    private boolean isAccentTintEnabled() {
        if (this.currentConfig != null && this.currentConfig.has("enable_accent_tint")) {
            return this.currentConfig.get("enable_accent_tint").getAsBoolean();
        }
        if (this.currentConfig != null && this.currentConfig.has("color_gaia_accent")) {
            String legacyColor = this.currentConfig.get("color_gaia_accent").getAsString();
            return legacyColor != null && !legacyColor.equalsIgnoreCase(String.format("#%06X", 14831708));
        }
        return false;
    }

    private int resolveConfiguredColor(String key, int fallback) {
        if (this.currentConfig == null || key == null || !this.currentConfig.has(key)) {
            return fallback;
        }
        String raw = this.currentConfig.get(key).getAsString();
        if (raw != null && raw.matches("^#[0-9A-Fa-f]{6}$")) {
            try {
                return Integer.parseInt(raw.substring(1), 16);
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return fallback;
    }

    private void renderGradientHaloRing(GuiGraphics guiGraphics, long elapsed, float alpha) {
        if (!this.configHaloRingEnabled || alpha <= 0.001f || this.configHaloRingWidth <= 0.0f || this.configHaloRingRadius <= 0.0f) {
            return;
        }
        float focusAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/valorant", "frame");
        if (focusAlpha <= 0.001f) {
            return;
        }
        float centerX = this.resolveTextureOffset("frame", "texture_x_offset");
        float centerY = this.resolveTextureOffset("frame", "texture_y_offset");
        float outerRadius = this.configHaloRingRadius + this.configHaloRingWidth * 0.5f;
        float innerRadius = Math.max(0.0f, this.configHaloRingRadius - this.configHaloRingWidth * 0.5f);
        float minY = centerY - outerRadius;
        float maxY = centerY + outerRadius;
        float yRange = Math.max(0.001f, maxY - minY);
        int red = this.configHaloRingColor >> 16 & 0xFF;
        int green = this.configHaloRingColor >> 8 & 0xFF;
        int blue = this.configHaloRingColor & 0xFF;
        float angleOffset = (float)(elapsed % 2200L) / 2200.0f * ((float)Math.PI * 2);
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= 72; ++i) {
            float angle = (float)Math.PI * 2 * (float)i / 72.0f + angleOffset;
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            float xo = centerX + cos * outerRadius;
            float yo = centerY + sin * outerRadius;
            float xi = centerX + cos * innerRadius;
            float yi = centerY + sin * innerRadius;
            float outerAlphaFactor = Mth.clamp((maxY - yo) / yRange, 0.0f, 1.0f);
            float innerAlphaFactor = Mth.clamp((maxY - yi) / yRange, 0.0f, 1.0f);
            int ao = Mth.clamp((int)(focusAlpha * outerAlphaFactor * 255.0f), 0, 255);
            int ai = Mth.clamp((int)(focusAlpha * innerAlphaFactor * 255.0f), 0, 255);
            builder.vertex(matrix, xo, yo, 0.0f).color(red, green, blue, ao).endVertex();
            builder.vertex(matrix, xi, yi, 0.0f).color(red, green, blue, ai).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private boolean getBooleanConfig(String key, boolean fallback) {
        return this.currentConfig != null && key != null && this.currentConfig.has(key) ? this.currentConfig.get(key).getAsBoolean() : fallback;
    }

    private float getFloatConfig(String key, float fallback) {
        return this.currentConfig != null && key != null && this.currentConfig.has(key) ? this.currentConfig.get(key).getAsFloat() : fallback;
    }

    private int getIntConfig(String key, int fallback) {
        return this.currentConfig != null && key != null && this.currentConfig.has(key) ? this.currentConfig.get(key).getAsInt() : fallback;
    }

    private int resolveLayerParticleColor(String keyPrefix, int fallbackColor) {
        return this.resolveConfiguredColor("color_" + keyPrefix, fallbackColor);
    }

    private void renderBaseParticles(GuiGraphics guiGraphics, String texturePath, long elapsed, float alpha, float brightness, float contrast, int defaultColor) {
        if (!this.getBooleanConfig("enable_base_particle", true)) {
            return;
        }
        if (alpha <= 0.0f || texturePath == null || texturePath.isEmpty()) {
            return;
        }
        IconTextureAnimationManager.TextureFrame frame = IconTextureAnimationManager.getTextureFrame(ConfigManager.getCurrentPresetId(), "kill_icon/valorant", "base_particle", texturePath, this.startTime, this.currentConfig);
        float particleSize = 112.0f * this.resolveTextureScale("base_particle");
        float layerAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/valorant", "base_particle");
        if (layerAlpha <= 0.001f) {
            return;
        }
        int particleColor = this.resolveLayerParticleColor("base_particle", defaultColor);
        ResourceLocation texture = this.resolveParticleTexture(texturePath, brightness, contrast);
        this.renderMirroredParticleBurst(guiGraphics, texture, frame, particleSize, particleSize, this.resolveTextureOffset("base_particle", "texture_x_offset"), 0.0f, this.resolveTextureOffset("base_particle", "texture_y_offset"), layerAlpha, particleColor, false, 0.0f, 0.8046875f);
    }

    private void renderOverlayParticles(GuiGraphics guiGraphics, ParticleOverlaySpec spec, String texturePath, long elapsed, float alpha, float iconWidth, float iconHeight, float brightness, float contrast, int defaultColor, float extraYOffset) {
        String keyPrefix = spec.configKeyPrefix();
        if (!this.getBooleanConfig("enable_" + keyPrefix, true)) {
            return;
        }
        if (alpha <= 0.0f || texturePath == null || texturePath.isEmpty()) {
            return;
        }
        float sourceScale = spec.defaultSourceScale() * this.resolveTextureScale(keyPrefix);
        IconTextureAnimationManager.TextureFrame frame = IconTextureAnimationManager.getTextureFrame(ConfigManager.getCurrentPresetId(), "kill_icon/valorant", keyPrefix, texturePath, this.startTime, this.currentConfig);
        float drawWidth = iconWidth * sourceScale * ((float)frame.width / 1729.0f);
        float drawHeight = iconHeight * sourceScale * ((float)frame.height / 1729.0f);
        if (drawWidth <= 0.0f || drawHeight <= 0.0f) {
            return;
        }
        ResourceLocation texture = this.resolveParticleTexture(texturePath, brightness, contrast);
        float layerAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/valorant", keyPrefix);
        if (layerAlpha <= 0.001f) {
            return;
        }
        int particleColor = this.resolveLayerParticleColor(keyPrefix, defaultColor);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(spec.offsetXPx() + this.resolveTextureOffset(keyPrefix, "texture_x_offset"), spec.offsetYPx() + this.resolveTextureOffset(keyPrefix, "texture_y_offset") + extraYOffset, 0.0);
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, spec.anchorXRatio(), spec.anchorYRatio(), layerAlpha, particleColor, spec.additive(), false);
        guiGraphics.pose().popPose();
    }

    private void renderMirroredParticleBurst(GuiGraphics guiGraphics, ResourceLocation texture, IconTextureAnimationManager.TextureFrame frame, float drawWidth, float drawHeight, float centerOffsetX, float mirrorOffsetX, float centerOffsetY, float alpha, int rgb, boolean additive, float anchorXRatio, float anchorYRatio) {
        if (alpha <= 0.0f || texture == null) {
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerOffsetX + mirrorOffsetX, centerOffsetY, 0.0);
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, anchorXRatio, anchorYRatio, alpha, rgb, additive, false);
        guiGraphics.pose().popPose();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(centerOffsetX - mirrorOffsetX, centerOffsetY, 0.0);
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 1.0f - anchorXRatio, anchorYRatio, alpha, rgb, additive, true);
        guiGraphics.pose().popPose();
    }

    private ResourceLocation resolveParticleTexture(String texturePath, float brightness, float contrast) {
        if (!this.needsTextureProcessing(texturePath, false, brightness, contrast)) {
            return ModTextures.get(texturePath);
        }
        ResourceLocation processedTexture = IconGlowProcessor.getOrCreateProcessedTexture(ConfigManager.getCurrentPresetId(), texturePath, false, 0xFFFFFF, brightness, contrast);
        return processedTexture != null ? processedTexture : ModTextures.get(texturePath);
    }

    private void drawCenteredTexture(GuiGraphics guiGraphics, String textureKey, String texturePath, float drawWidth, float drawHeight, float alpha, int rgb, boolean additive) {
        if (texturePath == null) {
            return;
        }
        ResourceLocation texture = ModTextures.get(texturePath);
        if (texture == null) {
            return;
        }
        IconTextureAnimationManager.TextureFrame frame = this.resolveAnimatedFrame(textureKey, texturePath);
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 0.5f, 0.5f, alpha, rgb, additive, false);
    }

    private void drawColorizableTexture(GuiGraphics guiGraphics, String textureKey, String texturePath, float drawWidth, float drawHeight, float alpha, int rgb, boolean additive, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        boolean translated;
        if ((alpha *= PreviewTextureFocusContext.alphaMultiplier("kill_icon/valorant", textureKey)) <= 0.001f) {
            return;
        }
        float finalOpacity = this.resolveTextureFinalOpacity(textureKey);
        if (finalOpacity <= 0.001f) {
            return;
        }
        if ((alpha *= finalOpacity) <= 0.001f) {
            return;
        }
        float offsetX = this.resolveTextureOffset(textureKey, "texture_x_offset");
        float offsetY = this.resolveTextureOffset(textureKey, "texture_y_offset");
        boolean bl = translated = Math.abs(offsetX) > 0.001f || Math.abs(offsetY) > 0.001f;
        if (translated) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(offsetX, offsetY, 0.0);
        }
        ResourceLocation processedTexture = null;
        boolean useProcessedTexture = this.needsTextureProcessing(texturePath, accentTintEnabled, brightness, contrast);
        if (useProcessedTexture) {
            processedTexture = IconGlowProcessor.getOrCreateProcessedTexture(ConfigManager.getCurrentPresetId(), texturePath, accentTintEnabled, accentColor, brightness, contrast);
        }
        if (this.configIconGlowEnabled) {
            if (processedTexture != null) {
                this.drawGlowResourceTexture(guiGraphics, processedTexture, textureKey, texturePath, drawWidth, drawHeight, alpha);
            } else {
                this.drawGlowTexture(guiGraphics, textureKey, texturePath, drawWidth, drawHeight, alpha, accentTintEnabled, accentColor, brightness, contrast);
            }
        }
        if (!useProcessedTexture || processedTexture == null) {
            this.drawCenteredTexture(guiGraphics, textureKey, texturePath, drawWidth, drawHeight, alpha, rgb, additive);
            if (translated) {
                guiGraphics.pose().popPose();
            }
            return;
        }
        this.drawCenteredResourceTexture(guiGraphics, processedTexture, textureKey, texturePath, drawWidth, drawHeight, alpha, rgb, additive);
        if (translated) {
            guiGraphics.pose().popPose();
        }
    }

    private void drawCenteredResourceTexture(GuiGraphics guiGraphics, ResourceLocation texture, String textureKey, String texturePath, float drawWidth, float drawHeight, float alpha, int rgb, boolean additive) {
        if (texture == null) {
            return;
        }
        IconTextureAnimationManager.TextureFrame frame = this.resolveAnimatedFrame(textureKey, texturePath);
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 0.5f, 0.5f, alpha, rgb, additive, false);
    }

    private void drawGlowTexture(GuiGraphics guiGraphics, String textureKey, String texturePath, float drawWidth, float drawHeight, float alpha, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        ResourceLocation processedTexture;
        if (texturePath == null || texturePath.isEmpty()) {
            return;
        }
        boolean useProcessedTexture = this.needsTextureProcessing(texturePath, accentTintEnabled, brightness, contrast);
        if (useProcessedTexture && (processedTexture = IconGlowProcessor.getOrCreateProcessedTexture(ConfigManager.getCurrentPresetId(), texturePath, accentTintEnabled, accentColor, brightness, contrast)) != null) {
            this.drawGlowResourceTexture(guiGraphics, processedTexture, textureKey, texturePath, drawWidth, drawHeight, alpha);
            return;
        }
        ResourceLocation texture = ModTextures.get(texturePath);
        if (texture != null) {
            this.drawGlowResourceTexture(guiGraphics, texture, textureKey, texturePath, drawWidth, drawHeight, alpha);
        }
    }

    private void drawGlowResourceTexture(GuiGraphics guiGraphics, ResourceLocation texture, String textureKey, String texturePath, float drawWidth, float drawHeight, float alpha) {
        if (texture == null || !this.configIconGlowEnabled || this.configIconGlowSize <= 0.01f || this.configIconGlowIntensity <= 0.001f || alpha <= 0.001f) {
            return;
        }
        float outerSpread = Math.max(0.5f, this.configIconGlowSize);
        float innerSpread = outerSpread * 0.55f;
        float glowAlpha = Mth.clamp(alpha * this.configIconGlowIntensity, 0.0f, 1.0f);
        int color = this.configIconGlowColor;
        IconTextureAnimationManager.TextureFrame frame = this.resolveAnimatedFrame(textureKey, texturePath);
        for (float[] offset : GLOW_OFFSETS) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(offset[0] * outerSpread, offset[1] * outerSpread, 0.0);
            this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 0.5f, 0.5f, glowAlpha * 0.16f, color, true, false);
            guiGraphics.pose().popPose();
        }
        for (float[] offset : GLOW_OFFSETS) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(offset[0] * innerSpread, offset[1] * innerSpread, 0.0);
            this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 0.5f, 0.5f, glowAlpha * 0.11f, color, true, false);
            guiGraphics.pose().popPose();
        }
        this.drawAnchoredResourceTextureFrame(guiGraphics, texture, frame, drawWidth, drawHeight, 0.5f, 0.5f, glowAlpha * 0.09f, color, true, false);
    }

    private IconTextureAnimationManager.TextureFrame resolveAnimatedFrame(String textureKey, String texturePath) {
        if (texturePath == null || texturePath.isEmpty() || textureKey == null || textureKey.isEmpty()) {
            return new IconTextureAnimationManager.TextureFrame(0, 0, 1, 1, 1, 1);
        }
        return IconTextureAnimationManager.getTextureFrame(ConfigManager.getCurrentPresetId(), "kill_icon/valorant", textureKey, texturePath, this.startTime, this.currentConfig);
    }

    private void drawAnchoredResourceTextureFrame(GuiGraphics guiGraphics, ResourceLocation texture, IconTextureAnimationManager.TextureFrame frame, float drawWidth, float drawHeight, float anchorXRatio, float anchorYRatio, float alpha, int rgb, boolean additive, boolean flipX) {
        if (texture == null || frame == null) {
            return;
        }
        if (additive) {
            RenderSystem.blendFunc(770, 1);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        float red = (float)(rgb >> 16 & 0xFF) / 255.0f;
        float green = (float)(rgb >> 8 & 0xFF) / 255.0f;
        float blue = (float)(rgb & 0xFF) / 255.0f;
        float x0 = -drawWidth * anchorXRatio;
        float y0 = -drawHeight * anchorYRatio;
        float x1 = x0 + drawWidth;
        float y1 = y0 + drawHeight;
        float u0 = (float)frame.u / (float)frame.totalWidth;
        float u1 = (float)(frame.u + frame.width) / (float)frame.totalWidth;
        float v0 = (float)frame.v / (float)frame.totalHeight;
        float v1 = (float)(frame.v + frame.height) / (float)frame.totalHeight;
        if (flipX) {
            float swapped = u0;
            u0 = u1;
            u1 = swapped;
        }
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(red, green, blue, alpha);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, x0, y1, 0.0f).uv(u0, v1).endVertex();
        builder.vertex(matrix, x1, y1, 0.0f).uv(u1, v1).endVertex();
        builder.vertex(matrix, x1, y0, 0.0f).uv(u1, v0).endVertex();
        builder.vertex(matrix, x0, y0, 0.0f).uv(u0, v0).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    private void renderBarRing(GuiGraphics guiGraphics, String barTexturePath, float barWidth, float barHeight, float barTravel, float alpha, float barRotation, float barRadiusOffset, float barCenterXOffset, float barCenterYOffset, float[] layoutAngles, boolean accentTintEnabled, int accentColor, float brightness, float contrast, long elapsed) {
        float distance = 36.0f + barRadiusOffset + barTravel;
        if ((alpha *= this.motion.barAlpha(elapsed)) <= 0.001f) {
            return;
        }
        float entryScale = this.resolveBarEntryScale(this.motion.barElapsed(elapsed));
        for (float layoutAngle : layoutAngles) {
            guiGraphics.pose().pushPose();
            if (barCenterXOffset != 0.0f || barCenterYOffset != 0.0f) {
                guiGraphics.pose().translate(barCenterXOffset, barCenterYOffset, 0.0);
            }
            guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(layoutAngle + barRotation));
            guiGraphics.pose().translate(0.0, -distance, 0.0);
            float orientationOffset = this.motion.barOrientationOffset(layoutAngle, this.comboCount);
            if (Math.abs(orientationOffset) > 0.001f) {
                guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(orientationOffset));
            }
            if (this.motion.usesFoxFan() && layoutAngle < 0.0f) {
                guiGraphics.pose().scale(-1.0f, 1.0f, 1.0f);
            }
            if (Math.abs(entryScale - 1.0f) > 0.001f) {
                guiGraphics.pose().scale(entryScale, entryScale, 1.0f);
            }
            this.drawColorizableTexture(guiGraphics, "bar", barTexturePath, barWidth, barHeight, alpha, 0xFFFFFF, false, accentTintEnabled, accentColor, brightness, contrast);
            RenderSystem.defaultBlendFunc();
            guiGraphics.pose().popPose();
        }
    }

    private float resolveBarEntryScale(long elapsed) {
        long durationMs = Math.round(this.configBarEntryDuration * 1000.0f);
        if (durationMs <= 0L || this.configBarEntryInitialScale <= 1.0f) {
            return 1.0f;
        }
        if (elapsed >= durationMs) {
            return 1.0f;
        }
        float t = Mth.clamp((float)elapsed / (float)durationMs, 0.0f, 1.0f);
        return Mth.lerp(ValorantIconRenderer.easeOutCubic(t), this.configBarEntryInitialScale, 1.0f);
    }

    private boolean isAccentTintableTexture(String texturePath) {
        return texturePath != null && !texturePath.isEmpty();
    }

    private boolean needsTextureProcessing(String texturePath, boolean accentTintEnabled, float brightness, float contrast) {
        if (texturePath == null || texturePath.isEmpty()) {
            return false;
        }
        if (accentTintEnabled && this.isAccentTintableTexture(texturePath)) {
            return true;
        }
        return Math.abs(brightness - 1.0f) > 0.001f || Math.abs(contrast - 1.0f) > 0.001f;
    }

    private static float easeOutCubic(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        float inverted = 1.0f - clamped;
        return 1.0f - inverted * inverted * inverted;
    }

    private static float easeInQuart(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        return clamped * clamped * clamped * clamped;
    }

    private static float easeInQuad(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        return clamped * clamped;
    }

    private static float easeOutQuad(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        float inverted = 1.0f - clamped;
        return 1.0f - inverted * inverted;
    }

    private static float easeOutSoft(float value) {
        float quad = ValorantIconRenderer.easeOutQuad(value);
        float cubic = ValorantIconRenderer.easeOutCubic(value);
        return Mth.lerp(0.2f, quad, cubic);
    }

    private static int applyAlpha(int rgb, float alpha) {
        int a = Mth.clamp((int)(alpha * 255.0f), 0, 255);
        return a << 24 | rgb;
    }

    private float resolveFrameBladeRotation(String styleId, long elapsed) {
        if (!this.configBladeRotationEffect) {
            return 0.0f;
        }
        float baseDegPerSecond = 1350.0f;
        if (styleId != null && styleId.contains("gaia")) {
            baseDegPerSecond = 1100.0f;
        }
        float windowSec = Math.max(0.2f, this.configBladeDecelerationWindowSec);
        float t = Mth.clamp((float)elapsed / (windowSec * 1000.0f), 0.0f, 1.0f);
        float totalDegrees = baseDegPerSecond * windowSec * (1.0f - (float)Math.pow(1.0f - t, 4.0)) * 0.25f;
        return -(totalDegrees * this.spinDirection);
    }

    private float resolveEffectiveBrightness(boolean gaiaSkin) {
        return gaiaSkin ? this.configBrightness * 1.3f : this.configBrightness;
    }

    private float resolveEffectiveContrast(boolean gaiaSkin) {
        return gaiaSkin ? 1.1f : 1.0f;
    }

    private String resolveStyleId() {
        return ValorantStyleCatalog.resolveStyleId(ConfigManager.getCurrentPresetId(), this.currentConfig);
    }

    private String resolveBladeTexturePath(String styleId, String selectedBladeTexturePath) {
        if (selectedBladeTexturePath != null && !selectedBladeTexturePath.trim().isEmpty()) {
            return selectedBladeTexturePath;
        }
        String styleBlade = ValorantStyleCatalog.getFrameBladeTextureFileName(styleId);
        if (styleBlade != null && !styleBlade.trim().isEmpty()) {
            return styleBlade;
        }
        return ValorantStyleCatalog.getOfficialTextureFileNameForStyle("bubblegum_deathwish", "blade");
    }

    private boolean isGaiaSkin(String styleId, String iconTexturePath, String barTexturePath) {
        if (styleId != null && styleId.contains("gaia")) {
            return true;
        }
        return iconTexturePath != null && iconTexturePath.contains("gaia") || barTexturePath != null && barTexturePath.contains("gaia");
    }

    private boolean isEntryMotionFrameLocked(String styleId) {
        return ValorantStyleCatalog.useEmblemOnlyEntryMotion(styleId);
    }

    private HeadshotAnimState resolveHeadshotAnimState(long elapsed, int restColor) {
        int color;
        long durationMs = Math.round(this.configHeadshotAnimDuration * 1000.0f);
        if (durationMs <= 0L || this.configHeadshotAnimInitialScale <= 1.0f) {
            return HeadshotAnimState.idle(restColor);
        }
        if (elapsed >= durationMs) {
            return HeadshotAnimState.idle(restColor);
        }
        float t = Mth.clamp((float)elapsed / (float)durationMs, 0.0f, 1.0f);
        float scale = Mth.lerp(ValorantIconRenderer.easeOutCubic(t), this.configHeadshotAnimInitialScale, 1.0f);
        float flickerFreq = 18.0f;
        if (flickerFreq <= 0.0f) {
            color = restColor;
        } else {
            float phase = (float)Math.sin((double)((float)elapsed * flickerFreq) * Math.PI * 2.0 / 1000.0);
            boolean flashWhite = phase > 0.0f;
            float envelope = (1.0f - t) * (1.0f - t);
            color = flashWhite ? ValorantIconRenderer.lerpColor(restColor, 0xFFFFFF, envelope) : restColor;
        }
        return new HeadshotAnimState(scale, color);
    }

    private static int lerpColor(int colorA, int colorB, float t) {
        int rA = colorA >> 16 & 0xFF;
        int gA = colorA >> 8 & 0xFF;
        int bA = colorA & 0xFF;
        int rB = colorB >> 16 & 0xFF;
        int gB = colorB >> 8 & 0xFF;
        int bB = colorB & 0xFF;
        int r = Math.round(Mth.lerp(t, rA, rB));
        int g = Math.round(Mth.lerp(t, gA, gB));
        int b = Math.round(Mth.lerp(t, bA, bB));
        return r << 16 | g << 8 | b;
    }

    private static final class SmallParticle {
        private final float angleDeg;
        private final float tiltDeg;
        private final float startRadius;
        private final float endRadius;
        private final float width;
        private final float height;
        private final float alphaScale;
        private final float jagScale;
        private final float bendScale;
        private final float lateralBoost;
        private final float gravityPx;
        private final long delayMs;

        public SmallParticle(float angleDeg, float tiltDeg, float startRadius, float endRadius, float width, float height, float alphaScale, float jagScale, float bendScale, float lateralBoost, float gravityPx, long delayMs) {
            this.angleDeg = angleDeg;
            this.tiltDeg = tiltDeg;
            this.startRadius = startRadius;
            this.endRadius = endRadius;
            this.width = width;
            this.height = height;
            this.alphaScale = alphaScale;
            this.jagScale = jagScale;
            this.bendScale = bendScale;
            this.lateralBoost = lateralBoost;
            this.gravityPx = gravityPx;
            this.delayMs = delayMs;
        }

        public float angleDeg() {
            return this.angleDeg;
        }

        public float tiltDeg() {
            return this.tiltDeg;
        }

        public float startRadius() {
            return this.startRadius;
        }

        public float endRadius() {
            return this.endRadius;
        }

        public float width() {
            return this.width;
        }

        public float height() {
            return this.height;
        }

        public float alphaScale() {
            return this.alphaScale;
        }

        public float jagScale() {
            return this.jagScale;
        }

        public float bendScale() {
            return this.bendScale;
        }

        public float lateralBoost() {
            return this.lateralBoost;
        }

        public float gravityPx() {
            return this.gravityPx;
        }

        public long delayMs() {
            return this.delayMs;
        }
    }

    private static final class HeadshotAnimState {
        private final float scale;
        private final int color;

        public HeadshotAnimState(float scale, int color) {
            this.scale = scale;
            this.color = color;
        }

        public float scale() {
            return this.scale;
        }

        public int color() {
            return this.color;
        }

        private static HeadshotAnimState idle(int restColor) {
            return new HeadshotAnimState(1.0f, restColor);
        }
    }

    private static final class FlashState {
        private final int color;
        private final float alpha;

        public FlashState(int color, float alpha) {
            this.color = color;
            this.alpha = alpha;
        }

        public int color() {
            return this.color;
        }

        public float alpha() {
            return this.alpha;
        }

        private static FlashState none() {
            return new FlashState(0xFFFFFF, 0.0f);
        }
    }

    private static final class ParticleOverlaySpec {
        private final String configKeyPrefix;
        private final float anchorXRatio;
        private final float anchorYRatio;
        private final float defaultSourceScale;
        private final float offsetXPx;
        private final float offsetYPx;
        private final boolean additive;

        public ParticleOverlaySpec(String configKeyPrefix, float anchorXRatio, float anchorYRatio, float defaultSourceScale, float offsetXPx, float offsetYPx, boolean additive) {
            this.configKeyPrefix = configKeyPrefix;
            this.anchorXRatio = anchorXRatio;
            this.anchorYRatio = anchorYRatio;
            this.defaultSourceScale = defaultSourceScale;
            this.offsetXPx = offsetXPx;
            this.offsetYPx = offsetYPx;
            this.additive = additive;
        }

        public String configKeyPrefix() {
            return this.configKeyPrefix;
        }

        public float anchorXRatio() {
            return this.anchorXRatio;
        }

        public float anchorYRatio() {
            return this.anchorYRatio;
        }

        public float defaultSourceScale() {
            return this.defaultSourceScale;
        }

        public float offsetXPx() {
            return this.offsetXPx;
        }

        public float offsetYPx() {
            return this.offsetYPx;
        }

        public boolean additive() {
            return this.additive;
        }
    }
}

