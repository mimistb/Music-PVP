package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.ArmorHudModule
import me.sorihiko.dango.module.impls.music.InventoryDisplayHudModule
import me.sorihiko.dango.module.impls.music.PotionStatusHudModule
import me.sorihiko.dango.module.impls.music.TargetInfoModule
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11

/**
 * HUD 的原版贴图叠加层：药水效果图标（inventory.png 的 18×18 图标格）与背包物品
 * （RenderItem 渲染，含数量/耐久叠加）必须用原版绘制管线，所以放在 HUD 之后单独画一遍。
 * 位置由公共模块按同一帧的布局发布到 [PotionStatusHudModule.iconRects] /
 * [InventoryDisplayHudModule.slotRects]（窗口像素），这里只负责贴图和物品。
 */
internal object LegacyHudOverlayRuntime {

    private val INVENTORY_TEXTURE = ResourceLocation("textures/gui/container/inventory.png")

    fun onRenderOverlay() {
        val mc = Minecraft.getMinecraft()
        val potionModule = PotionStatusHudModule.of()
        val inventoryModule = InventoryDisplayHudModule.of()
        val armorModule = ArmorHudModule.of()
        val targetModule = TargetInfoModule.of()
        val drawArmor = armorModule != null && armorModule.enabled
        val armorCount = if (drawArmor) countArmor(mc.thePlayer) else 0
        // 模块本身拿不到 ItemStack，件数由这里回写给面板布局（下一帧生效）
        ArmorHudModule.armorCount = armorCount
        val drawTargetArmor = targetModule != null && targetModule.enabled && TargetInfoModule.targetItemCount > 0
        val drawPotions = potionModule != null && potionModule.enabled && PotionStatusHudModule.iconCount > 0
        val drawItems = inventoryModule != null && inventoryModule.enabled && InventoryDisplayHudModule.slotCount > 0
        if (!drawPotions && !drawItems && armorCount == 0 && !drawTargetArmor) return

        val dw = mc.displayWidth
        val dh = mc.displayHeight
        // 任何 GUI 界面打开时 HUD 都被界面盖住，整层跳过（设置页 / 背包 / 聊天轮盘等）
        if (mc.currentScreen != null) return
        GlStateManager.pushMatrix()
        GlStateManager.matrixMode(GL11.GL_PROJECTION)
        GlStateManager.pushMatrix()
        GlStateManager.loadIdentity()
        GlStateManager.ortho(0.0, dw.toDouble(), dh.toDouble(), 0.0, 1000.0, 3000.0)
        GlStateManager.matrixMode(GL11.GL_MODELVIEW)
        GlStateManager.pushMatrix()
        GlStateManager.loadIdentity()
        // 正交投影的近/远平面是 1000/3000（可视深度区间 [-3000, -1000]），
        // loadIdentity 之后必须整体后移 2000，否则 z=0 的药水图标 quad 与
        // RenderItem 内部 z=100~200 的 quad 全部落在近远平面之外被裁掉，
        // 表现为「位置占好了但图标完全为空」。原版 EntityRenderer#setupOverlayRendering
        // 与项目内 LegacyGfx 都带这一步。
        GlStateManager.translate(0.0, 0.0, -2000.0)
        GlStateManager.disableDepth()
        GlStateManager.disableLighting()
        GlStateManager.enableBlend()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
        try {
            if (drawPotions) drawPotionIcons(mc)
            if (drawItems) drawInventory(mc)
            if (drawArmor && armorCount > 0) drawArmorHud(mc)
            if (drawTargetArmor) drawTargetArmorRow(mc)
        } finally {
            // 三层栈要一一对应：MODELVIEW(进入前) / PROJECTION(正交) / MODELVIEW(loadIdentity)。
            // 以前最后一个 popMatrix 还在 PROJECTION 模式下执行，等于把投影栈多弹一次、
            // 而进入前的 MODELVIEW 从来没弹过：每帧泄漏一层矩阵栈，约 30 帧后必现
            // GL_STACK_OVERFLOW，并且会把紧随其后的 KillIcon 等叠加层的矩阵模式带错。
            GlStateManager.matrixMode(GL11.GL_MODELVIEW)
            GlStateManager.popMatrix()
            GlStateManager.matrixMode(GL11.GL_PROJECTION)
            GlStateManager.popMatrix()
            GlStateManager.matrixMode(GL11.GL_MODELVIEW)
            GlStateManager.popMatrix()
            GlStateManager.disableBlend()
            GlStateManager.enableDepth()
            GlStateManager.enableLighting()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
    }

    private fun drawPotionIcons(mc: Minecraft) {
        val count = minOf(PotionStatusHudModule.iconCount, PotionStatusHudModule.iconIndices.size)
        val rects = PotionStatusHudModule.iconRects
        if (count <= 0 || rects.size < count * 4) return
        mc.textureManager.bindTexture(INVENTORY_TEXTURE)
        // 所有图标一次 begin/draw，避免每个图标切换 Tessellator 批次
        val tess = Tessellator.getInstance()
        val wr = tess.worldRenderer
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX)
        for (i in 0 until count) {
            val icon = PotionStatusHudModule.iconIndices[i]
            val u = (icon % 8) * 18
            val v = 198 + (icon / 8) * 18
            val x = rects[i * 4].toFloat()
            val y = rects[i * 4 + 1].toFloat()
            val w = rects[i * 4 + 2].toFloat()
            val h = rects[i * 4 + 3].toFloat()
            // 纹理里每个效果图标固定占 18×18，而 w/h 是缩放后的窗口像素（= 逻辑尺寸 × k）。
            // 以前直接把 h 当成纹理跨度，k≠1 时会采样到相邻图标（放大 2 倍就串成两格）。
            val texSpan = 18f
            wr.pos(x.toDouble(), (y + h).toDouble(), 0.0).tex((u / 256f).toDouble(), ((v + texSpan) / 256f).toDouble()).endVertex()
            wr.pos((x + w).toDouble(), (y + h).toDouble(), 0.0).tex(((u + texSpan) / 256f).toDouble(), ((v + texSpan) / 256f).toDouble()).endVertex()
            wr.pos((x + w).toDouble(), y.toDouble(), 0.0).tex(((u + texSpan) / 256f).toDouble(), (v / 256f).toDouble()).endVertex()
            wr.pos(x.toDouble(), y.toDouble(), 0.0).tex((u / 256f).toDouble(), (v / 256f).toDouble()).endVertex()
        }
        tess.draw()
    }

