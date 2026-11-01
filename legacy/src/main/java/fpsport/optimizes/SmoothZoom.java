package fpsport.optimizes;

import fpsport.core.BindKeys;
import fpsport.core.Feature;
import fpsport.core.MathUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * 平滑缩放。
 *
 * <p>本体只负责「按住时把 zoom 置真、滚轮改倍率」；真正的视野变化由
 * {@code MixinEntityRenderer.getFOVModifier} 调用 {@link #adjustFov} 完成，
 * 滚轮切换物品的抑制由 {@code MixinInventoryPlayer} 完成。
 */
public class SmoothZoom extends Feature {

    /** 按住式缩放键（LWJGL 码，负数表示鼠标侧键）。 */
    public static int zoomBind = Keyboard.KEY_C;
    /** 按下缩放时打开原版平滑相机（smoothCamera），降低晕动感。 */
    public static boolean smoothMouse = false;
    /** 对视野变化做缓动而不是瞬时跳变。 */
    public static boolean smoothCamera = false;
    /** 缩放期间用滚轮微调倍率，而不是切换快捷栏。 */
    public static boolean wheelZoom = false;
    /** 缓动速度，1.0 ~ 10.0。 */
    public static double speed = 4.0;

    /** Mixin 读取的总开关。 */
    public static boolean using = false;
    /** 当前是否处于缩放状态（由 {@link #onUpdate()} 维护）。 */
    public static boolean zoom = false;
    /** 当前缩放倍率（滚轮可调，范围 2 ~ 20）。 */
    public static float zoomScale = 4f;
    /** 初始缩放倍率（设置项），松开缩放键后回到这个值。 */
    public static float baseScale = 4f;

    /** useFOVSetting = true 分支的缓动缓存。 */
    private static float screenScale = -1f;
    /** useFOVSetting = false 分支的缓动缓存。 */
    private static float screenScale2 = -1f;

    public SmoothZoom() {
        super("SmoothZoom");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        zoom = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (smoothMouse && mc != null) {
            mc.gameSettings.smoothCamera = false;
        }
    }

    /** 宿主每帧调用一次（对应源码的 EventUpdate）。 */
    public void onUpdate() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        // 打开任意界面时立刻退出缩放，避免视野卡在放大状态
        if (mc.currentScreen != null) {
            zoom = false;
            zoomScale = baseScale;
            if (smoothMouse) {
                mc.gameSettings.smoothCamera = false;
            }
            return;
        }
        if (isZoomKeyDown()) {
            if (wheelZoom) {
                int dWheel = Mouse.getDWheel();
                zoomScale += dWheel / 60f;
                zoomScale = Math.min(Math.max(zoomScale, 1f), 20f);
            }
            zoom = true;
            if (smoothMouse) {
                mc.gameSettings.smoothCamera = true;
            }
        } else {
            zoom = false;
            zoomScale = baseScale;
            if (smoothMouse) {
                mc.gameSettings.smoothCamera = false;
            }
        }
    }

    public static boolean isZoomKeyDown() {
        return BindKeys.isDown(zoomBind);
    }

    public static boolean isZoomKeyActive() {
        return using && isZoomKeyDown();
    }

    /**
     * 修正原版算出来的视野值。
     *
     * <p>由 {@code MixinEntityRenderer.getFOVModifier} 在 {@code RETURN} 处调用，传入的是原版
     * 已经处理过水下 / 濒死等修正的最终值，所以这里只做「除以倍率 + 缓动」。
     */
    public static float adjustFov(float partialTicks, boolean useFOVSetting, float base) {
        if (!using || base <= 0f) {
            return base;
        }
        float f = base;
        float step = (float) (speed / (float) Math.max(1, Minecraft.getDebugFPS()) * 150.0f);
        float zoomed = f / zoomScale;

        if (useFOVSetting) {
            if (screenScale == -1f || Float.isNaN(screenScale)) {
                screenScale = f;
            }
            if (zoom) {
                screenScale = smoothCamera ? MathUtil.decreasedSpeed(screenScale, f, zoomed, step) : zoomed;
            } else {
                screenScale = smoothCamera ? MathUtil.decreasedSpeed(screenScale, zoomed, f, step) : f;
            }
            return screenScale;
        }

        if (screenScale2 == -1f || Float.isNaN(screenScale2)) {
            screenScale2 = f;
        }
        if (zoom) {
            screenScale2 = smoothCamera ? MathUtil.decreasedSpeed(screenScale2, f, zoomed, step) : zoomed;
        } else {
            screenScale2 = smoothCamera ? MathUtil.decreasedSpeed(screenScale2, zoomed, f, step) : f;
        }
        return screenScale2;
    }

    /** 视野基准值变化（例如改了 FOV 设置）时清掉缓存。 */
    public static void resetSmoothing() {
        screenScale = -1f;
        screenScale2 = -1f;
    }
}
