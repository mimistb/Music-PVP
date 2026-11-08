/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.compat;

public final class VertexFormat {

    public static enum Mode {
        QUADS(7),
        TRIANGLES(4),
        TRIANGLE_STRIP(5);

        public final int gl;

        private Mode(int g) {
            this.gl = g;
        }
    }
}

