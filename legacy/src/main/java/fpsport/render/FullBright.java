package fpsport.render;

import fpsport.core.Feature;
import me.sorihiko.dango.utils.GammaGuard;
import net.minecraft.client.Minecraft;

/**
 * 永久亮度（FullBright）——把 gamma 拉到 100，夜里/洞里全亮。
 *
 * <p>逻辑：开启时记录原 gamma 并写 100，每帧再写一次（部分光影/资源包会自己改
 * {@code gammaSetting}），关闭时还原。
 *
 * <p>原亮度只在「开启前还没到上限」时才记录：否则一旦开启前亮度就已经是 100，
 * 关闭时会把这个 100 当成用户原值写回去，亮度被永久锁死在上限。
 *
 * <p>无 UI。宿主在每帧更新回调里调 {@link #onUpdate()}。
 */
public class FullBright extends Feature {

    /** 原版亮度上限是 1.0，这里用 100 是原客户端的做法，比自己改 lightmap 稳。 */
    public static final float GAMMA_VALUE = GammaGuard.FULL_GAMMA;

    /** Mixin / 宿主读取的开关标志。 */
    public static boolean using = false;

    /** 当前处于开启状态的实例：保存设置 / 离开世界时静态入口要拿它的旧值 */
    private static FullBright active;

    /** 开启前的 gamma，关闭时还原。 */
    private float oldGamma;

    /** 是否记录过有效的旧值（开启前就已经是上限时不记录，关闭时也就不该写回） */
    private boolean hasOldGamma;

    public FullBright() {
        super("FullBright");
    }

    @Override
    protected void onEnable() {
        using = true;
        active = this;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        float current = mc.gameSettings.gammaSetting;
        hasOldGamma = !GammaGuard.isFull(current);
        oldGamma = GammaGuard.rememberedGamma(current);
        mc.gameSettings.gammaSetting = GAMMA_VALUE;
    }

    @Override
    protected void onDisable() {
        if (!using) {
            return;
        }
        using = false;
        if (active == this) {
            active = null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        // 只有亮度仍是我们写进去的上限时才还原：期间用户调过滑条或别的 mod 改过亮度，
        // 就保留他们的新值，避免关闭后亮度被锁死、滑条看起来失效。
        // 没有有效旧值（开启前就已经是 100）时退回原版上限，不能继续留在 100。
        mc.gameSettings.gammaSetting =
                GammaGuard.restoreValue(mc.gameSettings.gammaSetting, oldGamma, hasOldGamma);
    }

    /**
     * 原版 {@code GameSettings#saveOptions()} 之前调用（由变压器插入）。
     *
     * <p>开启状态下 gamma 一直是 100，如果直接让原版存盘，这个 100 会写进 options.txt，
     * 下次启动即使功能关着亮度也是上限 —— 这就是"永久亮度"关不掉、亮度滑条失效的根源。
     * 这里先把 gamma 还原成用户值再让它存，下一帧 {@link #onUpdate()} 会自动写回 100。
     */
    public static void beforeSaveOptions() {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        FullBright f = active;
        mc.gameSettings.gammaSetting = GammaGuard.restoreValue(
                mc.gameSettings.gammaSetting,
                f == null ? GammaGuard.VANILLA_MAX_GAMMA : f.oldGamma,
                f != null && f.hasOldGamma);
    }

    /** 功能关闭时的兜底清理：历史 options.txt 里残留的 100 会让亮度永久全亮。 */
    public static void sanitizeSavedGamma() {
        if (using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        if (GammaGuard.isFull(mc.gameSettings.gammaSetting)) {
            mc.gameSettings.gammaSetting = GammaGuard.VANILLA_MAX_GAMMA;
        }
    }

    /** 离开世界时调用：把开启状态下写进去的 100 还原，别留到后续存盘里。 */
    public static void restoreNow() {
        if (using) {
            beforeSaveOptions();
        } else {
            sanitizeSavedGamma();
        }
    }

    /** 每帧调用；关闭时不应再调用（或调用也无副作用）。 */
    public static void onUpdate() {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!GammaGuard.isFull(mc.gameSettings.gammaSetting)) {
            mc.gameSettings.gammaSetting = GAMMA_VALUE;
        }
    }
}
