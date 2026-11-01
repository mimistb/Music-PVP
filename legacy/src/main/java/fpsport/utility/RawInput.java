package fpsport.utility;

import fpsport.core.Feature;

/**
 * 原始输入（Raw Input）。
 *
 * <p>原始实现依赖一个 JNI 原生库（{@code RawInputMod} + DirectInput/RawInput 环境插件），
 * 无法随源码一起移植，因此这里降级为**可插拔后端**：
 * <ul>
 *   <li>宿主有同类原生实现时，实现 {@link Backend} 并注入；</li>
 *   <li>没有时保持 {@link #NOOP}，开关可以正常显示但不会改变鼠标行为。</li>
 * </ul>
 * 本类不含任何 UI。
 */
public class RawInput extends Feature {

    /** 原始输入后端。 */
    public interface Backend {
        void start();

        void stop();

        void setAcceptingInput(boolean accepting);
    }

    /** 空实现：只切换鼠标抓取状态，保持可玩性。 */
    public static final Backend NOOP = new Backend() {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void setAcceptingInput(boolean accepting) {
        }
    };

    private final Backend backend;

    public RawInput() {
        this(NOOP);
    }

    public RawInput(Backend backend) {
        super("RawInput");
        this.backend = backend == null ? NOOP : backend;
    }

    @Override
    protected void onEnable() {
        backend.start();
        backend.setAcceptingInput(true);
    }

    @Override
    protected void onDisable() {
        backend.stop();
    }
}
