/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.Component;
import net.minecraft.client.Minecraft;

public final class Font {
    public static final Font INSTANCE = new Font();
    public final int lineHeight = 9;

    public int width(String s) {
        return Minecraft.getMinecraft().fontRendererObj.getStringWidth(s);
    }

    public int width(Component s) {
        return this.width(s.formatted());
    }
}

