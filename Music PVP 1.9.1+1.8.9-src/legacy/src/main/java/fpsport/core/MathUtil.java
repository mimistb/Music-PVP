package fpsport.core;

/** 通用缓动数学。 */
public final class MathUtil {

    private MathUtil() {
    }

    /**
     * 从 {@code start} 出发、以 {@code speed} 的步长向 {@code target} 逼近，返回下一帧的值。
     *
     * <p>与源码等价；额外加了 {@code start == target} 的短路，避免 {@code speed / 0} 产生
     * NaN（例如锚定缩放比例为 1 时目标值与起始值相同）。
     */
    public static float decreasedSpeed(float current, float start, float target, float speed) {
        if (start == target) {
            return target;
        }
        float k = speed / (start - target);
        return current + (k * (current - start) + speed) * (start > target ? -1 : 1);
    }
}
