package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.platform.Keys

/**
 * Gale AntiAim：静默旋转（Rotations）+ 本地大陀螺（LocalSpin）的移植版。
 *
 * 模块总开关（[Module] 的 enabled）是全局开关：关掉后引擎、移动修正、本地旋转全部立即停止，
 * 不需要重启。设置项标签与默认值保持 Gale 原版。
 *
 * [silentKey] / [spinKey] 是本模块独立的按键绑定：按住前者触发静默伪装朝向，后者切换本地旋转。
 */
@ModuleMeta(Category.MUSIC, "GaleAntiAim")
class GaleAntiAimModule : Module() {

    @ModuleValue("静默转头键", "按住时服务端朝向伪装为背后，相机保持不动")
    val silentKey = KeyBindValue(intArrayOf(Keys.R))

    @ModuleValue("本地旋转键", "按下切换 LocalSpin（F5 看自己转圈）")
    val spinKey = KeyBindValue(intArrayOf(Keys.L))

    // ---------------------------------------------------------------- Rotations

    @ModuleValue("GCD fix", "转动量吸附鼠标灵敏度整数倍，像真鼠标")
    val gcdFix = BooleanValue(true)

    @ModuleValue("Release speed", "没有请求时回正到相机的速度 5 ~ 180 °/tick")
    val releaseSpeed = FloatValue(5f, 180f, 45f, 0)

    @ModuleValue("Move fix", "静默转头时 WASD 的修正方式：Legit / Exact / None")
    val moveFix = IntValue(0, 2, 0)

    // ---------------------------------------------------------------- LocalSpin

    @ModuleValue("LocalSpin", "本地大陀螺：F5 看自己转圈，别人看到的朝向不变")
    val localSpin = BooleanValue(false)

    @ModuleValue("Speed", "本地旋转速度 90 ~ 3600 deg/s")
    val spinSpeed = FloatValue(90f, 3600f, 1440f, 0)

    override fun init() {
        instance = this
    }

    fun toggleSpin() {
        localSpin.toggle()
    }

    companion object {
        /** Move fix 的下拉显示顺序：Legit / Exact / None */
        val MOVE_FIX_NAMES = arrayOf("Legit", "Exact", "None")

        @Volatile
        private var instance: GaleAntiAimModule? = null

        fun of(): GaleAntiAimModule? = instance
    }
}
