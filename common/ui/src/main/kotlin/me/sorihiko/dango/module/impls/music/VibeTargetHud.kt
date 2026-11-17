package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vibe 目标信息 HUD 的 11 套样式（TargetHudRenderer + UpstreamTargetHuds 的移植版）。
 *
 * 源工程直接调 Minecraft 的 FontRenderer / GL11 / 物品渲染，这里统一改成本项目的
 * GuiCanvas + MixedText：矩形、圆角、描边、文字、头像贴图一一对应；源工程里
 * 用 3D 玩家模型 / 圆弧仪表 / 图片素材的部分，因为画布没有对应图元，
 * 分别退化为「头像贴图 + 分段圆点仪表 + 纯色块」，布局与信息量保持一致。
 *
 * 物品（护甲行 / 手持）沿用项目已有的 legacy 叠加层：本类只负责算出格子矩形，
 * 由 [TargetInfoModule.targetItemRects] 交给原版 RenderItem 绘制。
 */
internal class VibeTargetHud {

    /** 每帧从模块配置里读出来的渲染参数（避免渲染器直接依赖 ModuleValue） */
    class Params(
        val width: Float,
        val radius: Float,
        val background: Int,
        val backgroundAlpha: Float,
        val text: Int,
        val health: Int,
        val healthEnd: Int,
        val gradient: Boolean,
        val absorption: Boolean,
        val ping: Boolean,
        val percent: Boolean,
        val smoothing: Boolean,
        val particles: Boolean,
        val particleColor: Int,
        val particleCount: Int,
        val particleSize: Float,
        val originalColours: Boolean,
        val glass: Boolean,
    )

