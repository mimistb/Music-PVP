package gale.event.impl;

/**
 * 独立化替身：原 Gale 的 2D 渲染事件。击杀图标只需要画布宽高与插值时间。
 * 宿主在自己的 HUD 渲染阶段构造一个实例交给模块即可。
 */
public final class Render2DEvent {

    /** 帧间插值，0.0~1.0。 */
    public final float partialTicks;

    private final int width;
    private final int height;

    public Render2DEvent(int width, int height, float partialTicks) {
        this.width = width;
        this.height = height;
        this.partialTicks = partialTicks;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
