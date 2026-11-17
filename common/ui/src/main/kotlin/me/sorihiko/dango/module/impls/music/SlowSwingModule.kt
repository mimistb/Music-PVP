package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.min

/**
 * 慢挥：把武器挥砍动画的持续时间拉长（原版默认 6 tick，这里可设 2 ~ 20）。
 *
 * 实现：ASM 在 EntityLivingBase#getArmSwingAnimationEnd 的每个 return 前注入
 * SlowSwingHooks.modify(original)；legacy 的 SlowSwingRuntime 每 tick 把配置写入 hook。
 * 平滑过渡：开启/修改数值后，hook 返回值会在约 0.25 秒内从原值滑到目标值，而不是瞬间跳变。
 * 状态提示：开启后在左下角显示一枚小提示，显示当前实际时长。
 */
@ModuleMeta(Category.MUSIC, "Slow Swing")
class SlowSwingModule : Module() {

    @ModuleValue("挥砍时长", "挥砍动画持续的 tick 数（原版 6）")
    private val delay = IntValue(2, 20, 14)

    @ModuleValue("平滑过渡", "开启后从原时长平滑过渡到目标时长")
    private val smooth = BooleanValue(true)

    @ModuleValue("状态提示", "左下角显示当前慢挥时长")
    private val hint = BooleanValue(true)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    override fun init() {
        instance = this
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled || !hint.get()) return
        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val w = 132f
        val h = 24f
        val x = 12f
        val y = lh - h - 12f

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(false, -1, -1)
        try {
            ctx.pushScale(k, k, 1f)
            val panel = LayoutBounds(x, y, w, h)
            ctx.drawShape(panel, ShapeStyle(Color(0.02f, 0.02f, 0.03f, 0.78f), 8f, 1f, Theme.HAIRLINE))
            ctx.drawShape(LayoutBounds(panel.x + 8f, panel.y + 6f, 3f, 12f), ShapeStyle(Theme.ACCENT, 1.5f))
            mt.clear()
            mt.clip = panel
            mt.text(ctx, "慢挥", panel.x + 17f, panel.y + 4.5f, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val value = "x" + SlowSwingModule.of()?.effectiveTicks()
            val vw = mt.width(value, 9f, Theme.FONT_REGULAR)
            mt.text(ctx, value, panel.right - 8f - vw, panel.y + 5.5f, 9f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    fun configuredDelay(): Int = delay.value

    fun smoothEnabled(): Boolean = smooth.get()

    fun effectiveTicks(): Int = SlowSwingRuntimeBridge.ticks(delay.value)

    companion object {
        @Volatile
        private var instance: SlowSwingModule? = null

        fun of(): SlowSwingModule? = instance
    }
}

/** 只读桥：legacy 写入当前实际时长，模块用于状态提示（避免 common 直接依赖 legacy） */
object SlowSwingRuntimeBridge {
    @Volatile
    @JvmStatic
    var currentTicks = 6

    @JvmStatic
    fun ticks(configured: Int): Int = currentTicks.coerceAtLeast(0).takeIf { it > 0 } ?: configured
}
