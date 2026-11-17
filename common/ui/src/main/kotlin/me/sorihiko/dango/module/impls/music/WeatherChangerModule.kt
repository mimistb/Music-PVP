package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 天气切换：客户端强制指定天气，支持 晴 / 雨 / 雷暴 / 雪 四种模式无缝切换。
 *
 * 实现：legacy 的 WeatherRuntime 每 tick 写 WorldInfo（雨/雷标志与计时）并同步 ASM hook：
 *  - World#getRainStrength / World#getThunderStrength 的返回值按配置缩放
 *  - WorldInfo#isRaining / isThundering 返回配置状态
 *  - WorldChunkManager#getTemperatureAtHeight 在雪天返回低温，让降水以雪的形式出现
 * 雪天额外由 runtime 在玩家周围补雪花粒子（避免只改温度而没有可见效果）。
 * 关闭模块后 hook 立即恢复原版返回值，天气随服务器同步自然回退。
 */
@ModuleMeta(Category.MUSIC, "Weather")
class WeatherChangerModule : Module() {

    @ModuleValue("天气", "晴 / 雨 / 雷暴 / 雪")
    private val weather = IntValue(0, 3, 0)

    @ModuleValue("降水强度", "雨的视觉强度（0 ~ 1）")
    private val rainStrength = FloatValue(0f, 1f, 1f, 2)

    @ModuleValue("雷暴强度", "雷暴的视觉强度（0 ~ 1）")
    private val thunderStrength = FloatValue(0f, 1f, 1f, 2)

    override fun init() {
        instance = this
    }

    fun mode(): Int = weather.value

    fun rain(): Float = rainStrength.value

    fun thunder(): Float = thunderStrength.value

    companion object {
        const val CLEAR = 0
        const val RAIN = 1
        const val STORM = 2
        const val SNOW = 3

        val MODE_NAMES = arrayOf("晴天", "下雨", "雷暴", "下雪")

        @Volatile
        private var instance: WeatherChangerModule? = null

        fun of(): WeatherChangerModule? = instance
    }
}
