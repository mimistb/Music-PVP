package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;

import java.awt.Color;

/**
 * 自定义迷雾。
 *
 * <p>由 {@code MixinEntityRenderer} 在 {@code updateFogColor} / {@code setupFog} 的 {@code RETURN}
 * 处接管。失明药水是硬性例外：原版会把雾强制成极短距离，覆盖它等于解除视野限制。
 */
public class CustomFog extends Feature {

    public static Color color = new Color(0, 200, 255);
    public static double startDistance = 32.0;
    public static double endDistance = 64;
    /** Linear / Exponential。 */
    public static String fogMode = "Linear";
    public static boolean affectWater = false;
    public static boolean affectLava = false;

    public static boolean using;

    public CustomFog() {
        super("CustomFog");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 是否应该接管当前这一帧的雾。 */
    public static boolean shouldOverrideFog() {
        if (!using) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return false;
        }
        if (mc.thePlayer.isPotionActive(Potion.blindness)) {
            return false;
        }
        if (!affectWater && mc.thePlayer.isInsideOfMaterial(Material.water)) {
            return false;
        }
        if (!affectLava && mc.thePlayer.isInsideOfMaterial(Material.lava)) {
            return false;
        }
        return true;
    }
}
