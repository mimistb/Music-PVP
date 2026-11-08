/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.GlStateManager
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.GameRenderer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

public final class RenderSystem {
    public static void enableBlend() {
        GlStateManager.enableBlend();
    }

    public static void disableBlend() {
        GlStateManager.disableBlend();
    }

    public static void enableCull() {
        GlStateManager.enableCull();
    }

    public static void disableCull() {
        GlStateManager.disableCull();
    }

    public static void enableDepthTest() {
        GlStateManager.enableDepth();
    }

    public static void disableDepthTest() {
        GlStateManager.disableDepth();
    }

    public static void defaultBlendFunc() {
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
    }

    public static void blendFunc(int s, int d) {
        GlStateManager.blendFunc((int)s, (int)d);
    }

    public static void setShaderColor(float r, float g, float b, float a) {
        GlStateManager.color((float)r, (float)g, (float)b, (float)a);
    }

    public static void setShaderTexture(int unit, ResourceLocation r) {
        GlStateManager.enableTexture2D();
        Minecraft.getMinecraft().getTextureManager().bindTexture(r);
    }

    public static void setShader(Supplier<Object> shader) {
        GlStateManager.disableAlpha();
        if (shader.get() == GameRenderer.COLOR) {
            GlStateManager.disableTexture2D();
        } else {
            GlStateManager.enableTexture2D();
        }
    }
}

