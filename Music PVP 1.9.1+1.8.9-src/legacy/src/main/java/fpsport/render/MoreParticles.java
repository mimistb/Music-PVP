package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.util.EnumParticleTypes;

/**
 * 更多粒子：受击暴击 / 锋利粒子、特殊命中特效、击杀特效。
 *
 * <p>完全自包含，不需要任何 Mixin。宿主在「攻击命中」与「每帧更新」两个回调里调用
 * {@link #onAttack(Entity)} 与 {@link #onUpdate()} 即可。
 */
public class MoreParticles extends Feature {

    /** 锋利附魔时每次攻击补的魔法暴击粒子数量。 */
    public static int sharpness = 2;
    public static boolean alwaysSharpness = false;
    /** 下落攻击时补的暴击粒子数量。 */
    public static int crit = 2;
    public static boolean alwaysCrit = false;
    /** None / Heart / Flame / Blood。 */
    public static String special = "None";
    /** None / Lightning / Explosion。 */
    public static String killEffect = "None";

    /** 最近一次攻击的目标，用于在它死亡时放击杀特效。 */
    private Entity target = null;
    private Entity lastEffect = null;

    public MoreParticles() {
        super("MoreParticles");
    }

    @Override
    protected void onDisable() {
        target = null;
        lastEffect = null;
    }

    /** 宿主每帧调用一次（对应源码的 EventUpdate）。 */
    public void onUpdate() {
        if (!(target instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase living = (EntityLivingBase) target;
        if (lastEffect != target && (living.getHealth() <= 0 || !living.isEntityAlive())) {
            Minecraft mc = Minecraft.getMinecraft();
            if ("Lightning".equals(killEffect)) {
                if (mc.theWorld != null) {
                    mc.theWorld.addWeatherEffect(
                            new EntityLightningBolt(mc.theWorld, living.posX, living.posY, living.posZ));
                    mc.theWorld.playSound(living.posX, living.posY, living.posZ, "random.explode", 1f, 1.0f, false);
                }
            } else if ("Explosion".equals(killEffect)) {
                mc.effectRenderer.emitParticleAtEntity(target, EnumParticleTypes.EXPLOSION_LARGE);
                if (mc.theWorld != null) {
                    mc.theWorld.playSound(living.posX, living.posY, living.posZ, "random.explode", 1f, 1.0f, false);
                }
            }
            lastEffect = target;
            target = null;
        }
    }

    /** 宿主在攻击命中时调用（对应源码的 EventAttack）。 */
    public void onAttack(Entity attacked) {
        if (attacked == null || !attacked.isEntityAlive()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        target = attacked;

        if (mc.thePlayer != null && (mc.thePlayer.fallDistance != 0f || alwaysCrit)) {
            for (int i = 0; i < crit; i++) {
                mc.effectRenderer.emitParticleAtEntity(attacked, EnumParticleTypes.CRIT);
            }
        }

        boolean needSharpness = false;
        if (mc.thePlayer != null && mc.thePlayer.inventory.getCurrentItem() != null) {
            needSharpness = mc.thePlayer.inventory.getCurrentItem().getEnchantmentTagList() != null
                    && !mc.thePlayer.inventory.getCurrentItem().getEnchantmentTagList().hasNoTags()
                    && mc.thePlayer.inventory.getCurrentItem().getEnchantmentTagList().toString().contains("id:16s");
        }
        if (needSharpness || alwaysSharpness) {
            for (int i = 0; i < sharpness; i++) {
                mc.effectRenderer.emitParticleAtEntity(attacked, EnumParticleTypes.CRIT_MAGIC);
            }
        }

        if ("Heart".equals(special)) {
            // 头顶上方一圈爱心
            double baseY = attacked.posY + attacked.height + 0.6;
            if (mc.theWorld != null) {
                for (int i = 0; i < 5; i++) {
                    double angle = Math.PI * 2.0 * i / 5.0;
                    mc.theWorld.spawnParticle(EnumParticleTypes.HEART,
                            attacked.posX + Math.cos(angle) * 0.35,
                            baseY,
                            attacked.posZ + Math.sin(angle) * 0.35,
                            0.0, 0.0, 0.0);
                }
            } else {
                mc.effectRenderer.emitParticleAtEntity(attacked, EnumParticleTypes.HEART);
            }
        } else if ("Flame".equals(special)) {
            // 腰间一圈火焰，带一点向上飘的速度
            double baseY = attacked.posY + attacked.height * 0.5;
            if (mc.theWorld != null) {
                for (int i = 0; i < 6; i++) {
                    double angle = Math.PI * 2.0 * i / 6.0;
                    mc.theWorld.spawnParticle(EnumParticleTypes.FLAME,
                            attacked.posX + Math.cos(angle) * 0.4,
                            baseY,
                            attacked.posZ + Math.sin(angle) * 0.4,
                            Math.cos(angle) * 0.02, 0.02, Math.sin(angle) * 0.02);
                }
            } else {
                mc.effectRenderer.emitParticleAtEntity(attacked, EnumParticleTypes.FLAME);
            }
        } else if ("Blood".equals(special)) {
            // 1.8.9 的 REDSTONE 颜色走 spawnParticle 的速度参数（Factory 把它们当作 RGB），
            // 所以「扩散」用圆周上的落点表现，速度槽留给颜色。
            double centerX = attacked.posX;
            double centerY = attacked.posY + attacked.height * 0.5;
            double centerZ = attacked.posZ;
            if (mc.objectMouseOver != null && mc.objectMouseOver.hitVec != null) {
                centerX = mc.objectMouseOver.hitVec.xCoord;
                centerY = mc.objectMouseOver.hitVec.yCoord;
                centerZ = mc.objectMouseOver.hitVec.zCoord;
            }
            if (mc.theWorld != null) {
                mc.theWorld.playSound(centerX, centerY, centerZ, "dig.stone", 1f, 1f, false);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 2.0 * i / 8.0;
                    mc.theWorld.spawnParticle(EnumParticleTypes.REDSTONE,
                            centerX + Math.cos(angle) * 0.35,
                            centerY,
                            centerZ + Math.sin(angle) * 0.35,
                            0.75, 0.05, 0.05);
                }
            } else {
                mc.effectRenderer.emitParticleAtEntity(attacked, EnumParticleTypes.REDSTONE);
            }
        }
    }
}
