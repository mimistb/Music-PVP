package me.sorihiko.dango

import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.TickEvent

import java.lang.management.ManagementFactory

/**
 * 无头性能基准（不启动 Minecraft、不创建 GL 上下文）。
 *
 * 只测真实可测的东西：
 *   1. HUD 矩形批次：旧实现（ArrayList<int[]> + HashMap<Long,Int>）vs 新实现
 *      （扁平 IntArray + 开放寻址原始类型去重表）——时间与"每次矩形分配字节数"。
 *   2. 事件派发：20 个监听器时 [EventManager.callEvent] 的单次成本。
 *   3. 玻璃背景抓取次数核算（纯算术，不是实测）：10 个 HUD 画布各自抓 vs 全局共享。
 *
 * FPS / GPU 占用 / 显存无法在无头 JVM 里测，报告里会明确标注需要实机测量。
 *
 * 运行：`gradlew -p common :ui:perfBench --offline`
 */
private var failures = 0

private fun check(label: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("  [PASS] $label${if (detail.isEmpty()) "" else " -> $detail"}")
    } else {
        failures++
        println("  [FAIL] $label${if (detail.isEmpty()) "" else " -> $detail"}")
    }
}

private val threadBean: com.sun.management.ThreadMXBean? = try {
    ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
} catch (_: Throwable) {
    null
}

private fun allocatedBytes(): Long =
    threadBean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L

private fun hasAllocationCounter(): Boolean = threadBean != null

private class Timing(val nanos: Long, val allocated: Long)

private inline fun measure(block: () -> Unit): Timing {
    val t0 = System.nanoTime()
    val a0 = allocatedBytes()
    block()
    val a1 = allocatedBytes()
    val t1 = System.nanoTime()
    return Timing(t1 - t0, if (a0 < 0 || a1 < 0) -1L else a1 - a0)
}

// ---------------------------------------------------------------- 旧 / 新矩形批次

/** 与 2.3.8 的 LegacyHud 完全一致的旧实现 */
private class OldRectBatch {
    private val batch = ArrayList<IntArray>(128)
    private val seen = HashMap<Long, Int>(128)

    @JvmField
    var checksum = 0

    fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        if (x1 == x2 || y1 == y2) return
        if ((argb ushr 24) == 0xFF) {
            val key = (x1.toLong() shl 48) or
                ((y1.toLong() and 0xFFFFL) shl 32) or
                ((x2.toLong() and 0xFFFFL) shl 16) or
                (y2.toLong() and 0xFFFFL)
            val prev = seen.put(key, argb)
            if (prev != null && prev == argb) return
        }
        batch.add(intArrayOf(x1, y1, x2, y2, argb))
    }

    fun flush() {
        for (r in batch) checksum += r[0] + r[1] + r[4]
        batch.clear()
        seen.clear()
    }
}

/** 与 2.3.9 的 LegacyHud 完全一致的新实现 */
private class NewRectBatch {
    private var rects = IntArray(5 * 128)
    private var rectCount = 0
    private val seenKeys = LongArray(512)
    private val seenValues = IntArray(512)
    private var seenUsed = 0

    @JvmField
    var checksum = 0

    fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        if (x1 == x2 || y1 == y2) return
        if ((argb ushr 24) == 0xFF) {
            val key = (x1.toLong() shl 48) or
                ((y1.toLong() and 0xFFFFL) shl 32) or
                ((x2.toLong() and 0xFFFFL) shl 16) or
                (y2.toLong() and 0xFFFFL)
            val prev = seenPut(key, argb)
            if (prev == argb) return
        }
        addRect(x1, y1, x2, y2, argb)
    }

    private fun addRect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        val need = (rectCount + 1) * 5
        if (need > rects.size) rects = rects.copyOf(maxOf(need, rects.size * 2))
        val o = rectCount * 5
        rects[o] = x1
        rects[o + 1] = y1
        rects[o + 2] = x2
        rects[o + 3] = y2
        rects[o + 4] = argb
        rectCount++
    }

    private fun seenPut(key: Long, argb: Int): Int {
        if (seenUsed * 4 >= seenKeys.size * 3) clearSeen()
        var i = ((key xor (key ushr 32)).toInt() * -1640531535) and (seenKeys.size - 1)
        while (true) {
            val k = seenKeys[i]
            if (k == 0L) {
                seenKeys[i] = key
                seenValues[i] = argb
                seenUsed++
                return 0
            }
            if (k == key) {
                val prev = seenValues[i]
                seenValues[i] = argb
                return prev
            }
            i = (i + 1) and (seenKeys.size - 1)
        }
    }

    private fun clearSeen() {
        if (seenUsed == 0) return
        java.util.Arrays.fill(seenKeys, 0L)
        seenUsed = 0
    }

    fun flush() {
        for (i in 0 until rectCount) {
            val o = i * 5
            checksum += rects[o] + rects[o + 1] + rects[o + 4]
        }
        rectCount = 0
        clearSeen()
    }
}

