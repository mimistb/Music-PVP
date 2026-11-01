package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;

/**
 * 防砍动画（手持剑右键格挡时的第一人称动画）。
 *
 * <p>原版 {@code ItemRenderer#renderItemInFirstPerson} 在 BLOCK 分支里依次调用
 * {@code transformFirstPersonItem(f, 0.0F)} → {@code doBlockTransformations()}。
 * 由 {@code BlockAnimationTransformer} 把前一个调用替换成 {@link #transform(float, float)}，
 * 并在格挡变换之后再调用 {@link #post(float, float)}，其余原版逻辑（含 {@code doBlockTransformations}）完全保留。
 *
 * <p>样式与变换数据移植自 fpsmaster 的 OldAnimations / MixinItemRenderer：
 * Lunar（默认）/ 1.7 / Debug / Jello / Jigsaw / Luna / Push / Swang / Swank / Swong / Sigma。
 * 功能关闭时严格复刻原版 transformFirstPersonItem，保证不改变原版手感。
 */
public class BlockAnimation extends Feature {

    /** 功能开关（transformer 注入的钩子读取）。 */
    public static volatile boolean using = false;

    /** 样式下标，顺序与设置页 blockStyleNames 一致。 */
    public static volatile int style = 0;

    /** 格挡动画整体偏移（格）。 */
    public static volatile float offX = 0f;
    public static volatile float offY = 0f;
    public static volatile float offZ = 0f;

    public BlockAnimation() {
        super("BlockAnimation");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 替换原版 transformFirstPersonItem(f, 0.0F)（只用于 BLOCK 分支）。 */
    public static void transform(float equipProgress, float swingProgress) {
        try {
            if (!using) {
                vanilla(equipProgress, swingProgress);
                return;
            }
            GlStateManager.translate(offX, offY, offZ);
            switch (style) {
                case 0: // Lunar
                    lunar(swingProgress);
                    break;
                case 1: // 1.7：原版姿态
                    vanilla(equipProgress, swingProgress);
                    break;
                case 2: // Debug
                case 3: // Jello
                    vanilla(0.2f, swingProgress);
                    break;
                case 4: // Jigsaw
                    vanilla(0.1f, swingProgress);
                    break;
                case 5: // Luna
                case 6: // Push
                    vanilla(equipProgress, 0f);
                    break;
                case 7: // Swang
                case 8: // Swank
                    vanilla(equipProgress / 2f, swingProgress);
                    break;
                case 9: // Swong
                    vanilla(equipProgress / 2f, 0f);
                    break;
                case 10: // Sigma
                    vanilla(equipProgress, swingProgress);
                    break;
                default:
                    vanilla(equipProgress - 0.3f, swingProgress);
                    break;
            }
        } catch (Throwable ignored) {
            // 出异常就退回原版姿态
            try {
                vanilla(equipProgress, swingProgress);
            } catch (Throwable ignoredToo) {
                // 忽略
            }
        }
    }

    /** 在 doBlockTransformations() 之后调用，补上各样式特有的后置变换。 */
    public static void post(float swingProgress, float equipProgress) {
        try {
            if (!using) return;
            switch (style) {
                case 0: // Lunar
                case 2: // Debug
                case 3: // Jello
                    GlStateManager.translate(-0.5f, 0.2f, 0f);
                    break;
                case 4: // Jigsaw
                    GlStateManager.translate(-0.5f, 0f, 0f);
                    break;
                case 5: { // Luna
                    float sin = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
                    GlStateManager.translate(-0.2f, 0.45f, 0.25f);
                    GlStateManager.rotate(-sin * 20f, -5f, -5f, 9f);
                    break;
                }
                case 6: { // Push
                    float sin = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
                    GlStateManager.rotate(-sin * 35f, -8f, 0f, 9f);
                    GlStateManager.rotate(-sin * 10f, 1f, -0.4f, -0.5f);
                    break;
                }
                case 7: { // Swang
                    float v = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
                    GlStateManager.rotate(v * 30f / 2f, -v, 0f, 9f);
                    GlStateManager.rotate(v * 40f, 1f, -v / 2f, 0f);
                    break;
                }
                case 8: { // Swank
                    float v = MathHelper.sin(MathHelper.sqrt_float(equipProgress) * (float) Math.PI);
                    GlStateManager.rotate(v * 30f, -v, 0f, 9f);
                    GlStateManager.rotate(v * 40f, 1f, -v, 0f);
                    break;
                }
                case 9: { // Swong
                    float v = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
                    GlStateManager.rotate(-v * 40f / 2f, v / 2f, 0f, 9f);
                    GlStateManager.rotate(-v * 30f, 1f, v / 2f, 0f);
                    break;
                }
                case 10: { // Sigma
                    float swong = MathHelper.sin(MathHelper.sqrt_float(equipProgress) * (float) Math.PI);
                    GlStateManager.rotate(-swong * 55f / 2f, -8f, 0f, 9f);
                    GlStateManager.rotate(-swong * 45f, 1f, swong / 2f, 0f);
                    GlStateManager.translate(1.2f, 0.3f, 0.5f);
                    boolean sneaking = Minecraft.getMinecraft().thePlayer != null
                            && Minecraft.getMinecraft().thePlayer.isSneaking();
                    GlStateManager.translate(-1f, sneaking ? -0.1f : -0.2f, 0.2f);
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 原版 transformFirstPersonItem，逐行复刻（关闭功能时保持原版手感）。 */
    private static void vanilla(float equipProgress, float swingProgress) {
        GlStateManager.translate(0.56f, -0.52f, -0.71999997f);
        GlStateManager.translate(0f, equipProgress * -0.6f, 0f);
        GlStateManager.rotate(45f, 0f, 1f, 0f);
        float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        GlStateManager.rotate(f * -20f, 0f, 1f, 0f);
        GlStateManager.rotate(f1 * -20f, 0f, 0f, 1f);
        GlStateManager.rotate(f1 * -80f, 1f, 0f, 0f);
        GlStateManager.scale(0.4f, 0.4f, 0.4f);
    }

    /** Lunar 样式的前置变换（源码 fpsport$transformLunar 的硬编码部分）。 */
    private static void lunar(float swingProgress) {
        GlStateManager.translate(0.07f, -0.14f, -0.11f);
        GlStateManager.translate(0.56f, -0.52f, -0.72f);
        GlStateManager.rotate(45f, 0f, 1f, 0f);
        float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        GlStateManager.rotate(f * -20f, 0f, 1f, 0f);
        GlStateManager.rotate(f1 * -20f, 0f, 0f, 1f);
        GlStateManager.rotate(f1 * -80f, 1f, 0f, 0f);
        GlStateManager.scale(0.4f, 0.4f, 0.4f);
    }
}
