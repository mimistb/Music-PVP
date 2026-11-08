package gale.core;

/**
 * 独立化替身：原 Gale 的调度器只被击杀图标模块用来取「当前 tick 数」。
 * 宿主每 tick 调用一次 {@link #tick()} 即可，不需要 Gale 的完整调度器。
 */
public final class Scheduler {

    private static volatile long ticks;

    private Scheduler() {
    }

    public static long ticks() {
        return ticks;
    }

    public static void tick() {
        ticks++;
    }

    /** 测试/回放时可直接设定。 */
    public static void setTicks(long value) {
        ticks = value;
    }
}