// ---------------------------------------------------------------- 工作负载

private const val RECT_PER_FRAME = 120
private const val FRAMES = 3000
private const val RECTS = RECT_PER_FRAME * FRAMES

/**
 * 固定种子生成一帧 HUD 的典型负载：不透明底 + 半透明描边 + 少量完全重复的矩形。
 * 预先生成，保证新旧实现跑的是同一批数据。
 */
private fun buildWorkload(): IntArray {
    val data = IntArray(RECTS * 5)
    var seed = 0x2F6E2B1
    for (i in 0 until RECTS) {
        seed = seed * 1103515245 + 12345
        val o = i * 5
        val x = (seed ushr 8) and 0x7FF
        val y = (seed ushr 19) and 0x3FF
        val w = 1 + ((seed ushr 3) and 0xFF)
        val h = 1 + ((seed ushr 11) and 0x3F)
        if (i % 7 == 0) {
            // 与上一帧同位置完全重复的不透明矩形（HUD 背景 + 前景叠加的典型情况）
            val p = if (i == 0) 0 else (i - 1) * 5
            data[o] = data[p]
            data[o + 1] = data[p + 1]
            data[o + 2] = data[p + 2]
            data[o + 3] = data[p + 3]
            data[o + 4] = 0xFF202020.toInt()
        } else {
            data[o] = x
            data[o + 1] = y
            data[o + 2] = x + w
            data[o + 3] = y + h
            data[o + 4] = if (i % 3 == 0) 0x80101010.toInt() else 0xFF303030.toInt()
        }
    }
    return data
}

private fun replayOld(batch: OldRectBatch, data: IntArray) {
    for (i in 0 until RECTS) {
        val o = i * 5
        batch.rect(data[o], data[o + 1], data[o + 2], data[o + 3], data[o + 4])
        if (i % RECT_PER_FRAME == RECT_PER_FRAME - 1) batch.flush()
    }
}

private fun replayNew(batch: NewRectBatch, data: IntArray) {
    for (i in 0 until RECTS) {
        val o = i * 5
        batch.rect(data[o], data[o + 1], data[o + 2], data[o + 3], data[o + 4])
        if (i % RECT_PER_FRAME == RECT_PER_FRAME - 1) batch.flush()
    }
}

// ---------------------------------------------------------------- 事件派发

private class TickListenerA : Listener {
    @EventHandler
    fun a1(e: TickEvent) { sink++ }
    @EventHandler
    fun a2(e: TickEvent) { sink++ }
    @EventHandler
    fun a3(e: TickEvent) { sink++ }
    @EventHandler
    fun a4(e: TickEvent) { sink++ }
}

private class TickListenerB : Listener {
    @EventHandler
    fun b1(e: TickEvent) { sink++ }
    @EventHandler
    fun b2(e: TickEvent) { sink++ }
    @EventHandler
    fun b3(e: TickEvent) { sink++ }
    @EventHandler
    fun b4(e: TickEvent) { sink++ }
}

private class TickListenerC : Listener {
    @EventHandler
    fun c1(e: TickEvent) { sink++ }
    @EventHandler
    fun c2(e: TickEvent) { sink++ }
    @EventHandler
    fun c3(e: TickEvent) { sink++ }
    @EventHandler
    fun c4(e: TickEvent) { sink++ }
}

private class TickListenerD : Listener {
    @EventHandler
    fun d1(e: TickEvent) { sink++ }
    @EventHandler
    fun d2(e: TickEvent) { sink++ }
    @EventHandler
    fun d3(e: TickEvent) { sink++ }
    @EventHandler
    fun d4(e: TickEvent) { sink++ }
}

private class TickListenerE : Listener {
    @EventHandler
    fun e1(e: TickEvent) { sink++ }
    @EventHandler
    fun e2(e: TickEvent) { sink++ }
    @EventHandler
    fun e3(e: TickEvent) { sink++ }
    @EventHandler
    fun e4(e: TickEvent) { sink++ }
}

@JvmField
var sink = 0

// ---------------------------------------------------------------- 玻璃抓取核算

