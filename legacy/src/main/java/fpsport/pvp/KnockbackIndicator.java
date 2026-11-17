package fpsport.pvp;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;

/**
 * 受击击退方向提示：屏幕边缘的箭头指向被击退的方向。
 *
 * <p>方向优先级：弹道实体的运动方向 → 远离攻击者的方向 → 自身速度方向。
 * 箭头位置按当前相机 yaw 实时换算，玩家转头时箭头会跟着转到正确的一侧。
 */
public class KnockbackIndicator extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 提示持续时间（毫秒）。 */
    public static int durationMs = 900;
    /** 箭头大小（像素）。 */
    public static int size = 24;
    /** 箭头颜色，ARGB 的高 24 位（alpha 由淡出计算）。 */
    public static int color = 0xFFFF5A5A;

    private static double dirX;
    private static double dirZ;
    private static long startMs;
    private static boolean active;

    public KnockbackIndicator() {
        super("KnockbackIndicator");
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

    public static void reset() {
        active = false;
        dirX = 0.0D;
        dirZ = 0.0D;
        startMs = 0L;
    }

    /** 自己受到伤害时调用（事件未被取消）。 */
    public static void onHurt(DamageSource source) {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || source == null) {
            return;
        }
        Entity direct = source.getSourceOfDamage();
        Entity attacker = source.getEntity();

        double dx;
        double dz;
        if (direct != null && direct != player
                && direct.motionX * direct.motionX + direct.motionZ * direct.motionZ > 1.0E-4D) {
            // 箭 / 投掷物：沿弹道飞行方向被推开
            dx = direct.motionX;
            dz = direct.motionZ;
        } else if (attacker != null && attacker != player) {
            // 近战 / 爆炸：远离攻击者
            dx = player.posX - attacker.posX;
            dz = player.posZ - attacker.posZ;
        } else {
            if (player.motionX * player.motionX + player.motionZ * player.motionZ < 1.0E-4D) {
                return;
            }
            dx = player.motionX;
            dz = player.motionZ;
        }

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) {
            return;
        }
        dirX = dx / len;
        dirZ = dz / len;
        startMs = System.currentTimeMillis();
        active = true;
    }

    /** HUD 阶段调用。 */
    public static void render(int width, int height) {
        if (!using || !active || durationMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - startMs;
        if (elapsed < 0L || elapsed > durationMs) {
            active = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            return;
        }

        float fade = 1.0F - (float) elapsed / (float) durationMs;
        int alpha = (int) (fade * fade * 255.0F);
        if (alpha <= 4) {
            return;
        }

        double angle = PvpLogic.screenAngle(dirX, dirZ, player.rotationYaw);
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        float len = Math.max(8.0F, size);
        float margin = 20.0F + len;
        float radiusX = Math.max(10.0F, centerX - margin);
        float radiusY = Math.max(10.0F, centerY - margin);

        float ox = (float) Math.sin(angle);
        float oy = (float) -Math.cos(angle);
        float px = centerX + ox * radiusX;
        float py = centerY + oy * radiusY;

        float tipX = px + ox * len;
        float tipY = py + oy * len;
        float baseX = px - ox * (len * 0.18F);
        float baseY = py - oy * (len * 0.18F);
        float half = len * 0.62F;
        float perpX = -oy;
        float perpY = ox;
        float leftX = baseX + perpX * half;
        float leftY = baseY + perpY * half;
        float rightX = baseX - perpX * half;
        float rightY = baseY - perpY * half;

        int fill = (alpha << 24) | (color & 0xFFFFFF);
        int outline = (alpha << 24) | 0xFFFFFF;
        PvpHud.triangle(tipX, tipY, leftX, leftY, rightX, rightY, fill);
        PvpHud.line(tipX, tipY, leftX, leftY, outline, 2.0F);
        PvpHud.line(leftX, leftY, rightX, rightY, outline, 2.0F);
        PvpHud.line(rightX, rightY, tipX, tipY, outline, 2.0F);
    }
}
