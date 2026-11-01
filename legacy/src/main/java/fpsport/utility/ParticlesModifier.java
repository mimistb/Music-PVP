package fpsport.utility;

import fpsport.core.Feature;

/**
 * 粒子修改：拦截方块破坏粒子。
 *
 * <p>只维护静态开关；真正的拦截在 {@code MixinEffectRenderer.addEffect} 里。
 */
public class ParticlesModifier extends Feature {

    /** true = 保留方块破坏粒子；false = 完全丢弃。 */
    public static boolean block = true;
    public static boolean using = false;

    public ParticlesModifier() {
        super("ParticlesModifier");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }
}