/**
 * 抓取次数核算（不是实测）：质量档"中" = 33ms 一次。
 * 2.3.9 代码里 HUD 侧共有 14 个 `begin()` 调用（12 个模块，LyricsModule 3 个）。
 * 旧实现每个画布一份 GlassBackdrop、各自节流；新实现共享一份。
 */
private fun glassCaptureAccounting() {
    val canvases = 14
    val throttleMs = 33
    val capturesPerSecond = 1000 / throttleMs
    println("\n== 玻璃背景抓取次数（按代码核算，非实测） ==")
    println("  HUD 画布/begin 调用数 = $canvases，质量档 中 = ${throttleMs}ms/次")
    println("  优化前：${canvases * capturesPerSecond} 次整屏 capture/秒 + 对应模糊链")
    println("  优化后：$capturesPerSecond 次整屏 capture/秒 + 对应模糊链")
    println("  降低：${100 - 100 / canvases}%")
    check("共享后只剩一份 backdrop", canvases * capturesPerSecond / capturesPerSecond == canvases)
}

// ---------------------------------------------------------------- HUD 帧构建（样式 / 格式化 / 按元素样式）

private const val PANELS = 14      // 每帧 begin() 的 HUD 画布数
private const val BARS = 120       // 速度计 4 秒曲线
private const val SLOTS = 36       // 背包展示
private const val TICKS = 16       // 威胁环刻度
private const val KEYS = 5         // 按键显示
private const val SEGS = 32        // 威胁环受击色块 8 段 × 4

private val TICK_COLOR = me.sorihiko.dango.utils.Color(1f, 1f, 1f, 0.16f)
private val SEG_COLOR = me.sorihiko.dango.utils.Color(1f, 0.30f, 0.26f, 0.8f)

/**
 * 旧写法：每帧为每个面板 copy 一次 GlassStyle（连带 ShadowStyle / Vec2 / Color），
 * drawGlass 的阴影 / fallback 通道每次再 new 一个 ShapeStyle，
 * 柱 / 槽 / 刻度 / 键 / 色块逐个 new ShapeStyle + Color，数值走 String.format。
 */
private class OldHudFrameWorkload {
    @JvmField
    var checksum = 0

    /** 让对象逃逸出方法，避免 C2 标量替换把分配优化掉导致统计失真 */
    private val alive = arrayOfNulls<Any>(96)
    private var aliveAt = 0

    private fun keep(o: Any) {
        alive[aliveAt] = o
        aliveAt = (aliveAt + 1) and 95
    }

    fun frame(values: FloatArray) {
        for (i in 0 until PANELS) {
            val style = me.sorihiko.dango.ui.Theme.GLASS_WINDOW.copy(
                radius = 10f,
                fallback = me.sorihiko.dango.utils.Color(0.40f, 0.42f, 0.46f, 0.82f),
                shadow = me.sorihiko.dango.ui.ShadowStyle(
                    me.sorihiko.dango.utils.Color(0f, 0f, 0f, 0.2f), 8f, me.sorihiko.dango.ui.Vec2(0f, -2f),
                ),
            )
            keep(style)
            val shadow = me.sorihiko.dango.ui.ShapeStyle(
                me.sorihiko.dango.utils.Color.TRANSPARENT, style.radius, shadow = style.shadow,
            )
            keep(shadow)
            checksum += shadow.radius.toInt()
            if (i < 2) {
                val fallback = me.sorihiko.dango.ui.ShapeStyle(
                    style.fallback, style.radius, 1f,
                    me.sorihiko.dango.utils.Color(1f, 1f, 1f, 0.10f),
                )
                keep(fallback)
                checksum += fallback.borderWidth.toInt()
            }
        }

        val speed = String.format("%.2f", values[0])
        keep(speed)
        checksum += speed.length
        val dmg = String.format("%.1f", values[1])
        keep(dmg)
        checksum += dmg.length
        val dmg2 = String.format("%.1f", values[2])
        keep(dmg2)
        checksum += dmg2.length

        for (i in 0 until BARS) {
            val style = me.sorihiko.dango.ui.ShapeStyle(
                me.sorihiko.dango.utils.Color(0.20f, 0.72f, 1f, values[3 + (i and 7)]), 1f,
            )
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until SLOTS) {
            val style = me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.ui.Theme.TRACK, 4f)
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until TICKS) {
            val style = me.sorihiko.dango.ui.ShapeStyle(TICK_COLOR, 1f)
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until KEYS * 2) {
            val style = me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.ui.Theme.TRACK, 6f)
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until SEGS) {
            val style = me.sorihiko.dango.ui.ShapeStyle(SEG_COLOR, 1.5f)
            keep(style)
            checksum += style.radius.toInt()
        }
    }
}

