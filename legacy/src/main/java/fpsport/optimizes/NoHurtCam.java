package fpsport.optimizes;

import fpsport.core.Feature;

/**
 * 关闭受伤镜头抖动。
 *
 * <p>只维护一个静态标志，由 {@code MixinEntityRenderer} 在 {@code hurtCameraEffect} 的
 * {@code HEAD} 处读取并取消原版调用。
 */
public class NoHurtCam extends Feature {

    public static boolean using = false;

    public NoHurtCam() {
        super("NoHurtCam");
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
