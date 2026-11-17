package me.sorihiko.dango.core;

import org.lwjgl.opengl.GL11;

/**
 * 闪光药水 ASM 回调：RenderItem#renderEffect 绑定附魔光效贴图后调用
 * {@link #applyGlintFilter()}，开启时把该贴图改成最近邻采样（颗粒感闪光），
 * 关闭时立刻恢复线性采样，不残留状态。
 */
public final class ShinyPotsHooks {

    public static volatile boolean enabled;

    private ShinyPotsHooks() {
    }

    public static void applyGlintFilter() {
        int filter = enabled ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
    }
}
