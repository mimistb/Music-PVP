/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.render.impl;

import gale.kill.gd.client.config.ClientConfigManager;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.compat.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;

public class AceLogoRenderer
implements IHudRenderer {
    private static final ResourceLocation TEXTURE = new ResourceLocation("gd656killicon", "icon/ace_logo.png");
    private static final long DISPLAY_DURATION_MS = 10000L;
    private static final long FADE_OUT_DURATION_MS = 600L;
    private static final int LOGO_WIDTH = 74;
    private static final int LOGO_HEIGHT = 25;
    private long startTime = -1L;
    private boolean isVisible = false;

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        if (!this.isVisible || this.startTime < 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - this.startTime;
        if (elapsed >= 10600L) {
            this.isVisible = false;
            this.startTime = -1L;
            return;
        }
        float alpha = 1.0f;
        if (elapsed > 10000L) {
            float t = (float)(elapsed - 10000L) / 600.0f;
            t = Mth.clamp(t, 0.0f, 1.0f);
            float fade = 1.0f - t;
            alpha = fade * fade;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new ScaledResolution(mc).getScaledWidth();
        int screenHeight = new ScaledResolution(mc).getScaledHeight();
        int drawX = screenWidth - 74;
        int drawY = screenHeight - 25;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        guiGraphics.blit(TEXTURE, drawX, drawY, 0.0f, 0.0f, 74, 25, 74, 25);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    @Override
    public void trigger(IHudRenderer.TriggerContext context) {
        if (!ClientConfigManager.isEnableAceLag()) {
            return;
        }
        this.startTime = System.currentTimeMillis();
        this.isVisible = true;
    }
}

