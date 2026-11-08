/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.client.resources.I18n
 *  net.minecraft.entity.Entity
 *  net.minecraft.entity.EntityLivingBase
 *  net.minecraft.item.ItemStack
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
import gale.kill.gd.client.sounds.ExternalSoundManager;
import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.client.util.ClientMessageLogger;
import gale.kill.gd.common.killtype.KillTypeRegistry;
import gale.kill.gd.compat.Font;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.PoseStack;
import gale.kill.gd.compat.RenderSystem;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

public class Battlefield1Renderer
implements IHudRenderer {
    private static final String SOUND_NAME = "killsound_bf1";
    private static final String HEADSHOT_SOUND_NAME = "headshotkillsound_bf1";
    private boolean visible = true;
    private int iconSize = 40;
    private int borderSize = 3;
    private int xOffset = 0;
    private int yOffset = 0;
    private String screenAnchor = "bottom_center";
    private int backgroundColor = 0;
    private float iconBoxAlpha = 0.2f;
    private float textBoxAlpha = 0.1f;
    private float scaleWeapon = 1.0f;
    private float scaleVictim = 1.2f;
    private float scaleHealth = 1.5f;
    private int colorVictim = 0xFF0000;
    private long animationDuration = 200L;
    private long displayDuration = 300L;
    private boolean enableIconGlow = false;
    private int iconGlowColor = 0xFFFFFF;
    private float iconGlowIntensity = 0.45f;
    private float iconGlowSize = 4.0f;
    private JsonObject currentConfig;
    private long startTime = -1L;
    private boolean isVisible = false;
    private static final int MAX_QUEUE_SIZE = 5;
    private final Queue<IHudRenderer.TriggerContext> displayQueue = new ArrayDeque<IHudRenderer.TriggerContext>();
    private long lastSwitchTime = 0L;
    private String weaponName = "";
    private String victimName = "";
    private String healthText = "";
    private int killType = 0;
    private String currentIconPath = "killicon_battlefield1_default.png";

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        if (context.type() == 4) {
            return;
        }
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "battlefield1");
        if (config2 == null) {
            return;
        }
        if (this.displayQueue.size() < 5) {
            this.displayQueue.offer(context);
        }
    }

    private void processContext(IHudRenderer.TriggerContext context) {
        String extraData;
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "battlefield1");
        if (config2 == null) {
            return;
        }
        this.loadConfig(config2);
        if (!this.visible) {
            this.isVisible = false;
            return;
        }
        this.killType = context.type();
        Minecraft mc = Minecraft.getMinecraft();
        String soundSlot = "bf1_default";
        if (this.killType == 1) {
            soundSlot = "bf1_headshot";
        }
        String textureKey = this.getTextureKey(this.killType);
        this.currentIconPath = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/battlefield1", textureKey, this.currentConfig);
        ExternalSoundManager.playConfiguredSound(ConfigManager.getCurrentPresetId(), soundSlot);
        String string = extraData = context.extraData() == null ? "" : context.extraData();
        if (this.killType == 5) {
            ItemStack held;
            if (!extraData.isEmpty() && extraData.contains("|")) {
                String[] parts = extraData.split("\\|", 2);
                this.victimName = parts.length > 0 ? I18n.format((String)parts[0], (Object[])new Object[0]) : I18n.format((String)"gd656killicon.client.text.vehicle", (Object[])new Object[0]);
                this.healthText = parts.length > 1 ? parts[1] : "?";
            } else {
                this.victimName = I18n.format((String)"gd656killicon.client.text.vehicle", (Object[])new Object[0]);
                this.healthText = "?";
            }
            this.weaponName = mc.thePlayer != null ? (mc.thePlayer.ridingEntity != null ? mc.thePlayer.ridingEntity.getDisplayName().getUnformattedText() : ((held = mc.thePlayer.getHeldItem()) == null ? I18n.format((String)"gd656killicon.client.text.bare_hand", (Object[])new Object[0]) : held.getDisplayName())) : "Unknown";
        } else {
            Entity entity;
            ItemStack held;
            this.weaponName = mc.thePlayer != null ? (mc.thePlayer.ridingEntity != null ? mc.thePlayer.ridingEntity.getDisplayName().getUnformattedText() : ((held = mc.thePlayer.getHeldItem()) == null ? I18n.format((String)"gd656killicon.client.text.bare_hand", (Object[])new Object[0]) : held.getDisplayName())) : "Unknown";
            String nameOverride = null;
            if (!extraData.isEmpty()) {
                String extra = extraData;
                nameOverride = extra.contains("|") ? extra.substring(0, extra.indexOf("|")) : extra;
            }
            if (nameOverride != null) {
                this.victimName = I18n.format(nameOverride, (Object[])new Object[0]);
                if (mc.theWorld != null && context.entityId() != -1) {
                    entity = mc.theWorld.getEntityByID(context.entityId());
                    if (entity instanceof EntityLivingBase) {
                        EntityLivingBase living = (EntityLivingBase)entity;
                        this.healthText = String.valueOf((int)living.getMaxHealth());
                    } else {
                        this.healthText = "20";
                    }
                } else {
                    this.healthText = "?";
                }
            } else if (mc.theWorld != null && context.entityId() != -1) {
                entity = mc.theWorld.getEntityByID(context.entityId());
                if (entity != null) {
                    this.victimName = entity.getDisplayName().getUnformattedText();
                    if (entity instanceof EntityLivingBase) {
                        EntityLivingBase living = (EntityLivingBase)entity;
                        float maxHealth = living.getMaxHealth();
                        this.healthText = String.valueOf((int)maxHealth);
                    } else {
                        this.healthText = "20";
                    }
                } else {
                    this.victimName = "Unknown";
                    this.healthText = "?";
                }
            } else {
                this.victimName = "Unknown";
                this.healthText = "?";
            }
        }
        this.lastSwitchTime = this.startTime = PreviewRenderTimeContext.currentTimeMillis();
        this.isVisible = true;
    }

    private String getTextureKey(int killType) {
        return KillTypeRegistry.get(killType).bf1TextureKey();
    }

    private long resolveQueueSwitchDelay(int nextKillType) {
        long baseDelay = this.animationDuration;
        if (this.killType == 5 || nextKillType == 5) {
            baseDelay = Math.max(baseDelay, this.displayDuration);
        }
        return baseDelay;
    }

    public void triggerPreview(int killType, String weaponName, String victimName, String healthText) {
        JsonObject config2 = ConfigManager.getElementConfig("kill_icon", "battlefield1");
        if (config2 == null) {
            return;
        }
        this.loadConfig(config2);
        if (!this.visible) {
            this.isVisible = false;
            return;
        }
        this.killType = killType;
        this.weaponName = weaponName;
        this.victimName = victimName;
        this.healthText = healthText;
        String textureKey = this.getTextureKey(this.killType);
        this.currentIconPath = ElementTextureDefinition.getSelectedTextureFileName(ConfigManager.getCurrentPresetId(), "kill_icon/battlefield1", textureKey, this.currentConfig);
        this.lastSwitchTime = this.startTime = PreviewRenderTimeContext.currentTimeMillis();
        this.isVisible = true;
        this.displayQueue.clear();
    }

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        if (!this.displayQueue.isEmpty()) {
            IHudRenderer.TriggerContext nextContext;
            int nextKillType;
            long switchDelay;
            long elapsedSinceSwitch;
            boolean shouldSwitch;
            boolean bl = shouldSwitch = !this.isVisible;
            if (this.isVisible && (elapsedSinceSwitch = currentTime - this.lastSwitchTime) >= (switchDelay = this.resolveQueueSwitchDelay(nextKillType = (nextContext = this.displayQueue.peek()) != null ? nextContext.type() : 0))) {
                shouldSwitch = true;
            }
            if (shouldSwitch) {
                this.processContext(this.displayQueue.poll());
            }
        }
        if (!this.isVisible || this.startTime == -1L) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        float centerX = ScreenAnchor.resolveCenterX(this.screenAnchor, this.xOffset, screenWidth);
        float centerY = ScreenAnchor.resolveCenterY(this.screenAnchor, this.yOffset, screenHeight);
        this.renderInternal(guiGraphics, partialTick, currentTime, centerX, centerY);
    }

    public void renderAt(GuiGraphics guiGraphics, float partialTick, float centerX, float centerY) {
        if (!this.isVisible || this.startTime == -1L) {
            return;
        }
        long currentTime = PreviewRenderTimeContext.currentTimeMillis();
        this.renderInternal(guiGraphics, partialTick, currentTime, centerX, centerY);
    }

    private void renderInternal(GuiGraphics guiGraphics, float partialTick, long currentTime, float centerX, float centerY) {
        float subBoxH;
        float subBoxRight;
        float victimY;
        long elapsed = currentTime - this.startTime;
        float alpha = this.getAlpha(elapsed);
        float globalScale = this.getGlobalScale(elapsed);
        if (this.displayQueue.isEmpty() && alpha <= 0.001f && elapsed > this.displayDuration) {
            this.isVisible = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Font font = Font.INSTANCE;
        int weaponW = font.width(this.weaponName);
        int victimW = font.width(this.victimName);
        int healthW = font.width(this.healthText);
        int fontH = font.lineHeight;
        float effWeaponW = (float)weaponW * this.scaleWeapon;
        float effWeaponH = (float)fontH * this.scaleWeapon;
        float effVictimW = (float)victimW * this.scaleVictim;
        float effVictimH = (float)fontH * this.scaleVictim;
        float effHealthW = (float)healthW * this.scaleHealth;
        float effHealthH = (float)fontH * this.scaleHealth;
        float weaponX = -effWeaponW / 2.0f;
        float weaponY = -effWeaponH / 2.0f;
        float weaponRight = weaponX + effWeaponW;
        float weaponTop = weaponY;
        float weaponBottom = weaponY + effWeaponH;
        float victimRight = weaponRight;
        float victimX = victimRight - effVictimW;
        float victimBottom = weaponTop - (float)this.borderSize;
        float victimTop = victimY = victimBottom - effVictimH;
        float victimLeft = victimX;
        float healthX = weaponRight + (float)this.borderSize;
        float spanTop = victimTop;
        float spanBottom = weaponBottom;
        float midY = (spanTop + spanBottom) / 2.0f;
        float healthY = midY - effHealthH / 2.0f;
        float healthRight = healthX + effHealthW;
        float healthTop = healthY;
        float healthBottom = healthY + effHealthH;
        float healthLeft = healthX;
        float destroyW = 0.0f;
        float destroyH = 0.0f;
        float destroyX = 0.0f;
        float destroyY = 0.0f;
        if (this.killType == 5) {
            String destroyText = I18n.format((String)"gd656killicon.client.text.vehicle_destroyed", (Object[])new Object[0]);
            destroyW = (float)font.width(destroyText) * this.scaleVictim;
            font.getClass();
            destroyH = 9.0f * this.scaleVictim;
            destroyY = midY - destroyH / 2.0f;
            destroyX = healthX - (float)this.borderSize - destroyW;
        }
        float subBoxTop = Math.min(weaponTop, Math.min(healthTop, victimTop)) - (float)this.borderSize;
        if (this.killType == 5) {
            subBoxRight = Math.max(weaponRight, Math.max(healthRight, victimRight)) + (float)this.borderSize;
        } else {
            float distY = healthTop - subBoxTop;
            distY = Math.max(distY, (float)this.borderSize);
            subBoxRight = healthRight + distY;
        }
        float subBoxLeft = Math.min(weaponX, Math.min(healthLeft, victimLeft)) - (float)this.borderSize;
        float subBoxBottom = Math.max(weaponBottom, Math.max(healthBottom, victimBottom)) + (float)this.borderSize;
        if (this.killType == 5) {
            subBoxLeft = Math.min(subBoxLeft, destroyX - (float)this.borderSize);
            subBoxTop = Math.min(subBoxTop, destroyY - (float)this.borderSize);
            subBoxBottom = Math.max(subBoxBottom, destroyY + destroyH + (float)this.borderSize);
        }
        float subBoxW = subBoxRight - subBoxLeft;
        float iconBoxSize = subBoxH = subBoxBottom - subBoxTop;
        float iconBoxRight = subBoxLeft;
        float iconBoxTop = subBoxTop;
        float iconBoxLeft = iconBoxRight - iconBoxSize;
        float iconBoxBottom = iconBoxTop + iconBoxSize;
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0.0);
        pose.scale(globalScale, globalScale, 1.0f);
        float subBoxFinalAlpha = alpha * this.textBoxAlpha;
        int subBoxColor = (int)(subBoxFinalAlpha * 255.0f) << 24 | this.backgroundColor & 0xFFFFFF;
        guiGraphics.fill((int)subBoxLeft, (int)subBoxTop, (int)subBoxRight, (int)subBoxBottom, subBoxColor);
        float iconBoxFinalAlpha = alpha * this.iconBoxAlpha;
        int iconBoxColor = (int)(iconBoxFinalAlpha * 255.0f) << 24 | this.backgroundColor & 0xFFFFFF;
        guiGraphics.fill((int)iconBoxLeft, (int)iconBoxTop, (int)iconBoxRight, (int)iconBoxBottom, iconBoxColor);
        float iconDrawSize = this.iconSize;
        float iconCenterX = iconBoxLeft + iconBoxSize / 2.0f;
        float iconCenterY = iconBoxTop + iconBoxSize / 2.0f;
        float iconDrawX = iconCenterX - iconDrawSize / 2.0f;
        float iconDrawY = iconCenterY - iconDrawSize / 2.0f;
        this.renderIcon(guiGraphics, iconDrawX, iconDrawY, iconDrawSize, alpha);
        int textAlpha = (int)(alpha * 255.0f);
        if (textAlpha < 5) {
            textAlpha = 5;
        }
        if (this.killType == 5) {
            String destroyText = I18n.format((String)"gd656killicon.client.text.vehicle_destroyed", (Object[])new Object[0]);
            this.drawScaledText(guiGraphics, font, destroyText, destroyX, destroyY, this.scaleVictim, 0xFFFFFF, textAlpha);
        } else {
            this.drawScaledText(guiGraphics, font, this.weaponName, weaponX, weaponY, this.scaleWeapon, 0xFFFFFF, textAlpha);
            this.drawScaledText(guiGraphics, font, this.victimName, victimX, victimY, this.scaleVictim, this.colorVictim, textAlpha);
        }
        this.drawScaledText(guiGraphics, font, this.healthText, healthX, healthY, this.scaleHealth, 0xFFFFFF, textAlpha);
        pose.popPose();
    }

    private void renderIcon(GuiGraphics guiGraphics, float x, float y, float size, float alpha) {
        ResourceLocation texture = ExternalTextureManager.getTexture(this.currentIconPath);
        String textureKey = this.getBattlefieldTextureKey();
        float frameWidthRatio = this.resolveFrameRatio(textureKey, "texture_frame_width_ratio");
        float frameHeightRatio = this.resolveFrameRatio(textureKey, "texture_frame_height_ratio");
        float drawWidth = size * frameWidthRatio;
        float drawHeight = size * frameHeightRatio;
        float drawX = x + (size - drawWidth) / 2.0f;
        float drawY = y + (size - drawHeight) / 2.0f;
        float focusedAlpha = alpha * PreviewTextureFocusContext.alphaMultiplier("kill_icon/battlefield1", textureKey);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
        RenderSystem.enableBlend();
        if (this.enableIconGlow) {
            IconGlowRenderEffect.drawGlowFrame(guiGraphics, texture, (int)drawX, (int)drawY, (int)drawWidth, (int)drawHeight, 0, 0, (int)drawWidth, (int)drawHeight, (int)drawWidth, (int)drawHeight, focusedAlpha, this.iconGlowColor, this.iconGlowIntensity, this.iconGlowSize);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, focusedAlpha);
        }
        guiGraphics.blit(texture, (int)drawX, (int)drawY, 0.0f, 0.0f, (int)drawWidth, (int)drawHeight, (int)drawWidth, (int)drawHeight);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawScaledText(GuiGraphics guiGraphics, Font font, String text, float x, float y, float scale, int colorRGB, int alpha) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(scale, scale, 1.0f);
        int color = alpha << 24 | colorRGB & 0xFFFFFF;
        guiGraphics.drawString(font, text, 0, 0, color, true);
        pose.popPose();
    }

    private void loadConfig(JsonObject config2) {
        try {
            this.currentConfig = config2;
            this.visible = !config2.has("visible") || config2.get("visible").getAsBoolean();
            this.iconSize = config2.has("icon_size") ? config2.get("icon_size").getAsInt() : 40;
            this.borderSize = config2.has("border_size") ? config2.get("border_size").getAsInt() : 3;
            this.xOffset = config2.has("x_offset") ? config2.get("x_offset").getAsInt() : 0;
            this.yOffset = config2.has("y_offset") ? config2.get("y_offset").getAsInt() : 0;
            this.screenAnchor = config2.has("screen_anchor") ? config2.get("screen_anchor").getAsString() : "bottom_center";
            String bgHex = config2.has("background_color") ? config2.get("background_color").getAsString() : "#000000";
            this.backgroundColor = this.parseHex(bgHex, 0);
            int iconTrans = config2.has("icon_box_opacity") ? config2.get("icon_box_opacity").getAsInt() : 80;
            this.iconBoxAlpha = (float)(100 - iconTrans) / 100.0f;
            int textTrans = config2.has("text_box_opacity") ? config2.get("text_box_opacity").getAsInt() : 90;
            this.textBoxAlpha = (float)(100 - textTrans) / 100.0f;
            this.scaleWeapon = config2.has("scale_weapon") ? config2.get("scale_weapon").getAsFloat() : 1.0f;
            this.scaleVictim = config2.has("scale_victim") ? config2.get("scale_victim").getAsFloat() : 1.2f;
            this.scaleHealth = config2.has("scale_health") ? config2.get("scale_health").getAsFloat() : 1.5f;
            String vicHex = config2.has("color_victim") ? config2.get("color_victim").getAsString() : "#FF0000";
            this.colorVictim = this.parseHex(vicHex, 0xFF0000);
            this.animationDuration = config2.has("animation_duration") ? (long)(config2.get("animation_duration").getAsFloat() * 1000.0f) : 200L;
            this.displayDuration = config2.has("display_duration") ? (long)(config2.get("display_duration").getAsFloat() * 1000.0f) : 300L;
            this.enableIconGlow = IconGlowRenderEffect.isEnabled(config2);
            this.iconGlowColor = IconGlowRenderEffect.resolveColor(config2);
            this.iconGlowIntensity = IconGlowRenderEffect.resolveIntensity(config2);
            this.iconGlowSize = IconGlowRenderEffect.resolveSize(config2);
        }
        catch (Exception e) {
            ClientMessageLogger.chatWarn("gd656killicon.client.config_error", "battlefield1");
            this.enableIconGlow = false;
            this.iconGlowColor = 0xFFFFFF;
            this.iconGlowIntensity = 0.45f;
            this.iconGlowSize = 4.0f;
        }
    }

    private String getBattlefieldTextureKey() {
        return KillTypeRegistry.get(this.killType).bf1TextureKey();
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

    private int parseHex(String hex, int def) {
        try {
            return Integer.parseInt(hex.replace("#", ""), 16);
        }
        catch (Exception e) {
            return def;
        }
    }

    private float getAnimationProgress(long elapsed) {
        if (elapsed < this.animationDuration) {
            return (float)elapsed / (float)this.animationDuration;
        }
        return 1.0f;
    }

    private float getAlpha(long elapsed) {
        if (elapsed < this.animationDuration) {
            return (float)elapsed / (float)this.animationDuration;
        }
        long fadeOutStart = this.displayDuration;
        if (elapsed > fadeOutStart) {
            long fadeElapsed = elapsed - fadeOutStart;
            if (fadeElapsed >= this.animationDuration) {
                return 0.0f;
            }
            return 1.0f - (float)fadeElapsed / (float)this.animationDuration;
        }
        return 1.0f;
    }

    private float getGlobalScale(long elapsed) {
        if (elapsed < this.animationDuration) {
            float t = (float)elapsed / (float)this.animationDuration;
            float eased = 1.0f - (float)Math.pow(1.0f - t, 3.0);
            return Mth.lerp(eased, 0.6f, 1.0f);
        }
        return 1.0f;
    }
}