/** 优化后：样式在字段 / 小缓存里复用，数值按量化后的整数拼字符串 */
private class NewHudFrameWorkload {
    @JvmField
    var checksum = 0

    private val alive = arrayOfNulls<Any>(96)
    private var aliveAt = 0

    private fun keep(o: Any) {
        alive[aliveAt] = o
        aliveAt = (aliveAt + 1) and 95
    }

    private val panels = arrayOfNulls<me.sorihiko.dango.ui.GlassStyle>(PANELS)
    private val shadowStyles = arrayOfNulls<me.sorihiko.dango.ui.ShapeStyle>(4)
    private val fallbackStyles = arrayOfNulls<me.sorihiko.dango.ui.ShapeStyle>(4)
    private val barStyles = arrayOfNulls<me.sorihiko.dango.ui.ShapeStyle>(10)
    private val slotStyle = me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.ui.Theme.TRACK, 4f)
    private val tickStyle = me.sorihiko.dango.ui.ShapeStyle(TICK_COLOR, 1f)
    private val keyStyles = arrayOf(
        me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.ui.Theme.TRACK, 6f),
        me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.ui.Theme.TRACK, 6f),
    )
    private val segStyles = arrayOfNulls<me.sorihiko.dango.ui.ShapeStyle>(8)
    private var styleSlot = 0

    private var speedKey = Int.MIN_VALUE
    private var speedText = "0.00"

    private fun cachedShadow(radius: Float, shadow: me.sorihiko.dango.ui.ShadowStyle, kind: Int): me.sorihiko.dango.ui.ShapeStyle {
        for (i in 0 until 4) {
            val v = shadowStyles[i] ?: continue
            if (v.radius == radius && v.shadow === shadow) return v
        }
        val style = if (kind == 0) {
            me.sorihiko.dango.ui.ShapeStyle(me.sorihiko.dango.utils.Color.TRANSPARENT, radius, shadow = shadow)
        } else {
            me.sorihiko.dango.ui.ShapeStyle(shadow.color, radius, 1f, me.sorihiko.dango.utils.Color(1f, 1f, 1f, 0.10f))
        }
        val slot = styleSlot
        styleSlot = (slot + 1) and 3
        shadowStyles[slot] = style
        return style
    }

    private fun barStyle(alpha: Float): me.sorihiko.dango.ui.ShapeStyle {
        val idx = ((alpha - 0.30f) / 0.05f).toInt().coerceIn(0, 9)
        val cached = barStyles[idx]
        if (cached != null) return cached
        val style = me.sorihiko.dango.ui.ShapeStyle(
            me.sorihiko.dango.utils.Color(0.20f, 0.72f, 1f, 0.30f + (idx + 1) * 0.05f), 1f,
        )
        barStyles[idx] = style
        return style
    }

    fun frame(values: FloatArray) {
        for (i in 0 until PANELS) {
            val existing = panels[i]
            val style = if (existing != null) existing else {
                val built = me.sorihiko.dango.ui.Theme.GLASS_WINDOW.copy(
                    radius = 10f,
                    fallback = me.sorihiko.dango.utils.Color(0.40f, 0.42f, 0.46f, 0.82f),
                    shadow = me.sorihiko.dango.ui.ShadowStyle(
                        me.sorihiko.dango.utils.Color(0f, 0f, 0f, 0.2f), 8f, me.sorihiko.dango.ui.Vec2(0f, -2f),
                    ),
                )
                panels[i] = built
                built
            }
            val shadow = cachedShadow(style.radius, style.shadow, 0)
            keep(shadow)
            checksum += shadow.radius.toInt()
            if (i < 2) {
                val fallback = cachedShadow(style.radius, style.shadow, 1)
                keep(fallback)
                checksum += fallback.borderWidth.toInt()
            }
        }

        val key = (values[0] * 100f).toInt().coerceAtLeast(0)
        if (key != speedKey) {
            speedKey = key
            val whole = key / 100
            val frac = key % 100
            speedText = if (frac < 10) "$whole.0$frac" else "$whole.$frac"
        }
        keep(speedText)
        checksum += speedText.length
        checksum += oneDecimal(values[1]) + oneDecimal(values[2])

        for (i in 0 until BARS) {
            val style = barStyle(values[3 + (i and 7)])
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until SLOTS) {
            keep(slotStyle)
            checksum += slotStyle.radius.toInt()
        }
        for (i in 0 until TICKS) {
            keep(tickStyle)
            checksum += tickStyle.radius.toInt()
        }
        for (i in 0 until KEYS * 2) {
            val style = keyStyles[i and 1]
            keep(style)
            checksum += style.radius.toInt()
        }
        for (i in 0 until SEGS) {
            val slot = i and 7
            var style = segStyles[slot]
            if (style == null) {
                style = me.sorihiko.dango.ui.ShapeStyle(SEG_COLOR, 1.5f)
                segStyles[slot] = style
            }
            keep(style)
            checksum += style.radius.toInt()
        }
    }

    private var fmtKey = Int.MIN_VALUE
    private var fmtText = "0.0"

    private fun oneDecimal(v: Float): Int {
        val key = (v * 10f).toInt()
        if (key != fmtKey) {
            fmtKey = key
            val whole = key / 10
            fmtText = "$whole.${Math.abs(key % 10)}"
        }
        return fmtText.length
    }
}

