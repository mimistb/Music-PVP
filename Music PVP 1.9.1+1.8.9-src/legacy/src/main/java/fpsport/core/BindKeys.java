package fpsport.core;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * 按住式绑定的按下判断。
 *
 * <p>键位码约定与原版 GuiControls 一致：正数是 LWJGL 键盘码（0 = 未绑定）；负数是鼠标码
 * {@code -100 + button}。左 / 右键留给界面点击与攻击，只有中键与侧键可以绑定。
 */
public final class BindKeys {

    /** 原版鼠标码基准：{@code -100 + button}（0 = 左键）。 */
    public static final int MOUSE_BASE = -100;

    /** 可绑定的最小鼠标按钮：中键。 */
    public static final int MIN_BINDABLE_BUTTON = 2;

    private BindKeys() {
    }

    /** 负数是鼠标码，正数才是键盘码。 */
    public static boolean isMouse(int code) {
        return code < 0;
    }

    /** 鼠标码 → LWJGL 按钮号（0 = 左键）。 */
    public static int mouseButton(int code) {
        return code - MOUSE_BASE;
    }

    public static int mouseCode(int button) {
        return MOUSE_BASE + button;
    }

    public static boolean isBindableButton(int button) {
        return button >= MIN_BINDABLE_BUTTON;
    }

    /** 按住式绑定的按下判断：缩放、自由视角都走这里。 */
    public static boolean isDown(int code) {
        if (isMouse(code)) {
            int button = mouseButton(code);
            return isBindableButton(button) && Mouse.isButtonDown(button);
        }
        return code > 0 && Keyboard.isKeyDown(code);
    }
}
