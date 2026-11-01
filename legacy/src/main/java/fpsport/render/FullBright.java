package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;

/**
 * 永久亮度（FullBright）——把 gamma 拉到 100，夜里/洞里全亮。
 *
 * <p>原实现就是「开启时记录旧 gamma 并写 100，每帧再写一次，关闭时还原」。
 * 每帧重写是必要的：部分光影/资源包会自己改 {@code gammaSetting}。
 *
 * <p>无 UI。宿主在每帧更新回调里调 {@link #onUpdate()}（原版是 {@code EventUpdate}）。
 */
public class FullBright extends Feature {

    /** 原版亮度上限是 1.0，这里用 100 是原客户端的做法，比自己改 lightmap 稳。 */
    public static final float GAMMA_VALUE = 100.0F;

    /** Mixin / 宿主读取的开关标志。 */
    public static boolean using = false;

    /** 开启前的 gamma，关闭时还原。 */
    private float oldGamma;

    public FullBright() {
        super("FullBright");
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getMinecraft();
        oldGamma = mc.gameSettings.gammaSetting;
        mc.gameSettings.gammaSetting = GAMMA_VALUE;
        using = true;
    }

    @Override
    protected void onDisable() {
        Minecraft.getMinecraft().gameSettings.gammaSetting = oldGamma;
        using = false;
    }

    /** 每帧调用；关闭时不应再调用（或调用也无副作用）。 */
    public static void onUpdate() {
        if (!using) {
            return;
        }
        Minecraft.getMinecraft().gameSettings.gammaSetting = GAMMA_VALUE;
    }
}
