/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.renderer.Tessellator
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;

public final class BufferUploader {
    public static void drawWithShader(BufferBuilder b) {
        Tessellator.getInstance().draw();
    }
}