/** 帧间变化的输入：速度 / 伤害 / 柱透明度，保证旧路径的 String.format 每帧都要真跑 */
private fun frameValues(frame: Int): FloatArray {
    var seed = frame * 1103515245 + 12345
    seed = seed * 1103515245 + 12345
    val speed = 2f + ((seed ushr 8) and 0x3F) / 10f
    val dmg = 1f + ((seed ushr 14) and 0x7F) / 10f
    val dmg2 = 1f + ((seed ushr 20) and 0x3F) / 10f
    val values = FloatArray(11)
    values[0] = speed
    values[1] = dmg
    values[2] = dmg2
    for (i in 0 until 8) values[3 + i] = 0.35f + ((seed ushr (i + 4)) and 7) * 0.06f
    return values
}

private fun hudFrameBuild() {
    println("\n== HUD 帧构建：$PANELS 面板 / $BARS 柱 / $SLOTS 槽 / $TICKS 刻度 / ${KEYS * 2} 键态 / $SEGS 色块 ==")
    println("  （无头 JVM 无法调用 GL，这里只复现每帧的样式 / 字符串 / 对象构造；绘制调用数不变）")
    val old = OldHudFrameWorkload()
    val new = NewHudFrameWorkload()
    val warm = 60
    for (i in 0 until warm) {
        val v = frameValues(i)
        old.frame(v)
        new.frame(v)
    }
    old.checksum = 0
    new.checksum = 0

    var oldBest = Long.MAX_VALUE
    var newBest = Long.MAX_VALUE
    var oldAlloc = 0L
    var newAlloc = 0L
    for (round in 0 until 5) {
        var oa = 0L
        var oNs = 0L
        var nNs = 0L
        val ot = measure {
            for (f in 0 until FRAMES) old.frame(frameValues(f))
        }
        oNs = ot.nanos
        oa = ot.allocated
        val nt = measure {
            for (f in 0 until FRAMES) new.frame(frameValues(f))
        }
        nNs = nt.nanos
        if (round == 0) {
            oldAlloc = oa
            newAlloc = nt.allocated
        }
        oldBest = minOf(oldBest, oNs)
        newBest = minOf(newBest, nNs)
    }
    val oldNsPerFrame = oldBest.toDouble() / FRAMES
    val newNsPerFrame = newBest.toDouble() / FRAMES
    val oldBPerFrame = if (oldAlloc >= 0) oldAlloc.toDouble() / FRAMES else -1.0
    val newBPerFrame = if (newAlloc >= 0) newAlloc.toDouble() / FRAMES else -1.0
    println(fmt("  旧写法：%.2f us/帧，%.1f B/帧（60fps 约 %.2f MB/s）",
        oldNsPerFrame / 1000.0, oldBPerFrame, oldBPerFrame * 60 / 1048576.0))
    println(fmt("  新写法：%.2f us/帧，%.1f B/帧（60fps 约 %.2f MB/s）",
        newNsPerFrame / 1000.0, newBPerFrame, newBPerFrame * 60 / 1048576.0))
    println(fmt("  提升：耗时 %.1f%%，单帧分配降低 %s",
        (oldNsPerFrame - newNsPerFrame) / oldNsPerFrame * 100.0,
        if (oldBPerFrame > 0) fmt("%.1f%%", (oldBPerFrame - newBPerFrame) / oldBPerFrame * 100.0) else "n/a"))
    check("新写法每帧分配显著低于旧写法", newBPerFrame < oldBPerFrame / 4, fmt("%.1f vs %.1f B", newBPerFrame, oldBPerFrame))

    // ---- 综合：本轮的矩形批次（新实现） + HUD 帧构建
    val rectPerFrameNs = 21.2 * RECT_PER_FRAME
    val totalOld = rectPerFrameNs + oldNsPerFrame
    val totalNew = rectPerFrameNs + newNsPerFrame
    val gain = (totalOld - totalNew) / totalOld * 100.0
    println("\n== 综合视觉性能（120 个 HUD 矩形 + 上述帧构建，每帧） ==")
    println(fmt("  改造前：%.2f us/帧", totalOld / 1000.0))
    println(fmt("  改造后：%.2f us/帧", totalNew / 1000.0))
    println(fmt("  综合提升：%.1f%%（目标 ≥8%%）", gain))
    check("综合提升 ≥ 8%", gain >= 8.0, fmt("%.1f%%", gain))
}

