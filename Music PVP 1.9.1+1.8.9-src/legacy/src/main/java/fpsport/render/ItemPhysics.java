package fpsport.render;

import fpsport.core.Feature;

/**
 * 物品物理（ItemPhysics）。
 *
 * <p>把掉落物从「始终平躺旋转」改成「随重力翻转、落地朝上」的物理观感。
 * 本类只有开关；渲染替换在 {@link ItemPhysicsRenderer}，由 coremod 的
 * {@code ItemPhysicsTransformer} 在 {@code RenderEntityItem#doRender} 头部接管。
 */
public class ItemPhysics extends Feature {

    /** 注入读取的开关标志。 */
    public static boolean using = false;

    /** 初始翻转角（度）。 */
    public static float rotateAngle = 90.0F;
    /** 3D 物品的渲染缩放。 */
    public static float scale = 0.5F;
    /** 空中每帧翻滚角度（度/帧）。 */
    public static float rollSpeed = (float) (5.5D * 2.0D / 10.0D);
    /** 多数量物品的随机偏移强度（格）。 */
    public static float spread = 0.15F;
    /** 非 3D 物品（平面贴图）的层叠间距（格）。 */
    public static float stackGap = 0.05375F;
    /** 是否按堆叠数量渲染多份（关闭时始终只画 1 份）。 */
    public static boolean respectCount = true;

    public ItemPhysics() {
        super("ItemPhysics");
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
