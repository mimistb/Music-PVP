package me.sorihiko.dango.core;

/**
 * 慢挥 ASM 回调：EntityLivingBase#getArmSwingAnimationEnd 的每个 return 前都会调用这里，
 * 把原版返回值换成配置的挥砍时长。状态由 legacy 的 SlowSwingRuntime 每 tick 写入。
 */
public final class SlowSwingHooks {

    public static volatile boolean enabled;
    public static volatile int ticks = 6;

    private SlowSwingHooks() {
    }

    public static int modify(int original) {
        return enabled ? ticks : original;
    }
}
