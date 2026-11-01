package fpsport.optimizes;

import fpsport.core.Feature;

/**
 * 增强鱼竿（BetterFishingRod）——把钓线的 GL 线宽调粗，看得更清楚。
 *
 * <p>原模块只有一个设置：{@code StringWidth}，默认 1.0、范围 [1.0, 10.0]、步长 0.5。
 * 行为写在 {@code MixinRenderItem}：在 {@code RenderItem#renderItemModelForEntity} 的
 * {@code ModelResourceLocation} 构造处把 {@code GL11.glLineWidth} 设为该值。
 */
public class BetterFishingRod extends Feature {

    public static final float STRING_WIDTH_MIN = 1.0F;
    public static final float STRING_WIDTH_MAX = 10.0F;
    public static final float STRING_WIDTH_STEP = 0.5F;
    public static final float STRING_WIDTH_DEFAULT = 1.0F;

    /** Mixin 读取的开关标志。 */
    public static boolean using = false;

    /** 钓线宽度；范围 [1.0, 10.0]，步长 0.5。 */
    public static float stringWidth = STRING_WIDTH_DEFAULT;

    public BetterFishingRod() {
        super("BetterFishingRod");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /**
     * 注入点调用（宿主用 ASM 在 {@code RenderItem#renderItemModelForEntity} 头部插桩，
     * 等价于原工程的 Mixin）。模块关闭时不碰 GL 状态。
     */
    public static void applyLineWidth() {
        if (using) {
            org.lwjgl.opengl.GL11.glLineWidth(stringWidth);
        }
    }

    /** 宿主 UI 拖动滑条时用：按 0.5 步长吸附并夹到 [1.0, 10.0]。 */
    public static float clampStringWidth(float value) {
        float snapped = Math.round(value / STRING_WIDTH_STEP) * STRING_WIDTH_STEP;
        if (!Float.isNaN(snapped)) {
            stringWidth = Math.max(STRING_WIDTH_MIN, Math.min(STRING_WIDTH_MAX, snapped));
        }
        return stringWidth;
    }
}
