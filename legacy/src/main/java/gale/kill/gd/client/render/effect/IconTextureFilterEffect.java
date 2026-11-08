/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.texture.ITextureObject
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.client.config.ClientConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

public final class IconTextureFilterEffect {
    private IconTextureFilterEffect() {
    }

    public static void apply(ResourceLocation textureLocation) {
        if (textureLocation == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        ITextureObject texture = minecraft.getTextureManager().getTexture(textureLocation);
        if (texture != null) {
            texture.setBlurMipmap(ClientConfigManager.isEnableIconAntialiasing(), false);
        }
    }
}

