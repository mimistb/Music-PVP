package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * 强制疾跑 / 切换疾跑（原模块继承 HUD 基类，这里已是纯功能类，不产生任何文字 HUD）。
 *
 * <p>{@code MixinKeyBinding} 会在 {@code KeyBinding.isKeyDown} 里对「疾跑键」直接返回
 * {@link #sprint}，因此长按与否都会被覆盖。
 */
public class Sprint extends Feature {

    /** true = 按一下切换；false = 一直强制疾跑。 */
    public static boolean toggleSprint = true;

    public static boolean using = false;
    public static boolean sprint = true;

    public Sprint() {
        super("Sprint");
    }

    @Override
    protected void onEnable() {
        using = true;
        // 重新启用后一定处于「想疾跑」状态，避免上一次关闭时的 false 残留
        sprint = true;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            int key = mc.gameSettings.keyBindSprint.getKeyCode();
            // 玩家自己还按着疾跑键时不要替他松开，否则会打断手动疾跑
            if (key != 0 && !Keyboard.isKeyDown(key)) {
                KeyBinding.setKeyBindState(key, false);
                if (mc.thePlayer != null) {
                    mc.thePlayer.setSprinting(false);
                }
            }
        }
        using = false;
    }

    /** 宿主每帧调用一次（对应源码的 EventUpdate）。 */
    public void onUpdate() {
        if (!toggleSprint) {
            sprint = true;
        }
    }

    /** 宿主在按键回调里调用（对应源码的 EventKey）。 */
    public void onKey(int key) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        // 打开聊天 / 背包等界面时不切换疾跑状态，避免在 GUI 里误触发
        if (mc.currentScreen != null) {
            return;
        }
        if (toggleSprint && key == mc.gameSettings.keyBindSprint.getKeyCode()) {
            sprint = !sprint;
            if (!sprint && mc.thePlayer != null) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }
}
