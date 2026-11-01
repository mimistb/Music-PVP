package me.sorihiko.dango.core;

/**
 * ASM 补丁读取的三个运行期开关（由 legacy 的 FpsPortRuntime 每 tick 写入）。
 *
 * 放在 coremod 包里，保证和 transformer 由同一个类加载器加载，注入的 GETSTATIC 一定解析得到。
 */
public final class FpsFlags {

    /** 关闭受伤镜头抖动（EntityRenderer#hurtCameraEffect 头插 return）。 */
    public static volatile boolean noHurtCam = false;

    /** 关闭第一人称火焰遮挡（ItemRenderer#renderFireInFirstPerson 头插 return）。 */
    public static volatile boolean hideFirstPersonFire = false;

    /** 丢弃全部粒子（EffectRenderer#addEffect 头插 return）。 */
    public static volatile boolean dropParticles = false;

    private FpsFlags() {
    }
}
