package me.sorihiko.dango.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Custom Held Items ASM 回调。
 *
 * <p>变换拆成两次注入：
 * <ul>
 *   <li>{@link #translate()} 在 {@code ItemRenderer#renderItemInFirstPerson} 方法头调用，
 *       平移仍作用在相机空间，保持已有配置的位移手感；</li>
 *   <li>{@link #scale()} 在 {@code GlStateManager.enableRescaleNormal()} 之前调用
 *       （即 {@code rotateArroundXAndY} / {@code rotateWithPlayerRotations} 之后），
 *       让非等比缩放在物品本地轴生效。若仍在方法头缩放，缩放会先于视角旋转，
 *       抬头/低头时模型被剪切拉伸，表现为装备穿模、材质错乱。</li>
 * </ul>
 *
 * <p>两条钩子都直接返回/不 push 矩阵：平移在 renderHand 的 push/pop 范围内，
 * 缩放在原版 pushMatrix 之前、由同一段 push/pop 收尾，不会泄漏到后续渲染。
 * 数值由 legacy 的 CustomHeldItemsRuntime 每 tick 写入。
 */
public final class CustomHeldItemsHooks {

    public static volatile boolean enabled;
    public static volatile float x;
    public static volatile float y;
    public static volatile float z;
    public static volatile float xScale = 1.0f;
    public static volatile float yScale = 1.0f;
    public static volatile float zScale = 1.0f;

    private CustomHeldItemsHooks() {
    }

    public static void translate() {
        if (!enabled || !holdingItem()) {
            return;
        }
        GlStateManager.translate(x, y, z);
    }

    public static void scale() {
        if (!enabled || !holdingItem()) {
            return;
        }
        GlStateManager.scale(xScale, yScale, zScale);
    }

    /**
     * 只在手里真的有物品时才做变换。
     *
     * <p>{@code renderItemInFirstPerson} 在空手时会走 {@code renderPlayerArm} 画手臂（皮肤贴图），
     * 方法头的平移同样会作用到手臂上，表现为第一人称的皮肤手臂错位/飘在屏幕外。
     */
    private static boolean holdingItem() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && mc.thePlayer.getHeldItem() != null;
    }
}