    private fun drawInventory(mc: Minecraft) {
        val player = mc.thePlayer ?: return
        val rects = InventoryDisplayHudModule.slotRects
        if (rects.size < 36 * 4) return
        val main = player.inventory.mainInventory
        // 光照状态只切换一次（原版是每格 enable/disable，36 格 = 72 次状态切换）
        RenderHelper.enableGUIStandardItemLighting()
        // 3D 方块/工具需要深度测试才能正确排序面；外层覆盖层是 disableDepth 状态，
        // 不打开的话模型会缺面/穿插，看起来像模型加载错误、材质异常。
        GlStateManager.enableDepth()
        try {
            for (display in 0 until 36) {
                val row = display / 9
                val col = display % 9
                val slot = if (row < 3) 9 + row * 9 + col else col
                val stack = main.getOrNull(slot) ?: continue
                if (stack.stackSize <= 0) continue
                val x = rects[display * 4]
                val y = rects[display * 4 + 1]
                val size = rects[display * 4 + 2]
                val scale = size / 16f
                // 清除 HUD 其它元素遗留的颜色，避免物品贴图被染色
                GlStateManager.color(1f, 1f, 1f, 1f)
                GlStateManager.pushMatrix()
                GlStateManager.translate(x.toDouble(), y.toDouble(), 0.0)
                GlStateManager.scale(scale.toDouble(), scale.toDouble(), 1.0)
                GlStateManager.enableRescaleNormal()
                mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
                // 数量与耐久条属于单独的叠加层，1.8.9 的 renderItemAndEffectIntoGUI 不含它们
                mc.renderItem.renderItemOverlayIntoGUI(mc.fontRendererObj, stack, 0, 0, null)
                GlStateManager.disableRescaleNormal()
                GlStateManager.popMatrix()
            }
        } finally {
            RenderHelper.disableStandardItemLighting()
            GlStateManager.disableDepth()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
    }

    /** 非空护甲件数（头盔→靴子），模块用它决定面板高度 */
    private fun countArmor(player: EntityPlayer?): Int {
        if (player == null) return 0
        val armor = player.inventory.armorInventory
        var n = 0
        for (i in 3 downTo 0) if (armor.getOrNull(i) != null) n++
        return n
    }

    /** 玩家自己的护甲 HUD：竖排时在图标右侧画剩余耐久数字。 */
    private fun drawArmorHud(mc: Minecraft) {
        val player = mc.thePlayer ?: return
        val rects = ArmorHudModule.itemRects
        val count = minOf(ArmorHudModule.publishedCount, rects.size / 4)
        if (count <= 0) return
        val armor = player.inventory.armorInventory
        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableDepth()
        try {
            var slot = 0
            for (i in 3 downTo 0) {
                val stack = armor.getOrNull(i) ?: continue
                if (slot >= count) break
                drawStack(mc, stack, rects, slot)
                if (ArmorHudModule.publishedDurability) drawDurability(mc, stack, rects, slot)
                slot++
            }
        } finally {
            RenderHelper.disableStandardItemLighting()
            GlStateManager.disableDepth()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
    }

    /** 目标信息卡（展览风格）下方的装备行：4 件护甲 + 手持物品。 */
    private fun drawTargetArmorRow(mc: Minecraft) {
        val entity = TargetInfoModule.targetEntity as? EntityPlayer ?: return
        val world = mc.theWorld ?: return
        if (entity.isDead || !world.playerEntities.contains(entity)) return
        val rects = TargetInfoModule.targetItemRects
        val count = minOf(TargetInfoModule.targetItemCount, rects.size / 4)
        if (count <= 0) return
        val armor = entity.inventory.armorInventory
        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableDepth()
        try {
            var slot = 0
            for (i in 3 downTo 0) {
                if (slot >= count) break
                val stack = armor.getOrNull(i) ?: continue
                drawStack(mc, stack, rects, slot)
                slot++
            }
            val held = entity.heldItem
            if (held != null && slot < count) drawStack(mc, held, rects, slot)
        } finally {
            RenderHelper.disableStandardItemLighting()
            GlStateManager.disableDepth()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
    }

    private fun drawStack(mc: Minecraft, stack: ItemStack, rects: IntArray, index: Int) {
        val x = rects[index * 4]
        val y = rects[index * 4 + 1]
        val size = rects[index * 4 + 2]
        if (size <= 0) return
        val scale = size / 16f
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.pushMatrix()
        GlStateManager.translate(x.toDouble(), y.toDouble(), 0.0)
        GlStateManager.scale(scale.toDouble(), scale.toDouble(), 1.0)
        GlStateManager.enableRescaleNormal()
        mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    /** 剩余耐久数字：>50% 白色，>25% 黄色，否则红色。 */
    private fun drawDurability(mc: Minecraft, stack: ItemStack, rects: IntArray, index: Int) {
        if (!stack.isItemStackDamageable || stack.maxDamage <= 0) return
        val remaining = stack.maxDamage - stack.itemDamage
        val x = rects[index * 4]
        val y = rects[index * 4 + 1]
        val size = rects[index * 4 + 2]
        val ratio = remaining.toFloat() / stack.maxDamage
        val color = when {
            ratio > 0.5f -> 0xFFFFFF
            ratio > 0.25f -> 0xFFFF55
            else -> 0xFF5555
        }
        val scale = size / 16f * 0.62f
        GlStateManager.pushMatrix()
        GlStateManager.translate(
            (x + size + 2).toDouble(),
            (y + (size - 7f * scale) * 0.5f).toDouble(),
            0.0,
        )
        GlStateManager.scale(scale.toDouble(), scale.toDouble(), 1.0)
        mc.fontRendererObj.drawStringWithShadow(remaining.toString(), 0f, 0f, color)
        GlStateManager.popMatrix()
    }
}
