package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.min

/**
 * 信息胶囊：玩家名 / 帧率 / 延迟 / 时间，尾部可选护甲与点击速度。
 * 从灵动岛里拆出来的独立模块，液态玻璃底，位置可以拖动 / 微调。
 *
 * <p>每一类信息各自一个小胶囊，横向排开，不再挤在同一条里；
 * 护甲与 CPS 的数据由 legacy 的 FpsPortRuntime 每 tick 写进 companion 的静态桥字段，
 * 本模块只负责排版与绘制（common 不能反向依赖 legacy）。
 */
@ModuleMeta(Category.MUSIC, "HudInfo")
class HudInfoModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑胶囊")
    private val glass = BooleanValue(true)

    @ModuleValue("玩家名", "显示当前玩家名")
    private val showName = BooleanValue(true)

    @ModuleValue("帧率", "显示帧率")
    private val showFps = BooleanValue(true)

    @ModuleValue("延迟", "显示到服务器的延迟（单人游戏不显示）")
    private val showPing = BooleanValue(true)

    @ModuleValue("时间", "显示系统时间")
    private val showClock = BooleanValue(true)

    @ModuleValue("缩放", "胶囊大小")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    @ModuleValue("X 坐标", "水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Y 坐标", "垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    private val clock = SimpleDateFormat("HH:mm")

    /** 同一分钟内不重复格式化 */
    private var clockMinute = Long.MIN_VALUE
    private var clockText = ""

    /**
     * 每帧的文字段：文字 / 告警色 / 加粗。
     * 容量 24：基础 4 段 + 护甲最多 4 段 + CPS 1 段，留足余量，避免扩容越界。
     */
    private val segText = arrayOfNulls<String>(24)
    private val segWarn = BooleanArray(24)
    private val segBold = BooleanArray(24)
    private var segCount = 0

    /** 每一类信息各占一个独立胶囊：记录它在 segText 里的区间 */
    private val blockStart = IntArray(8)
    private val blockEnd = IntArray(8)
    private var blockCount = 0

    /** fps / 延迟 / 玩家名 / CPS 的字符串缓存：数值没变就直接复用，避免每帧新建字符串 */
    private var lastFps = Int.MIN_VALUE
    private var fpsText = ""
    private var lastPing = Int.MIN_VALUE
    private var pingText = ""
    private var lastName: String? = null
    private var nameText = ""
    private var lastCpsLeft = Int.MIN_VALUE
    private var lastCpsRight = Int.MIN_VALUE
    private var cpsText = ""

    /** 低耐久告警色 */
    private val warnColor = Color.fromHex("#FF453A")

    /** 独立胶囊的高度 / 左右内边距 / 胶囊间距（逻辑像素） */
    private val chipH = 26f
    private val chipPad = 12f
    private val chipGap = 6f

    /** 每块自己的液态玻璃；半径按胶囊高度取半，避免小面板被大斜面糊住 */
    private val chipGlass = Theme.GLASS_WINDOW.copy(
        radius = chipH / 2f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)),
    )

    /** 关闭液态玻璃时的纯黑胶囊与描边 */
    private val darkChip = Color(0.02f, 0.02f, 0.03f, 0.9f)
    private val chipBorder = Color(1f, 1f, 1f, 0.07f)

    override fun init() {
        instance = this
    }

    private fun clockNow(): String {
        val minute = System.currentTimeMillis() / 60000L
        if (minute != clockMinute) {
            clockMinute = minute
            clockText = clock.format(Date())
        }
        return clockText
    }

    private fun addSeg(text: String?, warn: Boolean = false, bold: Boolean = false) {
        if (text == null || text.isEmpty() || segCount >= segText.size) return
        segText[segCount] = text
        segWarn[segCount] = warn
        segBold[segCount] = bold
        segCount++
    }

    /** 收集这一帧要显示的文字；返回条数 */
    private fun parts(): Int {
        segCount = 0
        blockCount = 0
        val platform = DangoClient.platform
        addBlock {
            if (showName.get()) {
                val name = platform.playerName
                if (name != lastName) {
                    lastName = name
                    nameText = name
                }
                addSeg(nameText, bold = true)
            }
        }
        addBlock {
            if (showFps.get()) {
                val fps = platform.fps
                if (fps != lastFps) {
                    lastFps = fps
                    fpsText = "$fps fps"
                }
                addSeg(fpsText)
            }
        }
        addBlock {
            if (showPing.get()) {
                val ping = platform.pingMs
                if (ping > 0) {
                    if (ping != lastPing) {
                        lastPing = ping
                        pingText = "$ping ms"
                    }
                    addSeg(pingText)
                }
            }
        }
        addBlock { if (showClock.get()) addSeg(clockNow()) }

        // 护甲段：legacy 已经按设置拼好文字，这里只按低耐久标记着色
        val armor = armorSegments
        val warn = armorWarn
        addBlock {
            if (armor != null) {
                for (i in armor.indices) {
                    addSeg(armor[i], warn != null && i < warn.size && warn[i])
                }
            }
        }

        // CPS：-1 表示该侧不显示；两侧都显示时拼成 "左/右 cps"
        val left = cpsLeft
        val right = cpsRight
        if (left >= 0 || right >= 0) {
            if (left != lastCpsLeft || right != lastCpsRight) {
                lastCpsLeft = left
                lastCpsRight = right
                cpsText = when {
                    left >= 0 && right >= 0 -> "$left/$right cps"
                    else -> "${if (left >= 0) left else right} cps"
                }
            }
            addBlock { addSeg(cpsText) }
        } else {
            // 关闭时清掉缓存，下次开启立即重建
            lastCpsLeft = Int.MIN_VALUE
            lastCpsRight = Int.MIN_VALUE
        }
        return segCount
    }

    /** 一段独立的显示块：里面 0 条文字就不占位置 */
    private inline fun addBlock(build: () -> Unit) {
        val i = blockCount
        if (i >= blockStart.size) return
        val start = segCount
        build()
        if (segCount > start) {
            blockStart[i] = start
            blockEnd[i] = segCount
            blockCount++
        }
    }

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private val sep = "  ·  "

    /** 一个独立胶囊的宽度（左右内边距 + 内部文字） */
    private fun chipWidth(b: Int): Float {
        var w = chipPad * 2f
        for (i in blockStart[b] until blockEnd[b]) {
            val s = segText[i] ?: continue
            w += width(s, 12f, if (segBold[i]) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
            if (i < blockEnd[b] - 1) w += width(sep, 12f)
        }
        return w
    }

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        val n = parts()
        if (n <= 0 || blockCount <= 0) return
        val platform = DangoClient.platform

        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val k = uiScale * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        // 每一类信息一个小胶囊，横向排开；整体仍由 posX / posY 决定位置
        val widths = FloatArray(blockCount)
        var w = 0f
        for (b in 0 until blockCount) {
            widths[b] = chipWidth(b)
            w += widths[b]
            if (b > 0) w += chipGap
        }
        val h = chipH
        var x = posX.value * (lw - w)
        var y = posY.value * (lh - h)

        if (!platform.chatOpen) {
            dragging = false
        } else if (platform.isMouseDown(0)) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                x = (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f))
                y = (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f))
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val total = LayoutBounds(x, y, w, h)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            val glassOn = glass.get()
            val ty = y + h / 2f - 12f * 0.39f - 0.5f
            mt.clear()
            mt.clip = total
            var bx = x
            for (b in 0 until blockCount) {
                val bw = widths[b]
                val chip = LayoutBounds(bx, y, bw, h)
                if (glassOn) {
                    ctx.drawGlass(chip, chipGlass)
                } else {
                    ctx.drawShape(chip, ShapeStyle(darkChip, h / 2f, 1f, chipBorder))
                }
                var tx = chip.x + chipPad
                for (i in blockStart[b] until blockEnd[b]) {
                    if (i > blockStart[b]) {
                        text(ctx, sep, tx, ty, 12f, Theme.TEXT_TERTIARY)
                        tx += width(sep, 12f)
                    }
                    val s = segText[i] ?: continue
                    val color = when {
                        segWarn[i] -> warnColor
                        segBold[i] -> Theme.TEXT_PRIMARY
                        else -> Theme.TEXT_SECONDARY
                    }
                    val font = if (segBold[i]) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
                    text(ctx, s, tx, ty, 12f, color, font)
                    tx += width(s, 12f, font)
                }
                bx += bw + chipGap
            }
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        @Volatile
        private var instance: HudInfoModule? = null

        fun of(): HudInfoModule? = instance

        /** legacy 每 tick 写入的护甲文字快照；null = 不显示 */
        @JvmStatic
        @Volatile
        var armorSegments: Array<String>? = null

        /** 与 [armorSegments] 等长；true 表示该段低于耐久阈值 */
        @JvmStatic
        @Volatile
        var armorWarn: BooleanArray? = null

        /** 左 / 右键 CPS；-1 表示该侧不显示 */
        @JvmStatic
        @Volatile
        var cpsLeft: Int = -1

        @JvmStatic
        @Volatile
        var cpsRight: Int = -1
    }
}