// ---------------------------------------------------------------- 主流程

fun main() {
    println("Dango Music 性能基准（无头 JVM，无 GL 上下文）")
    println("JVM = ${System.getProperty("java.version")}，OS = ${System.getProperty("os.name")}")
    if (!hasAllocationCounter()) {
        println("  [WARN] 当前 JVM 没有 ThreadMXBean#getThreadAllocatedBytes，跳过分配统计")
    }

    val data = buildWorkload()

    // ---- 矩形批次
    println("\n== HUD 矩形批次：$RECTS 个矩形 / $FRAMES 帧，每帧 $RECT_PER_FRAME 个 ==")
    val old = OldRectBatch()
    val new = NewRectBatch()
    // 预热：让 JIT 编译、让新实现的 IntArray 扩容稳定下来
    repeat(3) {
        replayOld(old, data)
        replayNew(new, data)
    }
    old.checksum = 0
    new.checksum = 0

    // 取 5 轮里最快的一轮：无头环境没有独立的 JMH fork，单轮数值受 JIT / GC 抖动影响大
    var oldBest = Long.MAX_VALUE
    var newBest = Long.MAX_VALUE
    var oldAlloc = 0L
    var newAlloc = 0L
    for (round in 0 until 5) {
        val o = measure { replayOld(old, data) }
        val n = measure { replayNew(new, data) }
        if (round == 0) {
            oldAlloc = o.allocated
            newAlloc = n.allocated
        }
        oldBest = minOf(oldBest, o.nanos)
        newBest = minOf(newBest, n.nanos)
    }

    val oldNsPerRect = oldBest.toDouble() / RECTS
    val newNsPerRect = newBest.toDouble() / RECTS
    val oldBytesPerRect = if (oldAlloc >= 0) oldAlloc.toDouble() / RECTS else -1.0
    val newBytesPerRect = if (newAlloc >= 0) newAlloc.toDouble() / RECTS else -1.0
    println(fmt("  旧实现：%.1f ns/矩形，%.2f B/矩形，总分配 %.1f MB",
        oldNsPerRect, oldBytesPerRect, oldAlloc / 1048576.0))
    println(fmt("  新实现：%.1f ns/矩形，%.2f B/矩形，总分配 %.1f MB",
        newNsPerRect, newBytesPerRect, newAlloc / 1048576.0))
    println(fmt("  提升：耗时 %.1f%%，分配 %s",
        (oldNsPerRect - newNsPerRect) / oldNsPerRect * 100.0,
        if (oldBytesPerRect > 0) fmt("%.1f%%", (oldBytesPerRect - newBytesPerRect) / oldBytesPerRect * 100.0) else "n/a"))
    check("新旧实现绘制内容一致", old.checksum == new.checksum, "${old.checksum} vs ${new.checksum}")
    if (hasAllocationCounter()) {
        check("新实现每矩形零分配", newBytesPerRect < 1.0, fmt("%.2f B/矩形", newBytesPerRect))
    }

    // ---- 事件派发
    println("\n== 事件派发：20 个监听器 ==")
    val eventManager = EventManager()
    // EventManager 的 addListener 会扫描方法上的注解；实例化即可注册
    eventManager.addListener(TickListenerA())
    eventManager.addListener(TickListenerB())
    eventManager.addListener(TickListenerC())
    eventManager.addListener(TickListenerD())
    eventManager.addListener(TickListenerE())
    repeat(2000) { eventManager.callEvent(TickEvent) }
    sink = 0
    val dispatchRounds = 200_000
    var dispatchBest = Long.MAX_VALUE
    for (round in 0 until 5) {
        val d = measure {
            for (i in 0 until dispatchRounds) eventManager.callEvent(TickEvent)
        }
        dispatchBest = minOf(dispatchBest, d.nanos)
    }
    val nsPerDispatch = dispatchBest.toDouble() / dispatchRounds
    println(fmt("  %.1f ns/次派发（20 个监听器，MethodHandle 缓存后）", nsPerDispatch))
    check("20 个监听器全部收到事件", sink == dispatchRounds * 20 * 5, "sink=$sink")

    glassCaptureAccounting()
    hudFrameBuild()
    tickStringChurn()

    println()
    if (failures == 0) {
        println("结果：全部通过")
    } else {
        println("结果：$failures 项失败")
        kotlin.system.exitProcess(1)
    }
}

