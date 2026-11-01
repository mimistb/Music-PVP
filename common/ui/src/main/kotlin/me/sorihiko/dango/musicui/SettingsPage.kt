package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.config.UiPrefs
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.HitColorHudModule
import me.sorihiko.dango.module.impls.music.HudInfoModule
import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.module.impls.music.KeystrokesHudModule
import me.sorihiko.dango.module.impls.music.LyricPalette
import me.sorihiko.dango.module.impls.music.Lyrics2DModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.impls.music.TargetInfoHudModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页（侧栏齿轮）：两个标签 Lyrics / Music & HUD，每个标签是若干分组卡片，两列瀑布流排开。
 * 直接读写各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘。
 */
internal class SettingsPage(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private enum class Ctl { TOGGLE, SLIDER, SWATCH, KEY, TEXT }

    /**
     * 设置页统一栅格：卡片、行、控件、段标题、页签全部按这套尺寸排布，避免各处散落的魔数。
     * 横向：内容左缘贴 page.x（与页面标题同一条基准线），右侧留 [RIGHT_GUTTER] 给滚动条；
     * 卡片内左右统一 [CARD_PAD]，卡片标题、行标签、行分隔线都从这条线起始；控件一律右对齐到卡片内容右缘。
     * 纵向：卡片标题栏与每行同高（[HEAD_H] / [ROW_H]），行内控件按行中线居中，卡片底部留 [CARD_BOTTOM]。
     */
    private object Metrics {
        /** 内容区右侧留给滚动条的余量 */
        const val RIGHT_GUTTER = 12f
        /** 卡片内左右内边距 */
        const val CARD_PAD = 14f
        /** 卡片标题栏高度（与行高一致，纵向节奏统一） */
        const val HEAD_H = 40f
        /** 每行（标签 + 描述 + 控件）的高度 */
        const val ROW_H = 40f
        /** 卡片底部留白 */
        const val CARD_BOTTOM = 8f
        /** 同列相邻卡片间距 */
        const val CARD_GAP = 14f
        /** 段与段之间的间距（比卡片间距大一档，形成层次） */
        const val SECTION_GAP = 20f
        /** 段标题高度：段名 + 通栏分隔线 + 与首张卡片的空隙 */
        const val SECTION_H = 34f
        /** 行内标签与描述的中线间距 */
        const val LINE_GAP = 15f
        /** 标签列与控件列之间至少留出的空隙 */
        const val LABEL_GAP = 14f
        /** 卡片标题与副标题之间的空隙 */
        const val TITLE_GAP = 8f
        /** 卡片标题右侧总开关与「右键展开」提示之间的空隙 */
        const val HEAD_ACTION_GAP = 8f
        /** 滚动条宽度 */
        const val SCROLLBAR_W = 4f
        /** 页面标题块：副标题相对标题的偏移、标题块整体占据的高度 */
        const val HEADER_SUB_Y = 30f
        const val HEADER_H = 54f

        // 页签
        const val TAB_W = 104f
        const val TAB_H = 32f
        const val TAB_PAD = 4f
        const val TAB_RADIUS = 8f

        // 控件
        const val TOGGLE_W = 34f
        const val TOGGLE_H = 20f
        const val KEY_W = 72f
        const val KEY_H = 24f
        const val FIELD_W = 166f
        const val FIELD_H = 22f
        const val FIELD_RADIUS = 6f
        /** 输入框内文字与左右边缘的距离 */
        const val FIELD_INSET = 7f
        /** 滑条槽 = 轨道 + 空隙 + 数值框（110 + 10 + 46），右缘与其它控件贴齐 */
        const val SLIDER_W = 166f
        const val SLIDER_TRACK_W = 110f
        const val SLIDER_VALUE_W = 46f
        const val SLIDER_VALUE_GAP = 10f
        const val SWATCH = 16f
        const val SWATCH_GAP = 8f

        /** 文字视觉中线与其 y 参数的换算（MixedText 以 0.39 × 字号作为中线偏移） */
        fun textY(centerY: Float, size: Float) = centerY - size * 0.39f
    }

    /** scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去 */
    private class Row(
        val key: String, val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: BooleanValue?, val fmt: (Any) -> String, val scale: Float = 1f,
        val secret: Boolean = false, val placeholder: String = "输入名字",
    )

    /**
     * owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示总开关
     * toggleValue：指定组头开关绑定的布尔项（为空则绑定 owner.enabled）
     * section：所属段名（空串表示不画段标题；和上一组不同时在该组上方画段标题 + 细分隔线）
     */
    private class Group(
        val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<Row>,
        val section: String = "", val toggleValue: BooleanValue? = null,
    )

    /** 显示成百分比的选项 */
    private val percentKeys = setOf("volume", "glowRadius", "posX", "posY", "barWidth", "alpha")

    /** 伤害色调：红 / 橙 / 黄 / 绿 / 青 / 蓝 / 紫 */
    private val paletteSwatches = HitColorHudModule.paletteSwatches()

    /** 敌人状态的跟随位置：在左 / 在右 / 在头顶 / 在下巴 */
    private val anchorNames = arrayOf("在左", "在右", "在头顶", "在下巴")

    /** 防砍（剑格挡）动画样式，顺序必须与 fpsport 里 BlockAnimation.style 一致 */
    private val blockStyleNames = arrayOf(
        "Lunar", "1.7", "Debug", "Jello", "Jigsaw", "Luna", "Push", "Swang", "Swank", "Swong", "Sigma",
    )

    /** 发光颜色：跟随字色 / 粉 / 蓝 / 金 */
    private val glowSwatches = listOf(
        Color(1f, 1f, 1f, 1f),            // 跟随字色
        Color(1f, 0.38f, 0.62f, 1f),      // 粉
        Color(0.32f, 0.66f, 1f, 1f),      // 蓝
        Color(1f, 0.74f, 0.30f, 1f),      // 金
    )

    /** 字体颜色：粉 / 蓝 / 白（暖白，和原来的默认字色一致）/ 金 */
    private val fontSwatches = listOf(
        Color(1f, 0.38f, 0.62f, 1f),      // 粉
        Color(0.32f, 0.66f, 1f, 1f),      // 蓝
        Color(0.969f, 0.882f, 0.843f, 1f),// 白
        Color(1f, 0.74f, 0.30f, 1f),      // 金
    )

    /** 歌词颜色：金 / 红 / 粉 / 白（和 [LyricPalette] 的顺序一致） */
    private val lyricSwatches = LyricPalette.all.toList()

    private fun swatchesFor(key: String): List<Color> = when (key) {
        "fontColor" -> fontSwatches
        "nextColor", "sungColor", "lyrics2dColor" -> lyricSwatches
        "palette" -> paletteSwatches
        "fogColor" -> FpsToolsModule.fogSwatches()
        "boFillColor", "boOutlineColor" -> FpsToolsModule.overlaySwatches()
        else -> glowSwatches
    }

    private val tabs: List<List<Group>> by lazy { buildSettings() }

    /** 本帧画了设置列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    /** 设置页滚动位置：关掉界面再打开接着上次的位置 */
    private var target = ScreenState.settingsScroll
    private var pos = ScreenState.settingsScroll
    private var maxScroll = 0f

    // 滑条拖动
    private var dragValue: Any? = null
    private var dragTrack = LayoutBounds()

    // 点数值 / 文字手动输入（光标、选区与剪贴板由 TextInput 负责）
    private var editRow: Row? = null
    private val edit = TextInput(StringValue.MAX_LENGTH)
    private var editRect = LayoutBounds()

    // 正在录快捷键的那一项，以及本帧所有快捷键按钮的区域（点别处就取消录制）
    var bindingKey: IntValue? = null
    private val keyButtons = mutableListOf<LayoutBounds>()

    // 设置页分组：右键标题栏展开 / 收起，状态持久化到 config.json 的 "ui" 段
    private val rightHits = mutableListOf<Hit>()
    private val groupShown = HashMap<String, Float>()
    private val groupAnimAt = HashMap<String, Long>()

    val editing: Boolean get() = editRow != null

    /** 展开 / 收起动画时长（毫秒），200~300 之间 */
    private val groupAnimMs = 240f

    /** 分组的固定键：段名 + 标题，和界面上的卡片一一对应 */
    private fun groupKey(g: Group): String =
        if (g.section.isEmpty()) g.title else "${g.section}/${g.title}"

    /** 当前显示进度：0 = 完全收起，1 = 完全展开；首次见到直接取持久化状态，不做假动画 */
    private fun groupProgress(key: String): Float {
        val target = if (UiPrefs.isGroupOpen(key)) 1f else 0f
        val at = groupAnimAt[key]
        val from = groupShown[key]
        if (at == null || from == null) {
            groupShown[key] = target
            return target
        }
        val t = ((System.currentTimeMillis() - at) / groupAnimMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            groupShown[key] = target
            groupAnimAt.remove(key)
            return target
        }
        val v = from + (target - from) * ui.easeOut(t)
        groupShown[key] = v
        return v
    }

    /** 右键标题栏：从当前进度平滑过渡到相反状态，并立刻写盘 */
    private fun toggleGroup(key: String) {
        groupShown[key] = groupProgress(key)
        groupAnimAt[key] = System.currentTimeMillis()
        UiPrefs.setGroupOpen(key, !UiPrefs.isGroupOpen(key))
        DangoMusic.instance.configManager.saveIfChanged()
    }

    /** 右键命中设置页分组标题栏时返回 true（调用方据此不再执行"返回上一页"） */
    fun mouseRightClicked(mx: Int, my: Int): Boolean {
        val hit = rightHits.firstOrNull { it.first.contains(mx, my) } ?: return false
        hit.second()
        return true
    }

    // ---------------------------------------------------------------- 每帧

    fun tick() {
        pos = ui.approach(pos, target, 70f)
        // 滑条：按住拖动
        if (dragValue != null) {
            if (DangoClient.platform.isMouseDown(0)) applyDrag() else dragValue = null
        }
    }

    fun beginFrame() {
        keyButtons.clear()
        rightHits.clear()
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
        ScreenState.settingsScroll = 0f
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -60f else 60f) * (if (DangoClient.platform.ctrlDown) 4f else 1f)).coerceIn(0f, maxScroll)
        ScreenState.settingsScroll = target
    }

    fun stopDrag() {
        dragValue = null
    }

    // ---------------------------------------------------------------- 数值输入

    /** 常用小数位的格式串（避免每帧 String.format 前重新拼格式） */
    private val floatFmts = arrayOf("%.0f", "%.1f", "%.2f", "%.3f")

    private fun fmtOf(decimals: Int): String =
        if (decimals in 0..3) floatFmts[decimals] else "%.${decimals}f"

    private fun numberText(r: Row): String = when (val v = r.value) {
        is IntValue -> (v.value * r.scale).roundToInt().toString()
        is FloatValue -> {
            val x = v.value * r.scale
            if (r.scale != 1f || v.decimals == 0) x.roundToInt().toString() else fmtOf(v.decimals).format(x)
        }
        else -> ""
    }

    private fun startEdit(r: Row) {
        if (editRow === r) return
        commitEdit()
        editRow = r
        val v = r.value
        if (v is StringValue) {
            edit.maxLength = StringValue.MAX_LENGTH
            edit.accept = { if (it.code in 32..126) it else null }
            edit.set(v.get())
        } else {
            edit.maxLength = 10
            edit.accept = { if (it.isDigit() || it == '.' || it == ',' || it == '-') it else null }
            edit.set(numberText(r))
        }
        screen.blurTextFields()
        bindingKey = null
    }

    /** 输入写回去（数值超出范围的自动夹到范围内，乱输的忽略；文本直接保存） */
    fun commitEdit() {
        val r = editRow ?: return
        editRow = null
        val value = r.value
        val text = edit.text.trim()
        if (value is StringValue) {
            value.set(text)
            // 查询玩家：回车即发起一次手动查询；空名字不请求
            if (r.key == "queryName" && text.isNotEmpty()) BedWarsData.queryRequest = text
            return
        }
        val n = text.replace(',', '.').toFloatOrNull() ?: return
        when (value) {
            is IntValue -> value.setValue((n / r.scale).roundToInt())
            is FloatValue -> value.setValue(n / r.scale)
        }
    }

    /** 点到别处时：收起快捷键录制和数值输入 */
    fun onClickElsewhere(hit: Hit?) {
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(area))) commitEdit()
    }

    // ---------------------------------------------------------------- 选项列表

    private fun fieldsOf(m: Module): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun fmtValue(v: Any): String = when (v) {
        is IntValue -> v.value.toString()
        is FloatValue -> fmtOf(v.decimals).format(v.value)
        else -> ""
    }

    private fun percent(v: Any): String = "${((v as FloatValue).value * 100f).roundToInt()}%"

    private fun buildSettings(): List<List<Group>> {
        val mm = DangoMusic.instance.moduleManager

        /** 一个模块的设置；take(...) 按名字取出若干行，剩下的最后放进 "Other" */
        class Src(val m: Module, val deps: Map<String, String> = emptyMap(), val fmts: Map<String, (Any) -> String> = emptyMap()) {
            val all = fieldsOf(m)
            val left = LinkedHashMap(all)
            fun take(names: List<String>? = null): List<Row> = (names ?: left.keys.toList()).mapNotNull { k ->
                val (ann, v) = left.remove(k) ?: return@mapNotNull null
                val ctl = when {
                    v is BooleanValue -> Ctl.TOGGLE
                    v is StringValue -> Ctl.TEXT
                    k.endsWith("Color") -> Ctl.SWATCH
                    k == "openKey" || (k.startsWith("key") && v is IntValue) -> Ctl.KEY
                    v is IntValue || v is FloatValue -> Ctl.SLIDER
                    else -> return@mapNotNull null
                }
                val dep = deps[k]?.let { all[it]?.second as? BooleanValue }
                val isKey = k.endsWith("Key") && v is StringValue
                Row(
                    k, ann.name.ifEmpty { k }, ann.desc, v, ctl, dep, fmts[k] ?: ::fmtValue,
                    if (k in percentKeys) 100f else 1f,
                    secret = isKey,
                    placeholder = if (isKey) "留空 = 免 Key 模式" else "输入名字",
                )
            }
        }

        val ly = Src(
            mm.get(LyricsModule::class.java),
            deps = mapOf(
                "distance" to "worldMode", "facePlayer" to "worldMode", "keepAway" to "worldMode", "stayOnScreen" to "worldMode", "aboveGround" to "worldMode", "spread" to "worldMode",
                "glowColor" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
                "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
                "flashSize" to "textFlash",
            ),
            fmts = mapOf(
                "spread" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°" },
                "glowRadius" to ::percent,
            ),
        )
        val lyrics = mutableListOf(
            Group("位置", "世界内显示", ly.m, false, ly.take(listOf("noLyrics", "worldMode", "distance", "facePlayer", "keepAway", "stayOnScreen", "aboveGround", "spread", "scale", "smoothFont", "splitLines")), section = "音乐"),
            Group("颜色与发光", "字色与光晕", ly.m, false, ly.take(listOf("fontColor", "glow", "glowColor", "glowStrength", "glowRadius")), section = "音乐"),
            Group("动画", "动画效果", ly.m, false, ly.take(listOf("wiggle", "wiggleFps", "wiggleAmount", "textFlash", "wordPop", "flashSize")), section = "音乐"),
        )
        // 自动识别歌词：网易云按 ID 取不到时，先找本地 .lrc，再按歌名 + 歌手联网匹配
        val auto = Src(
            mm.get(AutoLyricsModule::class.java),
            deps = mapOf("durationToleranceSec" to "onlineMatch"),
            fmts = mapOf("durationToleranceSec" to { v: Any -> "${(v as IntValue).value} 秒" }),
        )
        lyrics += Group("自动识别歌词", "本地 .lrc + 网易云匹配", auto.m, true, auto.take(), section = "音乐")
        // 左上角的原版风格"正在播放"和 Song card 是同一类东西，放到 Music & HUD 里
        val nowPlaying = Group("正在播放", "经典悬浮层", ly.m, false, ly.take(listOf("nowPlaying", "showCover")), section = "音乐")
        ly.take().let { if (it.isNotEmpty()) lyrics += Group("其他", "更多", ly.m, false, it, section = "音乐") }

        val player = Src(module, fmts = mapOf("volume" to ::percent))
        player.left.remove("shuffle")   // 随机播放用播放栏上的按钮就够了，设置页不再显示（状态照样保存）
        // 语音聊天的参数先取出来，否则会被下面"快捷键"组的 take() 全部吃掉
        val voiceRows = player.take(listOf(
            "voiceEnabled", "voicePushToTalk", "voiceKey", "voiceInputGain",
            "voiceOutputVolume", "voiceGate", "voiceBufferMs", "voiceMicMuted",
        ))
        // 组头开关绑定到语音总开关（避免误关整个播放器模块）
        val voiceSwitch = player.all["voiceEnabled"]?.second as? BooleanValue
        val card = Src(mm.get(SongCardModule::class.java), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
            "barWidth" to ::percent,
            "barHeight" to { v: Any -> "%.1f px".format((v as FloatValue).value) },
            "barOffset" to { v: Any -> "%.1f px".format((v as FloatValue).value) },
        ))
        val island = Src(mm.get(DynamicIslandModule::class.java), fmts = mapOf(
            "notifyTime" to { v: Any -> "%.1f 秒".format((v as FloatValue).value) },
            "lyricSize" to { v: Any -> "${(v as FloatValue).value.roundToInt()} px" },
        ))
        // 2D 歌词：横排显示在游戏画面里，字号 / 颜色 / 提前歌词 / 淡化平滑都可以调
        val l2d = Src(mm.get(Lyrics2DModule::class.java), fmts = mapOf(
            "fontSize" to { v: Any -> "${(v as FloatValue).value.roundToInt()} px" },
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        // 组件位置：所有 HUD 组件的整体偏移 / 微调
        val layoutSrc = Src(mm.get(HudLayoutModule::class.java))
        // 信息胶囊 / 按键显示 / 敌人状态 / 打击颜色（从灵动岛拆出来的信息与移植的 HUD 功能）
        val info = Src(mm.get(HudInfoModule::class.java), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        val keys = Src(mm.get(KeystrokesHudModule::class.java), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        val target = Src(mm.get(TargetInfoHudModule::class.java), deps = mapOf(
            "anchor" to "follow",
        ), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
            "anchor" to { v: Any -> anchorNames[(v as IntValue).value.coerceIn(0, anchorNames.size - 1)] },
        ))
        val hit = Src(mm.get(HitColorHudModule::class.java), fmts = mapOf(
            "alpha" to ::percent,
        ))
        // TAB 统计列表：按住 TAB 显示在场所有人的 Hypixel 数据
        val tabStats = Src(mm.get(TabStatsHudModule::class.java), fmts = mapOf(
            "scale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
        ))
        // 床提醒 / Hypixel 统计：只追加分组，复用现有开关、滑条与输入框
        val bw = Src(mm.get(BedWarsModule::class.java), fmts = mapOf(
            "bedRadius" to { v: Any -> "${(v as IntValue).value} 格" },
            "bedMode" to { v: Any -> if ((v as IntValue).value == 0) "严格" else "通用" },
            "hypCacheMinutes" to { v: Any -> "${(v as IntValue).value} 分钟" },
            "hypStyle" to { v: Any -> arrayOf("紧凑", "Lunar", "详细")[(v as IntValue).value.coerceIn(0, 2)] },
            "hypDecimals" to { v: Any -> "${(v as IntValue).value} 位小数" },
        ))
        // PVP 工具：只追加分组；参数由 legacy 的 FpsPortRuntime 每 tick 同步进 fpsport
        val fps = Src(mm.get(FpsToolsModule::class.java),
            deps = mapOf(
                "pmKeepBlock" to "particlesModifier",
                "mpSharpness" to "moreParticles", "mpCrit" to "moreParticles", "mpSpecial" to "moreParticles",
                "mpAlwaysCrit" to "moreParticles", "mpAlwaysSharpness" to "moreParticles", "mpKillEffect" to "moreParticles",
                "diScale" to "damageIndicator",
                "blockStyle" to "blockAnim", "blockOffX" to "blockAnim", "blockOffY" to "blockAnim", "blockOffZ" to "blockAnim",
                "keyZoom" to "smoothZoom", "zoomSmoothCamera" to "smoothZoom", "zoomWheel" to "smoothZoom", "zoomSpeed" to "smoothZoom",
                "zoomScale" to "smoothZoom", "zoomSmoothMouse" to "smoothZoom",
                "keyFreeLook" to "freeLook", "freeLookSens" to "freeLook",
                "fogColor" to "customFog", "fogLiquids" to "customFog",
                "levelShowSelf" to "levelTag", "levelHideNPC" to "levelTag",
                "sprintToggle" to "sprint",
                "ggMessage" to "autoGG", "ggServer" to "autoGG", "ggDelay" to "autoGG", "ggAutoPlay" to "autoGG",
                "keyAutoText" to "autoText", "msgAutoText" to "autoText",
                "keyQuick2" to "autoText", "msgQuick2" to "autoText",
                "keyQuick3" to "autoText", "msgQuick3" to "autoText",
                "keyQuick4" to "autoText", "msgQuick4" to "autoText",
                "fpsLimit" to "performance",
                "perfRenderDistance" to "performance", "perfEntityDistance" to "performance",
                "perfEntityShadows" to "performance", "perfGlassQuality" to "performance",
                // 性能：所有子优化都挂在「性能」总开关下
                "perfIgnoreStands" to "performance", "perfFastLoad" to "performance",
                "perfBatchModel" to "performance", "perfLowAnimTick" to "performance",
                "perfStaticParticle" to "performance", "perfLimitChunks" to "performance",
                "perfDownscaleIcons" to "performance", "perfParticleCulling" to "performance",
                "perfCacheSky" to "performance", "perfEntityCulling" to "performance",
                "perfCullPlayers" to "perfEntityCulling",
                "perfEntityCullingInterval" to "perfEntityCulling",
                "perfEntityCullingMin" to "perfEntityCulling",
                "perfCacheArmorTex" to "performance", "perfSignCulling" to "performance",
                "perfBlockEntityCulling" to "performance",
                "perfBlockEntityDistance" to "perfBlockEntityCulling",
                "perfCustomHudFont" to "performance",
                "perfCustomHudFontSize" to "perfCustomHudFont",
                "perfFastRender" to "performance",
                "perfHideGrass" to "performance", "perfHideDoubleGrass" to "performance",
                "perfHideFlowers" to "performance", "perfHideDoubleFlowers" to "performance",
                "perfHideFences" to "performance", "perfHideFenceGates" to "performance",
                "perfHideSkulls" to "performance", "perfHideArmorStands" to "performance",
                "perfHideItemFrames" to "performance", "perfHideMapFrames" to "performance",
                "perfHideStuckArrows" to "performance", "perfHideGroundArrows" to "performance",
                "perfHideLavaParticles" to "performance", "perfHideSpawnerParticles" to "performance",
                "perfHideMobInSpawner" to "performance", "perfHidePortalParticles" to "performance",
                "perfParticlesLimit" to "performance", "perfChunkUpdateLimit" to "performance",
                "perfPlayerDist" to "performance", "perfPassiveDist" to "performance",
                "perfHostileDist" to "performance", "perfMiscDist" to "performance",
                "perfTextureResolution" to "performance",
                "perfReuseChunks" to "performance", "perfReuseLevel" to "perfReuseChunks",
                "perfAdaptiveChunk" to "perfLimitChunks",
                "perfComposedModel" to "performance", "perfBatchVanillaFont" to "performance",
                "perfFastGlyph" to "performance", "perfSlowObfuscation" to "performance",
                "perfMergeShadow" to "performance", "perfCacheItemModels" to "performance",
                "perfFastCollision" to "performance", "perfFastTextureUpload" to "performance",
                // 第三批
                "rodStringWidth" to "betterFishingRod",
                "mbMode" to "motionBlur", "mbStrength" to "motionBlur",
                "boFill" to "blockOverlay", "boOutline" to "blockOverlay", "boThrough" to "blockOverlay",
                "boWidth" to "blockOverlay",
                "boFillColor" to "boFill", "boFillAlpha" to "boFill",
                "boOutlineColor" to "boOutline", "boOutlineAlpha" to "boOutline",
                "ipRotate" to "itemPhysics", "ipScale" to "itemPhysics", "ipRollSpeed" to "itemPhysics",
                "ipSpread" to "itemPhysics", "ipStackGap" to "itemPhysics", "ipRespectCount" to "itemPhysics",
                "chatFold" to "betterChat", "chatCopy" to "betterChat",
                "chatFoldActionBar" to "chatFold", "chatMinLength" to "chatCopy",
                "chatOpacity" to "betterChat",
                // 第四批：反作弊 / 聊天数据 / 会话
                "acSound" to "acEnabled", "acAutoBlock" to "acEnabled", "acLegitScaffold" to "acEnabled",
                "acScaffoldA" to "acEnabled", "acScaffoldB" to "acEnabled", "acNoSlow" to "acEnabled",
                "acConfirmCount" to "acEnabled", "acWindowSeconds" to "acEnabled",
                "acMinConfidence" to "acEnabled", "acLegitMultiplier" to "acEnabled",
                "adChatIgnoreTeammates" to "adChatEnabled", "adChatCompactBlacklist" to "adChatEnabled",
                "adChatMinStars" to "adChatEnabled", "adChatMinFkdr" to "adChatEnabled",
                "adChatMinSwKdr" to "adChatEnabled", "adChatHistory" to "adChatEnabled",
                "adChatMaxLength" to "adChatEnabled",
                "adSessionIdleSeconds" to "adSessionEnabled", "adSessionKeepWinstreak" to "adSessionEnabled",
                "adSessionMaxMinutes" to "adSessionEnabled", "adSessionAvgRoundSeconds" to "adSessionEnabled",
                "armorShowSlots" to "armorDisplay", "armorShowEmpty" to "armorDisplay", "armorPercent" to "armorDisplay",
                "armorOnlyDamaged" to "armorDisplay", "armorLowThreshold" to "armorDisplay", "armorLowWarn" to "armorDisplay",
                "cpsShowLeft" to "cpsDisplay", "cpsShowRight" to "cpsDisplay",
                "cpsWindow" to "cpsDisplay", "cpsInGameOnly" to "cpsDisplay",
            ),
            fmts = mapOf(
                "ggServer" to { v: Any -> if ((v as IntValue).value == 0) "Hypixel" else "普通" },
                "mpSpecial" to { v: Any -> arrayOf("无", "心形", "火焰", "血")[(v as IntValue).value.coerceIn(0, 3)] },
                "mpKillEffect" to { v: Any -> arrayOf("无", "闪电", "爆炸")[(v as IntValue).value.coerceIn(0, 2)] },
                "zoomScale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
                "diScale" to { v: Any -> "%.2f".format((v as FloatValue).value) },
                "blockStyle" to { v: Any -> blockStyleNames[(v as IntValue).value.coerceIn(0, blockStyleNames.size - 1)] },
                "blockOffX" to { v: Any -> "%.2f 格".format((v as FloatValue).value) },
                "blockOffY" to { v: Any -> "%.2f 格".format((v as FloatValue).value) },
                "blockOffZ" to { v: Any -> "%.2f 格".format((v as FloatValue).value) },
                "freeLookSens" to { v: Any -> "%.2f 倍".format((v as FloatValue).value) },
                "fpsLimit" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不限" else "$it fps" } },
                "perfRenderDistance" to { v: Any -> (v as IntValue).value.let { if (it == 0) "跟随原版" else "$it 区块" } },
                "perfEntityDistance" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不限" else "$it 格" } },
                "perfGlassQuality" to { v: Any -> arrayOf("高", "中", "低")[(v as IntValue).value.coerceIn(0, 2)] },
                "perfBlockEntityDistance" to { v: Any -> "${(v as IntValue).value} 格" },
                "perfCustomHudFontSize" to { v: Any -> "${(v as IntValue).value} px" },
                "perfParticlesLimit" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不限" else "$it 个" } },
                "perfChunkUpdateLimit" to { v: Any -> "${(v as IntValue).value} /帧" },
                "perfEntityCullingInterval" to { v: Any -> "${(v as IntValue).value} ms" },
                "perfEntityCullingMin" to { v: Any -> (v as IntValue).value.let { if (it == 0) "自动" else "$it 个" } },
                "perfPlayerDist" to { v: Any -> "%.2f×".format((v as FloatValue).value) },
                "perfPassiveDist" to { v: Any -> "%.2f×".format((v as FloatValue).value) },
                "perfHostileDist" to { v: Any -> "%.2f×".format((v as FloatValue).value) },
                "perfMiscDist" to { v: Any -> "%.2f×".format((v as FloatValue).value) },
                "perfTextureResolution" to { v: Any ->
                    arrayOf("Default", "Half", "Quarter", "Eighth", "Sixteenth")[(v as IntValue).value.coerceIn(0, 4)]
                },
                "perfReuseLevel" to { v: Any ->
                    arrayOf("Conservative", "Balanced", "Aggressive")[(v as IntValue).value.coerceIn(0, 2)]
                },
                "rodStringWidth" to { v: Any -> "%.1f".format((v as FloatValue).value) },
                "mbMode" to { v: Any -> arrayOf("Old 帧混合", "New 后处理")[(v as IntValue).value.coerceIn(0, 1)] },
                "mbStrength" to { v: Any -> "%.1f".format((v as FloatValue).value) },
                "boWidth" to { v: Any -> "%.1f px".format((v as FloatValue).value) },
                "boFillAlpha" to { v: Any -> "${(v as IntValue).value}%" },
                "boOutlineAlpha" to { v: Any -> "${(v as IntValue).value}%" },
                "ipRotate" to { v: Any -> "%.0f°".format((v as FloatValue).value) },
                "ipScale" to { v: Any -> "%.2f".format((v as FloatValue).value) },
                "ipRollSpeed" to { v: Any -> "%.1f".format((v as FloatValue).value) },
                "ipSpread" to { v: Any -> "%.2f".format((v as FloatValue).value) },
                "ipStackGap" to { v: Any -> "%.3f".format((v as FloatValue).value) },
                "chatMinLength" to { v: Any -> "${(v as IntValue).value} 字" },
                "chatOpacity" to { v: Any -> "${(v as IntValue).value}%" },
                "acConfirmCount" to { v: Any -> "${(v as IntValue).value} 条" },
                "acWindowSeconds" to { v: Any -> "${(v as IntValue).value} 秒" },
                "acMinConfidence" to { v: Any -> "%.2f".format((v as FloatValue).value) },
                "acLegitMultiplier" to { v: Any -> "${(v as IntValue).value}×" },
                "adChatMinStars" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不过滤" else "≥ $it ★" } },
                "adChatMinFkdr" to { v: Any -> (v as FloatValue).value.let { if (it <= 0f) "不过滤" else "%.2f".format(it) } },
                "adChatMinSwKdr" to { v: Any -> (v as FloatValue).value.let { if (it <= 0f) "不过滤" else "%.2f".format(it) } },
                "adChatHistory" to { v: Any -> "${(v as IntValue).value} 条" },
                "adChatMaxLength" to { v: Any -> "${(v as IntValue).value} 字" },
                "adSessionIdleSeconds" to { v: Any -> (v as IntValue).value.let { if (it == 0) "关闭" else "$it 秒" } },
                "adSessionMaxMinutes" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不限" else "$it 分钟" } },
                "adSessionAvgRoundSeconds" to { v: Any -> "${(v as IntValue).value} 秒" },
                "cpsWindow" to { v: Any -> "${(v as IntValue).value} ms" },
                "armorPercent" to { v: Any -> arrayOf("百分比", "剩余-最大")[(v as IntValue).value.coerceIn(0, 1)] },
                "armorLowThreshold" to { v: Any -> "${(v as IntValue).value}%" },
            ),
        )
        val music = listOf(
            Group("播放器", "播放", player.m, false, player.take(listOf("volume")), section = "音乐"),
            Group("快捷键", "点击后按下按键 · Esc 或退格键设为无", player.m, false, player.take(), section = "音乐"),
            Group("歌曲卡片", "悬浮层", card.m, true, card.take(), section = "音乐"),
            nowPlaying,
            Group("灵动岛", "悬浮层", island.m, true, island.take(), section = "音乐"),
            Group("2D 歌词", "横排 · 屏幕歌词", l2d.m, true, l2d.take(), section = "音乐"),
            // 语音大厅：进入大厅后可互相通话（运行层由平台侧实现）
            Group("语音大厅", "进入大厅后可互相通话", module, true, voiceRows, section = "音乐", toggleValue = voiceSwitch),
            Group("组件位置", "整体移动 / 微调", layoutSrc.m, false, layoutSrc.take(), section = "组件位置"),
            Group("信息胶囊", "玩家名 · 帧率 · 延迟 · 时间", info.m, true, info.take(), section = "信息与 HUD"),
            Group("按键显示", "W A S D · 空格", keys.m, true, keys.take(), section = "信息与 HUD"),
            Group("敌人状态", "准星对准玩家时显示", target.m, true, target.take(), section = "信息与 HUD"),
            Group("TAB 统计列表", "按住 TAB 在灵动岛里横向查看在场所有人的数据", tabStats.m, true, tabStats.take(), section = "信息与 HUD"),
            Group("打击颜色", "受伤红闪的颜色与透明度", hit.m, true, hit.take(listOf("palette", "alpha")), section = "信息与 HUD"),
            Group("床提醒", "床被威胁时在灵动岛提示", bw.m, false, bw.take(listOf("bedAlerts", "bedRadius", "bedMode", "queryName")), section = "网络与对战"),
            Group("Hypixel 统计", "可选官方 Key / NetherAPI Key · 留空回退公开源", bw.m, true, bw.take(listOf(
                "hypStats", "hypAutoQuery", "hypCacheMinutes", "hypStyle", "hypDecimals",
                "hypStars", "hypFkdr", "hypWlr", "hypBblr", "hypWinStreak", "hypWins", "hypNetLevel",
                "hypixelKey", "netherApiKey",
            )), section = "网络与对战"),
            Group("PVP 工具", "打击 · 粒子", fps.m, true, fps.take(listOf(
                "noHitDelay", "noHurtCam", "damageIndicator", "diScale", "moreParticles",
                "mpSharpness", "mpCrit", "mpAlwaysCrit", "mpAlwaysSharpness", "mpSpecial", "mpKillEffect",
                "particlesModifier", "pmKeepBlock",
            )), section = "PVP · 打击"),
            Group("视角与画面", "缩放 · 迷雾 · 名牌 · 防砍动画", fps.m, false, fps.take(listOf(
                "smoothZoom", "keyZoom", "zoomSmoothCamera", "zoomSmoothMouse", "zoomWheel", "zoomSpeed", "zoomScale",
                "freeLook", "keyFreeLook", "freeLookSens", "customFog", "fogColor", "fogLiquids",
                "fireModifier", "levelTag", "levelShowSelf", "levelHideNPC",
                "blockAnim", "blockStyle", "blockOffX", "blockOffY", "blockOffZ",
            )), section = "PVP · 视角与画面"),
            Group("自动化", "疾跑 · GG · 快捷发言", fps.m, false, fps.take(listOf(
                "sprint", "sprintToggle", "autoGG", "ggMessage", "ggServer", "ggDelay", "ggAutoPlay",
                "autoText", "keyAutoText", "msgAutoText",
                "keyQuick2", "msgQuick2", "keyQuick3", "msgQuick3", "keyQuick4", "msgQuick4",
            )), section = "PVP · 自动化"),
            Group("性能", "帧率上限 · 渲染距离", fps.m, false, fps.take(listOf(
                "performance", "fpsLimit",
                "perfRenderDistance", "perfEntityDistance", "perfEntityShadows", "perfGlassQuality",
            )), section = "PVP · 性能"),
            Group("性能 · 渲染与实体", "子优化都依赖「性能」总开关", fps.m, false, fps.take(listOf(
                "perfIgnoreStands", "perfFastLoad", "perfBatchModel", "perfLowAnimTick", "perfStaticParticle",
                "perfLimitChunks", "perfDownscaleIcons", "perfParticleCulling", "perfCacheSky",
                "perfEntityCulling", "perfCullPlayers", "perfEntityCullingInterval", "perfEntityCullingMin",
                "perfCacheArmorTex", "perfSignCulling", "perfBlockEntityCulling", "perfBlockEntityDistance",
                "perfCustomHudFont", "perfCustomHudFontSize", "perfFastRender",
            )), section = "PVP · 性能"),
            Group("性能 · 内容隐藏", "关闭不想要的画面元素 · 改动后自动重建区块", fps.m, false, fps.take(listOf(
                "perfHideGrass", "perfHideDoubleGrass", "perfHideFlowers", "perfHideDoubleFlowers",
                "perfHideFences", "perfHideFenceGates", "perfHideSkulls", "perfHideArmorStands",
                "perfHideItemFrames", "perfHideMapFrames", "perfHideStuckArrows", "perfHideGroundArrows",
                "perfHideLavaParticles", "perfHideSpawnerParticles", "perfHideMobInSpawner", "perfHidePortalParticles",
            )), section = "PVP · 性能"),
            Group("性能 · 数值与模式", "上限 / 刷新间隔 / 复用等级", fps.m, false, fps.take(listOf(
                "perfParticlesLimit", "perfChunkUpdateLimit",
                "perfPlayerDist", "perfPassiveDist", "perfHostileDist", "perfMiscDist",
                "perfTextureResolution", "perfReuseChunks", "perfReuseLevel", "perfAdaptiveChunk",
                "perfComposedModel", "perfBatchVanillaFont", "perfFastGlyph", "perfSlowObfuscation",
                "perfMergeShadow", "perfCacheItemModels", "perfFastCollision", "perfFastTextureUpload",
            )), section = "PVP · 性能"),
            Group("画质增强", "鱼竿 · 亮度 · 模糊", fps.m, false, fps.take(listOf(
                "betterFishingRod", "rodStringWidth", "fullBright", "motionBlur", "mbMode", "mbStrength",
            )), section = "PVP · 画质"),
            Group("方块与物品", "方块高亮 · 物品物理", fps.m, false, fps.take(listOf(
                "blockOverlay", "boFill", "boFillColor", "boFillAlpha", "boOutline", "boOutlineColor", "boOutlineAlpha",
                "boThrough", "boWidth",
                "itemPhysics", "ipRotate", "ipScale", "ipRollSpeed", "ipSpread", "ipStackGap", "ipRespectCount",
            )), section = "PVP · 方块与物品"),
            Group("聊天与视角", "聊天增强 · 反作弊 · 会话", fps.m, false, fps.take(listOf(
                "betterChat", "chatFold", "chatFoldActionBar", "chatCopy", "chatMinLength", "chatOpacity", "cleanView",
                "acEnabled", "acSound", "acAutoBlock", "acLegitScaffold", "acScaffoldA", "acScaffoldB", "acNoSlow",
                "acConfirmCount", "acWindowSeconds", "acMinConfidence", "acLegitMultiplier",
                "adChatEnabled", "adChatIgnoreTeammates", "adChatCompactBlacklist",
                "adChatMinStars", "adChatMinFkdr", "adChatMinSwKdr", "adChatHistory", "adChatMaxLength",
                "adSessionEnabled", "adSessionIdleSeconds", "adSessionKeepWinstreak", "adSessionMaxMinutes",
                "adSessionAvgRoundSeconds",
            )), section = "PVP · 聊天与护甲"),
            Group("护甲与点击速度", "各自独立一块显示", fps.m, false, fps.take(listOf(
                "armorDisplay", "armorShowSlots", "armorShowEmpty", "armorPercent", "armorOnlyDamaged",
                "armorLowThreshold", "armorLowWarn",
                "cpsDisplay", "cpsShowLeft", "cpsShowRight", "cpsWindow", "cpsInGameOnly",
            )), section = "PVP · 聊天与护甲"),
        )
        return listOf(lyrics, music)
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        ui.headerAnim {
            ui.text("设置", page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text("修改即时生效并自动保存", page.x, page.y + Metrics.HEADER_SUB_Y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            drawTabs(page)
        }

        val view = LayoutBounds(
            page.x, page.y + Metrics.HEADER_H, page.width,
            page.bottom - (page.y + Metrics.HEADER_H),
        )
        area = view
        val groups = tabs[ScreenState.settingsTab.coerceIn(0, tabs.size - 1)]

        // 按段分组：每段先画一条占满整行的段标题，段内再按两列瀑布流排卡片。
        // 不同分工的模块不会并排混在一起，段与段之间有明确的分割。
        val gap = Metrics.CARD_GAP
        val sectionGap = Metrics.SECTION_GAP
        val rowH = Metrics.ROW_H
        val headH = Metrics.HEAD_H
        // 内容右缘统一让出滚动条余量，左缘与页面标题共用 page.x 这条基准线
        val frameW = (view.width - Metrics.RIGHT_GUTTER).coerceAtLeast(0f)
        val cols = if (frameW >= 560f) 2 else 1
        val colW = (frameW - gap * (cols - 1)) / cols

        val sectionNames = mutableListOf<String>()
        val sectionGroups = mutableListOf<MutableList<Group>>()
        for (g in groups) {
            if (sectionGroups.isEmpty() || g.section != sectionNames.last()) {
                sectionNames += g.section
                sectionGroups += mutableListOf(g)
            } else {
                sectionGroups.last() += g
            }
        }

        class Placed(val group: Group, val col: Int, val y: Float, val h: Float, val open: Float)

        val placed = mutableListOf<Placed>()
        val sectionTop = FloatArray(sectionGroups.size)
        var y = 0f
        for (si in sectionGroups.indices) {
            sectionTop[si] = y
            if (sectionNames[si].isNotEmpty()) y += Metrics.SECTION_H
            val colY = FloatArray(cols)
            var idx = 0
            for (g in sectionGroups[si]) {
                // 卡片高度跟着展开进度变化：收起时只剩标题栏
                val open = groupProgress(groupKey(g))
                val h = headH + g.rows.size * rowH * open + Metrics.CARD_BOTTOM
                // 列固定轮转：动画过程中列不会被重排，卡片不会横向跳
                val c = idx % cols
                placed += Placed(g, c, y + colY[c], h, open)
                colY[c] += h + gap
                idx++
            }
            y += (colY.maxOrNull() ?: 0f) - gap + sectionGap
        }
        val contentH = max(0f, y - sectionGap)
        maxScroll = max(0f, contentH - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
        ScreenState.settingsScroll = target

        ui.beginClip(view)
        var pi = 0
        for (si in sectionGroups.indices) {
            val top = sectionTop[si]
            if (sectionNames[si].isNotEmpty()) {
                val hb = LayoutBounds(view.x, view.y + top - pos, frameW, Metrics.SECTION_H)
                if (hb.bottom >= view.y && hb.y <= view.bottom) drawSectionHeader(hb, sectionNames[si])
            }
            for (g in sectionGroups[si]) {
                val p = placed[pi]
                val b = LayoutBounds(view.x + p.col * (colW + gap), view.y + p.y - pos, colW, p.h)
                if (b.bottom >= view.y && b.y <= view.bottom) ui.itemAnim(pi) { drawGroup(b, g, view, rowH, headH, p.open) }
                pi++
            }
        }
        ui.endClip()

        // 滚动条（只显示位置）：画在右侧留白里，不与卡片重叠
        if (maxScroll > 0f && contentH > 0f) {
            val th = max(30f, view.height * view.height / contentH)
            val ty = view.y + (view.height - th) * (pos / maxScroll)
            ui.ctx.drawShape(
                LayoutBounds(view.right - Metrics.SCROLLBAR_W, ty, Metrics.SCROLLBAR_W, th),
                ShapeStyle(Color(1f, 1f, 1f, 0.30f), Metrics.SCROLLBAR_W / 2f),
            )
        }
    }

    /** 标签：Lyrics / Music & HUD；右缘与内容区对齐，竖直方向与标题块居中 */
    private fun drawTabs(page: LayoutBounds) {
        val names = listOf("歌词", "音乐与 HUD")
        val barW = Metrics.TAB_W * names.size + Metrics.TAB_PAD * 2
        val bar = LayoutBounds(
            page.right - Metrics.RIGHT_GUTTER - barW, page.y + Metrics.TAB_PAD,
            barW, Metrics.TAB_H,
        )
        ui.card(bar, Theme.CARD, 11f)
        names.forEachIndexed { i, n ->
            val b = LayoutBounds(
                bar.x + Metrics.TAB_PAD + i * Metrics.TAB_W, bar.y + Metrics.TAB_PAD,
                Metrics.TAB_W, Metrics.TAB_H - Metrics.TAB_PAD * 2,
            )
            val on = i == ScreenState.settingsTab
            if (on) ui.ctx.drawShape(b, ShapeStyle(RED, Metrics.TAB_RADIUS))
            else if (ui.hover(b)) ui.card(b, Theme.CARD_HOVER, Metrics.TAB_RADIUS)
            val w = ui.width(n, 12f, Theme.FONT_SEMIBOLD)
            ui.text(n, b.x + (b.width - w) / 2f, Metrics.textY(b.y + b.height / 2f, 12f) - 0.5f, 12f,
                if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            ui.hits += b to {
                if (ScreenState.settingsTab != i) {
                    ScreenState.settingsTab = i
                    resetScroll()
                    bindingKey = null
                }
            }
        }
    }

    /** 段标题：红色小竖条 + 13 px 粗体段名 + 通栏分隔线；段名与卡片标题共用同一条左基准线 */
    private fun drawSectionHeader(b: LayoutBounds, title: String) {
        val cy = b.y + Metrics.SECTION_H / 2f
        ui.ctx.drawShape(LayoutBounds(b.x, cy - 7.5f, 3f, 15f), ShapeStyle(RED, 1.5f))
        ui.text(title, b.x + Metrics.CARD_PAD, Metrics.textY(cy, 13f), 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val lx = b.x + Metrics.CARD_PAD + ui.width(title, 13f, Theme.FONT_SEMIBOLD) + 12f
        if (b.right > lx) {
            ui.ctx.drawShape(LayoutBounds(lx, cy - 0.5f, b.right - lx, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.16f)))
        }
    }

    private fun drawGroup(b: LayoutBounds, g: Group, clip: LayoutBounds, rowH: Float, headH: Float, open: Float) {
        val ctx = ui.ctx
        // 每张卡片加一圈很淡的描边，和相邻模块区分开
        ctx.drawShape(b, ShapeStyle(Color(1f, 1f, 1f, 0.05f), 14f, 1f, Color(1f, 1f, 1f, 0.07f)))
        val x = b.x + Metrics.CARD_PAD
        val iw = b.width - Metrics.CARD_PAD * 2
        // 标题栏内部元素统一按标题栏中线居中
        val headCy = b.y + headH / 2f
        ui.text(g.title, x, Metrics.textY(headCy, 13f), 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(
            g.sub, x + ui.width(g.title, 13f, Theme.FONT_SEMIBOLD) + Metrics.TITLE_GAP,
            Metrics.textY(headCy, 10f), 10f, Theme.TEXT_TERTIARY,
        )
        if (g.toggle) {
            val box = LayoutBounds(
                b.right - Metrics.CARD_PAD - Metrics.TOGGLE_W, headCy - Metrics.TOGGLE_H / 2f,
                Metrics.TOGGLE_W, Metrics.TOGGLE_H,
            )
            val v = g.toggleValue
            if (v != null) {
                ui.toggleSwitch(box, v.get(), clip) { v.toggle() }
            } else {
                val m = g.owner
                ui.toggleSwitch(box, m.enabled, clip) { m.enabled = !m.enabled }
            }
        }
        // 收起时提示一下操作方式（右键）；右缘与总开关保持固定间距
        if (open <= 0.001f) {
            val hint = "右键展开"
            val hw = ui.width(hint, 9.5f)
            val actionW = if (g.toggle) Metrics.TOGGLE_W + Metrics.HEAD_ACTION_GAP else 0f
            ui.text(hint, b.right - Metrics.CARD_PAD - actionW - hw, Metrics.textY(headCy, 9.5f), 9.5f, Theme.TEXT_TERTIARY)
        }
        // 整个标题栏都算右键区域，没有盲区
        rightHits += LayoutBounds(b.x, b.y, b.width, headH).intersect(clip) to { toggleGroup(groupKey(g)) }

        if (open <= 0.001f) return
        val bodyTop = b.y + headH
        val bodyH = b.height - headH - Metrics.CARD_BOTTOM
        if (bodyH <= 0f) return
        // 动画期间只显示已经"长出来"的部分；裁剪要跟着入场位移，避免错位
        val bodyClip = LayoutBounds(
            b.x + ui.mt.shiftX, bodyTop + ui.mt.shiftY, b.width, bodyH,
        ).intersect(clip)
        ui.beginClip(bodyClip)
        ctx.pushOpacity(open)
        var y = bodyTop
        val moduleOff = !g.owner.enabled
        for (r in g.rows) {
            ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.06f)))
            val dim = moduleOff || (r.dep != null && !r.dep.get())
            if (dim) ctx.pushOpacity(0.4f)
            drawRow(LayoutBounds(x, y, iw, rowH), r, bodyClip)
            if (dim) ctx.popOpacity()
            y += rowH
        }
        ctx.popOpacity()
        // ui 只有一层 scissor：画完卡片内容后恢复外层的页面裁剪
        ui.beginClip(clip)
    }

    private fun drawRow(b: LayoutBounds, r: Row, clip: LayoutBounds) {
        // 控件槽宽度按类型不同，但右缘统一贴齐卡片内容右缘；标签列据此让出 [LABEL_GAP]
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> Metrics.TOGGLE_W
            Ctl.SLIDER -> Metrics.SLIDER_W
            Ctl.SWATCH -> {
                val n = swatchesFor(r.key).size
                Metrics.SWATCH * n + Metrics.SWATCH_GAP * (n - 1)
            }
            Ctl.KEY -> Metrics.KEY_W
            Ctl.TEXT -> Metrics.FIELD_W
        }
        val textW = b.width - ctlW - Metrics.LABEL_GAP
        val cy = b.y + b.height / 2f
        // 有描述时两行文案整体按行中线居中；没有描述时标签单独居中对齐，避免文字偏高
        val labelCy = if (r.desc.isEmpty()) cy else cy - Metrics.LINE_GAP / 2f
        ui.text(ui.trim(r.label, textW, 12f, Theme.FONT_MEDIUM), b.x, Metrics.textY(labelCy, 12f), 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (r.desc.isNotEmpty()) {
            ui.text(ui.trim(r.desc, textW, 9.5f), b.x, Metrics.textY(cy + Metrics.LINE_GAP / 2f, 9.5f), 9.5f, Theme.TEXT_TERTIARY)
        }
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                ui.toggleSwitch(
                    LayoutBounds(cx, cy - Metrics.TOGGLE_H / 2f, Metrics.TOGGLE_W, Metrics.TOGGLE_H),
                    v.get(), clip,
                ) { v.toggle() }
            }
            Ctl.SLIDER -> drawSlider(r, clip, cx, cy)
            Ctl.SWATCH -> {
                val v = r.value as IntValue
                swatchesFor(r.key).forEachIndexed { i, col ->
                    if (i > v.maxRange) return@forEachIndexed
                    val sb = LayoutBounds(
                        cx + i * (Metrics.SWATCH + Metrics.SWATCH_GAP), cy - Metrics.SWATCH / 2f,
                        Metrics.SWATCH, Metrics.SWATCH,
                    )
                    if (v.value == i) ui.ctx.drawShape(sb.expand(3f, 3f), ShapeStyle(Color(0f, 0f, 0f, 0f), 11f, 1.5f, Theme.TEXT_PRIMARY))
                    ui.ctx.drawShape(sb, ShapeStyle(col, 8f))
                    // 黑色 / 深色色块在深色卡片上会看不见，补一圈浅描边
                    if (col.r + col.g + col.b < 0.5f) {
                        ui.ctx.drawShape(sb, ShapeStyle(Color(0f, 0f, 0f, 0f), 8f, 1f, Color(1f, 1f, 1f, 0.45f)))
                    }
                    ui.hits += sb.expand(4f, 4f).intersect(clip) to { v.setValue(i) }
                }
            }
            Ctl.KEY -> drawKeyButton(r.value as IntValue, clip, cx, cy)
            Ctl.TEXT -> drawTextField(r, clip, cx, cy)
        }
    }

    /** 文本框：复用数值输入框的外观（卡片 + 红色描边 + 闪烁光标），Key 用等长 * 号显示 */
    private fun drawTextField(r: Row, clip: LayoutBounds, cx: Float, cy: Float) {
        val ctx = ui.ctx
        val value = r.value as StringValue
        val box = LayoutBounds(cx, cy - Metrics.FIELD_H / 2f, Metrics.FIELD_W, Metrics.FIELD_H)
        val editing = editRow === r
        val text: String
        val color: Color
        if (editing) {
            editRect = box
            ui.card(box, Theme.CARD_HOVER, Metrics.FIELD_RADIUS)
            ctx.drawShape(box, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.FIELD_RADIUS, 1f, RED))
            text = edit.display(caretVisible(), if (r.secret) '•' else null)
            color = Theme.TEXT_PRIMARY
        } else {
            val raw = value.get()
            text = when {
                raw.isEmpty() -> r.placeholder
                r.secret -> "••••••"
                else -> raw
            }
            color = if (raw.isEmpty()) Theme.TEXT_TERTIARY else Theme.TEXT_SECONDARY
            ui.card(box, Theme.CARD, Metrics.FIELD_RADIUS)
        }
        val shown = ui.trim(text, box.width - Metrics.FIELD_INSET * 2f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(shown, box.x + Metrics.FIELD_INSET, Metrics.textY(cy, 11f) - 0.5f, 11f, color, Theme.FONT_SEMIBOLD)
        ui.hits += box.intersect(clip) to { startEdit(r) }
    }

    private fun drawSlider(r: Row, clip: LayoutBounds, cx: Float, cy: Float) {
        val ctx = ui.ctx
        // 槽内固定三段：轨道 → 空隙 → 数值框，右缘与其它控件贴齐
        val track = LayoutBounds(cx, cy - 2f, Metrics.SLIDER_TRACK_W, 4f)
        val p = when (val v = r.value) {
            is IntValue -> v.normalize()
            is FloatValue -> v.normalize()
            else -> 0f
        }.coerceIn(0f, 1f)
        val active = dragValue === r.value
        if (active) dragTrack = track
        ctx.drawShape(track, ShapeStyle(Theme.TRACK, 2f))
        ctx.drawShape(LayoutBounds(track.x, track.y, track.width * p, track.height), ShapeStyle(RED, 2f))
        val k = if (active || ui.hover(track.expand(6f, 10f))) 14f else 11f
        ctx.drawShape(LayoutBounds(track.x + track.width * p - k / 2f, cy - k / 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        // 数值：点一下可以直接输入；文字始终右对齐在固定数值框内，不随 hover 位移
        val vb = LayoutBounds(
            cx + Metrics.SLIDER_TRACK_W + Metrics.SLIDER_VALUE_GAP,
            cy - Metrics.FIELD_H / 2f, Metrics.SLIDER_VALUE_W, Metrics.FIELD_H,
        )
        if (editRow === r) {
            editRect = vb
            ui.card(vb, Theme.CARD_HOVER, Metrics.FIELD_RADIUS)
            ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.FIELD_RADIUS, 1f, RED))
            val et = edit.display(caretVisible())
            ui.text(
                et, vb.right - 6f - ui.width(et, 11f, Theme.FONT_SEMIBOLD), Metrics.textY(cy, 11f) - 0.5f,
                11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
            )
        } else {
            // 数值文字右缘固定在数值框内，hover 只换底色，不改变文字位置
            val s = r.fmt(r.value)
            val sw = ui.width(s, 11f, Theme.FONT_SEMIBOLD)
            val hot = ui.hover(vb)
            if (hot) {
                val pw = max(vb.width, sw + 12f)
                ui.card(LayoutBounds(vb.right - pw, vb.y, pw, vb.height), Theme.CARD_HOVER, Metrics.FIELD_RADIUS)
            }
            ui.text(s, vb.right - 6f - sw, Metrics.textY(cy, 11f) - 0.5f, 11f,
                if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        ui.hits += vb.intersect(clip) to { startEdit(r) }
        ui.hits += track.expand(8f, 12f).intersect(clip) to {
            dragValue = r.value
            dragTrack = track
            applyDrag()
        }
    }

    private fun drawKeyButton(v: IntValue, clip: LayoutBounds, cx: Float, cy: Float) {
        val kb = LayoutBounds(cx, cy - Metrics.KEY_H / 2f, Metrics.KEY_W, Metrics.KEY_H)
        val binding = bindingKey === v
        ui.card(kb, if (binding) RED.copy(a = 0.22f) else if (ui.hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
        if (binding) ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), 7f, 1f, RED))
        val name = when {
            binding -> "请按键"
            v.value == 0 -> "无"
            else -> Keys.name(v.value) ?: "#${v.value}"
        }
        val s = ui.trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(s, kb.x + (kb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f, Metrics.textY(cy, 11f) - 0.5f, 11f,
            if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hb = kb.intersect(clip)
        keyButtons += hb
        ui.hits += hb to { bindingKey = if (bindingKey === v) null else v }
    }

    private fun applyDrag() {
        val v = dragValue ?: return
        if (dragTrack.width <= 0f) return
        val p = ((ui.mx - dragTrack.x) / dragTrack.width).coerceIn(0f, 1f)
        when (v) {
            is IntValue -> v.setValue((v.minRange + p * (v.maxRange - v.minRange)).roundToInt())
            is FloatValue -> v.setValue(v.minRange + p * (v.maxRange - v.minRange))
        }
    }

    // ---------------------------------------------------------------- 按键

    /** 正在输入数值 / 文本或录快捷键时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        // 正在输入：Enter 确定，Esc 取消；其余交给 TextInput（含 Ctrl+C / V / X / A）
        if (editRow != null) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitEdit()
                keyCode == Keys.ESCAPE -> editRow = null
                else -> edit.handleKey(typedChar, keyCode)
            }
            return true
        }
        // 正在录按键：Esc 直接设为"无"；Backspace / Delete 也是设为"无"
        // （打开快捷键的 minRange 是 1，不能清空，按 Esc 就是取消录制）
        val v = bindingKey ?: return false
        when {
            keyCode == Keys.ESCAPE -> if (v.minRange == 0) v.setValue(Keys.NONE)
            (keyCode == Keys.BACK || keyCode == Keys.DELETE) && v.minRange == 0 -> v.setValue(Keys.NONE)
            keyCode != 0 -> v.setValue(keyCode)
        }
        bindingKey = null
        return true
    }
}
