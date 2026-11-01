package me.sorihiko.dango.module.impls.music

import glidehud.feature.TargetInfoState
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.hud.HudData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * 敌人状态：准星对准玩家时显示头像、名字、生命和护甲。
 * 取数来自 glidehud.feature.TargetInfoState（1.8.9 的准星目标由平台层通过 HudData 注入），
 * 视觉是本项目的液态玻璃面板 + 圆角小方块，不使用 glide 的任何外观类。
 *  - 可以跟随目标：面板贴在目标的左 / 右 / 头顶 / 下巴（世界坐标投影到屏幕）
 *  - 头像用玩家皮肤的脸部贴图
 *  - 目标掉血时面板抖一下（打击动态）
 */
@ModuleMeta(Category.MUSIC, "TargetInfo")
class TargetInfoHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.7f, 10f, 1f, 2)

    @ModuleValue("跟随目标", "面板实时跟在准星对准的玩家身边；关掉后用下面的 X / Y 坐标")
    private val follow = BooleanValue(true)

    @ModuleValue("跟随位置", "在左 / 在右 / 在头顶 / 在下巴")
    private val anchor = IntValue(0, 3, 1)

    @ModuleValue("显示时长", "瞄准敌人并按左键后，状态卡停留的时间（秒）")
    private val holdTime = FloatValue(0.2f, 5f, 1.5f, 1)

    @ModuleValue("淡出时长", "结束时淡出消失的时间（秒），0 = 直接消失")
    private val fadeTime = FloatValue(0f, 2f, 0.35f, 2)

    @ModuleValue("X 坐标", "不跟随时面板的位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Y 坐标", "不跟随时面板的位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.28f, 3)

    private val state = TargetInfoState(HudData.targets)
    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    private val pad = 12f
    private val headSize = 26f
    private val panelH = 52f

    /** 名字首字符（拿不到皮肤时作头像占位）缓存：名字没换就不每帧新建字符串 */
    private var initialName: String? = null
    private var initialChar = "?"

    // ---- 跟随定位：复用数组，避免每帧分配 ----
    private val headPos = DoubleArray(3)
    private val chinPos = DoubleArray(3)
    private val sHead = FloatArray(2)
    private val sChin = FloatArray(2)
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    // ---- 打击动态 ----
    private var lastHitName: String? = null
    private var lastHitHealth = 0f
    private var hitNanos = 0L
    private var hitPower = 0f

    // ---- 显示判定：瞄准 + 左键点击后停留 holdTime，最后 fadeTime 淡出 ----
    private var lastMouseDown = false
    private var shownAtNanos = 0L
    private var pinUntilNanos = 0L
    /** 当前正在显示（含淡出）的目标名；换目标才重新淡入。 */
    private var shownName: String? = null

    /** 复用颜色对象 */
    private val clear = Color(0f, 0f, 0f, 0f)
    private val hitEdge = Color(1f, 0.29f, 0.24f, 0f)

    override fun init() {
        instance = this
    }

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun bar(ctx: GuiCanvas, x: Float, y: Float, w: Float, ratio: Float, color: Color) {
        ctx.drawShape(LayoutBounds(x, y, w, 3f), ShapeStyle(Theme.TRACK, 1.5f))
        val fw = w * ratio.coerceIn(0f, 1f)
        if (fw > 0.5f) ctx.drawShape(LayoutBounds(x, y, fw, 3f), ShapeStyle(color, 1.5f))
    }

    /**
     * 世界坐标 → 屏幕逻辑坐标。out[0] / out[1] = x / y。
     * 目标在镜头后面时返回 false。
     */
    private fun project(
        wx: Double, wy: Double, wz: Double,
        pose: PlayerPose, lw: Float, lh: Float, out: FloatArray,
    ): Boolean {
        val yaw = Math.toRadians(pose.yaw.toDouble())
        val pitch = Math.toRadians(pose.pitch.toDouble())
        val sy = sin(yaw)
        val cy = cos(yaw)
        val sp = sin(pitch)
        val cp = cos(pitch)
        val dx = wx - pose.eyeX
        val dy = wy - pose.eyeY
        val dz = wz - pose.eyeZ

        // forward / right / up（和原版摄像机朝向一致）
        val fx = -sy * cp
        val fy = -sp
        val fz = cy * cp
        val rx = -cy
        val rz = -sy
        val ux = -sy * sp
        val uy = cp
        val uz = cy * sp

        val depth = dx * fx + dy * fy + dz * fz
        if (depth <= 0.1) return false
        val camX = dx * rx + dz * rz
        val camY = dx * ux + dy * uy + dz * uz

        val tanHalf = tan(Math.toRadians(DangoClient.platform.fov / 2.0)).toFloat().coerceAtLeast(0.05f)
        val f = (lh * 0.5f) / tanHalf
        val inv = 1f / depth.toFloat()
        out[0] = lw * 0.5f + camX.toFloat() * inv * f
        out[1] = lh * 0.5f - camY.toFloat() * inv * f
        return true
    }

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        state.provider = HudData.targets
        state.update()
        val platform = DangoClient.platform

        // 显示判定：准星瞄准玩家并按左键才出现；之后停留 holdTime，最后 fadeTime 淡出
        val down = platform.isMouseDown(0)
        // 只有真正在游戏里（没有任何 GUI）才把左键当作触发键：开背包点击物品不该弹面板
        val clicked = down && !lastMouseDown && platform.inGame
        lastMouseDown = down
        val targetName = state.name()
        if (clicked && state.hasTarget() && targetName != null) {
            val holdNanos = (holdTime.value * 1_000_000_000f).toLong()
            val stillShown = now < pinUntilNanos && targetName == shownName
            // 淡入 / 停留 / 淡出期间再次点击：只延长停留时间，不重播淡入动画。
            if (!stillShown) {
                shownAtNanos = now
                shownName = targetName
            }
            pinUntilNanos = now + holdNanos
        }
        val holdLeft = (pinUntilNanos - now) / 1e9f
        if (holdLeft <= 0f) return
        val name = targetName ?: return
        val fadeIn = min(1f, (now - shownAtNanos) / 1e9f / 0.12f)
        val fade = fadeTime.value
        // 淡出时长不能超过显示时长，否则面板一出现就处于半透明状态（holdTime < fadeTime 时）
        val fadeOut = if (fade <= 0.01f) 1f else min(1f, holdLeft / fade.coerceAtMost(holdTime.value))
        val alpha = fadeIn * fadeOut
        if (alpha <= 0.01f) return

        // 打击动态：用原始血量（不受平滑影响），突然掉血就抖一下
        val target = state.target()
        if (target != null) {
            if (target.name != lastHitName) {
                // 换目标：重置抖动，避免沿用上一个目标的打击动画
                lastHitName = target.name
                lastHitHealth = target.health
                hitNanos = 0L
                hitPower = 0f
            } else if (target.health < lastHitHealth - 0.01f) {
                hitNanos = now
                hitPower = ((lastHitHealth - target.health) / 6f).coerceIn(0.35f, 1f)
                lastHitHealth = target.health
            } else if (target.health > lastHitHealth) {
                lastHitHealth = target.health
            }
        }

        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val k = uiScale * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        mt.clear()
        val nameShown = mt.trim(name, 190f, 11f, Theme.FONT_SEMIBOLD)
        val nameW = width(nameShown, 11f, Theme.FONT_SEMIBOLD)
        val w = max(150f, pad + headSize + 8f + nameW + pad)
        val h = panelH

        val following = follow.get()
        var x = 0f
        var y = 0f
        var placed = false
        if (following) {
            val pose = platform.player
            val screen = HudData.targetScreen
            if (pose != null && screen != null && screen.headPos(headPos) && screen.chinPos(chinPos)) {
                val okHead = project(headPos[0], headPos[1], headPos[2], pose, lw, lh, sHead)
                val okChin = project(chinPos[0], chinPos[1], chinPos[2], pose, lw, lh, sChin)
                // 目标转到镜头后面 / 已经看不到：不是立刻消失，而是停在最后的位置等淡出
                if (okHead && okChin) {
                    when (anchor.value) {
                        0 -> {
                            x = sHead[0] - w - 14f
                            y = (sHead[1] + sChin[1]) * 0.5f - h * 0.5f
                        }
                        1 -> {
                            x = sHead[0] + 14f
                            y = (sHead[1] + sChin[1]) * 0.5f - h * 0.5f
                        }
                        2 -> {
                            x = sHead[0] - w * 0.5f
                            y = sHead[1] - h - 10f
                        }
                        else -> {
                            x = sChin[0] - w * 0.5f
                            y = sChin[1] + 10f
                        }
                    }
                    lastX = x
                    lastY = y
                    placed = true
                }
            }
        }
        if (!placed) {
            if (following) {
                // 目标刚消失、面板正在淡出：停在最后的位置
                if (!lastX.isFinite() || !lastY.isFinite()) return
                x = lastX
                y = lastY
            } else {
                x = posX.value * (lw - w)
                y = posY.value * (lh - h)
            }
        }
        // 跟随时不参与拖动（位置由目标决定）
        if (!platform.chatOpen || following) {
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

        // 打击动态：衰减抖动 + 边框闪一下
        val hitAge = if (hitNanos == 0L) 1f else (now - hitNanos) / 1e9f
        val decay = if (hitAge < 0.45f) 1f - hitAge / 0.45f else 0f
        val shake = decay * decay * hitPower * 4.5f
        if (shake > 0.01f) {
            x += sin(hitAge * 62f) * shake
            y += cos(hitAge * 48f) * shake * 0.35f
        }
        // 抖动之后再夹取：先夹后抖会把面板推出屏幕
        if (following) {
            x = x.coerceIn(0f, (lw - w).coerceAtLeast(0f))
            y = y.coerceIn(0f, (lh - h).coerceAtLeast(0f))
        }

        val panel = LayoutBounds(x, y, w, h)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            ctx.pushOpacity(alpha)
            if (glass.get()) {
                ctx.drawGlass(panel, Theme.GLASS_WINDOW.copy(
                    radius = min(h / 2f, 16f),
                    shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)),
                ))
            } else {
                ctx.drawShape(panel, ShapeStyle(
                    Color(0.02f, 0.02f, 0.03f, 0.9f), min(h / 2f, 16f), 1f, Color(1f, 1f, 1f, 0.07f),
                    ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                ))
            }
            if (decay > 0.01f) {
                hitEdge.a = 0.9f * decay
                ctx.drawShape(panel, ShapeStyle(clear, min(h / 2f, 16f), 1.5f, hitEdge))
            }

            mt.clear()
            mt.clip = panel

            // 头像：原版皮肤的脸部贴图（8..16 像素），拿不到时用名字首字符占位
            // 位置固定在面板顶部 13 px（面板加高时头像不会跟着往下跑）
            val head = LayoutBounds(panel.x + pad, panel.y + 13f, headSize, headSize)
            val headTex = DangoClient.gfx.headTexture(state.head())
            if (headTex != null) {
                ctx.drawImageRegion(head, headTex, headTex.u0, headTex.v0, headTex.u1, headTex.v1)
            } else {
                ctx.drawShape(head, ShapeStyle(Theme.TRACK, 8f))
                val initial = if (name != initialName) {
                    initialName = name
                    initialChar = name.firstOrNull()?.toString() ?: "?"
                    initialChar
                } else initialChar
                text(ctx, initial, head.x + (head.width - width(initial, 12f, Theme.FONT_SEMIBOLD)) / 2f,
                    head.y + (head.height - 12f * 0.73f) / 2f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            }

            val tx = head.right + 8f
            val ty = panel.y + 12f
            text(ctx, nameShown, tx, ty, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

            val bw = (panel.right - pad - tx).coerceAtLeast(24f)
            bar(ctx, tx, panel.y + 31f, bw, state.healthRatio(), Theme.GREEN)
            bar(ctx, tx, panel.y + 37f, bw, state.armorRatio(), Theme.TEXT_SECONDARY)

            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        @Volatile
        private var instance: TargetInfoHudModule? = null

        fun of(): TargetInfoHudModule? = instance
    }
}
