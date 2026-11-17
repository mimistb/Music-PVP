package fpsport.optimizes;

import fpsport.core.BindKeys;
import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

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
    /** 组合键的修饰键（Shift / Ctrl / Alt 的键码），为空表示单键。 */
    public static int[] zoomMods = new int[0];
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
    /** 当前缩放倍率（滚轮可调，范围 1 ~ 20；1 表示不放大）。 */
    public static float zoomScale = 4f;
    /** 初始缩放倍率（设置项），松开缩放键后回到这个值。 */
    public static float baseScale = 4f;

    /** 滚轮事件累积量（由鼠标事件喂入，单位与 dWheel 一致：120 = 一格）。 */
    private static int pendingWheel = 0;
    /** 实际用于 FOV 的平滑倍率；<= 0 表示尚未初始化，首帧直接吸附到目标值。 */
    private static float smoothedScale = 0f;
    /** 上一次缓动的时间戳（纳秒），用于帧率无关的指数逼近。 */
    private static long lastScaleNanos = 0L;

    /** 滚轮只在按住缩放键时改倍率，信号由宿主从鼠标事件转发过来。 */
    public static void addWheel(int dWheel) {
        pendingWheel += dWheel;
    }

    public SmoothZoom() {
        super("SmoothZoom");
    }

    @Override
    protected void onEnable() {
        using = true;
        smoothedScale = 0f;
        lastScaleNanos = 0L;
        pendingWheel = 0;
    }

    @Override
    protected void onDisable() {
        using = false;
        zoom = false;
        smoothedScale = 0f;
        lastScaleNanos = 0L;
        pendingWheel = 0;
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
            pendingWheel = 0;
            if (smoothMouse) {
                mc.gameSettings.smoothCamera = false;
            }
            return;
        }
        // 滚轮量来自宿主转发的鼠标事件；不能再轮询 Mouse.getDWheel()，
        // 它和原版 Mouse.next() 抢消费，经常读到 0（表现为滚轮没反应）
        int wheel = pendingWheel;
        pendingWheel = 0;
        if (isZoomKeyDown()) {
            if (wheelZoom && wheel != 0) {
                // 乘法步进：每格约 ×1.18，拉远 / 拉近比例一致，不会越放越大步越大
                zoomScale *= (float) Math.pow(1.18, wheel / 120.0);
                zoomScale = Math.min(Math.max(zoomScale, 1f), 20f);
            }
            // 倍率为 1 时等同于不放大：不进入缩放态，避免平滑相机等副作用
            zoom = zoomScale > 1.0001f;
            if (smoothMouse && zoom) {
                mc.gameSettings.smoothCamera = true;
            }
        } else {
            zoom = false;
            zoomScale = Math.min(Math.max(baseScale, 1f), 20f);
            if (smoothMouse) {
                mc.gameSettings.smoothCamera = false;
            }
        }
    }

    public static boolean isZoomKeyDown() {
        return BindKeys.isDown(zoomBind, zoomMods);
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
        // 缩放态向滚轮倍率逼近，非缩放态向 1 倍逼近：两个方向都走同一条帧率无关缓动，
        // 所以滚轮微调 / 按下 / 松开都不会出现视野瞬跳
        float target = zoom ? Math.min(Math.max(zoomScale, 1f), 20f) : 1f;
        return base / stepScale(target);
    }

    /**
     * 把 {@link #smoothedScale} 按真实经过的时间向 {@code target} 指数逼近。
     *
     * <p>同一帧里 getFOVModifier 可能被调用多次，每次只推进实际流逝的时间，
     * 总推进量与调用次数无关，观感不会随调用次数变化。
     */
    private static float stepScale(float target) {
        long now = System.nanoTime();
        if (smoothedScale <= 0f || lastScaleNanos == 0L) {
            smoothedScale = target;
            lastScaleNanos = now;
            return smoothedScale;
        }
        float dt = (float) ((now - lastScaleNanos) / 1.0E9);
        lastScaleNanos = now;
        if (dt <= 0f) {
            return smoothedScale;
        }
        if (dt > 0.1f) {
            dt = 0.1f;
        }
        // 复用「缓动速度」设置：默认 4.0 → smoothCamera 开 9 / 关 18（每帧约 26% / 45% 收敛）
        float rate = (float) (speed * (smoothCamera ? 2.25 : 4.5));
        float alpha = (float) (1.0 - Math.exp(-rate * dt));
        smoothedScale += (target - smoothedScale) * alpha;
        if (Math.abs(target - smoothedScale) < 0.001f) {
            smoothedScale = target;
        }
        return smoothedScale;
    }

    /** 视野基准值变化（例如改了 FOV 设置）时清掉缓存。 */
    public static void resetSmoothing() {
        smoothedScale = 0f;
        lastScaleNanos = 0L;
    }
}
