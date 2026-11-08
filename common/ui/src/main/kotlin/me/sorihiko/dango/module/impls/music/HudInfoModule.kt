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

    @ModuleValue("X 坐标", "玩家名 / 护甲的水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.40f, 3)

    @ModuleValue("Y 坐标", "玩家名 / 护甲的垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0f, 3)

    @ModuleValue("帧率 X 坐标", "帧率胶囊的水平位置（打开聊天栏后可单独拖动）")
    private val fpsX = FloatValue(0f, 1f, 0.50f, 3)

    @ModuleValue("帧率 Y 坐标", "帧率胶囊的垂直位置")
    private val fpsY = FloatValue(0f, 1f, 0f, 3)

    @ModuleValue("延迟 X 坐标", "延迟胶囊的水平位置（打开聊天栏后可单独拖动）")
    private val pingX = FloatValue(0f, 1f, 0.56f, 3)

    @ModuleValue("延迟 Y 坐标", "延迟胶囊的垂直位置")
    private val pingY = FloatValue(0f, 1f, 0f, 3)

    @ModuleValue("时间 X 坐标", "时间胶囊的水平位置（打开聊天栏后可单独拖动）")
    private val clockX = FloatValue(0f, 1f, 0.61f, 3)

    @ModuleValue("时间 Y 坐标", "时间胶囊的垂直位置")
    private val clockY = FloatValue(0f, 1f, 0f, 3)

    @ModuleValue("CPS X 坐标", "CPS 胶囊的水平位置（打开聊天栏后可单独拖动）")
    private val cpsX = FloatValue(0f, 1f, 0.66f, 3)

    @ModuleValue("CPS Y 坐标", "CPS 胶囊的垂直位置")
    private val cpsY = FloatValue(0f, 1f, 0f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    /** 当前正在拖动的胶囊类型；-1 表示没有拖动 */
    private var dragKind = -1
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

    /** 每一类信息各占一个独立胶囊：记录它在 segText 里的区间与类型 */
    private val blockStart = IntArray(8)
    private val blockEnd = IntArray(8)
    private val blockKind = IntArray(8)
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

    /** 独立胶囊的高度 / 左右内边距（逻辑像素） */
    private val chipH = 26f
    private val chipPad = 12f

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
        // 玩家名与护甲共用一个胶囊：它们始终相邻，拆分后位置项会翻倍且无实际意义
        addBlock(KIND_IDENTITY) {
            if (showName.get()) {
                val name = platform.playerName
                if (name != lastName) {
                    lastName = name
                    nameText = name
                }
                addSeg(nameText, bold = true)
            }
            val armor = armorSegments
            val warn = armorWarn
            if (armor != null) {
                for (i in armor.indices) {
                    addSeg(armor[i], warn != null && i < warn.size && warn[i])
                }
            }
        }
        addBlock(KIND_FPS) {
            if (showFps.get()) {
                val fps = platform.fps
                if (fps != lastFps) {
                    lastFps = fps
                    fpsText = "$fps fps"
                }
                addSeg(fpsText)
            }
        }
        addBlock(KIND_PING) {
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
        addBlock(KIND_CLOCK) { if (showClock.get()) addSeg(clockNow()) }

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
            addBlock(KIND_CPS) { addSeg(cpsText) }
        } else {
            // 关闭时清掉缓存，下次开启立即重建
            lastCpsLeft = Int.MIN_VALUE
            lastCpsRight = Int.MIN_VALUE
        }
        return segCount
    }

    /** 一段独立的显示块：里面 0 条文字就不占位置 */
    private inline fun addBlock(kind: Int, build: () -> Unit) {
        val i = blockCount
        if (i >= blockStart.size) return
        val start = segCount
        build()
        if (segCount > start) {
            blockStart[i] = start
            blockEnd[i] = segCount
            blockKind[i] = kind
            blockCount++
        }
    }

    /** 胶囊类型对应的水平位置项 */
    private fun xValue(kind: Int): FloatValue = when (kind) {
        KIND_FPS -> fpsX
        KIND_PING -> pingX
        KIND_CLOCK -> clockX
        KIND_CPS -> cpsX
        else -> posX
    }

    /** 胶囊类型对应的垂直位置项 */
    private fun yValue(kind: Int): FloatValue = when (kind) {
        KIND_FPS -> fpsY
        KIND_PING -> pingY
        KIND_CLOCK -> clockY
        KIND_CPS -> cpsY
        else -> posY
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
    fun render(e: HudRenderEvent) {
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
        val h = chipH

        // 每个胶囊按自己的位置项独立摆放：名字+护甲 / 帧率 / 延迟 / 时间 / CPS
        val widths = FloatArray(blockCount)
        val xs = FloatArray(blockCount)
        val ys = FloatArray(blockCount)
        for (b in 0 until blockCount) {
            widths[b] = chipWidth(b)
            val bw = widths[b]
            xs[b] = xValue(blockKind[b]).value * (lw - bw).coerceAtLeast(0f)
            ys[b] = yValue(blockKind[b]).value * (lh - h).coerceAtLeast(0f)
        }

        if (!platform.chatOpen) {
            dragKind = -1
        } else if (platform.isMouseDown(0)) {
            // 命中哪个胶囊就只拖哪个，其余胶囊位置完全不动
            if (dragKind < 0) {
                for (b in 0 until blockCount) {
                    if (mx >= xs[b] && mx < xs[b] + widths[b] && my >= ys[b] && my < ys[b] + h) {
                        dragKind = blockKind[b]
                        dragDX = mx - xs[b]
                        dragDY = my - ys[b]
                        break
                    }
                }
            }
            if (dragKind >= 0) {
                for (b in 0 until blockCount) {
                    if (blockKind[b] != dragKind) continue
                    val bw = widths[b]
                    val nx = (mx - dragDX).coerceIn(0f, (lw - bw).coerceAtLeast(0f))
                    val ny = (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f))
                    xValue(dragKind).setValue(if (lw > bw) nx / (lw - bw) else 0f)
                    yValue(dragKind).setValue(if (lh > h) ny / (lh - h) else 0f)
                    xs[b] = nx
                    ys[b] = ny
                    break
                }
            }
        } else {
            dragKind = -1
        }

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            val glassOn = glass.get()
            mt.clear()
            // 独立位置后不能再裁成整行矩形，否则拖到别处的胶囊会被裁掉
            mt.clip = LayoutBounds(0f, 0f, lw, lh)
            for (b in 0 until blockCount) {
                val chip = LayoutBounds(xs[b], ys[b], widths[b], h)
                if (glassOn) {
                    ctx.drawGlass(chip, chipGlass)
                } else {
                    ctx.drawShape(chip, ShapeStyle(darkChip, h / 2f, 1f, chipBorder))
                }
                val ty = chip.y + h / 2f - 12f * 0.39f - 0.5f
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
            }
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        /** 胶囊类型：玩家名+护甲 / 帧率 / 延迟 / 时间 / CPS */
        private const val KIND_IDENTITY = 0
        private const val KIND_FPS = 1
        private const val KIND_PING = 2
        private const val KIND_CLOCK = 3
        private const val KIND_CPS = 4

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
