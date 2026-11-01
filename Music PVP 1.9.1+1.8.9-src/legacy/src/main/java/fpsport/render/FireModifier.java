package fpsport.render;

import fpsport.core.Feature;

import java.awt.Color;

/**
 * 第一人称火焰修改：降低火焰贴图高度，可选自定义颜色。
 *
 * <p>由 {@code MixinItemRenderer.renderFireInFirstPerson} 在 {@code HEAD} 处读取并整段自绘。
 */
public class FireModifier extends Feature {

    public static boolean using = false;
    /** 火焰贴图向下偏移（格），0 ~ 0.7。 */
    public static double height = 0.2;
    public static boolean customColor = false;
    public static Color color = new Color(255, 0, 0);

    public FireModifier() {
        super("FireModifier");
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
