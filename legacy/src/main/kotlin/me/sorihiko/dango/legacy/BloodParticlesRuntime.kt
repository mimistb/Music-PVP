package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.BloodParticlesModule

import net.minecraft.block.Block
import net.minecraft.client.Minecraft
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.init.Blocks
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.ResourceLocation

/**
 * Blood Particles 运行时：攻击生物时在目标身上喷红石块碎裂粒子，按 Sound 开关播放 dig.stone。
 *
 * 由 ForgeBridge 驱动：
 *   tick()          ← ClientTickEvent(Phase.END)，每 tick 记录准星指向的生物
 *   onAttackEntity  ← AttackEntityEvent，粒子 / 音效的位置沿用原码取准星目标
 *
 * 目标每 tick 全量重算（准星离开生物后立刻清空），攻击时若准星目标与事件实体不一致
 * （挥击发生在 tick 之间的典型情况）则回退到被攻击的实体，保证粒子一定生成在正确的生物身上。
 * 关闭模块后 tick 会清掉目标、攻击事件直接返回，不产生任何粒子或音效。
 */
internal object BloodParticlesRuntime {

    private var target: EntityLivingBase? = null

    /** 红石块状态 id 只需计算一次，之后每次攻击复用。 */
    private var cachedStateId = -1

    fun tick() {
        val module = BloodParticlesModule.of()
        if (module == null || !module.enabled) {
            target = null
            return
        }
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld
        val hit = mc.objectMouseOver?.entityHit as? EntityLivingBase
        // 全量重算：准星离开生物、目标死亡或换世界时立刻清空，避免用完一次后仍指向旧目标
        target = if (hit != null && world != null && hit.isEntityAlive && hit.worldObj === world) hit else null
    }

    fun onAttackEntity(entity: Entity?) {
        val module = BloodParticlesModule.of() ?: return
        if (!module.enabled) return
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld ?: return

        val crosshair = target
        val victim = when {
            crosshair != null && (entity == null || crosshair === entity) && crosshair.isEntityAlive -> crosshair
            entity is EntityLivingBase && entity.isEntityAlive && entity.worldObj === world -> entity
            else -> null
        } ?: return

        val stateId = stateId()
        val box = victim.entityBoundingBox
        // 取包围盒 65% 高度处（玩家约在胸口），矮小生物也不会像原来的固定 -0.75 那样落到地面以下
        val centerY = box.minY + (box.maxY - box.minY) * 0.65
        val rand = world.rand
        repeat(module.amount()) {
            val ox = (rand.nextFloat() - 0.5f) * 0.5f
            val oy = (rand.nextFloat() - 0.5f) * 0.3f
            val oz = (rand.nextFloat() - 0.5f) * 0.5f
            world.spawnParticle(
                EnumParticleTypes.BLOCK_CRACK,
                victim.posX + ox, centerY + oy, victim.posZ + oz,
                (ox * 0.2).toDouble(), (oy * 0.2 + 0.02).toDouble(), (oz * 0.2).toDouble(),
                stateId,
            )
        }

        if (module.soundEnabled()) {
            // 构造参数顺序为 (soundId, volume, pitch, x, y, z)，与原码 (4.0f, 1.2f) 一致
            mc.soundHandler.playSound(
                PositionedSoundRecord(
                    ResourceLocation("dig.stone"),
                    4.0f, 1.2f,
                    victim.posX.toFloat(), victim.posY.toFloat(), victim.posZ.toFloat(),
                ),
            )
        }
    }

    private fun stateId(): Int {
        if (cachedStateId == -1) {
            cachedStateId = Block.getStateId(Blocks.redstone_block.defaultState)
        }
        return cachedStateId
    }
}
