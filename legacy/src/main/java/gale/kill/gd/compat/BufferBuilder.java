/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.renderer.Tessellator
 *  net.minecraft.client.renderer.WorldRenderer
 *  net.minecraft.client.renderer.vertex.VertexFormat
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.Matrix4f;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;

public final class BufferBuilder {
    private final WorldRenderer out = Tessellator.getInstance().getWorldRenderer();

    public void begin(gale.kill.gd.compat.VertexFormat.Mode mode,
                      net.minecraft.client.renderer.vertex.VertexFormat format) {
        this.out.begin(mode.gl, format);
    }

    public BufferBuilder vertex(Matrix4f ignored, float x, float y, float z) {
        this.out.pos((double)x, (double)y, (double)z);
        return this;
    }

    public BufferBuilder uv(float u, float v) {
        this.out.tex((double)u, (double)v);
        return this;
    }

    public BufferBuilder color(float r, float g, float b, float a) {
        this.out.color(r, g, b, a);
        return this;
    }

    public BufferBuilder color(int r, int g, int b, int a) {
        this.out.color(r, g, b, a);
        return this;
    }

    public void endVertex() {
        this.out.endVertex();
    }

    public BufferBuilder end() {
        return this;
    }
}