    /** 一帧的目标快照 */
    class View(
        val name: String,
        val health: Float,
        val maxHealth: Float,
        val absorption: Float,
        val ping: Int,
        val ratio: Float,
        val armorRatio: Float,
        val distance: Float,
        val head: Any?,
        val entity: Any?,
    )

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var max = 1f
        var size = 1f
    }

    private val particles = ArrayList<Particle>(48)
    private var particleOwner: String? = null
    private var lastSpawn = 0L

    /** 装备格矩形（窗口像素，x/y/w/h ×5），复用避免每帧新建 */
    private val itemRects = IntArray(20)

    private val headPlaceholder = ShapeStyle(Theme.TRACK, 4f)

    // ---------------------------------------------------------------- 尺寸

    /** 每套样式的面板高度；宽度由模块的「面板宽度」统一控制 */
    fun heightFor(mode: Int): Float = when (mode) {
        5 -> 42f     // Novoline
        6 -> 42f     // Tenacity
        7 -> 76f     // Zeroday
        8 -> 40f     // Rise
        9 -> 42f     // LiquidGlass
        10 -> 42f    // Lavalamp
        11 -> 58f    // ThunderHack
        12 -> 46f    // NurikZapen
        13 -> 38f    // NurikZapen Mini
        14 -> 62f    // CelkaPasta
        else -> 62f  // LiquidBounce
    }

    /** 需要画护甲行的样式返回 true */
    fun usesItems(mode: Int): Boolean = mode == 11 || mode == 12 || mode == 13 || mode == 14 || mode == 15

    // ---------------------------------------------------------------- 入口

    /**
     * 画一套样式，返回护甲行的格子数量（0 表示不需要叠加物品），
     * 同时把格子矩形写进 [itemRects]（窗口像素）。
     */
    fun draw(
        ctx: GuiCanvas, mt: MixedText, mode: Int, panel: LayoutBounds,
        p: Params, v: View, k: Float, damageTint: Float, nowNanos: Long,
    ): Int {
        when (mode) {
            5 -> novoline(ctx, mt, panel, p, v, damageTint)
            6 -> tenacity(ctx, mt, panel, p, v, damageTint)
            7 -> zeroday(ctx, mt, panel, p, v, damageTint)
            8 -> rise(ctx, mt, panel, p, v, damageTint, nowNanos)
            9 -> liquidGlass(ctx, mt, panel, p, v, damageTint)
            10 -> lavalamp(ctx, mt, panel, p, v, damageTint, nowNanos)
            11 -> thunderHack(ctx, mt, panel, p, v, k, damageTint)
            12 -> nurikZapen(ctx, mt, panel, p, v, k, damageTint, false)
            13 -> nurikZapen(ctx, mt, panel, p, v, k, damageTint, true)
            14 -> celkaPasta(ctx, mt, panel, p, v, k, damageTint)
            else -> liquidBounce(ctx, mt, panel, p, v, k, damageTint)
        }
        if (p.particles && mode != 7) spawnParticles(panel, p, nowNanos, v)
        drawParticles(ctx, p.particleColor)
        return if (usesItems(mode)) 4 else 0
    }

    // ---------------------------------------------------------------- 样式

    /** 5 Novoline：直角黑底、左侧 36px 头像、底部横幅血条 */
    private fun novoline(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float) {
        panel(ctx, b, p, 2f, 0.86f)
        head(ctx, mt, LayoutBounds(b.x + 3f, b.y + 3f, 36f, 36f), v)
        val tx = b.x + 44f
        name(ctx, mt, v.name, tx, b.y + 4f, 10f, p, tint)
        val bw = (b.right - 6f - tx).coerceAtLeast(20f)
        bar(ctx, tx, b.y + 22f, bw, 12f, v.ratio, p, 2f, 0.92f)
        val label = healthLabel(v, p)
        val lw = mt.width(label, 8f, Theme.FONT_SEMIBOLD)
        shadow(mt, ctx, label, tx + (bw - lw) / 2f, b.y + 24f, 8f)
        mt.text(ctx, label, tx + (bw - lw) / 2f, b.y + 24f, 8f, col(0xFFFFFF, 0.96f), Theme.FONT_SEMIBOLD)
    }

    /** 6 Tenacity：小圆角、头像 + 粗体名 + 右侧百分比 + 细血条 */
    private fun tenacity(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float) {
        panel(ctx, b, p, 4f, 0.88f)
        head(ctx, mt, LayoutBounds(b.x + 4f, b.y + 4f, 32f, 32f), v)
        val tx = b.x + 42f
        name(ctx, mt, v.name, tx, b.y + 6f, 10f, p, tint)
        val pct = pctText(v.ratio)
        val pw = mt.width(pct, 8.5f, Theme.FONT_SEMIBOLD)
        mt.text(ctx, pct, b.right - 6f - pw, b.y + 6f, 8.5f, col(p.health, 0.95f), Theme.FONT_SEMIBOLD)
        bar(ctx, tx, b.y + 25f, (b.right - 6f - tx).coerceAtLeast(20f), 3.5f, v.ratio, p, 3.5f, 0.9f)
    }

    /** 7 Zeroday：左侧头像 + 右侧三条分段仪表（血量 / 护甲 / 延迟） */
    private fun zeroday(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float) {
        panel(ctx, b, p, 6f, 0.9f)
        head(ctx, mt, LayoutBounds(b.x + 6f, b.y + 6f, 40f, 40f), v)
        val tx = b.x + 52f
        name(ctx, mt, v.name, tx, b.y + 6f, 10f, p, tint)
        val gw = (b.right - 8f - tx).coerceAtLeast(30f)
        gauge(ctx, mt, tx, b.y + 24f, gw, "HP", pctText(v.ratio), v.ratio, p.health)
        gauge(
            ctx, mt, tx, b.y + 38f, gw, "ARM",
            (v.armorRatio * 100f).roundToInt().toString(), v.armorRatio, 0x9AD1FF,
        )
        val pingRatio = if (v.ping <= 0) 0f else (1f - (v.ping / 300f)).coerceIn(0f, 1f)
        gauge(
            ctx, mt, tx, b.y + 52f, gw, "PING",
            if (v.ping < 0) "--" else v.ping.toString(), pingRatio, 0xFFD166,
        )
        dots(ctx, b.x + 6f, b.y + 52f, 40f, v.ratio, p.health)
    }

    /** 8 Rise：头像 + 名字 + 底部细条，命中时带粒子 */
    private fun rise(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float, now: Long) {
        panel(ctx, b, p, 5f, 0.9f)
        head(ctx, mt, LayoutBounds(b.x + 5f, b.y + 5f, 30f, 30f), v)
        val tx = b.x + 40f
        val prefix = "Rise"
        mt.text(ctx, prefix, tx, b.y + 6f, 9f, col(if (p.originalColours) 0x55FFFF else p.health, 0.9f), Theme.FONT_SEMIBOLD)
        val pw = mt.width(prefix, 9f, Theme.FONT_SEMIBOLD)
        name(ctx, mt, v.name, tx + pw + 2f, b.y + 6f, 10f, p, tint)
        val hpText = if (p.percent) pctText(v.ratio) else healthLabel(v, p)
        val hw = mt.width(hpText, 8f)
        mt.text(ctx, hpText, b.right - 6f - hw, b.y + 6f, 8f, col(0xF0F3F9, 0.8f))
        bar(ctx, tx, b.y + 26f, (b.right - 6f - tx).coerceAtLeast(20f), 2.5f, v.ratio, p, 2.5f, 0.85f)
    }

    /** 9 LiquidGlass：玻璃底 + 头像 + 渐变血条 + 右侧血量 */
    private fun liquidGlass(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float) {
        if (p.glass) {
            ctx.drawGlass(b, Theme.GLASS_WINDOW.copy(radius = p.radius.coerceAtLeast(10f)))
        } else {
            panel(ctx, b, p, p.radius.coerceAtLeast(10f), 0.72f)
        }
        head(ctx, mt, LayoutBounds(b.x + 5f, b.y + 4f, 34f, 34f), v)
        val tx = b.x + 44f
        name(ctx, mt, v.name, tx, b.y + 5f, 10f, p, tint)
        val hpText = healthLabel(v, p)
        val hw = mt.width(hpText, 8.5f, Theme.FONT_SEMIBOLD)
        mt.text(ctx, hpText, b.right - 7f - hw, b.y + 5f, 8.5f, col(0xF0F3F9, 0.85f), Theme.FONT_SEMIBOLD)
        bar(ctx, tx, b.y + 24f, (b.right - 7f - tx).coerceAtLeast(20f), 5f, v.ratio, p, 5f, 0.92f)
    }

    /** 10 Lavalamp：玻璃底 + 缓慢流动的色块 + 血条（原实现是逐帧 shader，这里用三段色块近似） */
    private fun lavalamp(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, tint: Float, now: Long) {
        panel(ctx, b, p, 12f, 0.82f)
        val phase = (now % 6_000_000_000L) / 6_000_000_000f * (Math.PI * 2).toFloat()
        for (i in 0 until 3) {
            val a = phase + i * 2.1f
            val cx = b.x + b.width * (0.5f + 0.28f * cos(a))
            val cy = b.y + b.height * (0.5f + 0.22f * sin(a * 1.3f))
            val r = b.height * 0.42f
            ctx.drawShape(
                LayoutBounds(cx - r, cy - r * 0.8f, r * 2f, r * 1.6f),
                ShapeStyle(col(if (i % 2 == 0) p.health else p.healthEnd, 0.13f), r),
            )
        }
        head(ctx, mt, LayoutBounds(b.x + 5f, b.y + 4f, 34f, 34f), v)
        val tx = b.x + 44f
        name(ctx, mt, v.name, tx, b.y + 5f, 10f, p, tint)
        val hpText = healthLabel(v, p)
        val hw = mt.width(hpText, 8f)
        mt.text(ctx, hpText, b.right - 7f - hw, b.y + 6f, 8f, col(0xF0F3F9, 0.85f))
        bar(ctx, tx, b.y + 25f, (b.right - 7f - tx).coerceAtLeast(20f), 4.5f, v.ratio, p, 4.5f, 0.92f)
    }

    /** 11 ThunderHack：左右分色底 + 45px 头像 + 发光血条 + 护甲行 */
    private fun thunderHack(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, k: Float, tint: Float) {
        ctx.drawShape(LayoutBounds(b.x, b.y, b.width, b.height), ShapeStyle(col(0x0B0B0B, 0.94f), p.radius.coerceAtMost(2f)))
        ctx.drawShape(
            LayoutBounds(b.x, b.y, b.width * 0.42f, b.height),
            ShapeStyle(col(0x161616, 0.94f), p.radius.coerceAtMost(2f)),
        )
        head(ctx, mt, LayoutBounds(b.x + 4f, b.y + 4f, 45f, 45f), v)
        val tx = b.x + 55f
        name(ctx, mt, v.name, tx, b.y + 5f, 10f, p, tint)
        // 发光血条：先画一层低透明度外扩，再画实体条
        bar(ctx, tx, b.y + 21f, (b.right - 6f - tx).coerceAtLeast(20f), 5f, v.ratio, p, 2f, 0.35f)
        bar(ctx, tx, b.y + 22f, (b.right - 6f - tx).coerceAtLeast(20f), 3.5f, v.ratio, p, 2f, 1f)
        val hpText = healthLabel(v, p)
        mt.text(ctx, hpText, tx, b.y + 31f, 7.5f, col(0xDDDDDD, 0.85f))
        val rectsY = b.y + 42f
        for (i in 0 until 4) {
            val sx = tx + i * 14f
            ctx.drawShape(LayoutBounds(sx, rectsY, 12f, 12f), ShapeStyle(col(0x000000, 0.5f), 1f, 1f, col(0xFFFFFF, 0.08f)))
            writeRect(i, sx, rectsY, 12f, k)
        }
    }

    /** 12 / 13 NurikZapen：黑底、头像、护甲行、底部血条；Mini 用更紧凑的比例 */
    private fun nurikZapen(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, k: Float, tint: Float, mini: Boolean) {
        panel(ctx, b, p, 4f, 0.94f)
        val hs = if (mini) 26f else 30f
        head(ctx, mt, LayoutBounds(b.x + 4f, b.y + 4f, hs, hs), v)
        val tx = b.x + hs + 10f
        name(ctx, mt, v.name, tx, b.y + 5f, if (mini) 9f else 10f, p, tint)
        val hpText = healthLabel(v, p)
        val hw = mt.width(hpText, 8f)
        mt.text(ctx, hpText, b.right - 6f - hw, b.y + 5f, 8f, col(0xD7D7D7, 0.9f))
        val rectsY = b.y + 18f
        val cell = if (mini) 9f else 11f
        for (i in 0 until 4) {
            val sx = tx + i * (cell + 1.5f)
            ctx.drawShape(LayoutBounds(sx, rectsY, cell, cell), ShapeStyle(col(0x000000, 0.45f), 1f, 1f, col(0xFFFFFF, 0.07f)))
            writeRect(i, sx, rectsY, cell, k)
        }
        bar(ctx, tx, b.bottom - 7f, (b.right - 6f - tx).coerceAtLeast(20f), 2.5f, v.ratio, p, 2.5f, 0.9f)
    }

    /** 14 CelkaPasta：上下双色渐变底 + 头像 + 2×2 护甲格 + 名字 + 细条 */
    private fun celkaPasta(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, k: Float, tint: Float) {
        ctx.drawShape(LayoutBounds(b.x, b.y, b.width, b.height / 2f), ShapeStyle(col(p.background, 0.92f), p.radius))
        ctx.drawShape(
            LayoutBounds(b.x, b.y + b.height / 2f, b.width, b.height / 2f),
            ShapeStyle(col(mix(p.background, 0x000000, 0.45f), 0.92f), p.radius),
        )
        head(ctx, mt, LayoutBounds(b.x + 5f, b.y + 5f, 40f, 40f), v)
        val gx = b.x + 52f
        for (i in 0 until 4) {
            val sx = gx + (i % 2) * 15f
            val sy = b.y + 6f + (i / 2) * 15f
            ctx.drawShape(LayoutBounds(sx, sy, 13f, 13f), ShapeStyle(col(0x000000, 0.4f), 1f, 1f, col(0xFFFFFF, 0.08f)))
            writeRect(i, sx, sy, 13f, k)
        }
        val tx = gx + 34f
        name(ctx, mt, v.name, tx, b.y + 8f, 10f, p, tint)
        val hpText = healthLabel(v, p)
        mt.text(ctx, hpText, tx, b.y + 24f, 8f, col(0xE7E7E7, 0.9f))
        bar(ctx, b.x + 5f, b.bottom - 9f, b.width - 10f, 4f, v.ratio, p, 4f, 0.92f)
    }

    /** 15 LiquidBounce：2 倍尺寸面板 + 头像 + 状态行 + 护甲行（含耐久条）+ 厚血条 */
    private fun liquidBounce(ctx: GuiCanvas, mt: MixedText, b: LayoutBounds, p: Params, v: View, k: Float, tint: Float) {
        ctx.drawShape(b, ShapeStyle(col(p.background, 0.9f), p.radius, 1.5f, col(0xFFFFFF, 0.10f)))
        head(ctx, mt, LayoutBounds(b.x + 5f, b.y + 5f, 50f, 50f), v)
        val tx = b.x + 60f
        name(ctx, mt, v.name, tx, b.y + 6f, 11f, p, tint)
        val line1 = "HP: " + healthLabel(v, p) + if (v.absorption > 0.05f) "  ABS: " + fmt1(v.absorption) else ""
        mt.text(ctx, line1, tx, b.y + 21f, 8f, col(0xD8D8D8, 0.9f))
        if (p.ping && v.ping >= 0) {
            mt.text(ctx, "PING: " + v.ping + "ms", tx, b.y + 31f, 8f, col(0xD8D8D8, 0.85f))
        }
        for (i in 0 until 4) {
            val sx = tx + i * 15f
            val sy = b.y + 42f
            ctx.drawShape(LayoutBounds(sx, sy, 13f, 13f), ShapeStyle(col(0x000000, 0.4f), 1f, 1f, col(0xFFFFFF, 0.08f)))
            writeRect(i, sx, sy, 13f, k)
        }
        bar(ctx, b.x + 5f, b.bottom - 8f, b.width - 10f, 5f, v.ratio, p, 5f, 0.95f)
    }

    // ---------------------------------------------------------------- 图元

    private fun panel(ctx: GuiCanvas, b: LayoutBounds, p: Params, radius: Float, alpha: Float) {
        if (p.glass) {
            ctx.drawGlass(b, Theme.GLASS_WINDOW.copy(radius = radius))
        } else {
            ctx.drawShape(b, ShapeStyle(col(p.background, p.backgroundAlpha * alpha), radius, 1f, col(0xFFFFFF, 0.06f)))
        }
    }

    private fun head(ctx: GuiCanvas, mt: MixedText, box: LayoutBounds, v: View) {
        // 皮肤贴图：复用项目 Gfx 的头像接口，拿不到时走首字母占位
        val tex = try {
            if (v.head == null) null else me.sorihiko.dango.DangoClient.gfx.headTexture(v.head)
        } catch (t: Throwable) {
            null
        }
        if (tex != null) {
            ctx.drawImageRegion(box, tex, tex.u0, tex.v0, tex.u1, tex.v1)
        } else {
            ctx.drawShape(box, headPlaceholder)
            val initial = v.name.firstOrNull()?.toString() ?: "?"
            val size = if (box.height >= 36f) 16f else 11f
            val w = mt.width(initial, size, Theme.FONT_SEMIBOLD)
            mt.text(
                ctx, initial,
                box.x + (box.width - w) / 2f,
                box.y + (box.height - size * 0.73f) / 2f,
                size, col(0xFFFFFF, 0.85f), Theme.FONT_SEMIBOLD,
            )
        }
    }

    /** 供模块取出护甲行的窗口像素矩形 */
    fun rects(): IntArray = itemRects

    private fun name(ctx: GuiCanvas, mt: MixedText, name: String, x: Float, y: Float, size: Float, p: Params, tint: Float) {
        val base = if (p.originalColours) 0xFFFFFF else p.text
        val color = if (tint > 0.01f) mix(base, 0xFF4444, tint.coerceIn(0f, 1f) * 0.75f) else base
        mt.text(ctx, name, x, y, size, col(color, 0.97f), Theme.FONT_SEMIBOLD)
    }

    private fun shadow(mt: MixedText, ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float) {
        mt.text(ctx, s, x + 0.8f, y + 0.8f, size, col(0x000000, 0.55f), Theme.FONT_SEMIBOLD)
    }

    /** 血条：原实现是横向渐变，这里用起点色 + 终点色两段近似；ratio 决定填充长度 */
    private fun bar(
        ctx: GuiCanvas, x: Float, y: Float, w: Float, h: Float, ratio: Float,
        p: Params, radius: Float, alpha: Float,
    ) {
        val r = ratio.coerceIn(0f, 1f)
        ctx.drawShape(LayoutBounds(x, y, w, h), ShapeStyle(col(0x000000, 0.55f * alpha), radius))
        val fw = w * r
        if (fw <= 0.4f) return
        if (!p.gradient) {
            ctx.drawShape(LayoutBounds(x, y, fw, h), ShapeStyle(col(p.health, alpha), radius))
            return
        }
        val split = fw * 0.55f
        ctx.drawShape(LayoutBounds(x, y, split, h), ShapeStyle(col(p.health, alpha), radius))
        ctx.drawShape(LayoutBounds(x + split, y, fw - split, h), ShapeStyle(col(p.healthEnd, alpha), radius))
    }

    /** 分段圆点仪表（Zeroday 的圆弧在画布里退化成点阵） */
    private fun dots(ctx: GuiCanvas, x: Float, y: Float, w: Float, ratio: Float, rgb: Int) {
        val n = 10
        val step = w / n
        val filled = (ratio.coerceIn(0f, 1f) * n).roundToInt()
        for (i in 0 until n) {
            val color = if (i < filled) col(rgb, 0.95f) else col(0xFFFFFF, 0.12f)
            ctx.drawShape(LayoutBounds(x + i * step, y, step - 1.5f, 3f), ShapeStyle(color, 1f))
        }
    }

    /** 文本 + 小条组合的小仪表（Zeroday 右侧三行） */
    private fun gauge(
        ctx: GuiCanvas, mt: MixedText, x: Float, y: Float, w: Float,
        label: String, value: String, ratio: Float, rgb: Int,
    ) {
        mt.text(ctx, label, x, y - 3f, 6.5f, col(0xFFFFFF, 0.55f))
        val vw = mt.width(value, 6.5f)
        mt.text(ctx, value, x + w - vw, y - 3f, 6.5f, col(rgb, 0.95f))
        ctx.drawShape(LayoutBounds(x, y + 5f, w, 2.5f), ShapeStyle(col(0xFFFFFF, 0.12f), 1f))
        ctx.drawShape(LayoutBounds(x, y + 5f, w * ratio.coerceIn(0f, 1f), 2.5f), ShapeStyle(col(rgb, 0.95f), 1f))
    }

    private fun writeRect(index: Int, x: Float, y: Float, size: Float, k: Float) {
        if (index < 0 || index >= 5) return
        itemRects[index * 4] = (x * k).toInt()
        itemRects[index * 4 + 1] = (y * k).toInt()
        itemRects[index * 4 + 2] = (size * k).toInt()
        itemRects[index * 4 + 3] = (size * k).toInt()
    }

    // ---------------------------------------------------------------- 粒子

    private fun spawnParticles(b: LayoutBounds, p: Params, now: Long, v: View) {
        if (particleOwner != v.name) {
            particleOwner = v.name
            particles.clear()
            lastSpawn = 0L
        }
        val cap = p.particleCount.coerceIn(0, 64)
        if (cap == 0) {
            particles.clear()
            return
        }
        val interval = 40_000_000L
        if (lastSpawn == 0L) lastSpawn = now
        if (now - lastSpawn >= interval) {
            lastSpawn = now
            if (particles.size < cap) {
                val pt = Particle()
                pt.x = b.x + (0.1f + rand() * 0.8f) * b.width
                pt.y = b.bottom - 2f
                pt.vx = (rand() - 0.5f) * 8f
                pt.vy = -(10f + rand() * 14f)
                pt.max = 0.6f + rand() * 0.5f
                pt.life = pt.max
                pt.size = p.particleSize
                particles.add(pt)
            }
        }
        var i = 0
        while (i < particles.size) {
            val pt = particles[i]
            pt.x += pt.vx * 0.016f
            pt.y += pt.vy * 0.016f
            pt.life -= 0.016f
            if (pt.life <= 0f) particles.removeAt(i) else i++
        }
    }

    private fun drawParticles(ctx: GuiCanvas, rgb: Int) {
        for (pt in particles) {
            val a = (pt.life / pt.max).coerceIn(0f, 1f) * 0.5f
            ctx.drawShape(LayoutBounds(pt.x, pt.y, pt.size, pt.size), ShapeStyle(col(rgb, a), pt.size / 2f))
        }
    }

    private fun rand(): Float = RANDOM.nextFloat()

    // ---------------------------------------------------------------- 工具

    private fun col(rgb: Int, a: Float): Color = Color(
        ((rgb shr 16) and 0xFF) / 255f,
        ((rgb shr 8) and 0xFF) / 255f,
        (rgb and 0xFF) / 255f,
        a.coerceIn(0f, 1f),
    )

    private fun mix(from: Int, to: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (((from shr 16) and 0xFF) * (1 - tt) + ((to shr 16) and 0xFF) * tt).roundToInt()
        val g = (((from shr 8) and 0xFF) * (1 - tt) + ((to shr 8) and 0xFF) * tt).roundToInt()
        val b = ((from and 0xFF) * (1 - tt) + (to and 0xFF) * tt).roundToInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun pctText(ratio: Float): String = (ratio.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"

    private fun healthLabel(v: View, p: Params): String =
        if (p.percent) pctText(v.ratio) else fmt1(v.health)

    private fun fmt1(value: Float): String {
        val tenths = (value * 10f).roundToInt()
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }

    companion object {
        private val RANDOM = java.util.Random()
    }
}
