package fpsport.core;

/**
 * 最小功能开关基类。
 *
 * <p>本类不包含任何 UI / 设置系统 / 事件总线依赖，只是「一个可以被宿主 UI 绑定的布尔开关」。
 * 宿主只需要调用 {@link #isEnabled()} 与 {@link #setEnabled(boolean)}；所有可配置项都是子类的
 * public 字段，由宿主自己的设置界面直接读写。
 *
 * <p>开关真正发生翻转时才回调 {@link #onEnable()} / {@link #onDisable()}，因此子类可以安全地
 * 在那里维护给 Mixin 读取的静态标志位。
 */
public abstract class Feature {

    /** 功能名，仅用于宿主 UI 显示与日志。 */
    public final String name;

    private volatile boolean enabled;

    protected Feature(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /** 幂等：状态没有变化时不会重复触发 onEnable/onDisable。 */
    public final void setEnabled(boolean state) {
        if (state == enabled) {
            return;
        }
        enabled = state;
        if (state) {
            onEnable();
        } else {
            onDisable();
        }
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }
}
