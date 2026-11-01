package fpsport.optimizes;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

/**
 * 无打击延迟。
 *
 * <p>原版每次左键后会在 {@code Minecraft.leftClickCounter} 里写入一个冷却 tick 数，这里每 tick
 * 把它清零。字段是 MCP 名，运行期不需要映射。
 *
 * <p>不属于 UI，也不注入任何方法：宿主在自己的 tick 回调里调用 {@link #onTick()} 即可。
 */
public class NoHitDelay extends Feature {

    public static boolean using = false;

    /** 反射结果缓存，避免每 tick 重新查找。 */
    private static Field leftClickCounter;

    public NoHitDelay() {
        super("NoHitDelay");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    public void onTick() {
        if (!using) {
            return;
        }
        resetClickDelay();
    }

    private static void resetClickDelay() {
        try {
            if (leftClickCounter == null) {
                Field field = Minecraft.class.getDeclaredField("leftClickCounter");
                field.setAccessible(true);
                leftClickCounter = field;
            }
            leftClickCounter.setInt(Minecraft.getMinecraft(), 0);
        } catch (Throwable ignored) {
            // 字段不存在（非 1.8.9 或已被其它核心 mod 改写）时静默降级。
        }
    }
}
