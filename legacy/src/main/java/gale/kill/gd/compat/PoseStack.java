/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.renderer.GlStateManager
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.Matrix4f;
import net.minecraft.client.renderer.GlStateManager;

public final class PoseStack {
    private static final Pose POSE = new Pose();

    public void pushPose() {
        GlStateManager.pushMatrix();
    }

    public void popPose() {
        GlStateManager.popMatrix();
    }

    public void translate(double x, double y, double z) {
        GlStateManager.translate((double)x, (double)y, (double)z);
    }

    public void scale(float x, float y, float z) {
        GlStateManager.scale((float)x, (float)y, (float)z);
    }

    public void mulPose(float degrees) {
        GlStateManager.rotate((float)degrees, (float)0.0f, (float)0.0f, (float)1.0f);
    }

    public Pose last() {
        return POSE;
    }

    public static final class Pose {
        private final Matrix4f matrix = new Matrix4f();

        public Matrix4f pose() {
            return this.matrix;
        }
    }
}

