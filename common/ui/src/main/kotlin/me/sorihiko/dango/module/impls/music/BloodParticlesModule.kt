package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * Blood Particles：攻击生物时在准星目标身上喷红石块碎裂粒子，可选 dig.stone 音效。
 *
 * 无 HUD。准星目标记录、粒子与音效播放都在 legacy 的 BloodParticlesRuntime：
 * 位置沿用原版行为取"准星目标"而不是事件实体。
 */
@ModuleMeta(Category.MUSIC, "Blood Particles")
class BloodParticlesModule : Module() {

    @ModuleValue("Amount", "每次攻击生成的粒子数量")
    private val amount = IntValue(1, 10, 2)

    @ModuleValue("Sound", "攻击时播放 dig.stone 音效")
    private val sound = BooleanValue(true)

    override fun init() {
        instance = this
    }

    fun amount(): Int = amount.value.coerceIn(1, 10)

    fun soundEnabled(): Boolean = sound.get()

    companion object {
        @Volatile
        private var instance: BloodParticlesModule? = null

        fun of(): BloodParticlesModule? = instance
    }
}
