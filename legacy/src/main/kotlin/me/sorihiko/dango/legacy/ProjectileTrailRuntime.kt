package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.ProjectileTrailModule
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.projectile.EntityArrow
import net.minecraft.entity.projectile.EntityEgg
import net.minecraft.entity.item.EntityEnderPearl
import net.minecraft.entity.projectile.EntityFishHook
import net.minecraft.entity.projectile.EntityPotion
import net.minecraft.entity.projectile.EntitySnowball
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemEgg
import net.minecraft.item.ItemEnderPearl
import net.minecraft.item.ItemFishingRod
import net.minecraft.item.ItemPotion
import net.minecraft.item.ItemSnowball
import net.minecraft.item.ItemStack
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumParticleTypes
import org.lwjgl.opengl.GL11
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 投掷物轨迹：
 *  - tick：给飞行的投掷物补粒子尾迹（类型由设置决定）
 *  - render3D：手持弓/投掷物时按真实初速 + 重力 + 阻力模拟 80 步，画出 3D 折线；
 *    力度倍率与角度偏移每帧读取模块配置，所以拖动滑块时预览跟着变
 */
internal object ProjectileTrailRuntime {

    private val TYPES = arrayOf(
        EnumParticleTypes.HEART,
        EnumParticleTypes.FLAME,
        EnumParticleTypes.PORTAL,
        EnumParticleTypes.SMOKE_NORMAL,
        EnumParticleTypes.ENCHANTMENT_TABLE,
        EnumParticleTypes.CRIT,
        EnumParticleTypes.SNOW_SHOVEL,
        EnumParticleTypes.REDSTONE,
        EnumParticleTypes.NOTE,
        EnumParticleTypes.SPELL_INSTANT,
    )

    // 复用顶点缓冲，避免每帧新建数组；轨迹相对眼睛计算，每 tick / 视线变化才重算
    private val points = DoubleArray(STEPS * 3)
    private var pointCount = 0
    private var cacheTick = -1
    private var cacheYaw = Int.MIN_VALUE
    private var cachePitch = Int.MIN_VALUE
    private var cachePower = -1f
    private var cacheAngle = Float.MIN_VALUE
    private var cacheSpeed = -1f
    private var cacheWorld: net.minecraft.world.World? = null

    fun tick() {
        val module = ProjectileTrailModule.of() ?: return
        if (!module.enabled || !module.trailEnabled()) return
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld ?: return
        val type = TYPES[module.type().coerceIn(0, TYPES.size - 1)]
        for (entity in world.loadedEntityList) {
            if (!isTrailed(entity)) continue
            world.spawnParticle(
                type,
                entity.posX + (randomOffset()),
                entity.posY + 0.15,
                entity.posZ + randomOffset(),
                0.0, 0.0, 0.0,
            )
            if (entity.ticksExisted % 2 == 0) {
                world.spawnParticle(
                    type,
                    entity.posX + randomOffset() * 0.4,
                    entity.posY - 0.1,
                    entity.posZ + randomOffset() * 0.4,
                    0.0, -0.02, 0.0,
                )
            }
        }
    }

