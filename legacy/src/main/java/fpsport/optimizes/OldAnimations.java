package fpsport.optimizes;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.WorldServer;

/**
 * 1.7 旧动画。
 *
 * <p>两类行为：
 * <ul>
 *   <li>潜行时的视角高度过渡（{@link #getClientEyeHeight}，供 {@code MixinEntityRenderer} 使用）；</li>
 *   <li>第一人称物品动画（方块格挡时的 13 种动作、鱼竿 / 弓的旧姿态、格挡时补一次挥臂），
 *       实现在 {@code MixinItemRenderer} 里。</li>
 * </ul>
 */
public class OldAnimations extends Feature {

    public static boolean noShield = true;
    public static boolean animationSneak = true;
    public static boolean oldBlock = true;
    /** 格挡动画样式：Lunar / 1.7 / Swang / Sigma / Swank / Swong / Debug / Luna / Jigsaw / Jello / Push。 */
    public static String animationMode = "Lunar";
    public static boolean oldRod = true;
    public static boolean oldBow = true;
    public static boolean oldSwing = true;
    public static boolean oldUsing = true;
    public static boolean blockSwing = true;
    public static boolean oldDamage = true;
    public static boolean oldThirdPerson = true;

    /** 格挡动画整体偏移（格）。 */
    public static double x = 0;
    public static double y = 0;
    public static double z = 0;
    public static double scale = 1;

    public static boolean using = false;

    private static final float START_HEIGHT = 1.62f;
    private static final float END_HEIGHT = 1.54f;

    private static float eyeHeight = 0f;
    private static float lastEyeHeight = 0f;

    public OldAnimations() {
        super("OldAnimations");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        eyeHeight = 0f;
        lastEyeHeight = 0f;
    }

    /** 宿主每 tick 调用一次（对应源码的 EventTick）。 */
    public void onTick() {
        lastEyeHeight = eyeHeight;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP thePlayer = mc.thePlayer;
        if (thePlayer == null) {
            return;
        }
        if (thePlayer.isSneaking()) {
            eyeHeight = END_HEIGHT;
        } else if (!animationSneak) {
            eyeHeight = START_HEIGHT;
        } else if (eyeHeight < START_HEIGHT) {
            float delta = START_HEIGHT - eyeHeight;
            delta *= 0.4f;
            eyeHeight = START_HEIGHT - delta;
        }

        if (mc.gameSettings.keyBindAttack.isKeyDown()
                && thePlayer.isUsingItem()
                && blockSwing
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            swingItem();
        }
    }

    /** 相机眼高（Mixin 在 orientCamera 里替换 {@code Entity.getEyeHeight()}）。 */
    public static float getClientEyeHeight(float partialTicks) {
        if (!animationSneak) {
            return eyeHeight;
        }
        return lastEyeHeight + (eyeHeight - lastEyeHeight) * partialTicks;
    }

    public static void swingItem() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        ItemStack stack = player.getHeldItem();
        if (stack == null || stack.getItem() == null || !stack.getItem().onEntitySwing(player, stack)) {
            if (!player.isSwingInProgress
                    || player.swingProgressInt >= getArmSwingAnimationEnd() / 2
                    || player.swingProgressInt < 0) {
                player.swingProgressInt = -1;
                player.isSwingInProgress = true;
                if (player.worldObj instanceof WorldServer) {
                    ((WorldServer) player.worldObj).getEntityTracker()
                            .sendToAllTrackingEntity(player, new S0BPacketAnimation(player, 0));
                }
            }
        }
    }

    private static int getArmSwingAnimationEnd() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return 6;
        }
        if (player.isPotionActive(Potion.digSpeed)) {
            return 6 - (1 + player.getActivePotionEffect(Potion.digSpeed).getAmplifier());
        }
        if (player.isPotionActive(Potion.digSlowdown)) {
            return 6 + (1 + player.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) * 2;
        }
        return 6;
    }
}
