package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.PotionRow
import me.sorihiko.dango.module.impls.music.PotionStatusHudModule
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.I18n
import net.minecraft.potion.Potion

/**
 * 药水状态：每 tick 读取本地玩家的 activePotionEffects，整理成显示行交给 HUD 模块。
 * 原版已经处理效果叠加（同一 effect 只保留最高等级并刷新时长），这里直接按集合顺序展示；
 * 效果到时消失后集合里不再有该条目，HUD 下一帧就移除对应行。
 */
internal object PotionStatusRuntime {

    private val ROMAN = arrayOf("", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X")

    fun tick() {
        val module = PotionStatusHudModule.of() ?: return
        if (!module.enabled) {
            PotionStatusHudModule.publish(emptyArray())
            return
        }
        val player = Minecraft.getMinecraft().thePlayer
        if (player == null) {
            PotionStatusHudModule.publish(emptyArray())
            return
        }
        val effects = player.activePotionEffects
        if (effects.isEmpty()) {
            PotionStatusHudModule.publish(emptyArray())
            return
        }
        val rows = ArrayList<PotionRow>(effects.size)
        for (effect in effects) {
            val id = effect.potionID
            if (id < 0 || id >= Potion.potionTypes.size) continue
            val potion = Potion.potionTypes[id] ?: continue
            val name = I18n.format(potion.name) + roman(effect.amplifier)
            rows.add(PotionRow(name, Potion.getDurationString(effect), potion.statusIconIndex))
        }
        PotionStatusHudModule.publish(rows.toTypedArray())
    }

    private fun roman(amplifier: Int): String =
        if (amplifier in ROMAN.indices) ROMAN[amplifier] else " +$amplifier"
}
