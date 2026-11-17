package me.sorihiko.dango.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 丢弃环境 / 天气类粒子（EffectRenderer#addEffect 里按粒子类型过滤）。
     *
     * 注意：只丢环境粒子，战斗特效（crit / flame / heart / 方块破坏）永远保留，
     * 否则一开这个开关 PVP 粒子也会跟着消失。
     */
    public static volatile boolean dropParticles = false;

    /** 开发环境 / 已经去混淆时的环境粒子类名 */
    private static final String[] AMBIENT_DEOBF = {
            "net.minecraft.client.particle.EntityLavaFX",
            "net.minecraft.client.particle.EntityPortalFX",
            "net.minecraft.client.particle.EntityBubbleFX",
            "net.minecraft.client.particle.EntitySplashFX",
            "net.minecraft.client.particle.EntitySuspendedFX",
            "net.minecraft.client.particle.EntityRainFX",
            "net.minecraft.client.particle.EntitySnowShovelFX",
            "net.minecraft.client.particle.EntityCloudFX",
            "net.minecraft.client.particle.EntitySmokeFX",
            "net.minecraft.client.particle.EntityLargeSmokeFX",
    };

    /** 生产环境里这些类叫 beb / bek 之类，由 transformer 在加载时把真实类名登记进来 */
    private static final Set<String> AMBIENT = ConcurrentHashMap.newKeySet();

    private FpsFlags() {
    }

    /** transformer 加载到环境粒子类时调用，把这个类在当前环境下的真实二进制名记下来 */
    public static void noteParticleClass(String runtimeName) {
        if (runtimeName == null || runtimeName.isEmpty()) return;
        AMBIENT.add(runtimeName);
    }

    /** true = 这个粒子属于环境 / 天气效果，可以丢；战斗特效永远返回 false */
    public static boolean shouldDropParticle(Object particle) {
        if (!dropParticles || particle == null) return false;
        String name = particle.getClass().getName();
        if (AMBIENT.contains(name)) return true;
        for (String candidate : AMBIENT_DEOBF) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
