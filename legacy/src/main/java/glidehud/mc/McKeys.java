package glidehud.mc;

import glidehud.api.Key;
import glidehud.api.KeyStateProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/** 1.8.9 key state / key name provider for {@link glidehud.modules.KeystrokesModule}. */
public final class McKeys implements KeyStateProvider {

    private final Minecraft mc = Minecraft.getMinecraft();

    /** 键名缓存：键位没变就直接复用，避免 Keyboard.getKeyName 每帧新建字符串 */
    private final int[] cachedCodes = new int[Key.values().length];
    private final String[] cachedNames = new String[Key.values().length];

    public McKeys() {
        for (int i = 0; i < this.cachedCodes.length; i++) {
            this.cachedCodes[i] = Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean isGuiOpen() {
        return this.mc.currentScreen != null;
    }

    @Override
    public boolean isDown(Key key) {
        int code = keyCode(key);
        return code != Keyboard.KEY_NONE && Keyboard.isKeyDown(code);
    }

    @Override
    public String keyName(Key key) {
        if (key == null) {
            return "?";
        }
        int code = keyCode(key);
        int i = key.ordinal();
        if (code != this.cachedCodes[i]) {
            this.cachedCodes[i] = code;
            this.cachedNames[i] = Keyboard.getKeyName(code);
        }
        String name = this.cachedNames[i];
        return name == null ? "?" : name;
    }

    private int keyCode(Key key) {
        GameSettings settings = this.mc.gameSettings;
        if (settings == null) {
            return Keyboard.KEY_NONE;
        }
        KeyBinding binding = binding(settings, key);
        return binding == null ? Keyboard.KEY_NONE : binding.getKeyCode();
    }

    private static KeyBinding binding(GameSettings settings, Key key) {
        switch (key) {
            case FORWARD:
                return settings.keyBindForward;
            case LEFT:
                return settings.keyBindLeft;
            case BACK:
                return settings.keyBindBack;
            case RIGHT:
                return settings.keyBindRight;
            case JUMP:
                return settings.keyBindJump;
            default:
                return null;
        }
    }
}