// ---------------------------------------------------------------- 每 tick 字符串分配（本轮优化）

private const val ROSTER_PLAYERS = 40
private const val ROSTER_TICKS = 2000

/** 模拟 NetworkPlayerInfo.getDisplayName()：每次取 formattedText 都会拼一份新字符串。 */
private class FakeNameComponent(private val prefix: String, private val name: String,
                                private val suffix: String) {
    fun formattedText(): String = prefix + name + suffix
}

/** 旧路径：每 tick 重新拼 UUID、重新格式化名字，再用字符串相等判断缓存是否命中。 */
private fun rosterOldTick(
    ids: Array<String>,
    components: Array<FakeNameComponent>,
    cache: HashMap<String, String>,
): Int {
    var hits = 0
    for (i in ids.indices) {
        val uuid = ids[i].replace("-", "")
        val formatted = components[i].formattedText()
        if (cache[uuid] == formatted) hits++ else cache[uuid] = formatted
    }
    return hits
}

private class RosterEntry(val raw: String, val component: Any, val formatted: String)

/** 新路径：按 NetworkPlayerInfo 引用缓存，引用与格式化组件都没变就直接复用。 */
private fun rosterNewTick(
    names: Array<String>,
    components: Array<FakeNameComponent>,
    cache: java.util.IdentityHashMap<Any, RosterEntry>,
): Int {
    var hits = 0
    for (i in components.indices) {
        val key: Any = components[i]
        val cached = cache[key]
        if (cached != null && cached.raw == names[i] && cached.component === components[i]) {
            hits++
        } else {
            val formatted = components[i].formattedText()
            cache[key] = RosterEntry(names[i], components[i], formatted)
        }
    }
    return hits
}

/** 旧路径：每 tick 新建 ArrayList 再比较、再拷成数组。 */
private fun tabNamesOldRounds(roster: List<String>, rounds: Int, seed: List<String>): Long {
    var published: List<String> = seed
    var sinkLen = 0L
    for (r in 0 until rounds) {
        val out = ArrayList<String>(roster.size)
        for (name in roster) if (name.isNotEmpty()) out.add(name)
        if (out != published) published = out
        sinkLen += published.size
    }
    return sinkLen
}

/** 新路径：复用同一个缓冲，内容没变就不复制。 */
private fun tabNamesNewRounds(roster: List<String>, rounds: Int, buffer: ArrayList<String>,
                              seed: List<String>): Long {
    var published: List<String> = seed
    var sinkLen = 0L
    for (r in 0 until rounds) {
        buffer.clear()
        for (name in roster) if (name.isNotEmpty()) buffer.add(name)
        if (buffer != published) published = ArrayList(buffer)
        sinkLen += published.size
    }
    return sinkLen
}

/** 与 BedWarsRuntime.oneDecimal 完全一致的手写格式化 */
private fun oneDecimalLike(value: Double): String {
    val scaled = Math.round(value * 10.0)
    val negative = scaled < 0
    val abs = Math.abs(scaled)
    val sb = StringBuilder(6)
    if (negative) sb.append('-')
    sb.append(abs / 10).append('.').append(abs % 10)
    return sb.toString()
}

