package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 投掷物轨迹：给飞行的投掷物补粒子尾迹，并对当前手持的投掷物实时预览抛物线。
 *
 * 逻辑全在 legacy 的 ProjectileTrailRuntime：
 *  - 尾迹：每 tick 在箭 / 雪球 / 鸡蛋 / 末影珍珠 / 喷溅药水 / 钓钩的位置补粒子，类型可换
 *  - 预览：按住弓或手持投掷物时，按物品初速 + 重力 + 阻力逐 tick 模拟 80 步，画成 3D 折线；
 *    力度倍率与角度偏移是滑块，改动下一帧就反映到预览（实时更新）
 */
@ModuleMeta(Category.MUSIC, "Projectile Trail")
class ProjectileTrailModule : Module() {

    @ModuleValue("粒子尾迹", "投掷物飞行时补粒子")
    private val trail = BooleanValue(true)

    @ModuleValue("尾迹类型", "心形 / 火焰 / 传送门 / 烟雾 / 附魔 / 暴击 / 雪花 / 红石 / 音符 / 瞬爆")
    private val trailType = IntValue(0, 9, 2)

    @ModuleValue("抛物线预测", "手持投掷物时预览落点轨迹")
    private val predict = BooleanValue(true)

    @ModuleValue("力度倍率", "预览初速倍率（0.5 ~ 2.0）")
    private val power = FloatValue(0.5f, 2f, 1f, 2)

    @ModuleValue("角度偏移", "预览仰角偏移（度，-30 ~ 30）")
    private val angle = FloatValue(-30f, 30f, 0f, 0)

    override fun init() {
        instance = this
    }

    fun trailEnabled(): Boolean = trail.get()

    fun type(): Int = trailType.value

    fun predictEnabled(): Boolean = predict.get()

    fun power(): Float = power.value

    fun angleOffset(): Float = angle.value

    companion object {
        @Volatile
        private var instance: ProjectileTrailModule? = null

        fun of(): ProjectileTrailModule? = instance

        /** 尾迹类型名称，顺序与 glide 的 ProjectileTrailType 一致 */
        val TYPE_NAMES = arrayOf(
            "心形", "火焰", "传送门", "烟雾", "附魔", "暴击", "雪花", "红石", "音符", "瞬爆",
        )
    }
}
