/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.renderer.Tessellator
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;

public final class Tesselator {
    private static final Tesselator INSTANCE = new Tesselator();
    private final BufferBuilder builder = new BufferBuilder();

    public static Tesselator getInstance() {
        return INSTANCE;
    }

    public BufferBuilder getBuilder() {
        return this.builder;
    }

    public void end() {
        Tessellator.getInstance().draw();
    }
}

