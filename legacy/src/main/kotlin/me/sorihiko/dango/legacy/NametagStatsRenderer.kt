package me.sorihiko.dango.legacy

import bedwarsstats.BedWarsStats
import bedwarsstats.StarText
import fpsport.optimizes.Performance
import fpsport.utility.LevelTag
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.impls.music.NametagStatsModule

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.client.event.RenderLivingEvent

import org.lwjgl.opengl.GL11

/**
 * 名牌上方的 Hypixel 统计行（BedWars / SkyWars 星级 / 网络等级）。
 *
 * 1.8.9 没有 Mixin 钩子，这里挂在 [RenderLivingEvent.Specials.Post]：
 * 原版名牌画完之后，在同一个世界坐标上向上偏移 0.226 格补绘一行。
 * GL 流程与 {@code fpsport.utility.LevelTag#renderName} 保持一致；
 * 数据只读 [BedWarsData]，不碰任何比赛态。
 */
object NametagStatsRenderer {

    /** HyInfo 的首行偏移：0.226 格正好压在原版名牌文字上方。 */
    private const val BASE_OFFSET = 0.226

    private const val NAME_TAG_RANGE = 64
    private const val NAME_TAG_RANGE_SNEAK = 32

    /** 统计行缓存上限：同屏玩家远少于这个数，超了直接清空，保证不会无界增长。 */
    private const val LINE_CACHE_LIMIT = 256

    /**
     * 统计行缓存：stats 对象身份 → 已拼好的行。
     *
     * 星级文本要走 101 / 51 行的配色表线性查找再逐字符插 § 码，而同一个玩家的
     * stats 对象在对局里几分钟都不变。用 IdentityHashMap 是因为统计刷新时会整对象替换，
     * 旧对象自然失效，不需要额外的版本号。
     */
    private val lineCache = java.util.IdentityHashMap<BedWarsStats, String>()

    /** 缓存对应的游戏模式与显示开关位掩码，任一变化就整体作废。 */
    private var cacheMode = Int.MIN_VALUE
    private var cacheMask = -1

    fun onPost(event: RenderLivingEvent.Specials.Post<EntityLivingBase>) {
        try {
            val module = NametagStatsModule.of() ?: return
            if (!module.enabled) return

            val entity = event.entity as? EntityPlayer ?: return
            val mc = Minecraft.getMinecraft()
            val player = mc.thePlayer ?: return
            if (!Minecraft.isGuiEnabled() || mc.gameSettings.hideGUI) return

            if (entity === player) {
                if (!BedWarsData.ntShowSelf) return
                // 原版只在第三人称渲染自己，第一人称没有可依附的名牌位置
                if (mc.gameSettings.thirdPersonView == 0) return
            }
            if (entity.isInvisibleToPlayer(player)) return

            // LevelTag 开启时 Pre 阶段会隐藏 NPC 等不符合条件的名牌，这里同步跳过
            if (LevelTag.using && !LevelTag.qualify(entity)) return

            // 与 FpsPortRuntime.onNametag 的远程剔除保持一致
            if (Performance.nametagCulling && entity !== player &&
                entity.getDistanceToEntity(player) > Performance.nametagDistance
            ) {
                return
            }

            val view = mc.renderViewEntity ?: return
            val maxDistance = if (entity.isSneaking) NAME_TAG_RANGE_SNEAK else NAME_TAG_RANGE
            if (entity.getDistanceSqToEntity(view) > (maxDistance * maxDistance).toDouble()) return

            val stats = BedWarsData.statsFor(entity.name) ?: return
            val line = line(stats)
            if (line.isEmpty()) return

            draw(mc, event.x, event.y + entity.height + 0.5 + BASE_OFFSET + BedWarsData.ntHeightOffset / 100.0, event.z, line)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music nametag stats failed: {}", t)
        }
    }

    /** 同一时刻只显示一行：按当前游戏切换；对应开关关闭时不回退到其它行。 */
    private fun line(stats: BedWarsStats): String {
        val mode = BedWarsData.gameMode
        var mask = 0
        if (BedWarsData.ntShowBedwars) mask = mask or 1
        if (BedWarsData.ntShowSkywars) mask = mask or 2
        if (BedWarsData.ntShowNetLevel) mask = mask or 4
        if (mode != cacheMode || mask != cacheMask) {
            cacheMode = mode
            cacheMask = mask
            lineCache.clear()
        }
        lineCache[stats]?.let { return it }

        val text = buildLine(stats, mode)
        if (lineCache.size >= LINE_CACHE_LIMIT) lineCache.clear()
        lineCache[stats] = text
        return text
    }

    private fun buildLine(stats: BedWarsStats, mode: Int): String {
        return when (mode) {
            BedWarsData.GAME_BEDWARS ->
                if (BedWarsData.ntShowBedwars) "§fBed§cWars§f: " + StarText.bedwars(stats.stars) else ""
            BedWarsData.GAME_SKYWARS ->
                // 0 星代表没有 SkyWars 数据（从未玩过），不显示空档
                if (BedWarsData.ntShowSkywars && stats.skywarsStars > 0) {
                    "§bSky§aWars§f: " + StarText.skywars(stats.skywarsStars)
                } else {
                    ""
                }
            else ->
                if (BedWarsData.ntShowNetLevel && stats.networkLevel > 0) {
                    "§9Level§f: §e" + stats.networkLevel
                } else {
                    ""
                }
        }
    }

    private fun draw(mc: Minecraft, x: Double, y: Double, z: Double, line: String) {
        val fontRenderer: FontRenderer = mc.fontRendererObj
        val scale = 0.016666668F * 1.6F

        GlStateManager.pushMatrix()
        // 任何一步抛异常都必须把矩阵 / 光照 / 深度 / 混合还原，否则整段世界渲染会残留状态
        try {
            GlStateManager.translate(x.toFloat(), y.toFloat(), z.toFloat())
            GL11.glNormal3f(0.0F, 1.0F, 0.0F)
            GlStateManager.rotate(-mc.renderManager.playerViewY, 0.0F, 1.0F, 0.0F)
            GlStateManager.rotate(mc.renderManager.playerViewX, 1.0F, 0.0F, 0.0F)
            GlStateManager.scale(-scale, -scale, scale)
            GlStateManager.disableLighting()
            GlStateManager.depthMask(false)
            GlStateManager.disableDepth()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)

            val textX = -fontRenderer.getStringWidth(line) / 2
            // 第一遍：关深度画暗色底字
            fontRenderer.drawString(line, textX, 0, 553648127)

            // 第二遍：开深度画正常字
            GlStateManager.enableDepth()
            GlStateManager.depthMask(true)
            fontRenderer.drawStringWithShadow(line, textX.toFloat(), 0f, -1)
        } finally {
            GlStateManager.enableLighting()
            GlStateManager.enableDepth()
            GlStateManager.depthMask(true)
            GlStateManager.disableBlend()
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F)
            GlStateManager.popMatrix()
        }
    }
}
