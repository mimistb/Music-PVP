/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.textures;

import gale.kill.gd.client.textures.ExternalTextureManager;
import net.minecraft.util.ResourceLocation;

public class ModTextures {
    public static ResourceLocation get(String path) {
        return ExternalTextureManager.getTexture(path);
    }
}

