package me.sorihiko.dango.module.impls.music

import glidehud.feature.TargetInfoState
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
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
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 目标信息 HUD（移植自 Lavender-rise 的 `TargetInfo` 模块）：
 * 准星锁定玩家后显示头像、名字、血量与护甲，共 5 套布局。
 *
 * <p>移植范围仅限显示层：原模块里「多目标」依赖 KillAura 的攻击目标列表，
 * 属于作弊功能，未移植；本模块固定显示准星锁定的那一个玩家。</p>
 *
 * 视觉全部走本项目的液态玻璃画布，不引入源工程的主题 / 渲染管线。
 */
@ModuleMeta(Category.MUSIC, "TargetInfo")
class TargetInfoModule : Module() {

    @ModuleValue("风格", "现代 / 月亮 / 展览 / 渐层 / 新款 + Vibe 的 11 套样式")
    private val style = IntValue(0, STYLE_NAMES.size - 1, 0)

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为深色面板")
    private val glass = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.7f, 3f, 1f, 2)

    @ModuleValue("护甲条", "血条下方的护甲值指示（现代 / 渐层 / 新款）")
    private val armorBar = BooleanValue(true)

    @ModuleValue("跟随目标", "面板实时跟在准星锁定的玩家身边；关掉后用下面的 X / Y 坐标")
    private val follow = BooleanValue(true)

    @ModuleValue("跟随位置", "在左 / 在右 / 在头顶 / 在下巴")
    private val anchor = IntValue(0, 3, 1)

    @ModuleValue("显示时长", "锁定目标并按左键后，信息卡停留的时间（秒）")
    private val holdTime = FloatValue(0.2f, 5f, 1.5f, 1)

    @ModuleValue("淡出时长", "结束时淡出消失的时间（秒），0 = 直接消失")
    private val fadeTime = FloatValue(0f, 2f, 0.35f, 2)

    @ModuleValue("X 坐标", "不跟随时面板的位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Y 坐标", "不跟随时面板的位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.28f, 3)

    // ---- Vibe 样式（索引 5–15）专用：默认值取自源工程 TargetHudSettings ----

    @ModuleValue("面板宽度", "Vibe 样式的面板宽度（高度随样式固定）")
    private val vibeWidth = FloatValue(120f, 320f, 175f, 0)

    @ModuleValue("面板圆角", "Vibe 样式的面板圆角")
    private val vibeRadius = FloatValue(0f, 14f, 6f, 0)

    @ModuleValue("面板底色", "Vibe 样式的底色（#RRGGBB）")
    private val vibeBackgroundColor = IntValue(0, 0xFFFFFF, 0x141B27)

    @ModuleValue("底色透明度", "Vibe 样式底色的不透明度")
    private val vibeBgAlpha = FloatValue(0.3f, 1f, 0.86f, 2)

    @ModuleValue("文字颜色", "Vibe 样式的名字颜色（#RRGGBB）")
    private val vibeTextColor = IntValue(0, 0xFFFFFF, 0xF0F3F9)

    @ModuleValue("血条颜色", "血条起始色（#RRGGBB）")
    private val vibeHealthColor = IntValue(0, 0xFFFFFF, 0x4ADE80)

    @ModuleValue("血条渐变色", "渐变血条的终点色（#RRGGBB）")
    private val vibeHealthEndColor = IntValue(0, 0xFFFFFF, 0x22D3EE)

    @ModuleValue("渐变血条", "血条用起止两色拼接；关闭后为纯色")
    private val vibeGradient = BooleanValue(true)

    @ModuleValue("血量显示", "数值（HP）/ 百分比")
    private val vibePercent = BooleanValue(false)

    @ModuleValue("显示吸收值", "有吸收心时在状态行里显示")
    private val vibeAbsorption = BooleanValue(true)

    @ModuleValue("显示延迟", "在状态行里显示 Tab 列表延迟")
    private val vibePing = BooleanValue(true)

    @ModuleValue("平滑血量", "血条与数字使用渐变动画；关闭后直接取当前血量")
    private val vibeSmoothing = BooleanValue(true)

    @ModuleValue("受伤染色", "目标掉血时名字短暂泛红")
    private val vibeDamageTint = BooleanValue(true)

    @ModuleValue("粒子效果", "面板底部上浮的粒子")
    private val vibeParticles = BooleanValue(true)

    @ModuleValue("粒子颜色", "粒子颜色（#RRGGBB）")
    private val vibeParticleColor = IntValue(0, 0xFFFFFF, 0xFFFFFF)

    @ModuleValue("粒子数量", "同时存在的粒子上限")
    private val vibeParticleCount = IntValue(0, 40, 12)

    @ModuleValue("粒子大小", "单个粒子的边长")
    private val vibeParticleSize = FloatValue(0.5f, 4f, 1.5f, 1)

    @ModuleValue("原版配色", "部分样式改用源工程自带的高亮色")
    private val vibeOriginal = BooleanValue(false)

    private val state = TargetInfoState(HudData.targets)
    private val vibe = VibeTargetHud()
    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    /** 目标装备行：展览风格在信息卡下方画 5 个格子（4 甲 + 手持） */
    private val itemRectBuffer = IntArray(5 * 4)

    /** 名字首字符（拿不到皮肤时作头像占位）缓存 */
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

    // ---- 血量 / 护甲平滑 ----
    private var smoothHealth = Float.NaN
    private var smoothArmor = Float.NaN
    private var smoothAtNanos = 0L

    // ---- 显示判定 ----
    private var lastMouseDown = false
    private var shownAtNanos = 0L
    private var pinUntilNanos = 0L
    private var shownName: String? = null

    private val clear = Color(0f, 0f, 0f, 0f)
    private val hitEdge = Color(1f, 0.29f, 0.24f, 0f)
    private val frameLight = Color(1f, 1f, 1f, 0.16f)
    private val frameDark = Color(0f, 0f, 0f, 0.55f)
    private val lacenderBlue = Color.fromHex("#3E59EC")
    private val lacenderTrack = Color.fromHex("#252424")
    private val moonFill = Color.fromHex("#7BE38B")

    private val glassRound = Theme.GLASS_WINDOW.copy(
        radius = 14f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)),
    )
    private val glassSoft = Theme.GLASS_WINDOW.copy(
        radius = 6f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)),
    )

    private val darkRound = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.9f), 14f, 1f, Color(1f, 1f, 1f, 0.07f),
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    private val darkSoft = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.9f), 6f, 1f, Color(1f, 1f, 1f, 0.07f),
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    private val darkFlat = ShapeStyle(
        Color(0.09f, 0.09f, 0.10f, 0.92f), 2f, 1f, Color(1f, 1f, 1f, 0.06f),
        ShadowStyle(Color(0f, 0f, 0f, 0.16f), 6f, Vec2(0f, -2f)),
    )

    private val hitStyle = ShapeStyle(clear, 14f, 1.5f, hitEdge)
    private val headPlaceholder = ShapeStyle(Theme.TRACK, 6f)
    private val slotStyle = ShapeStyle(Color(0f, 0f, 0f, 0.42f), 2f, 0.8f, Color(1f, 1f, 1f, 0.10f))
    private val frameLightStyle = ShapeStyle(clear, 1f, 1f, frameLight)
    private val frameDarkStyle = ShapeStyle(clear, 1f, 1f, frameDark)

    // 5 套条形样式预先建好，避免每帧 new ShapeStyle
    private val track45 = ShapeStyle(Theme.TRACK, 2.2f)
    private val fill45 = ShapeStyle(moonFill, 2.2f)
    private val track85 = ShapeStyle(Theme.TRACK, 4.2f)
    private val fill85 = ShapeStyle(moonFill, 4.2f)
    private val track11 = ShapeStyle(lacenderTrack, 2f)
    private val fill11 = ShapeStyle(lacenderBlue, 2f)
    private val track12 = ShapeStyle(Theme.TRACK, 5f)
    private val fill12 = ShapeStyle(lacenderBlue, 5f)
    private val track6 = ShapeStyle(Theme.TRACK, 3f)
    private val fill6 = ShapeStyle(Theme.TEXT_SECONDARY, 3f)
    private val segmentTrack = ShapeStyle(Color(0f, 0f, 0f, 0.5f), 1f)
    private val segmentFill = ShapeStyle(Theme.GREEN, 1f)
    private val track3 = ShapeStyle(Theme.TRACK, 1.5f)
    private val fill3 = ShapeStyle(Theme.TEXT_SECONDARY, 1.5f)
    private val track25 = ShapeStyle(lacenderTrack, 1.2f)
    private val fill25 = ShapeStyle(Theme.TEXT_SECONDARY, 1.2f)

    override fun init() {
        instance = this
    }

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun approach(current: Float, target: Float, dt: Float): Float {
        if (current.isNaN() || dt <= 0f) return target
        return current + (target - current) * (1f - exp(-18f * dt))
    }

    private fun fmt1(value: Float): String {
        val tenths = (value * 10f).roundToInt()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }

    private fun drawPanel(ctx: GuiCanvas, panel: LayoutBounds, radius: Float) {
        if (glass.get()) {
            ctx.drawGlass(panel, if (radius >= 10f) glassRound else glassSoft)
        } else {
            ctx.drawShape(panel, if (radius >= 10f) darkRound else if (radius >= 5f) darkSoft else darkFlat)
        }
    }

    private fun bar(
        ctx: GuiCanvas, x: Float, y: Float, w: Float, h: Float, ratio: Float,
        track: ShapeStyle, fill: ShapeStyle,
    ) {
        ctx.drawShape(LayoutBounds(x, y, w, h), track)
        val fw = w * ratio.coerceIn(0f, 1f)
        if (fw > 0.5f) ctx.drawShape(LayoutBounds(x, y, fw, h), fill)
    }

    /** 世界坐标 → 屏幕逻辑坐标；目标在镜头后面时返回 false。 */
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
    fun render(e: HudRenderEvent) {
        if (!enabled) {
            targetItemCount = 0
            targetEntity = null
            return
        }
        val now = System.nanoTime()
        state.provider = HudData.targets
        state.update()
        val platform = DangoClient.platform

        val down = platform.isMouseDown(0)
        val clicked = down && !lastMouseDown && platform.inGame
        lastMouseDown = down
        val targetName = state.name()
        if (clicked && state.hasTarget() && targetName != null) {
            val holdNanos = (holdTime.value * 1_000_000_000f).toLong()
            val stillShown = now < pinUntilNanos && targetName == shownName
            if (!stillShown) {
                shownAtNanos = now
                shownName = targetName
            }
            pinUntilNanos = now + holdNanos
        }
        val holdLeft = (pinUntilNanos - now) / 1e9f
        if (holdLeft <= 0f) {
            smoothHealth = Float.NaN
            smoothArmor = Float.NaN
            smoothAtNanos = 0L
            targetItemCount = 0
            targetEntity = null
            return
        }
        val name = targetName ?: return
        val fadeIn = min(1f, (now - shownAtNanos) / 1e9f / 0.12f)
        val fade = fadeTime.value
        val fadeOut = if (fade <= 0.01f) 1f else min(1f, holdLeft / fade.coerceAtMost(holdTime.value))
        val alpha = fadeIn * fadeOut
        if (alpha <= 0.01f) return

        val target = state.target()
        if (target != null) {
            if (target.name != lastHitName) {
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

        val hp = state.health().coerceIn(0f, 20f)
        val ar = state.armor().coerceIn(0f, 20f)
        val hpText = fmt1(hp)
        val styleIndex = style.value
        val headTextSize = if (styleIndex == 4) 26f else 12f

        // 每套布局的尺寸与名字截断宽度
        mt.clear()
        val nameShown: String
        var w: Float
        var h: Float
        if (styleIndex >= 5) {
            w = vibeWidth.value
            h = vibe.heightFor(styleIndex)
            nameShown = mt.trim(name, (w - 62f).coerceAtLeast(30f), 10f, Theme.FONT_SEMIBOLD)
        } else when (styleIndex) {
            1 -> {
                nameShown = mt.trim(name, 120f, 11f, Theme.FONT_SEMIBOLD)
                w = max(84f, 4.5f + 33f + 5.5f + max(width(nameShown, 11f, Theme.FONT_SEMIBOLD), width(hpText + "HP", 8f)) + 9f)
                h = 39f
            }
            2 -> {
                nameShown = mt.trim(name, 100f, 11f, Theme.FONT_SEMIBOLD)
                w = 160f
                h = 66f
            }
            3 -> {
                nameShown = mt.trim(name, 64f, 10f, Theme.FONT_SEMIBOLD)
                w = 127f
                h = 46f
            }
            4 -> {
                nameShown = mt.trim(name, 168f, 16f, Theme.FONT_DISPLAY)
                w = 260f
                h = 96f
            }
            else -> {
                nameShown = mt.trim(name, 140f, 11f, Theme.FONT_SEMIBOLD)
                w = max(168f, 7f + 32f + 8f + width(nameShown, 11f, Theme.FONT_SEMIBOLD) + 10f)
                h = 46f
            }
        }

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
                if (!lastX.isFinite() || !lastY.isFinite()) return
                x = lastX
                y = lastY
            } else {
                x = posX.value * (lw - w)
                y = posY.value * (lh - h)
            }
        }
        if (!platform.chatOpen || following) {
            dragging = false
        } else if (platform.isMouseDown(0)) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                val snap = HudSnap.snap(
                    "targetinfo",
                    (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f)),
                    (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f)),
                    w, h, lw, lh,
                )
                x = snap.x
                y = snap.y
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val hitAge = if (hitNanos == 0L) 1f else (now - hitNanos) / 1e9f
        val decay = if (hitAge < 0.45f) 1f - hitAge / 0.45f else 0f
        val shake = decay * decay * hitPower * 4.5f
        if (shake > 0.01f) {
            x += sin(hitAge * 62f) * shake
            y += cos(hitAge * 48f) * shake * 0.35f
        }
        if (following) {
            x = x.coerceIn(0f, (lw - w).coerceAtLeast(0f))
            y = y.coerceIn(0f, (lh - h).coerceAtLeast(0f))
        }

        val panel = LayoutBounds(x, y, w, h)
        HudSnap.register("targetinfo", panel)
        var vibeItems = 0
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            ctx.pushOpacity(alpha)
            drawPanel(ctx, panel, if (styleIndex == 3) 2f else if (styleIndex == 1) 8f else 14f)
            if (styleIndex == 2) {
                // 展览：双层描边，模仿源模块的叠层边框
                ctx.drawShape(panel.expand(-0.5f, -0.5f), frameLightStyle)
                ctx.drawShape(panel.expand(-3f, -3f), frameDarkStyle)
            }
            if (decay > 0.01f) {
                hitEdge.a = 0.9f * decay
                ctx.drawShape(panel, hitStyle)
            }

            mt.clear()
            mt.clip = panel
            if (styleIndex >= 5) {
                vibeItems = vibe.draw(
                    ctx, mt, styleIndex, panel, vibeParams(w),
                    vibeView(nameShown, target), k,
                    if (vibeDamageTint.get()) decay else 0f, now,
                )
            } else when (styleIndex) {
                1 -> drawMoon(ctx, panel, nameShown, hpText)
                2 -> drawExhibition(ctx, panel, nameShown, hpText, k, target?.distance ?: 0f)
                3 -> drawLacender(ctx, panel, nameShown, hpText, ar)
                4 -> drawModernNew(ctx, panel, nameShown, hpText, ar)
                else -> drawModern(ctx, panel, nameShown, hpText, ar)
            }

            ctx.popOpacity()
            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }

        // 展览风格下方的装备行交给 legacy 用原版 RenderItem 画；Vibe 的护甲行同理
        if (styleIndex == 2 && target?.entity != null) {
            targetEntity = target.entity
            targetItemCount = 5
        } else if (styleIndex >= 5 && vibeItems > 0 && target?.entity != null) {
            targetEntity = target.entity
            targetItemCount = vibeItems
            targetItemRects = vibe.rects()
        } else {
            targetEntity = null
            targetItemCount = 0
        }
    }

    /** 把模块配置打包成 Vibe 渲染器参数 */
    private fun vibeParams(width: Float) = VibeTargetHud.Params(
        width = width,
        radius = vibeRadius.value,
        background = vibeBackgroundColor.value,
        backgroundAlpha = vibeBgAlpha.value,
        text = vibeTextColor.value,
        health = vibeHealthColor.value,
        healthEnd = vibeHealthEndColor.value,
        gradient = vibeGradient.get(),
        absorption = vibeAbsorption.get(),
        ping = vibePing.get(),
        percent = vibePercent.get(),
        smoothing = vibeSmoothing.get(),
        particles = vibeParticles.get(),
        particleColor = vibeParticleColor.value,
        particleCount = vibeParticleCount.value,
        particleSize = vibeParticleSize.value,
        originalColours = vibeOriginal.get(),
        glass = glass.get(),
    )

    private fun vibeView(name: String, target: glidehud.api.TargetData?): VibeTargetHud.View {
        val maxHp = target?.maxHealth?.takeIf { it > 0.01f } ?: 20f
        val health = if (vibeSmoothing.get()) state.health() else (target?.health ?: 0f)
        val absorption = if (vibeAbsorption.get()) (target?.absorption ?: 0f) else 0f
        return VibeTargetHud.View(
            name = name,
            health = health.coerceAtLeast(0f),
            maxHealth = maxHp,
            absorption = absorption,
            ping = if (vibePing.get()) (target?.ping ?: -1) else -1,
            ratio = (health / maxHp).coerceIn(0f, 1f),
            armorRatio = state.armorRatio(),
            distance = target?.distance ?: 0f,
            head = state.head(),
            entity = target?.entity,
        )
    }

    /** 头像：皮肤脸部贴图，拿不到时用名字首字符占位。 */
    private fun drawHead(ctx: GuiCanvas, box: LayoutBounds, name: String) {
        val headTex = DangoClient.gfx.headTexture(state.head())
        if (headTex != null) {
            ctx.drawImageRegion(box, headTex, headTex.u0, headTex.v0, headTex.u1, headTex.v1)
        } else {
            ctx.drawShape(box, headPlaceholder)
            val initial = if (name != initialName) {
                initialName = name
                initialChar = name.firstOrNull()?.toString() ?: "?"
                initialChar
            } else initialChar
            val size = if (box.height >= 40f) 20f else 12f
            text(
                ctx, initial,
                box.x + (box.width - width(initial, size, Theme.FONT_SEMIBOLD)) / 2f,
                box.y + (box.height - size * 0.73f) / 2f,
                size, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
            )
        }
    }

    /** 0 现代：35px 头像 + 右侧名字 / 血条 / 护甲条 */
    private fun drawModern(ctx: GuiCanvas, panel: LayoutBounds, name: String, hpText: String, ar: Float) {
        drawHead(ctx, LayoutBounds(panel.x + 7f, panel.y + 7f, 32f, 32f), name)
        val tx = panel.x + 47f
        text(ctx, name, tx, panel.y + 8f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hpW = width(hpText, 9f, Theme.FONT_SEMIBOLD)
        text(ctx, hpText, panel.right - 9f - hpW, panel.y + 24f, 9f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        val bw = (panel.right - 9f - tx).coerceAtLeast(24f)
        val ratio = state.healthRatio()
        bar(ctx, tx, panel.y + 30f, bw, 4.5f, ratio, track45, fill45)
        if (armorBar.get() && ar > 0.01f) {
            ctx.drawShape(LayoutBounds(tx, panel.y + 37.5f, bw, 3f), track3)
            ctx.drawShape(LayoutBounds(tx, panel.y + 37.5f, bw * state.armorRatio(), 3f), fill3)
        }
    }

    /** 1 月亮：紧凑 33px 头像 + 名字 / HP 文本 / 圆角血条 */
    private fun drawMoon(ctx: GuiCanvas, panel: LayoutBounds, name: String, hpText: String) {
        drawHead(ctx, LayoutBounds(panel.x + 4.5f, panel.y + 3f, 33f, 33f), name)
        val tx = panel.x + 43f
        text(ctx, name, tx, panel.y + 5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, hpText + "HP", tx, panel.y + 18f, 8f, Theme.TEXT_SECONDARY)
        val bw = (panel.right - 8f - tx).coerceAtLeast(20f)
        bar(ctx, tx, panel.y + 27.5f, bw, 8.5f, state.healthRatio(), track85, fill85)
    }

    /** 2 展览：叠层边框 + 40px 头像 + 分段血条 + 距离 + 装备行 */
    private fun drawExhibition(
        ctx: GuiCanvas, panel: LayoutBounds, name: String, hpText: String, k: Float, distance: Float,
    ) {
        drawHead(ctx, LayoutBounds(panel.x + 6f, panel.y + 7f, 40f, 40f), name)
        val tx = panel.x + 52f
        text(ctx, name, tx, panel.y + 7f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hpW = width(hpText, 10f, Theme.FONT_SEMIBOLD)
        text(ctx, hpText, panel.right - 7f - hpW, panel.y + 7f, 10f, Theme.GREEN, Theme.FONT_SEMIBOLD)

        // 分段血条：11 段，和源模块的展示风格一致
        val barX = tx
        val barY = panel.y + 22f
        val barW = (panel.right - 7f - barX).coerceAtLeast(30f)
        val segW = (barW - 10f) / 11f
        val filled = (state.healthRatio() * 11f).roundToInt()
        for (i in 0 until 11) {
            val sx = barX + i * (segW + 1f)
            ctx.drawShape(LayoutBounds(sx, barY, segW, 5f), segmentTrack)
            if (i < filled) ctx.drawShape(LayoutBounds(sx, barY, segW, 5f), segmentFill)
        }
        val info = "HP: " + hpText + "  |  DIST: " + fmt1(distance)
        text(ctx, info, tx, panel.y + 31f, 7f, Theme.TEXT_SECONDARY)

        // 装备行格子（内容由 legacy 叠加层绘制）
        val itemSize = 13f
        val rects = itemRectBuffer
        for (i in 0 until 5) {
            val sx = tx + i * (itemSize + 2f)
            val sy = panel.y + 44f
            ctx.drawShape(LayoutBounds(sx, sy, itemSize, itemSize), slotStyle)
            rects[i * 4] = (sx * k).toInt()
            rects[i * 4 + 1] = (sy * k).toInt()
            rects[i * 4 + 2] = (itemSize * k).toInt()
            rects[i * 4 + 3] = (itemSize * k).toInt()
        }
        if (targetItemCount > 0) targetItemRects = rects
    }
    /** 3 渐层：28px 头像 + 名字 + 蓝色大血条（条内血量百分比） */
    private fun drawLacender(ctx: GuiCanvas, panel: LayoutBounds, name: String, hpText: String, ar: Float) {
        drawHead(ctx, LayoutBounds(panel.x + 3f, panel.y + 3f, 28f, 28f), name)
        val tx = panel.x + 38f
        text(ctx, name, tx, panel.y + 6f, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val bw = (panel.right - 6f - tx).coerceAtLeast(24f)
        val ratio = state.healthRatio()
        bar(ctx, tx, panel.y + 21f, bw, 11f, ratio, track11, fill11)
        val pct = "${(ratio * 100f).roundToInt()}%"
        val pw = width(pct, 8f, Theme.FONT_SEMIBOLD)
        text(ctx, pct, tx + (bw - pw) / 2f, panel.y + 22.5f, 8f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (armorBar.get() && ar > 0.01f) {
            ctx.drawShape(LayoutBounds(tx, panel.y + 34.5f, bw, 2.5f), track25)
            ctx.drawShape(LayoutBounds(tx, panel.y + 34.5f, bw * state.armorRatio(), 2.5f), fill25)
        }
    }

    /** 4 新款：大号头像 + 大字名字 + HP - 距离 + 厚血条（源模块为 3D 旋转面板，这里做 2D 等价布局） */
    private fun drawModernNew(ctx: GuiCanvas, panel: LayoutBounds, name: String, hpText: String, ar: Float) {
        drawHead(ctx, LayoutBounds(panel.x + 9f, panel.y + 9f, 60f, 60f), name)
        val tx = panel.x + 79f
        text(ctx, name, tx, panel.y + 10f, 16f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val dist = state.target()?.distance ?: 0f
        text(ctx, hpText + "HP  -  " + fmt1(dist) + "M", tx, panel.y + 35f, 10f, Theme.TEXT_SECONDARY)
        val bw = (panel.right - 12f - tx).coerceAtLeast(40f)
        bar(ctx, tx, panel.y + 58f, bw, 12f, state.healthRatio(), track12, fill12)
        if (armorBar.get() && ar > 0.01f) {
            ctx.drawShape(LayoutBounds(tx, panel.y + 74f, bw, 6f), track6)
            ctx.drawShape(LayoutBounds(tx, panel.y + 74f, bw * state.armorRatio(), 6f), fill6)
        }
    }

    companion object {
        /** 风格名，设置页展示用（前 5 套为本项目原有样式，后 11 套移植自 Vibe） */
        val STYLE_NAMES = arrayOf(
            "现代", "月亮", "展览", "渐层", "新款",
            "Novoline", "Tenacity", "Zeroday", "Rise", "LiquidGlass", "Lavalamp",
            "ThunderHack", "NurikZapen", "NurikZapen Mini", "CelkaPasta", "LiquidBounce",
        )

        @Volatile
        private var instance: TargetInfoModule? = null

        fun of(): TargetInfoModule? = instance

        /** 展览风格的目标实体（Object，1.8.9 下是 EntityPlayer），供 legacy 叠加层取装备 */
        @Volatile
        @JvmStatic
        var targetEntity: Any? = null

        /** 目标装备行 5 个格子的窗口像素矩形（x/y/w/h） */
        @Volatile
        @JvmStatic
        var targetItemRects: IntArray = IntArray(0)

        @Volatile
        @JvmStatic
        var targetItemCount = 0
    }
}
