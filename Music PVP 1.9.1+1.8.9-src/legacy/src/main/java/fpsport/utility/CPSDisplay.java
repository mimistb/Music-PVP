package fpsport.utility;

import fpsport.core.Feature;

import java.util.LinkedList;

/**
 * 点击速度显示（CPSDisplay）——**只算数，不画任何东西**。
 *
 * <p>宿主在自己的 HUD 里读 {@link #leftCps()} / {@link #rightCps()} 即可，本类不注册组件、
 * 不碰字体、不碰面板。
 */
public class CPSDisplay extends Feature {

    private static final LinkedList<Click> CLICKS = new LinkedList<>();

    /** 宿主读取的开关标志。 */
    public static boolean using = false;

    /** 统计窗口（毫秒），由宿主设置页写入；实际使用时夹到 200 ~ 5000。 */
    public static int windowMs = 1000;

    private static volatile long leftCps;
    private static volatile long rightCps;

    public CPSDisplay() {
        super("CPSDisplay");
    }

    @Override
    protected void onEnable() {
        using = true;
        reset();
    }

    @Override
    protected void onDisable() {
        using = false;
        reset();
    }

    /** 鼠标按下时调用（LWJGL 按钮号：0 左键、1 右键）。 */
    public static void onMouseClick(int button) {
        if (!using || button < 0 || button > 1) {
            return;
        }
        synchronized (CLICKS) {
            CLICKS.add(new Click(button, System.currentTimeMillis()));
        }
    }

    /** 每 tick 调用，重算最近一个统计窗口内的点击次数。 */
    public static void onTick() {
        long now = System.currentTimeMillis();
        long window = Math.max(200, Math.min(5000, windowMs));
        long left = 0;
        long right = 0;
        synchronized (CLICKS) {
            for (Click click : CLICKS) {
                if (now - click.time < window) {
                    if (click.button == 0) {
                        left++;
                    } else {
                        right++;
                    }
                }
            }
            leftCps = left;
            rightCps = right;
            CLICKS.removeIf(click -> now - click.time >= window);
        }
    }

    public static long leftCps() {
        return leftCps;
    }

    public static long rightCps() {
        return rightCps;
    }

    public static void reset() {
        synchronized (CLICKS) {
            CLICKS.clear();
        }
        leftCps = 0;
        rightCps = 0;
    }

    private static final class Click {
        final int button;
        final long time;

        Click(int button, long time) {
            this.button = button;
            this.time = time;
        }
    }
}