    fun onRender3D(partialTicks: Float) {
        val module = ProjectileTrailModule.of() ?: return
        if (!module.enabled || !module.predictEnabled()) return
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld ?: return
        val player = mc.thePlayer ?: return
        val stack = player.heldItem ?: return
        val base = initialSpeed(player, stack) ?: return

        // 缓存键：tick + 视线（0.5° 量化）+ 力度 / 角度 / 初速。
        // 命中时只把相对轨迹平移到当前眼睛位置，不再做 80 次方块碰撞查询。
        val power = module.power()
        val angleDeg = module.angleOffset()
        val yawKey = (player.rotationYaw * 2f).toInt()
        val pitchKey = (player.rotationPitch * 2f).toInt()
        val tick = player.ticksExisted
        if (world !== cacheWorld || tick != cacheTick || yawKey != cacheYaw || pitchKey != cachePitch ||
            power != cachePower || angleDeg != cacheAngle || base != cacheSpeed
        ) {
            computePath(world, player, base, power, angleDeg)
            cacheWorld = world
            cacheTick = tick
            cacheYaw = yawKey
            cachePitch = pitchKey
            cachePower = power
            cacheAngle = angleDeg
            cacheSpeed = base
        }
        if (pointCount < 2) return

        val eye = player.getPositionEyes(partialTicks)
        val rm = mc.renderManager
        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.disableLighting()
        GlStateManager.color(0.35f, 0.78f, 1f, 0.85f)
        GL11.glLineWidth(2.0f)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        try {
            val tess = Tessellator.getInstance()
            val wr = tess.worldRenderer
            wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION)
            for (i in 0 until pointCount) {
                wr.pos(
                    eye.xCoord + points[i * 3] - rm.viewerPosX,
                    eye.yCoord + points[i * 3 + 1] - rm.viewerPosY,
                    eye.zCoord + points[i * 3 + 2] - rm.viewerPosZ,
                ).endVertex()
            }
            tess.draw()
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH)
            GL11.glLineWidth(1f)
            GlStateManager.enableTexture2D()
            GlStateManager.disableBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.popMatrix()
        }
    }

    /** 计算相对眼睛的抛物线；方块碰撞隔步采样，减少 getBlockState 次数 */
    private fun computePath(
        world: net.minecraft.world.World,
        player: net.minecraft.entity.player.EntityPlayer,
        base: Float,
        power: Float,
        angleDeg: Float,
    ) {
        val look = player.getLookVec()
        // 角度偏移：绕水平右向量旋转视线
        val angle = Math.toRadians(angleDeg.toDouble())
        val rightX = look.zCoord
        val rightZ = -look.xCoord
        val rightLen = sqrt(rightX * rightX + rightZ * rightZ)
        val rx = if (rightLen > 1e-4) rightX / rightLen else 0.0
        val rz = if (rightLen > 1e-4) rightZ / rightLen else 1.0
        val cosA = cos(angle)
        val sinA = sin(angle)
        // k × v（k = (rx, 0, rz)）
        val crossX = -rz * look.yCoord
        val crossY = rz * look.xCoord - rx * look.zCoord
        val crossZ = rx * look.yCoord
        val dot = rx * look.xCoord + rz * look.zCoord
        val dx = look.xCoord * cosA + crossX * sinA + rx * dot * (1 - cosA)
        val dy = look.yCoord * cosA + crossY * sinA
        val dz = look.zCoord * cosA + crossZ * sinA + rz * dot * (1 - cosA)

        val speed = base * power
        var px = dx * 0.35
        var py = dy * 0.35
        var pz = dz * 0.35
        var vx = dx * speed
        var vy = dy * speed
        var vz = dz * speed

        pointCount = 0
        // 路径全部存成「相对眼睛」的偏移，绘制时随当前眼睛位置平移即可
        val eye = player.getPositionEyes(1f)
        for (i in 0 until STEPS) {
            points[pointCount * 3] = px
            points[pointCount * 3 + 1] = py
            points[pointCount * 3 + 2] = pz
            pointCount++
            px += vx
            py += vy
            pz += vz
            vy -= 0.03
            vx *= 0.99
            vy *= 0.99
            vz *= 0.99
            // 前 24 步逐格检查（贴脸/近距命中要准），之后隔步采样：
            // 采样精度保持不变，避免为了省几次 getBlockState 引入轨迹穿墙
            if (i < 24 || i % 2 == 0) {
                if (isSolid(world, eye.xCoord + px, eye.yCoord + py, eye.zCoord + pz)) break
            }
        }
    }

    private fun initialSpeed(player: net.minecraft.entity.player.EntityPlayer, stack: ItemStack): Float? {
        val item = stack.item ?: return null
        return when {
            item is ItemBow && player.isUsingItem && player.itemInUseCount > 0 -> {
                val charge = (72000 - player.itemInUseCount).coerceIn(0, 72000)
                val f = (charge.toFloat() / 20f).coerceIn(0f, 1f)
                (f * f + f * 2f) / 3f * 3f
            }
            item is ItemSnowball || item is ItemEgg || item is ItemEnderPearl -> 1.5f
            item is ItemPotion -> 0.6f
            item is ItemFishingRod && player.isUsingItem -> 1.2f
            else -> null
        }
    }

    private fun isSolid(world: net.minecraft.world.World, x: Double, y: Double, z: Double): Boolean {
        val pos = BlockPos(x, y, z)
        val state = world.getBlockState(pos)
        val block = state.block ?: return false
        return block.material.isSolid
    }

    private fun isTrailed(entity: Entity): Boolean =
        entity is EntityArrow || entity is EntitySnowball || entity is EntityEgg ||
            entity is EntityEnderPearl || entity is EntityPotion || entity is EntityFishHook

    // 共用 Random：Math.random() 每次都走全局同步随机源，粒子多时是热点
    private val random = java.util.Random()

    private fun randomOffset(): Double = (random.nextDouble() - 0.5) * 0.5

    private const val STEPS = 80
}
