package me.sorihiko.dango.core;

/**
 * 天气切换 ASM 回调：legacy 的 WeatherRuntime 每 tick 写状态，
 * 这里把原版的降水/雷暴/温度返回值改写成配置值。
 */
public final class WeatherHooks {

    public static final int CLEAR = 0;
    public static final int RAIN = 1;
    public static final int STORM = 2;
    public static final int SNOW = 3;

    public static volatile boolean enabled;
    public static volatile int mode = CLEAR;
    public static volatile float rain = 1f;
    public static volatile float thunder = 1f;

    private WeatherHooks() {
    }

    public static float rainStrength(float original) {
        if (!enabled) return original;
        return mode == CLEAR || mode == SNOW ? 0f : rain;
    }

    public static float thunderStrength(float original) {
        if (!enabled) return original;
        return mode == STORM ? thunder : 0f;
    }

    public static boolean raining(boolean original) {
        if (!enabled) return original;
        return mode != CLEAR;
    }

    public static boolean thundering(boolean original) {
        if (!enabled) return original;
        return mode == STORM;
    }

    /** 雪天把温度压到 0 以下，让原版降水粒子/音效走雪的分支 */
    public static float temperature(float original) {
        if (!enabled) return original;
        return mode == SNOW ? -0.1f : original;
    }
}
