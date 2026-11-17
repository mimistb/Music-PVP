package fpsport.pvp;

import fpsport.core.Feature;
import me.sorihiko.dango.utils.GammaGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.EntityViewRenderEvent;

import java.util.List;

/**
 * 防盲：致盲药水 / 附近强光（闪电）时自动把场景亮度抬到下限，并削弱致盲雾。
 *
 * <p>亮度处理遵守两条规则：
 * <ul>
 *   <li>只在当前 gamma 低于保护值时写入，不主动降低用户的亮度；</li>
 *   <li>解除保护时，只有 gamma 仍等于我们写进去的值才还原，避免覆盖「永久亮度」写的 100。</li>
 * </ul>
 */
public class BlindGuard extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 致盲时的 gamma 下限，0 ~ 1（原版上限为 1）。 */
    public static float gammaFloor = 1.0F;
    /** 强光保持时间（毫秒）。 */
    public static int flashMs = 1200;

    private static boolean applied;
    private static float oldGamma;
    private static long flashUntil;
    private static int scanTicks;

    public BlindGuard() {
        super("BlindGuard");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        reset();
    }

    /** 切世界 / 关闭时把亮度还原。 */
    public static void reset() {
        restore();
        flashUntil = 0L;
        scanTicks = 0;
    }

    /** 每 tick 调用：低频扫描附近闪电，作为「强光」来源。 */
    public void onTick() {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            restore();
            return;
        }
        if (++scanTicks < 2) {
            return;
        }
        scanTicks = 0;
        EntityPlayer player = mc.thePlayer;
        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - 48.0D, player.posY - 32.0D, player.posZ - 48.0D,
                player.posX + 48.0D, player.posY + 32.0D, player.posZ + 48.0D);
        List<EntityLightningBolt> bolts = mc.theWorld.getEntitiesWithinAABB(EntityLightningBolt.class, box);
        if (bolts != null && !bolts.isEmpty()) {
            flashUntil = System.currentTimeMillis() + Math.max(200, flashMs);
        }
    }

    /** 每帧调用（必须在 FullBright.onUpdate 之前）。 */
    public static void onUpdate() {
        if (!using) {
            restore();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.gameSettings == null) {
            restore();
            return;
        }
        boolean blind = mc.thePlayer.isPotionActive(Potion.blindness);
        boolean flash = System.currentTimeMillis() < flashUntil;
        if (!blind && !flash) {
            restore();
            return;
        }
        if (!applied) {
            // 永久亮度会先把 gamma 写成 100：这个值不是用户亮度，记下来会在解除致盲后
            // 把 100 写回去（表现为关闭永久亮度后依然全亮）。统一夹到原版上限。
            oldGamma = Math.min(GammaGuard.VANILLA_MAX_GAMMA,
                    Math.max(0.0F, mc.gameSettings.gammaSetting));
            applied = true;
        }
        float target = Math.max(oldGamma, gammaFloor);
        if (mc.gameSettings.gammaSetting < target) {
            mc.gameSettings.gammaSetting = target;
        }
    }

    /** 致盲雾：把密度压到能看到近处轮廓，避免完全失明。 */
    public static void applyFogDensity(EntityViewRenderEvent.FogDensity event) {
        if (!using || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !mc.thePlayer.isPotionActive(Potion.blindness)) {
            return;
        }
        if (event.density > 0.45F) {
            event.density = 0.45F;
        }
    }

    private static void restore() {
        if (!applied) {
            return;
        }
        applied = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        float guard = Math.max(oldGamma, gammaFloor);
        if (Math.abs(mc.gameSettings.gammaSetting - guard) < 1.0E-4F) {
            mc.gameSettings.gammaSetting = oldGamma;
        }
    }
}