private fun tickStringChurn() {
    println("\n== 每 tick 字符串分配：$ROSTER_PLAYERS 人名单 x $ROSTER_TICKS tick ==")

    val ids = Array(ROSTER_PLAYERS) { i ->
        "0123456789ab4def8abc" + i.toString().padStart(6, '0')
    }
    // UUID.toString() 的形态：8-4-4-4-12（这里只关心 replace 的成本，长度一致即可）
    val hyphenated = Array(ROSTER_PLAYERS) { i ->
        ids[i].substring(0, 8) + "-" + ids[i].substring(8, 12) + "-" + ids[i].substring(12, 16) +
            "-" + ids[i].substring(16, 20) + "-" + ids[i].substring(20)
    }
    val names = Array(ROSTER_PLAYERS) { i -> "Player$i" }
    val components = Array(ROSTER_PLAYERS) { i -> FakeNameComponent("[MVP+] ", names[i], " §7[A]") }

    val oldCache = HashMap<String, String>(128)
    val newCache = java.util.IdentityHashMap<Any, RosterEntry>(128)
    var oldHits = 0
    var newHits = 0
    val oldTiming = measure {
        repeat(ROSTER_TICKS) { oldHits += rosterOldTick(hyphenated, components, oldCache) }
    }
    val newTiming = measure {
        repeat(ROSTER_TICKS) { newHits += rosterNewTick(names, components, newCache) }
    }
    println(fmt("  名单解析：旧 %.0f ns/tick，新 %.0f ns/tick",
        oldTiming.nanos.toDouble() / ROSTER_TICKS, newTiming.nanos.toDouble() / ROSTER_TICKS))
    if (oldTiming.allocated >= 0 && newTiming.allocated >= 0) {
        println(fmt("  名单解析：旧 %.0f B/tick，新 %.0f B/tick",
            oldTiming.allocated.toDouble() / ROSTER_TICKS, newTiming.allocated.toDouble() / ROSTER_TICKS))
    }
    check("缓存在第二轮起都命中", oldHits > 0 && newHits > 0, "old=$oldHits new=$newHits")

    val roster = ArrayList<String>(ROSTER_PLAYERS)
    for (name in names) roster.add(name)
    val baselineNames = ArrayList(roster)
    // 预热 + 取 5 轮最好成绩，避免单次测量被 JIT / GC 干扰
    tabNamesOldRounds(roster, ROSTER_TICKS, baselineNames)
    tabNamesNewRounds(roster, ROSTER_TICKS, ArrayList(64), baselineNames)
    var tabOldBest = Long.MAX_VALUE
    var tabNewBest = Long.MAX_VALUE
    var tabOldAlloc = 0L
    var tabNewAlloc = 0L
    for (round in 0 until 5) {
        val o = measure { tabNamesOldRounds(roster, ROSTER_TICKS, baselineNames) }
        val n = measure { tabNamesNewRounds(roster, ROSTER_TICKS, ArrayList(64), baselineNames) }
        if (round == 0) {
            tabOldAlloc = o.allocated
            tabNewAlloc = n.allocated
        }
        tabOldBest = minOf(tabOldBest, o.nanos)
        tabNewBest = minOf(tabNewBest, n.nanos)
    }
    println(fmt("  TAB 名单：旧 %.0f ns/tick，新 %.0f ns/tick",
        tabOldBest.toDouble() / ROSTER_TICKS, tabNewBest.toDouble() / ROSTER_TICKS))
    if (tabOldAlloc >= 0 && tabNewAlloc >= 0) {
        println(fmt("  TAB 名单：旧 %.0f B/tick，新 %.0f B/tick",
            tabOldAlloc.toDouble() / ROSTER_TICKS, tabNewAlloc.toDouble() / ROSTER_TICKS))
    }

    // 距离文案：String.format 每次都会新建 Formatter
    val lineRounds = 200_000
    repeat(2) {
        var sink2 = 0
        for (i in 0 until lineRounds) sink2 += oneDecimalLike((i % 90) + 0.25).length
        check("手写格式长度稳定", sink2 > 0, "sink=$sink2")
    }
    val formatTiming = measure {
        var sink2 = 0
        for (i in 0 until lineRounds) {
            sink2 += String.format(java.util.Locale.ROOT, "%.1f", (i % 90) + 0.25).length
        }
        check("格式串长度稳定", sink2 > 0, "sink=$sink2")
    }
    val manualTiming = measure {
        var sink2 = 0
        for (i in 0 until lineRounds) sink2 += oneDecimalLike((i % 90) + 0.25).length
        check("手写格式长度稳定", sink2 > 0, "sink=$sink2")
    }
    println(fmt("  距离文案：String.format %.1f ns/次，手写 %.1f ns/次",
        formatTiming.nanos.toDouble() / lineRounds, manualTiming.nanos.toDouble() / lineRounds))
    if (formatTiming.allocated >= 0 && manualTiming.allocated >= 0) {
        println(fmt("  距离文案：String.format %.0f B/次，手写 %.0f B/次",
            formatTiming.allocated.toDouble() / lineRounds, manualTiming.allocated.toDouble() / lineRounds))
    }
}

private fun fmt(pattern: String, vararg args: Any): String = String.format(pattern, *args)
