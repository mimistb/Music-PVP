package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.AntiCheatModule
import me.sorihiko.dango.module.impls.music.AutoBlacklistModule
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.ArmorHudModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.BloodParticlesModule
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.ChatTranslateSettingsModule
import me.sorihiko.dango.module.impls.music.CustomHeldItemsModule
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.GaleAntiAimModule
import me.sorihiko.dango.module.impls.music.HitColorHudModule
import me.sorihiko.dango.module.impls.music.HudInfoModule
import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.module.impls.music.InventoryDisplayHudModule
import me.sorihiko.dango.module.impls.music.KeystrokesHudModule
import me.sorihiko.dango.module.impls.music.KillIconHudModule
import me.sorihiko.dango.module.impls.music.LyricPalette
import me.sorihiko.dango.module.impls.music.Lyrics2DModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.MechvibesModule
import me.sorihiko.dango.module.impls.music.NametagStatsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.PartyVoiceKeyModule
import me.sorihiko.dango.module.impls.music.PotionStatusHudModule
import me.sorihiko.dango.module.impls.music.ProjectileTrailModule
import me.sorihiko.dango.module.impls.music.ScoreboardModule
import me.sorihiko.dango.module.impls.music.ShinyPotsModule
import me.sorihiko.dango.module.impls.music.SniperWarningModule
import me.sorihiko.dango.module.impls.music.SlowSwingModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.SpeedometerHudModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.impls.music.TargetInfoModule
import me.sorihiko.dango.module.impls.music.WeatherChangerModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.KeyBindRegistry
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 设置页（侧栏齿轮）：两个标签 Lyrics / Music & HUD，每个标签是若干分组卡片，两列瀑布流排开。
 * 直接读写各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘。
 */
internal class SettingsPage(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private enum class Ctl { TOGGLE, SLIDER, SWATCH, KEY, TEXT, BUTTON }

    /**
     * 设置页统一栅格：卡片、行、控件、段标题、页签全部按这套尺寸排布，避免各处散落的魔数。
     * 横向：内容左缘贴 page.x（与页面标题同一条基准线），右侧留 [RIGHT_GUTTER] 给滚动条；
     * 卡片内左右统一 [CARD_PAD]，卡片标题、行标签、行分隔线都从这条线起始；控件一律右对齐到卡片内容右缘。
     * 纵向：卡片标题栏与每行同高（[HEAD_H] / [ROW_H]），行内控件按行中线居中，卡片底部留 [CARD_BOTTOM]。
     */
    private object Metrics {
        /** 内容区右侧留给滚动条的余量 */
        const val RIGHT_GUTTER = 14f
        /** 卡片内左右内边距（8pt 栅格两格） */
        const val CARD_PAD = 16f
        /** 卡片标题栏高度：标题 / 副标题两行文字的呼吸空间 */
        const val HEAD_H = 46f
        /** 每行（标签 + 描述 + 控件）的高度 */
        const val ROW_H = 46f
        /** 卡片底部留白 */
        const val CARD_BOTTOM = 10f
        /** 同列相邻卡片间距 */
        const val CARD_GAP = 12f
        /** 段与段之间的间距（比卡片间距大一档，形成层次） */
        const val SECTION_GAP = 24f
        /** 段标题高度：段名 + 通栏分隔线 + 与首张卡片的空隙 */
        const val SECTION_H = 38f
        /** 行内标签与描述的中线间距 */
        const val LINE_GAP = 15f
        /** 标签列与控件列之间至少留出的空隙 */
        const val LABEL_GAP = 18f
        /** 卡片标题与副标题之间的空隙 */
        const val TITLE_GAP = 10f
        /** 卡片标题右侧总开关与「右键设置」提示之间的空隙 */
        const val HEAD_ACTION_GAP = 10f
        /** 滚动条宽度 */
        const val SCROLLBAR_W = 4f
        /** 行悬停高亮相对行框左右各外扩的量（离卡片边缘仍有余量） */
        const val ROW_HOVER_INSET = 8f
        /** 列表页吸顶段标题的底色（叠加在卡片上，保证文字始终可读） */
        const val STICKY_SCRIM = 0.55f
        /** 页面标题块：副标题偏移、标题块整体高度（与 Theme 的页面标题规范同步） */
        const val HEADER_SUB_Y = Theme.PAGE_SUB_Y
        const val HEADER_H = Theme.PAGE_HEADER_H
        /** 主标题专属背景：顶部压暗强度、渐变分段数（与整页背景同为 32 段，过渡一致） */
        const val HEADER_SCRIM = 0.36f
        const val HEADER_BANDS = 32

        // 页签
        const val TAB_W = 104f
        const val TAB_H = 32f
        const val TAB_PAD = 4f
        const val TAB_RADIUS = 8f

        // 控件：尺寸与圆角全部按同一套规范，开关 / 输入框 / 键位按钮 / 色块视觉重心对齐
        const val TOGGLE_W = 38f
        const val TOGGLE_H = 22f
        const val KEY_W = 76f
        const val KEY_H = 26f
        const val FIELD_W = 166f
        const val FIELD_H = 26f
        /** 所有小控件共用的大圆角（胶囊感一致） */
        const val CONTROL_RADIUS = Theme.RADIUS_CHIP
        const val FIELD_RADIUS = CONTROL_RADIUS
        /** 输入框内文字与左右边缘的距离 */
        const val FIELD_INSET = 8f
        /** 滑条槽 = 轨道 + 空隙 + 数值框（110 + 10 + 46），右缘与其它控件贴齐 */
        const val SLIDER_W = 166f
        const val SLIDER_VALUE_W = 46f
        const val SLIDER_VALUE_GAP = 10f
        const val SWATCH = 18f
        const val SWATCH_GAP = 8f

        /** 文字视觉中线与其 y 参数的换算：与 Theme.textCenterY 保持同一套基线 */
        fun textY(centerY: Float, size: Float) = Theme.textCenterY(centerY, size)
    }

    /** scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去 */
    private class Row(
        val key: String, val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: BooleanValue?, val fmt: (Any) -> String, val scale: Float = 1f,
        val secret: Boolean = false, val placeholder: String = "输入名字",
        /** 按钮类行（Ctl.BUTTON）点击时执行；为空则不可点 */
        val action: (() -> Unit)? = null,
    )

    /**
     * owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示总开关
     * toggleValue：指定组头开关绑定的布尔项（为空则绑定 owner.enabled）
     * section：所属段名（空串表示不画段标题；和上一组不同时在该组上方画段标题 + 细分隔线）
     */
    private class Group(
        val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<Row>,
        val section: String = "", val toggleValue: BooleanValue? = null,
        /** 过长分组被拆成多张卡时，所有分片共用同一个键（展开状态 / 右键收起都联动） */
        val keyOverride: String? = null,
        /** 行列表下方的自定义区域高度（击杀图标预览 / 参数管理） */
        val customH: Float = 0f,
        /** 自定义区域绘制：参数是(区域, 裁剪区) */
        val custom: ((LayoutBounds, LayoutBounds) -> Unit)? = null,
        /**
         * 整组是否随 owner 的开关置灰并禁用点击。
         * 性能 / 聊天增强这类「容器模块里的独立功能」设为 false：容器关闭时仍可直接操作。
         */
        val gateOnModule: Boolean = true,
    )

    /** 显示成百分比的选项 */
    private val percentKeys = setOf(
        "volume", "soundVolume", "glowRadius", "posX", "posY", "barWidth", "alpha",
        "fpsX", "fpsY", "pingX", "pingY", "clockX", "clockY", "cpsX", "cpsY",
    )

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
        "kbColor", "markTeammateColor", "markEnemyColor", "healthColor" -> FpsToolsModule.overlaySwatches()
        else -> glowSwatches
    }

    private val tabs: List<List<Group>> by lazy { buildSettings() }

    /** 「右键设置」的固定宽度：字符串不变，量一次就够，避免每帧测量 */
    private val hintTextW: Float by lazy { ui.width("右键设置", 9.5f) }

    /**
     * 设置列表里的卡片。
     *
     * 卡片在列表页一律只画标题栏：右键任意模块会把它的全部配置项放进专用设置页，
     * 不再原地展开，所以不需要按列拆卡平衡高度。
     */
    private fun groupsFor(tab: Int): List<Group> = tabs[tab]

    /**
     * 列表页的分段布局。
     *
     * 段名、卡片位置只和「标签页 + 列数 + 内容宽度」有关，跟滚动位置无关：
     * 以前每帧都要重新分组、重建卡片位置列表，渲染循环里持续产生临时对象；
     * 现在只在窗口尺寸 / 标签页变化时算一次，之后每帧只做绘制。
     */
    private class Section(
        val name: String, val count: Int, val titleW: Float,
        val meta: String, val metaW: Float, val top: Float,
    )

    private class CardSlot(val group: Group, val col: Int, val y: Float, val h: Float)

    private class PageLayout(
        val sections: List<Section>, val cards: List<CardSlot>,
        val colW: Float, val contentH: Float,
    )

    private var layoutCache: PageLayout? = null
    private var layoutTab = -1
    private var layoutCols = -1
    private var layoutWidth = -1f

    private fun layoutFor(tab: Int, cols: Int, frameW: Float): PageLayout {
        // 用字段直接比较，避免每帧拼 key 字符串
        layoutCache?.let {
            if (layoutTab == tab && layoutCols == cols && layoutWidth == frameW) return it
        }
        layoutTab = tab
        layoutCols = cols
        layoutWidth = frameW

        val groups = groupsFor(tab)
        val gap = Metrics.CARD_GAP
        val colW = ((frameW - gap * (cols - 1)) / cols).coerceAtLeast(1f)
        val cardH = Metrics.HEAD_H + Metrics.CARD_BOTTOM

        val sections = ArrayList<Section>(groups.size)
        val cards = ArrayList<CardSlot>(groups.size)
        var y = 0f
        var i = 0
        while (i < groups.size) {
            val name = groups[i].section
            var end = i
            while (end + 1 < groups.size && groups[end + 1].section == name) end++
            val count = end - i + 1
            val meta = "$count 个模块"
            sections += Section(
                name, count,
                if (name.isEmpty()) 0f else ui.width(name, 13f, Theme.FONT_SEMIBOLD),
                meta, ui.width(meta, 10f), y,
            )
            if (name.isNotEmpty()) y += Metrics.SECTION_H
            // 段内按瀑布流分配到当前最矮的一列
            val colY = FloatArray(cols)
            for (k in i..end) {
                var c = 0
                for (j in 1 until cols) if (colY[j] < colY[c]) c = j
                cards += CardSlot(groups[k], c, y + colY[c], cardH)
                colY[c] += cardH + gap
            }
            y += (colY.maxOrNull() ?: 0f) - gap + Metrics.SECTION_GAP
            i = end + 1
        }

        val layout = PageLayout(sections, cards, colW, max(0f, y - Metrics.SECTION_GAP))
        layoutCache = layout
        return layout
    }

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

    // 参数管理：档案名输入、档案列表缓存与操作结果提示
    private val profileName = TextInput(40)
    private var profileEditing = false
    private var profileNameRect = LayoutBounds()
    private var profileNames: List<String> = emptyList()
    private var profileNamesAt = 0L
    private var profileStatus = ""
    private var profileStatusAt = 0L

    // 正在录快捷键的那一项，以及本帧所有快捷键按钮的区域（点别处就取消录制）
    var bindingKey: KeyBindValue? = null
    private val keyButtons = mutableListOf<LayoutBounds>()
    /** 右键快捷键按钮 = 恢复默认键位 */
    private val resetKeyHits = mutableListOf<Hit>()

    /** 最近一次被设置页消费的 Esc 时间：防止按住 / 连按 Esc 顺带把播放器界面关掉 */
    private var escapeConsumedAt = 0L

    // 所有键位字段的索引（惰性构建一次）；冲突检测直接在索引里比较
    private class BindRef(val value: KeyBindValue, val label: String)
    private val allBinds: List<BindRef> by lazy {
        val out = ArrayList<BindRef>()
        for (module in DangoMusic.instance.moduleManager.modules.values.flatten()) {
            for (field in module.javaClass.declaredFields) {
                if (!field.isAnnotationPresent(ModuleValue::class.java)) continue
                field.isAccessible = true
                val value = try { field.get(module) as? KeyBindValue } catch (_: Throwable) { null } ?: continue
                val label = field.getAnnotation(ModuleValue::class.java).name.ifEmpty { field.name }
                out += BindRef(value, "${module.name} · $label")
            }
        }
        out
    }

    // 列表页：右键命中的模块卡片（整张卡都是热区）
    private val openHits = mutableListOf<Hit>()

    // 专用设置页的滚动：与列表分开存，返回列表时列表位置不丢
    private var detailTarget = ScreenState.settingsDetailScroll
    private var detailPos = ScreenState.settingsDetailScroll
    private var detailMax = 0f

    val editing: Boolean get() = editRow != null

    /** 分组的固定键：段名 + 标题，和界面上的卡片一一对应 */
    private fun groupKey(g: Group): String =
        g.keyOverride ?: if (g.section.isEmpty()) g.title else "${g.section}/${g.title}"

    /** 当前是否停在某个模块的专用设置页 */
    private fun detailMode(): Boolean = ScreenState.page == Page.SETTINGS_DETAIL

    /**
     * 右键模块：立刻跳转到该模块的专用设置页。
     * 先收尾正在进行的编辑 / 快捷键录制，避免输入焦点跟着页面串过去。
     */
    private fun openDetail(g: Group) {
        commitEdit()
        bindingKey = null
        editRow = null
        profileEditing = false
        ScreenState.settingsDetail = groupKey(g)
        ScreenState.settingsDetailTab = ScreenState.settingsTab.coerceIn(0, tabs.size - 1)
        ScreenState.settingsDetailScroll = 0f
        detailTarget = 0f
        detailPos = 0f
        ScreenState.page = Page.SETTINGS_DETAIL
        screen.blurTextFields()
    }

    /** 详情页的返回：回到设置列表（右键空白处 / 返回按钮 / Esc 都走这里） */
    private fun backToList() {
        cancelInput()
        bindingKey = null
        ScreenState.settingsDetail = null
        ScreenState.page = Page.SETTINGS
    }

    /** 右键命中模块卡片时返回 true（调用方据此不再执行"返回上一页"） */
    fun mouseRightClicked(mx: Int, my: Int): Boolean {
        openHits.firstOrNull { it.first.contains(mx, my) }?.let {
            it.second()
            return true
        }
        // 右键快捷键：恢复该字段的默认键位
        resetKeyHits.firstOrNull { it.first.contains(mx, my) }?.let {
            it.second()
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- 每帧

    fun tick() {
        // 两个页面各自维护滚动：只推进当前显示的那一页
        if (detailMode()) detailPos = ui.approach(detailPos, detailTarget, 70f)
        else pos = ui.approach(pos, target, 70f)
        // 滑条：按住拖动
        if (dragValue != null) {
            if (DangoClient.platform.isMouseDown(0)) applyDrag()
            else {
                dragValue = null
                persist()
            }
        }
    }

    fun beginFrame() {
        keyButtons.clear()
        resetKeyHits.clear()
        openHits.clear()
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
        ScreenState.settingsScroll = 0f
    }

    fun scroll(wheel: Int) {
        val step = (if (wheel > 0) -60f else 60f) * (if (DangoClient.platform.ctrlDown) 4f else 1f)
        if (detailMode()) {
            detailTarget = (detailTarget + step).coerceIn(0f, detailMax)
            ScreenState.settingsDetailScroll = detailTarget
        } else {
            target = (target + step).coerceIn(0f, maxScroll)
            ScreenState.settingsScroll = target
        }
    }

    fun stopDrag() {
        dragValue = null
        persist()
    }

    /**
     * 交互结束立刻写盘。关闭界面 / 松手 / 回车这类明确的“操作完成”时刻调用，
     * 不依赖 2 秒一次的后台自动保存，避免玩家秒关游戏时丢掉最后一次改动。
     */
    private fun persist() {
        try {
            DangoMusic.instance.configManager.saveIfChanged()
        } catch (_: Throwable) {
            // 写盘失败不应该影响界面操作
        }
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
        try {
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
        } finally {
            persist()
        }
    }

    /** 点到别处时：收起快捷键录制和数值输入 */
    fun onClickElsewhere(hit: Hit?) {
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(area))) commitEdit()
        if (profileEditing && (hit == null || hit.first != profileNameRect.intersect(area))) profileEditing = false
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
                    v is KeyBindValue -> Ctl.KEY
                    k.endsWith("Color") -> Ctl.SWATCH
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
            "voiceEnabled", "voicePushToTalk", "voiceInputGain",
            "voiceOutputVolume", "voiceGate", "voiceBufferMs", "voiceMicMuted",
        ))
        // 一起听 2.0 的语音按键单独成组（独立模块统一管理配置、监听与响应）
        val voiceKeySrc = Src(mm.get(PartyVoiceKeyModule::class.java))
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
            "style" to { v: Any -> KeystrokesHudModule.STYLE_NAMES[(v as IntValue).value.coerceIn(0, KeystrokesHudModule.STYLE_NAMES.size - 1)] },
            "cellAlpha" to ::percent,
            "cellBackgroundColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "pressedColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "pressedTextColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
        ))
        val target = Src(mm.get(TargetInfoModule::class.java), deps = mapOf(
            "anchor" to "follow",
        ), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
            "anchor" to { v: Any -> anchorNames[(v as IntValue).value.coerceIn(0, anchorNames.size - 1)] },
            "style" to { v: Any -> TargetInfoModule.STYLE_NAMES[(v as IntValue).value.coerceIn(0, TargetInfoModule.STYLE_NAMES.size - 1)] },
            "vibeBackgroundColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "vibeTextColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "vibeHealthColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "vibeHealthEndColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "vibeParticleColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
        ))
        // 装备显示：只提供 4 件护甲的图标位置，图标由 legacy 叠加层绘制
        val armor = Src(mm.get(ArmorHudModule::class.java), fmts = mapOf(
            "posX" to ::percent,
            "posY" to ::percent,
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
        // 名牌统计：名牌上方补一行 Hypixel 数据，渲染在 legacy 的 NametagStatsRenderer
        val nt = Src(mm.get(NametagStatsModule::class.java), fmts = mapOf(
            "heightOffset" to { v: Any -> "%.2f 格".format((v as IntValue).value / 100.0) },
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
                "perfEntityFovAngle" to "perfEntityFov",
                "perfNametagDistance" to "perfNametagCulling",
                "perfHideClouds" to "performance",
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
                // 第四批：聊天数据 / 会话
                "adChatIgnoreTeammates" to "adChatEnabled", "adChatCompactBlacklist" to "adChatEnabled",
                "adChatMinStars" to "adChatEnabled", "adChatMinFkdr" to "adChatEnabled",
                "adChatMinSwKdr" to "adChatEnabled", "adChatHistory" to "adChatEnabled",
                "adChatMaxLength" to "adChatEnabled",
                "adSessionIdleSeconds" to "adSessionEnabled", "adSessionKeepWinstreak" to "adSessionEnabled",
                "adSessionMaxMinutes" to "adSessionEnabled", "adSessionAvgRoundSeconds" to "adSessionEnabled",
                "cpsShowLeft" to "cpsDisplay", "cpsShowRight" to "cpsDisplay",
                "cpsWindow" to "cpsDisplay", "cpsInGameOnly" to "cpsDisplay",
                // 第五批：PVP 增强，子项跟随各自的功能开关置灰
                "comboTimeout" to "comboCounter", "comboMinHits" to "comboCounter",
                "comboPos" to "comboCounter", "comboScale" to "comboCounter",
                "comboResetOnHurt" to "comboCounter",
                "kbDuration" to "knockbackArrow", "kbSize" to "knockbackArrow",
                "kbColor" to "knockbackArrow",
                "blindGamma" to "blindGuard", "blindFlashMs" to "blindGuard",
                "markTeammateColor" to "entityMarkers", "markEnemyColor" to "entityMarkers",
                "markDistance" to "entityMarkers", "markShowDistance" to "entityMarkers",
                "markSize" to "entityMarkers",
                "placeAlpha" to "placementPreview",
                "healthThreshold" to "healthAlert", "healthBorder" to "healthAlert",
                "healthPulse" to "healthAlert", "healthColor" to "healthAlert",
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
                "perfEntityFovAngle" to { v: Any -> "${(v as IntValue).value}°" },
                "perfNametagDistance" to { v: Any -> "${(v as IntValue).value} 格" },
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
                "adChatMinStars" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不过滤" else "≥ $it ★" } },
                "adChatMinFkdr" to { v: Any -> (v as FloatValue).value.let { if (it <= 0f) "不过滤" else "%.2f".format(it) } },
                "adChatMinSwKdr" to { v: Any -> (v as FloatValue).value.let { if (it <= 0f) "不过滤" else "%.2f".format(it) } },
                "adChatHistory" to { v: Any -> "${(v as IntValue).value} 条" },
                "adChatMaxLength" to { v: Any -> "${(v as IntValue).value} 字" },
                "adSessionIdleSeconds" to { v: Any -> (v as IntValue).value.let { if (it == 0) "关闭" else "$it 秒" } },
                "adSessionMaxMinutes" to { v: Any -> (v as IntValue).value.let { if (it == 0) "不限" else "$it 分钟" } },
                "adSessionAvgRoundSeconds" to { v: Any -> "${(v as IntValue).value} 秒" },
                "cpsWindow" to { v: Any -> "${(v as IntValue).value} ms" },
                "comboPos" to { v: Any ->
                    arrayOf("左上", "中上", "右下")[(v as IntValue).value.coerceIn(0, 2)]
                },
                "comboTimeout" to { v: Any -> "${(v as IntValue).value} ms" },
                "comboMinHits" to { v: Any -> "${(v as IntValue).value} 次" },
                "comboScale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
                "kbDuration" to { v: Any -> "${(v as IntValue).value} ms" },
                "kbSize" to { v: Any -> "${(v as IntValue).value} px" },
                "blindFlashMs" to { v: Any -> "${(v as IntValue).value} ms" },
                "blindGamma" to { v: Any -> "%.2f".format((v as FloatValue).value) },
                "markDistance" to { v: Any -> "${(v as IntValue).value} 格" },
                "markSize" to { v: Any -> "${(v as IntValue).value} px" },
                "placeAlpha" to { v: Any -> "${(v as IntValue).value}%" },
                "healthThreshold" to { v: Any -> "%.1f 点".format((v as FloatValue).value) },
                "healthBorder" to { v: Any -> "${(v as IntValue).value} px" },
                "healthPulse" to { v: Any -> "%.1f×".format((v as FloatValue).value) },
            ),
        )
        val killIcon = Src(mm.get(KillIconHudModule::class.java), fmts = mapOf(
            "soundVolume" to ::percent,
            "presetIndex" to { v: Any ->
                val i = (v as IntValue).value.coerceIn(0, KillIconHudModule.presetLabels.size - 1)
                KillIconHudModule.presetLabels.getOrElse(i) { "第 ${i + 1} 套" }
            },
            "anchorXPercent" to { v: Any -> "${(v as FloatValue).value.roundToInt()}%" },
            "anchorYPercent" to { v: Any -> "${(v as FloatValue).value.roundToInt()}%" },
            "size" to { v: Any -> "${(v as FloatValue).value.roundToInt()} px" },
        ))
        val languageFmt: (Any) -> String = { v: Any ->
            ChatTranslateSettingsModule.LANGUAGE_NAMES[
                (v as IntValue).value.coerceIn(0, ChatTranslateSettingsModule.LANGUAGE_NAMES.size - 1)
            ]
        }
        // 接收 / 发送两条链路的目标语言分开显示、分开配置
        val chatTranslate = Src(mm.get(ChatTranslateSettingsModule::class.java), fmts = mapOf(
            "incomingLanguage" to languageFmt,
            "outgoingLanguage" to languageFmt,
        ))
        // 反狙击（Meowtils 移植）：反作弊 / 自动黑名单 / 狙击预警
        val antiCheat = Src(mm.get(AntiCheatModule::class.java), fmts = mapOf(
            "violationLevel" to { v: Any ->
                val n = (v as IntValue).value
                if (n == 0) "立即判定" else "$n 次"
            },
        ))
        val autoBlacklist = Src(
            mm.get(AutoBlacklistModule::class.java),
            deps = mapOf(
                "flagAutoblock" to "forFlags",
                "flagNoslow" to "forFlags",
                "flagKillaura" to "forFlags",
                "flagLegitScaffold" to "forFlags",
                "whenReportCommand" to "forReports",
                "whenWdrCommand" to "forReports",
            ),
        )
        val sniperWarning = Src(mm.get(SniperWarningModule::class.java))
        // 移植模块（GlideClient）：药水状态 / 计分板 / 速度计 / 背包 / 慢挥 / 轨迹 / 闪光 / 天气
        val potionStatus = Src(mm.get(PotionStatusHudModule::class.java), fmts = mapOf(
            "scale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        val scoreboard = Src(mm.get(ScoreboardModule::class.java), fmts = mapOf(
            "scale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
            "posX" to ::percent,
            "posY" to ::percent,
            "alpha" to ::percent,
            "domainMode" to { v: Any -> ScoreboardModule.MODE_NAMES[(v as IntValue).value.coerceIn(0, 2)] },
            "domainSpeed" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
            "domainSaturation" to ::percent,
            "domainColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
            "domainFadeColor" to { v: Any -> "#%06X".format((v as IntValue).value and 0xFFFFFF) },
        ))
        val speedometer = Src(mm.get(SpeedometerHudModule::class.java), fmts = mapOf(
            "scale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        val inventoryDisplay = Src(mm.get(InventoryDisplayHudModule::class.java), fmts = mapOf(
            "scale" to { v: Any -> "%.1f 倍".format((v as FloatValue).value) },
            "posX" to ::percent,
            "posY" to ::percent,
        ))
        val slowSwing = Src(mm.get(SlowSwingModule::class.java), fmts = mapOf(
            "delay" to { v: Any -> "${(v as IntValue).value} tick" },
        ))
        val projectileTrail = Src(mm.get(ProjectileTrailModule::class.java), fmts = mapOf(
            "trailType" to { v: Any ->
                ProjectileTrailModule.TYPE_NAMES[
                    (v as IntValue).value.coerceIn(0, ProjectileTrailModule.TYPE_NAMES.size - 1)
                ]
            },
            "power" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "angle" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°" },
        ))
        val shinyPots = Src(mm.get(ShinyPotsModule::class.java))
        val weather = Src(mm.get(WeatherChangerModule::class.java), fmts = mapOf(
            "weather" to { v: Any ->
                WeatherChangerModule.MODE_NAMES[
                    (v as IntValue).value.coerceIn(0, WeatherChangerModule.MODE_NAMES.size - 1)
                ]
            },
            "rainStrength" to ::percent,
            "thunderStrength" to ::percent,
        ))
        val mechvibes = Src(mm.get(MechvibesModule::class.java), fmts = mapOf(
            "type" to { v: Any ->
                MechvibesModule.TYPE_NAMES[
                    (v as IntValue).value.coerceIn(0, MechvibesModule.TYPE_NAMES.size - 1)
                ]
            },
            "keyboardVolume" to ::percent,
            "mouseVolume" to ::percent,
        ))
        val bloodParticles = Src(mm.get(BloodParticlesModule::class.java), fmts = mapOf(
            "amount" to { v: Any -> "${(v as IntValue).value} 个" },
        ))
        val customHeldItems = Src(mm.get(CustomHeldItemsModule::class.java), fmts = mapOf(
            "xSetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "ySetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "zSetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "xScaleSetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "yScaleSetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
            "zScaleSetting" to { v: Any -> "%.2f".format((v as FloatValue).value) },
        ))
        val gale = Src(mm.get(GaleAntiAimModule::class.java), fmts = mapOf(
            "releaseSpeed" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°/tick" },
            "moveFix" to { v: Any ->
                GaleAntiAimModule.MOVE_FIX_NAMES[(v as IntValue).value.coerceIn(0, 2)]
            },
            "spinSpeed" to { v: Any -> "${(v as FloatValue).value.roundToInt()} deg/s" },
        ))
        // 界面段只保留显示效果开关
        val music = listOf(
            Group("参数管理", "档案 · 一键导入导出", player.m, false, emptyList(),
                section = "参数管理", customH = ProfileMetrics.HEIGHT, custom = ::drawProfileManager),
            Group("界面", "液态模糊 · 显示效果", player.m, true, player.take(listOf("liquidBlur")), section = "界面"),
            Group("播放器", "播放", player.m, false, player.take(listOf("volume")), section = "音乐"),
            Group("快捷键", "点击录制 · 支持组合键 · Esc 取消绑定 · 右键恢复默认", player.m, false, player.take(), section = "音乐"),
            Group("歌曲卡片", "悬浮层", card.m, true, card.take(), section = "音乐"),
            nowPlaying,
            Group("灵动岛", "悬浮层", island.m, true, island.take(), section = "音乐"),
            Group("2D 歌词", "横排 · 屏幕歌词", l2d.m, true, l2d.take(), section = "音乐"),
            // 语音大厅：进入大厅后可互相通话（运行层由平台侧实现）
            Group("语音大厅", "进入大厅后可互相通话", module, true, voiceRows + voiceKeySrc.take(), section = "音乐", toggleValue = voiceSwitch),
            Group("组件位置", "整体移动 / 微调", layoutSrc.m, false, layoutSrc.take(), section = "组件位置"),
            Group("信息胶囊", "玩家名 · 帧率 · 延迟 · 时间", info.m, true, info.take(), section = "信息与 HUD"),
            Group("按键显示", "液态玻璃 / 经典方格（Vibe）", keys.m, true, keys.take(), section = "信息与 HUD"),
            Group("敌人状态", "准星锁定玩家后显示头像 / 血量 / 护甲（5 套原有 + Vibe 11 套）", target.m, true, target.take(), section = "信息与 HUD"),
            Group("装备显示", "护甲图标与剩余耐久 · 竖排 / 横排", armor.m, true, armor.take(), section = "信息与 HUD"),
            Group("TAB 统计列表", "按住 TAB 在灵动岛里横向查看在场所有人的数据", tabStats.m, true, tabStats.take(), section = "信息与 HUD"),
            Group("打击颜色", "受伤红闪的颜色与透明度", hit.m, true, hit.take(listOf("palette", "alpha")), section = "信息与 HUD"),
            Group("床提醒", "床被威胁时在灵动岛提示", bw.m, false, bw.take(listOf("bedAlerts", "bedRadius", "bedMode", "queryName")), section = "网络与对战"),
            Group("Hypixel 统计", "可选官方 Key / NetherAPI Key · 留空回退公开源", bw.m, true, bw.take(listOf(
                "hypStats", "hypAutoQuery", "hypCacheMinutes", "hypStyle", "hypDecimals",
                "hypStars", "hypFkdr", "hypWlr", "hypBblr", "hypWinStreak", "hypWins", "hypNetLevel",
                "hypixelKey", "netherApiKey",
            )), section = "网络与对战"),
            Group("名牌统计", "名牌上方显示 BedWars / SkyWars 星级 · 其它场景显示网络等级", nt.m, true, nt.take(), section = "网络与对战"),
            Group("PVP 工具", "打击 · 粒子", fps.m, true, fps.take(listOf(
                "noHitDelay", "noHurtCam", "damageIndicator", "diScale", "moreParticles",
                "mpSharpness", "mpCrit", "mpAlwaysCrit", "mpAlwaysSharpness", "mpSpecial", "mpKillEffect",
                "particlesModifier", "pmKeepBlock",
            )), section = "PVP · 打击"),
            Group("击杀图标预览", "实时预览 · ◀ ▶ 切换预设", killIcon.m, false, emptyList(),
                section = "PVP · 打击", customH = KillPreviewMetrics.HEIGHT, custom = ::drawKillIconPreview),
            Group("击杀图标", "42 套预设 · 屏幕锚点", killIcon.m, true, killIcon.take(), section = "PVP · 打击"),
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
            )), section = "PVP · 性能", gateOnModule = false),
            Group("性能 · 渲染与实体", "子优化都依赖「性能」总开关", fps.m, false, fps.take(listOf(
                "perfIgnoreStands", "perfFastLoad", "perfBatchModel", "perfLowAnimTick", "perfStaticParticle",
                "perfLimitChunks", "perfDownscaleIcons", "perfParticleCulling", "perfCacheSky",
                "perfEntityCulling", "perfCullPlayers", "perfEntityCullingInterval", "perfEntityCullingMin",
                "perfEntityFov", "perfEntityFovAngle", "perfNametagCulling", "perfNametagDistance",
                "perfHideClouds",
                "perfCacheArmorTex", "perfSignCulling", "perfBlockEntityCulling", "perfBlockEntityDistance",
                "perfCustomHudFont", "perfCustomHudFontSize", "perfFastRender",
            )), section = "PVP · 性能", gateOnModule = false),
            Group("性能 · 内容隐藏", "关闭不想要的画面元素 · 改动后自动重建区块", fps.m, false, fps.take(listOf(
                "perfHideGrass", "perfHideDoubleGrass", "perfHideFlowers", "perfHideDoubleFlowers",
                "perfHideFences", "perfHideFenceGates", "perfHideSkulls", "perfHideArmorStands",
                "perfHideItemFrames", "perfHideMapFrames", "perfHideStuckArrows", "perfHideGroundArrows",
                "perfHideLavaParticles", "perfHideSpawnerParticles", "perfHideMobInSpawner", "perfHidePortalParticles",
            )), section = "PVP · 性能", gateOnModule = false),
            Group("性能 · 数值与模式", "上限 / 刷新间隔 / 复用等级", fps.m, false, fps.take(listOf(
                "perfParticlesLimit", "perfChunkUpdateLimit",
                "perfPlayerDist", "perfPassiveDist", "perfHostileDist", "perfMiscDist",
                "perfTextureResolution", "perfReuseChunks", "perfReuseLevel", "perfAdaptiveChunk",
                "perfComposedModel", "perfBatchVanillaFont", "perfFastGlyph", "perfSlowObfuscation",
                "perfMergeShadow", "perfCacheItemModels", "perfFastCollision", "perfFastTextureUpload",
            )), section = "PVP · 性能", gateOnModule = false),
            Group("画质增强", "鱼竿 · 亮度 · 模糊", fps.m, false, fps.take(listOf(
                "betterFishingRod", "rodStringWidth", "fullBright", "motionBlur", "mbMode", "mbStrength",
            )), section = "PVP · 画质"),
            Group("方块与物品", "方块高亮 · 物品物理", fps.m, false, fps.take(listOf(
                "blockOverlay", "boFill", "boFillColor", "boFillAlpha", "boOutline", "boOutlineColor", "boOutlineAlpha",
                "boThrough", "boWidth",
                "itemPhysics", "ipRotate", "ipScale", "ipRollSpeed", "ipSpread", "ipStackGap", "ipRespectCount",
            )), section = "PVP · 方块与物品"),
            Group("PVP 增强 · 连击与击退", "连击统计 · 受击击退方向", fps.m, false, fps.take(listOf(
                "comboCounter", "comboTimeout", "comboMinHits", "comboPos", "comboScale", "comboResetOnHurt",
                "knockbackArrow", "kbDuration", "kbSize", "kbColor",
            )), section = "PVP · 增强"),
            Group("PVP 增强 · 防盲与标记", "亮度保护 · 队友 / 敌人标记", fps.m, false, fps.take(listOf(
                "blindGuard", "blindGamma", "blindFlashMs",
                "entityMarkers", "markTeammateColor", "markEnemyColor", "markDistance", "markShowDistance", "markSize",
            )), section = "PVP · 增强"),
            Group("PVP 增强 · 放置与警报", "放置预览 · 低血量边框", fps.m, false, fps.take(listOf(
                "placementPreview", "placeAlpha",
                "healthAlert", "healthThreshold", "healthBorder", "healthPulse", "healthColor",
            )), section = "PVP · 增强"),
            // 聊天增强独立成组：不跟随容器模块置灰，开关直接驱动 fpsport 的 BetterChat
            Group("聊天增强", "折叠重复消息 · 复制 · 透明度 · 平滑滚动 · 头像", fps.m, false, fps.take(listOf(
                "betterChat", "chatFold", "chatFoldActionBar", "chatCopy", "chatMinLength", "chatOpacity",
                "chatSmooth", "chatAvatars",
            )), section = "PVP · 聊天与点击", gateOnModule = false),
            Group("聊天数据 · 会话", "聊天数据 · 会话统计", fps.m, false, fps.take(listOf(
                "cleanView",
                "adChatEnabled", "adChatIgnoreTeammates", "adChatCompactBlacklist",
                "adChatMinStars", "adChatMinFkdr", "adChatMinSwKdr", "adChatHistory", "adChatMaxLength",
                "adSessionEnabled", "adSessionIdleSeconds", "adSessionKeepWinstreak", "adSessionMaxMinutes",
                "adSessionAvgRoundSeconds",
            )), section = "PVP · 聊天与点击"),
            Group("聊天翻译", "微软翻译 · ✎ 快捷按钮 · 发送前翻译", chatTranslate.m, true, chatTranslate.take(), section = "PVP · 聊天与点击"),
            Group("点击速度", "独立一块显示左键 / 右键 CPS", fps.m, false, fps.take(listOf(
                "cpsDisplay", "cpsShowLeft", "cpsShowRight", "cpsWindow", "cpsInGameOnly",
            )), section = "PVP · 聊天与点击"),
            // ---------------------------------------------------------------- 移植模块
            Group("药水状态", "效果叠加 · 时长计时 · 图标", potionStatus.m, true, potionStatus.take(), section = "PVP · 移植模块"),
            Group("计分板", "Vibe 侧边栏 · 服务器标题替换 · 域名配色", scoreboard.m, true, scoreboard.take(), section = "PVP · 移植模块"),
            Group("速度计", "实时速度 · 仪表曲线", speedometer.m, true, speedometer.take(), section = "PVP · 移植模块"),
            Group("背包显示", "物品格子 · 数量实时刷新", inventoryDisplay.m, true, inventoryDisplay.take(), section = "PVP · 移植模块"),
            Group("慢挥", "降低武器挥砍速度 · 状态提示", slowSwing.m, true, slowSwing.take(), section = "PVP · 移植模块"),
            Group("投掷物轨迹", "抛物线预览 · 力度 / 角度", projectileTrail.m, true, projectileTrail.take(), section = "PVP · 移植模块"),
            Group("闪光药水", "高亮附魔光效", shinyPots.m, true, emptyList(), section = "PVP · 移植模块"),
            Group("天气切换", "晴天 / 雨 / 雷暴 / 雪", weather.m, true, weather.take(), section = "PVP · 移植模块"),
            Group("Mechvibes", "机械键盘 · 鼠标点击音效", mechvibes.m, true, mechvibes.take(), section = "PVP · 移植模块"),
            Group("Blood Particles", "攻击生物时喷红石粒子 · 可选音效", bloodParticles.m, true, bloodParticles.take(), section = "PVP · 移植模块"),
            Group("Custom Held Items", "第一人称手持物品 · 位置与缩放", customHeldItems.m, true, customHeldItems.take(), section = "PVP · 移植模块"),
            Group("Gale AntiAim", "静默旋转 · 本地大陀螺 · 组合键", gale.m, true, gale.take(), section = "PVP · 移植模块"),
            // ---------------------------------------------------------------- 反狙击
            Group("反作弊", "可疑行为检测 · 标记消息 · WDR 按钮", antiCheat.m, true, antiCheat.take(), section = "PVP · 反狙击"),
            Group("自动黑名单", "按标记 / 举报自动拉黑 · UUID 持久化", autoBlacklist.m, true, autoBlacklist.take(), section = "PVP · 反狙击"),
            Group("狙击预警", "装备 · 名字 · 战绩 三重预警", sniperWarning.m, true, sniperWarning.take(), section = "PVP · 反狙击"),
        )
        return listOf(lyrics, music)
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        if (detailMode()) {
            drawDetail(page)
            return
        }

        ui.headerAnim {
            ui.text("设置", page.x, page.y, Theme.PAGE_TITLE_SIZE, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            // 副标题在标签栏左侧就截断，窗口变窄时不会钻到标签底下
            val subMax = (page.right - Metrics.RIGHT_GUTTER - tabBarWidth() - 16f - page.x).coerceAtLeast(0f)
            val sub = ui.trim("右键任意模块打开独立设置页 · 修改即时生效", subMax, 11f, Theme.FONT_SEMIBOLD)
            if (sub.isNotEmpty()) {
                ui.text(sub, page.x, page.y + Metrics.HEADER_SUB_Y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            }
            drawTabs(page)
        }

        val view = LayoutBounds(
            page.x, page.y + Metrics.HEADER_H, page.width,
            page.bottom - (page.y + Metrics.HEADER_H),
        )
        area = view
        val tabIndex = ScreenState.settingsTab.coerceIn(0, tabs.size - 1)

        // 按段分组：每段先画一条占满整行的段标题，段内再按瀑布流排卡片。
        // 列表页的卡片一律只占标题栏高度（内容都在专用设置页里）。
        val gap = Metrics.CARD_GAP
        val rowH = Metrics.ROW_H
        val headH = Metrics.HEAD_H
        // 内容右缘统一让出滚动条余量，左缘与页面标题共用 page.x 这条基准线
        val frameW = (view.width - Metrics.RIGHT_GUTTER).coerceAtLeast(0f)
        // 列数按可用宽度切换；段 / 卡片位置由 layoutFor 缓存，尺寸不变时不重算
        val cols = when {
            frameW >= 1180f -> 3
            frameW >= 620f -> 2
            else -> 1
        }
        val layout = layoutFor(tabIndex, cols, frameW)
        val contentH = layout.contentH
        maxScroll = max(0f, contentH - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
        ScreenState.settingsScroll = target

        ui.beginClip(view)
        val colW = layout.colW
        // 手动计数：withIndex() 每帧都会给每个元素新建 IndexedValue，纯渲染路径上要避开
        var cardIndex = 0
        for (slot in layout.cards) {
            val b = LayoutBounds(view.x + slot.col * (colW + gap), view.y + slot.y - pos, colW, slot.h)
            // 视口外的卡片直接跳过绘制
            if (b.bottom >= view.y && b.y <= view.bottom) ui.itemAnim(cardIndex) { drawGroup(b, slot.group, view, rowH, headH, 0f) }
            cardIndex++
        }
        // 段标题画在卡片之上：滚动时卡片会从它下面穿过，文字始终清晰
        for (s in layout.sections) {
            if (s.name.isEmpty()) continue
            val hb = LayoutBounds(view.x, view.y + s.top - pos, frameW, Metrics.SECTION_H)
            if (hb.bottom >= view.y && hb.y <= view.bottom) drawSectionHeader(hb, s)
        }
        drawStickySection(view, layout)
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

    /**
     * 模块专用设置页：整页只放一个分组。
     *
     * 这里是"新页面"而不是原地展开：进入时由 MusicScreen 重置 pageAt，
     * 页面整体走 [ui.headerAnim] / [ui.itemAnim] 的入场动画；
     * 行渲染完全复用 [drawGroup]（open 恒为 1），所以控件、默认值、依赖置灰、
     * 快捷键录制、手输数值、击杀图标预览与档案管理都和原来完全一致。
     */
    private fun drawDetail(page: LayoutBounds) {
        val key = ScreenState.settingsDetail
        val tab = ScreenState.settingsDetailTab.coerceIn(0, tabs.size - 1)
        val g = if (key == null) null else tabs[tab].firstOrNull { groupKey(it) == key }
        if (g == null) {
            // 分组已不存在（配置迁移 / 模块被移除）：直接退回列表，不留空白页
            ScreenState.settingsDetail = null
            ScreenState.page = Page.SETTINGS
            return
        }

        ui.headerAnim {
            val chipW = max(54f, ui.width("‹ 返回", 11f, Theme.FONT_SEMIBOLD) + 20f)
            val chip = LayoutBounds(page.x, page.y + 3f, chipW, 22f)
            ui.panel(chip, 11f)
            ui.text(
                "‹ 返回", chip.x + 10f, Metrics.textY(chip.y + chip.height / 2f, 11f),
                11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
            )
            ui.hits += chip to { backToList() }

            val textX = chip.right + 12f
            val textW = (page.right - Metrics.RIGHT_GUTTER - textX).coerceAtLeast(0f)
            val cy = chip.y + chip.height / 2f
            val title = ui.trim(g.title, textW, 15f, Theme.FONT_SEMIBOLD)
            ui.text(title, textX, Metrics.textY(cy, 15f), 15f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val subX = textX + ui.width(title, 15f, Theme.FONT_SEMIBOLD) + Metrics.TITLE_GAP
            val subMax = page.right - Metrics.RIGHT_GUTTER - subX
            if (g.section.isNotEmpty()) {
                val crumb = ui.trim(g.section, subMax, 10f)
                if (crumb.isNotEmpty()) {
                    ui.text(crumb, subX, Metrics.textY(cy, 10f), 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
                }
            } else if (subMax > 8f) {
                ui.text(ui.trim(g.sub, subMax, 10f), subX, Metrics.textY(cy, 10f), 10f, Theme.TEXT_TERTIARY)
            }
        }

        val view = LayoutBounds(
            page.x, page.y + Metrics.HEADER_H, page.width,
            page.bottom - (page.y + Metrics.HEADER_H),
        )
        area = view
        val rowH = Metrics.ROW_H
        val headH = Metrics.HEAD_H
        val frameW = (view.width - Metrics.RIGHT_GUTTER).coerceAtLeast(0f)
        val cardH = headH + g.rows.size * rowH + g.customH + Metrics.CARD_BOTTOM
        detailMax = max(0f, cardH - view.height)
        detailTarget = detailTarget.coerceIn(0f, detailMax)
        detailPos = detailPos.coerceIn(0f, detailMax)
        ScreenState.settingsDetailScroll = detailTarget

        ui.beginClip(view)
        ui.itemAnim(0) {
            drawGroup(
                LayoutBounds(view.x, view.y - detailPos, frameW, cardH),
                g, view, rowH, headH, 1f, navEnabled = false,
            )
        }
        ui.endClip()

        if (detailMax > 0f) {
            val th = max(30f, view.height * view.height / cardH)
            val ty = view.y + (view.height - th) * (detailPos / detailMax)
            ui.ctx.drawShape(
                LayoutBounds(view.right - Metrics.SCROLLBAR_W, ty, Metrics.SCROLLBAR_W, th),
                ShapeStyle(Color(1f, 1f, 1f, 0.30f), Metrics.SCROLLBAR_W / 2f),
            )
        }
    }

    /** 标签名固定不变，提出来避免 drawTabs 每帧新建 list */
    private val tabNames = listOf("歌词", "音乐与 HUD")

    private fun tabBarWidth(): Float = Metrics.TAB_W * tabNames.size + Metrics.TAB_PAD * 2

    /** 标签：Lyrics / Music & HUD；右缘与内容区对齐，竖直方向与标题块居中 */
    private fun drawTabs(page: LayoutBounds) {
        val barW = tabBarWidth()
        val bar = LayoutBounds(
            page.right - Metrics.RIGHT_GUTTER - barW, page.y + Metrics.TAB_PAD,
            barW, Metrics.TAB_H,
        )
        ui.panel(bar, 11f, interactive = false)
        tabNames.forEachIndexed { i, n ->
            val b = LayoutBounds(
                bar.x + Metrics.TAB_PAD + i * Metrics.TAB_W, bar.y + Metrics.TAB_PAD,
                Metrics.TAB_W, Metrics.TAB_H - Metrics.TAB_PAD * 2,
            )
            val on = i == ScreenState.settingsTab
            when {
                on -> ui.ctx.drawShape(b, ShapeStyle(RED, Metrics.TAB_RADIUS))
                ui.pressed(b) -> ui.card(b, Theme.CARD_PRESS, Metrics.TAB_RADIUS)
                ui.hover(b) -> ui.card(b, Theme.CARD_HOVER, Metrics.TAB_RADIUS)
            }
            val w = ui.width(n, 12f, Theme.FONT_SEMIBOLD)
            ui.text(n, b.x + (b.width - w) / 2f, Metrics.textY(b.y + b.height / 2f, 12f), 12f,
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

    /** 段标题：红色小竖条 + 13 px 粗体段名 + 细分隔线 + 右侧模块计数 */
    private fun drawSectionHeader(b: LayoutBounds, s: Section) {
        val cy = b.y + Metrics.SECTION_H / 2f
        ui.ctx.drawShape(LayoutBounds(b.x, cy - 8f, 3f, 16f), ShapeStyle(RED, 1.5f))
        ui.text(s.name, b.x + Metrics.CARD_PAD, Metrics.textY(cy, 13f), 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        // 分隔线从段名右缘拉到计数文字左侧，两端各留 12px 呼吸
        val lx = b.x + Metrics.CARD_PAD + s.titleW + 14f
        val rx = b.right - s.metaW
        if (rx - lx > 28f) {
            ui.ctx.drawShape(LayoutBounds(lx, cy - 0.5f, rx - lx - 12f, 1f), ShapeStyle(Theme.HAIRLINE_HOVER))
        }
        ui.text(s.meta, rx, Metrics.textY(cy, 10f), 10f, Theme.TEXT_TERTIARY)
    }

    /**
     * 吸顶段标题：滚到某一段中间时把段名固定在内容区顶部，并垫一层同色过渡带，
     * 避免段名和下面滚过的卡片糊在一起（只在列表页出现）。
     */
    private fun drawStickySection(view: LayoutBounds, layout: PageLayout) {
        if (pos <= 0.5f) return
        var active: Section? = null
        for (s in layout.sections) {
            if (s.name.isEmpty()) continue
            if (view.y + s.top - pos <= view.y + 0.5f) active = s else break
        }
        val s = active ?: return
        val frameW = (view.width - Metrics.RIGHT_GUTTER).coerceAtLeast(0f)
        val b = LayoutBounds(view.x, view.y, frameW, Metrics.SECTION_H)
        ui.ctx.drawShape(b, ShapeStyle(Color(0.06f, 0.06f, 0.075f, Metrics.STICKY_SCRIM), 0f))
        // 下缘三段渐隐，压暗层与背景自然过渡
        for (i in 0 until 3) {
            ui.ctx.drawShape(
                LayoutBounds(b.x, b.bottom + i * 3f, b.width, 3f),
                ShapeStyle(Color(0.06f, 0.06f, 0.075f, Metrics.STICKY_SCRIM * (2 - i) / 6f), 0f),
            )
        }
        drawSectionHeader(b, s)
    }

    /**
     * 一个分组卡片。
     *
     * [open] 为 1 时画完整行列表（专用设置页）；列表页恒为 0，只画标题栏。
     * [navEnabled] 为 true 时整张卡片登记右键热区：右键立刻跳到专用设置页。
     */
    private fun drawGroup(
        b: LayoutBounds, g: Group, clip: LayoutBounds, rowH: Float, headH: Float, open: Float,
        navEnabled: Boolean = true,
    ) {
        val ctx = ui.ctx
        // 每张卡片统一玻璃材质：列表页可悬停提亮 / 按下加深，专用设置页保持静态
        ui.panel(b, Theme.RADIUS_CARD, interactive = navEnabled)
        val x = b.x + Metrics.CARD_PAD
        val iw = b.width - Metrics.CARD_PAD * 2
        // 标题栏内部元素统一按标题栏中线居中
        val headCy = b.y + headH / 2f
        // 标题右侧要为总开关 / 「右键设置」提示留位，文字按可用宽度截断，窄列也不会压到控件
        val hintShown = navEnabled && open <= 0.001f
        val hw = if (hintShown) hintTextW else 0f
        val actionW = if (g.toggle) Metrics.TOGGLE_W + Metrics.HEAD_ACTION_GAP else 0f
        val reserve = actionW + (if (hintShown) hw + Metrics.HEAD_ACTION_GAP else 0f)
        val titleMax = (iw - reserve).coerceAtLeast(0f)
        val shownTitle = ui.trim(g.title, titleMax, 13f, Theme.FONT_SEMIBOLD)
        val titleW = ui.width(shownTitle, 13f, Theme.FONT_SEMIBOLD)
        ui.text(
            shownTitle, x, Metrics.textY(headCy, 13f), 13f,
            // 模块关闭（整组置灰）时标题降一档，扫一眼就能看出哪些没开
            if (g.gateOnModule && !g.owner.enabled) Theme.TEXT_SECONDARY else Theme.TEXT_PRIMARY,
            Theme.FONT_SEMIBOLD,
        )
        val subX = x + titleW + Metrics.TITLE_GAP
        val subMax = x + iw - reserve - subX
        if (subMax > 8f) {
            ui.text(ui.trim(g.sub, subMax, 10f), subX, Metrics.textY(headCy, 10f), 10f, Theme.TEXT_TERTIARY)
        }
        if (g.toggle) {
            val box = LayoutBounds(
                b.right - Metrics.CARD_PAD - Metrics.TOGGLE_W, headCy - Metrics.TOGGLE_H / 2f,
                Metrics.TOGGLE_W, Metrics.TOGGLE_H,
            )
            val v = g.toggleValue
            if (v != null) {
                ui.toggleSwitch(box, v.get(), clip) {
                    v.toggle()
                    persist()
                }
            } else {
                val m = g.owner
                ui.toggleSwitch(box, m.enabled, clip) {
                    m.enabled = !m.enabled
                    persist()
                }
            }
        }
        // 右键提示：整张卡片都是热区，点了直接进专用设置页
        if (hintShown) {
            val hint = "右键设置"
            ui.text(hint, b.right - Metrics.CARD_PAD - actionW - hw, Metrics.textY(headCy, 9.5f), 9.5f, Theme.TEXT_TERTIARY)
        }
        if (navEnabled) {
            // 热区跟进入场位移走，动画途中右键也能准确命中当前卡片
            val hit = LayoutBounds(
                b.x + ui.mt.shiftX, b.y + ui.mt.shiftY, b.width, b.height,
            ).intersect(clip)
            openHits += hit to { openDetail(g) }
        }

        if (open <= 0.001f) return
        val bodyTop = b.y + headH
        val bodyH = b.height - headH - Metrics.CARD_BOTTOM
        if (bodyH <= 0f) return
        // 标题栏与行列表之间一条细分隔线，信息层级更清楚
        ctx.drawShape(LayoutBounds(x, bodyTop - 0.5f, iw, 1f), ShapeStyle(Theme.HAIRLINE))
        // 动画期间只显示已经"长出来"的部分；裁剪要跟着入场位移，避免错位
        val bodyClip = LayoutBounds(
            b.x + ui.mt.shiftX, bodyTop + ui.mt.shiftY, b.width, bodyH,
        ).intersect(clip)
        // 热区用未平移的区域：HitList 登记时会自动叠加当前入场位移，
        // 若这里再传已经平移过的 bodyClip 就会造成「双重位移」。
        val hitClip = LayoutBounds(b.x, bodyTop, b.width, bodyH).intersect(clip)
        ui.beginClip(bodyClip)
        ctx.pushOpacity(open)
        var y = bodyTop
        val moduleOff = g.gateOnModule && !g.owner.enabled
        var rowIndex = -1
        for (r in g.rows) {
            rowIndex++
            // 行级裁剪：卡片很长时，滚动到视口外的行直接跳过绘制
            if (y >= bodyClip.bottom) break
            if (y + rowH >= bodyClip.y) {
                // 分隔线只画在相邻两行之间，标题栏下方不再多一条
                if (rowIndex > 0) ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Theme.SEPARATOR))
                val dim = moduleOff || (r.dep != null && !r.dep.get())
                if (dim) ctx.pushOpacity(0.4f)
                val hitBase = ui.hits.size
                drawRow(LayoutBounds(x, y, iw, rowH), r, hitClip, dim)
                if (dim) {
                    ctx.popOpacity()
                    // 置灰行只做展示：把 drawRow 刚登记的点击热区丢掉，避免用户改到"不可用"的值
                    while (ui.hits.size > hitBase) ui.hits.removeAt(ui.hits.size - 1)
                }
            }
            y += rowH
        }
        // 行列表下方的自定义区域（击杀图标实时预览 / 参数管理）
        val custom = g.custom
        if (custom != null && g.customH > 0f && y < bodyClip.bottom) {
            custom.invoke(LayoutBounds(x, y, iw, g.customH), hitClip)
        }
        ctx.popOpacity()
        // ui 只有一层 scissor：画完卡片内容后恢复外层的页面裁剪
        ui.beginClip(clip)
    }

    private fun drawRow(b: LayoutBounds, r: Row, clip: LayoutBounds, dim: Boolean) {
        // 控件右缘统一贴齐内容右缘；窄卡片（小窗口 / 高 GUI 缩放）时控件最多占 55%，剩下的留给标签
        val maxCtl = (b.width * 0.55f).coerceAtLeast(Metrics.TOGGLE_W)
        var swatchSize = Metrics.SWATCH
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> Metrics.TOGGLE_W
            Ctl.SLIDER -> min(Metrics.SLIDER_W, maxCtl)
            Ctl.SWATCH -> {
                val n = swatchesFor(r.key).size
                // 色块数量固定，宽度不够就整体等比缩小，绝不越过标签列
                swatchSize = min(Metrics.SWATCH, ((maxCtl - Metrics.SWATCH_GAP * (n - 1)) / n).coerceAtLeast(10f))
                swatchSize * n + Metrics.SWATCH_GAP * (n - 1)
            }
            Ctl.KEY, Ctl.BUTTON -> min(Metrics.KEY_W, maxCtl)
            Ctl.TEXT -> min(Metrics.FIELD_W, maxCtl)
        }
        val textW = (b.width - ctlW - Metrics.LABEL_GAP).coerceAtLeast(0f)
        val cy = b.y + b.height / 2f
        // 整行悬停高亮：控件本身有反馈，行级再给一层，指向性更明确
        if (!dim && ui.hover(b)) {
            ui.card(
                LayoutBounds(b.x - Metrics.ROW_HOVER_INSET, b.y + 3f, b.width + Metrics.ROW_HOVER_INSET * 2f, b.height - 6f),
                Theme.CARD_HOVER, 10f,
            )
        }
        // 快捷键冲突：把描述临时换成冲突来源，并用警示色显示
        val conflict = if (r.ctl == Ctl.KEY) conflictFor(r.value as KeyBindValue) else null
        val desc = if (conflict != null) "冲突：$conflict" else r.desc
        // 有描述时两行文案整体按行中线居中；没有描述时标签单独居中对齐，避免文字偏高
        val labelCy = if (desc.isEmpty()) cy else cy - Metrics.LINE_GAP / 2f
        ui.text(ui.trim(r.label, textW, 12f, Theme.FONT_MEDIUM), b.x, Metrics.textY(labelCy, 12f), 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (desc.isNotEmpty()) {
            ui.text(
                ui.trim(desc, textW, 9.5f), b.x, Metrics.textY(cy + Metrics.LINE_GAP / 2f, 9.5f), 9.5f,
                if (conflict != null) Color(1f, 0.68f, 0.24f, 1f) else Theme.TEXT_TERTIARY,
            )
        }
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                ui.toggleSwitch(
                    LayoutBounds(cx, cy - Metrics.TOGGLE_H / 2f, Metrics.TOGGLE_W, Metrics.TOGGLE_H),
                    v.get(), clip,
                ) {
                    v.toggle()
                    persist()
                }
            }
            Ctl.SLIDER -> drawSlider(r, clip, cx, cy, ctlW)
            Ctl.SWATCH -> {
                val v = r.value as IntValue
                val radius = swatchSize / 2f
                swatchesFor(r.key).forEachIndexed { i, col ->
                    if (i > v.maxRange) return@forEachIndexed
                    val sb = LayoutBounds(
                        cx + i * (swatchSize + Metrics.SWATCH_GAP), cy - swatchSize / 2f,
                        swatchSize, swatchSize,
                    )
                    val selected = v.value == i
                    if (selected) {
                        ui.ctx.drawShape(sb.expand(3f, 3f), ShapeStyle(Color(0f, 0f, 0f, 0f), radius + 3f, 1.5f, Theme.TEXT_PRIMARY))
                    } else if (ui.hover(sb.expand(2f, 2f))) {
                        // 未选中色块悬停时浮一圈浅环，点之前就知道会选中哪一块
                        ui.ctx.drawShape(sb.expand(2f, 2f), ShapeStyle(Color(0f, 0f, 0f, 0f), radius + 2f, 1f, Color(1f, 1f, 1f, 0.38f)))
                    }
                    ui.ctx.drawShape(sb, ShapeStyle(col, radius))
                    // 黑色 / 深色色块在深色卡片上会看不见，补一圈浅描边
                    if (col.r + col.g + col.b < 0.5f) {
                        ui.ctx.drawShape(sb, ShapeStyle(Color(0f, 0f, 0f, 0f), radius, 1f, Color(1f, 1f, 1f, 0.45f)))
                    }
                    ui.hits += sb.expand(4f, 4f).intersect(clip) to {
                        v.setValue(i)
                        persist()
                    }
                }
            }
            Ctl.KEY -> drawKeyButton(r.value as KeyBindValue, clip, cx, cy, ctlW)
            Ctl.TEXT -> drawTextField(r, clip, cx, cy, ctlW)
            Ctl.BUTTON -> {
                val bb = LayoutBounds(cx, cy - Metrics.KEY_H / 2f, ctlW, Metrics.KEY_H)
                val hb = bb.intersect(clip)
                // 悬停提亮、按下加深，和开关 / 输入框共用一套圆角
                ui.card(bb, when {
                    ui.pressed(hb) -> Theme.CARD_PRESS
                    ui.hover(bb) -> Theme.CARD_HOVER
                    else -> Theme.CARD
                }, Metrics.CONTROL_RADIUS)
                val s = ui.trim(r.fmt(r.value), bb.width - 12f, 11f, Theme.FONT_SEMIBOLD)
                ui.text(
                    s, bb.x + (bb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f,
                    Metrics.textY(cy, 11f), 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
                )
                val action = r.action
                if (action != null) ui.hits += hb to action
            }
        }
    }

    /** 文本框：复用数值输入框的外观（卡片 + 红色描边 + 闪烁光标），Key 用等长 * 号显示 */
    private fun drawTextField(r: Row, clip: LayoutBounds, cx: Float, cy: Float, width: Float) {
        val ctx = ui.ctx
        val value = r.value as StringValue
        val box = LayoutBounds(cx, cy - Metrics.FIELD_H / 2f, width, Metrics.FIELD_H)
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
            // 悬停提亮：弱化状态下也能看出这块可以点进去改
            ui.card(box, if (ui.hover(box)) Theme.CARD_HOVER else Theme.CARD, Metrics.FIELD_RADIUS)
        }
        val shown = ui.trim(text, box.width - Metrics.FIELD_INSET * 2f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(shown, box.x + Metrics.FIELD_INSET, Metrics.textY(cy, 11f), 11f, color, Theme.FONT_SEMIBOLD)
        ui.hits += box.intersect(clip) to { startEdit(r) }
    }

    private fun drawSlider(r: Row, clip: LayoutBounds, cx: Float, cy: Float, width: Float) {
        val ctx = ui.ctx
        // 槽内固定三段：轨道 → 空隙 → 数值框；整体宽度随卡片收窄，右缘与其它控件贴齐
        val valueW = min(Metrics.SLIDER_VALUE_W, (width * 0.32f).coerceAtLeast(38f))
        val trackW = (width - valueW - Metrics.SLIDER_VALUE_GAP).coerceAtLeast(36f)
        val track = LayoutBounds(cx, cy - 2f, trackW, 4f)
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
            cx + trackW + Metrics.SLIDER_VALUE_GAP,
            cy - Metrics.FIELD_H / 2f, valueW, Metrics.FIELD_H,
        )
        if (editRow === r) {
            editRect = vb
            ui.card(vb, Theme.CARD_HOVER, Metrics.FIELD_RADIUS)
            ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.FIELD_RADIUS, 1f, RED))
            val et = edit.display(caretVisible())
            ui.text(
                et, vb.right - 6f - ui.width(et, 11f, Theme.FONT_SEMIBOLD), Metrics.textY(cy, 11f),
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
            ui.text(s, vb.right - 6f - sw, Metrics.textY(cy, 11f), 11f,
                if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        ui.hits += vb.intersect(clip) to { startEdit(r) }
        ui.hits += track.expand(8f, 12f).intersect(clip) to {
            dragValue = r.value
            dragTrack = track
            applyDrag()
        }
    }

    /**
     * 组合键按钮：
     * - 左键开始录制，录制中实时显示已经按下的修饰键；
     * - 右键恢复该字段的默认键位；
     * - 与其它模块 / 本模块其它快捷键重复时描边变黄，描述行显示冲突来源。
     */
    private fun drawKeyButton(v: KeyBindValue, clip: LayoutBounds, cx: Float, cy: Float, width: Float) {
        val kb = LayoutBounds(cx, cy - Metrics.KEY_H / 2f, width, Metrics.KEY_H)
        val hb = kb.intersect(clip)
        val binding = bindingKey === v
        val conflict = conflictFor(v)
        val warn = Color(1f, 0.68f, 0.24f, 1f)
        // 录制 / 悬停 / 按下三态共用一套圆角，和输入框、普通按钮保持一致
        ui.card(kb, when {
            binding -> RED.copy(a = 0.22f)
            ui.pressed(hb) -> Theme.CARD_PRESS
            ui.hover(kb) -> Theme.CARD_HOVER
            else -> Theme.CARD
        }, Metrics.CONTROL_RADIUS)
        when {
            binding -> ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.CONTROL_RADIUS, 1f, RED))
            conflict != null -> ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.CONTROL_RADIUS, 1f, warn))
        }
        val held = if (binding) heldModifiers() else emptyList()
        val name = when {
            binding && held.isNotEmpty() ->
                held.joinToString(" + ") { KeyBindValue.labelOf(it) } + " + 请按键"
            binding -> "请按键"
            !v.isSet -> "无"
            else -> v.displayName()
        }
        val s = ui.trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
        val color = when {
            binding -> RED
            !v.isSet -> Theme.TEXT_TERTIARY
            conflict != null -> warn
            else -> Theme.TEXT_PRIMARY
        }
        ui.text(s, kb.x + (kb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f, Metrics.textY(cy, 11f), 11f, color, Theme.FONT_SEMIBOLD)
        keyButtons += hb
        ui.hits += hb to { bindingKey = if (bindingKey === v) null else v }
        resetKeyHits += hb to {
            v.resetDefault()
            persist()
        }
    }

    /** 录制时读取当前按着的修饰键（Shift / Ctrl / Alt / Win），用于组合键与实时预览 */
    private fun heldModifiers(): List<Int> {
        val p = DangoClient.platform
        val out = ArrayList<Int>(4)
        val pairs = intArrayOf(
            Keys.LSHIFT, Keys.RSHIFT,
            Keys.LCONTROL, Keys.RCONTROL,
            Keys.LMENU, Keys.RMENU,
            219, 220,
        )
        var i = 0
        while (i < pairs.size) {
            val left = pairs[i]
            val right = pairs[i + 1]
            val leftDown = try { p.isKeyDown(left) } catch (_: Throwable) { false }
            val rightDown = if (leftDown) false else try { p.isKeyDown(right) } catch (_: Throwable) { false }
            when {
                leftDown -> out += left
                rightDown -> out += right
            }
            i += 2
        }
        return out
    }

    /**
     * 跨模块冲突检测：主键 + 修饰键集合互相包含即冲突。
     * 返回第一个冲突来源（"模块 · 设置项"），没有冲突返回 null。
     */
    private fun conflictFor(v: KeyBindValue): String? {
        if (!v.isSet) return null
        allBinds.firstOrNull { it.value !== v && it.value.conflictsWith(v) }?.let { return it.label }
        return KeyBindRegistry.vanillaNameFor(v.primary)?.let { "原版 $it" }
    }

    // ---------------------------------------------------------------- 击杀图标预览 / 参数管理

    /** 击杀图标预览区尺寸 */
    private object KillPreviewMetrics {
        const val HEIGHT = 150f
        const val HEAD = 30f
        const val ARROW = 26f
    }

    /** 参数管理卡片尺寸（3 行档案 + 输入行 + 底部操作行） */
    private object ProfileMetrics {
        const val HEIGHT = 194f
        const val FIELD_H = 26f
        const val ROW_H = 28f
        const val LIST_ROWS = 3
        const val BUTTON_W = 72f
    }

    /**
     * 击杀图标实时预览：顶部 ◀ 预设名 ▶，下方留出一块区域交给平台层用真实渲染器画图标。
     * 平台不支持时画一行提示，不会空着。
     */
    private fun drawKillIconPreview(b: LayoutBounds, clip: LayoutBounds) {
        val ctx = ui.ctx
        val module = try {
            DangoMusic.instance.moduleManager.get(KillIconHudModule::class.java)
        } catch (_: Throwable) {
            return
        }
        ctx.drawShape(b, ShapeStyle(Color(0f, 0f, 0f, 0.30f), 10f))
        val labels = KillIconHudModule.presetLabels
        val count = max(1, labels.size)
        val idx = module.presetIndex.value.coerceIn(0, count - 1)
        val headCy = b.y + KillPreviewMetrics.HEAD / 2f

        val prev = LayoutBounds(b.x + 8f, headCy - KillPreviewMetrics.ARROW / 2f, KillPreviewMetrics.ARROW, KillPreviewMetrics.ARROW)
        val next = LayoutBounds(b.right - 8f - KillPreviewMetrics.ARROW, prev.y, KillPreviewMetrics.ARROW, KillPreviewMetrics.ARROW)
        drawArrow(prev, "◀", clip) {
            module.presetIndex.setValue((idx - 1 + count) % count)
            persist()
        }
        drawArrow(next, "▶", clip) {
            module.presetIndex.setValue((idx + 1) % count)
            persist()
        }
        val name = labels.getOrNull(idx) ?: "第 ${idx + 1} 套"
        val title = "第 ${idx + 1}/$count 套 · $name"
        val shownTitle = ui.trim(title, b.width - KillPreviewMetrics.ARROW * 2f - 24f, 11f, Theme.FONT_SEMIBOLD)
        val tw = ui.width(shownTitle, 11f, Theme.FONT_SEMIBOLD)
        ui.text(
            shownTitle,
            b.x + (b.width - tw) / 2f, Metrics.textY(headCy, 11f), 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD,
        )

        val area = LayoutBounds(b.x + 8f, b.y + KillPreviewMetrics.HEAD, b.width - 16f,
            b.height - KillPreviewMetrics.HEAD - 6f)
        ctx.drawShape(area, ShapeStyle(Color(1f, 1f, 1f, 0.035f), 8f))
        val px = (area.x * ui.scale).toInt()
        val py = (area.y * ui.scale).toInt()
        val pw = (area.width * ui.scale).toInt()
        val ph = (area.height * ui.scale).toInt()
        val drawn = try {
            // 多套一层裁剪，避免图标动画的光晕溢出预览框
            ctx.pushClip(area)
            try {
                DangoClient.platform.renderKillIconPreview(px, py, pw, ph, idx)
            } finally {
                ctx.popClip()
            }
        } catch (_: Throwable) {
            false
        }
        if (!drawn) {
            val hint = "当前平台不支持实时预览"
            ui.text(hint, area.x + (area.width - ui.width(hint, 10f)) / 2f, Metrics.textY(area.y + area.height / 2f, 10f),
                10f, Theme.TEXT_TERTIARY)
        }
    }

    private fun drawArrow(b: LayoutBounds, glyph: String, clip: LayoutBounds, action: () -> Unit) {
        val hb = b.intersect(clip)
        ui.card(b, when {
            ui.pressed(hb) -> Theme.CARD_PRESS
            ui.hover(b) -> Theme.CARD_HOVER
            else -> Theme.CARD
        }, Metrics.CONTROL_RADIUS)
        ui.icon(glyph, b.x + b.width / 2f, b.y + b.height / 2f, 11f, Theme.TEXT_PRIMARY)
        ui.hits += hb to action
    }

    /**
     * 参数管理：档案名输入 + 保存、档案列表（载入 / 删除）、一键导出 / 导入。
     * 全部操作走 [me.sorihiko.dango.config.ConfigManager]，与自动存盘共用同一份配置。
     */
    private fun drawProfileManager(b: LayoutBounds, clip: LayoutBounds) {
        val cm = try {
            DangoMusic.instance.configManager
        } catch (_: Throwable) {
            return
        }
        // 档案列表每 2 秒刷新一次；操作完成后会强制刷新
        val now = System.currentTimeMillis()
        if (now - profileNamesAt > 2000L) refreshProfiles(cm)

        // 第一行：档案名 + 保存
        val field = LayoutBounds(b.x, b.y + 2f, b.width - ProfileMetrics.BUTTON_W - 8f, ProfileMetrics.FIELD_H)
        profileNameRect = field
        ui.card(field, if (profileEditing || ui.hover(field)) Theme.CARD_HOVER else Theme.CARD, Metrics.CONTROL_RADIUS)
        if (profileEditing) {
            ui.ctx.drawShape(field, ShapeStyle(Color(0f, 0f, 0f, 0f), Metrics.CONTROL_RADIUS, 1f, RED))
        }
        val shown = if (profileEditing) {
            profileName.display(caretVisible())
        } else {
            profileName.text.ifEmpty { "输入档案名" }
        }
        ui.text(
            ui.trim(shown, field.width - 14f, 11f, Theme.FONT_SEMIBOLD), field.x + 7f,
            Metrics.textY(field.y + field.height / 2f, 11f), 11f,
            if (profileName.text.isEmpty() && !profileEditing) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY,
            Theme.FONT_SEMIBOLD,
        )
        ui.hits += field.intersect(clip) to {
            profileEditing = true
            commitEdit()
            screen.blurTextFields()
            bindingKey = null
            profileName.selectAll()
        }
        drawSmallButton(
            LayoutBounds(b.right - ProfileMetrics.BUTTON_W, b.y + 2f, ProfileMetrics.BUTTON_W, ProfileMetrics.FIELD_H),
            "保存当前", clip,
        ) {
            val name = profileName.text.trim()
            if (name.isEmpty()) {
                setStatus("先输入档案名")
            } else if (cm.saveProfile(name)) {
                profileName.clear()
                profileEditing = false
                refreshProfiles(cm, true)
                setStatus("已保存：$name")
            } else {
                setStatus("保存失败，请查看日志")
            }
        }

        // 档案列表：最多显示 3 行，超出时底部提示总数
        val listTop = b.y + ProfileMetrics.FIELD_H + 8f
        val rows = profileNames.take(ProfileMetrics.LIST_ROWS)
        if (rows.isEmpty()) {
            ui.text(
                "还没有档案：输入名字后点「保存当前」", b.x, Metrics.textY(listTop + ProfileMetrics.ROW_H / 2f, 10f),
                10f, Theme.TEXT_TERTIARY,
            )
        }
        rows.forEachIndexed { i, name ->
            val ry = listTop + i * ProfileMetrics.ROW_H
            val row = LayoutBounds(b.x, ry, b.width, ProfileMetrics.ROW_H - 2f)
            if (i % 2 == 0) ui.ctx.drawShape(row, ShapeStyle(Color(1f, 1f, 1f, 0.03f), 6f))
            ui.text(ui.trim(name, b.width - ProfileMetrics.BUTTON_W * 2f - 18f, 10.5f, Theme.FONT_MEDIUM),
                b.x + 6f, Metrics.textY(ry + row.height / 2f, 10.5f), 10.5f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            drawSmallButton(
                LayoutBounds(b.right - ProfileMetrics.BUTTON_W * 2f - 4f, ry + 1f, ProfileMetrics.BUTTON_W, ProfileMetrics.ROW_H - 4f),
                "载入", clip,
            ) {
                if (cm.loadProfile(name)) {
                    refreshProfiles(cm, true)
                    setStatus("已载入：$name")
                } else {
                    setStatus("载入失败：$name")
                }
            }
            drawSmallButton(
                LayoutBounds(b.right - ProfileMetrics.BUTTON_W, ry + 1f, ProfileMetrics.BUTTON_W, ProfileMetrics.ROW_H - 4f),
                "删除", clip,
            ) {
                if (cm.deleteProfile(name)) {
                    refreshProfiles(cm, true)
                    setStatus("已删除：$name")
                } else {
                    setStatus("删除失败：$name")
                }
            }
        }
        if (profileNames.size > ProfileMetrics.LIST_ROWS) {
            ui.text(
                "共 ${profileNames.size} 份档案，只显示前 ${ProfileMetrics.LIST_ROWS} 份",
                b.x + 6f, Metrics.textY(listTop + ProfileMetrics.LIST_ROWS * ProfileMetrics.ROW_H + 6f, 9f),
                9f, Theme.TEXT_TERTIARY,
            )
        }

        // 底部：一键导出 / 一键导入 + 操作结果
        val by = b.bottom - 28f
        drawSmallButton(LayoutBounds(b.x, by, 84f, 24f), "导出当前", clip) {
            val path = cm.exportCurrent()
            setStatus(if (path != null) "已导出到 exports 目录" else "导出失败，请查看日志")
        }
        drawSmallButton(LayoutBounds(b.x + 90f, by, 84f, 24f), "导入最新", clip) {
            setStatus(if (cm.importLatestExport()) "已导入最新的导出文件" else "没有可导入的导出文件")
        }
        val status = profileStatus
        if (status.isNotEmpty() && now - profileStatusAt < 5000L) {
            val sx = b.x + 182f
            ui.text(ui.trim(status, b.right - sx, 9.5f), sx, Metrics.textY(by + 12f, 9.5f), 9.5f, RED)
        }
    }

    private fun drawSmallButton(b: LayoutBounds, label: String, clip: LayoutBounds, action: () -> Unit) {
        ui.card(b, if (ui.hover(b)) Theme.CARD_HOVER else Theme.CARD, 6f)
        val w = ui.width(label, 10f, Theme.FONT_SEMIBOLD)
        ui.text(label, b.x + (b.width - w) / 2f, Metrics.textY(b.y + b.height / 2f, 10f), 10f,
            Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.hits += b.intersect(clip) to action
    }

    private fun refreshProfiles(cm: me.sorihiko.dango.config.ConfigManager, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - profileNamesAt < 2000L) return
        profileNames = cm.listProfiles()
        profileNamesAt = now
    }

    private fun setStatus(text: String) {
        profileStatus = text
        profileStatusAt = System.currentTimeMillis()
        persist()
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

    /**
     * 离开设置页时清掉所有"正在输入"的状态。
     * 否则录制中的快捷键会继续吞按键（例如想按空格暂停，结果把空格写进了快捷键）。
     */
    fun cancelInput() {
        commitEdit()
        bindingKey = null
        profileEditing = false
        dragValue = null
    }

    /**
     * 设置页刚刚用 Esc 取消输入 / 清除了快捷键：调用方（播放器界面）在这个短窗口内
     * 不要再把 Esc 当成「关闭界面」，避免按住或连按 Esc 时顺手关掉整个界面。
     */
    fun escapeCloseBlocked(): Boolean = System.currentTimeMillis() - escapeConsumedAt < 350L

    /** 正在输入数值 / 文本或录快捷键时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        // 不在设置相关页面时不该再消费按键：残留的编辑 / 录制状态直接作废
        if (ScreenState.page != Page.SETTINGS && ScreenState.page != Page.SETTINGS_DETAIL) {
            bindingKey = null
            editRow = null
            profileEditing = false
            return false
        }
        // 参数管理的档案名输入
        if (profileEditing) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> profileEditing = false
                keyCode == Keys.ESCAPE -> {
                    profileEditing = false
                    escapeConsumedAt = System.currentTimeMillis()
                }
                else -> profileName.handleKey(typedChar, keyCode)
            }
            return true
        }
        // 正在输入：Enter 确定，Esc 取消；其余交给 TextInput（含 Ctrl+C / V / X / A）
        if (editRow != null) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitEdit()
                keyCode == Keys.ESCAPE -> {
                    editRow = null
                    escapeConsumedAt = System.currentTimeMillis()
                }
                else -> edit.handleKey(typedChar, keyCode)
            }
            return true
        }
        // 正在录按键：修饰键单独按下不提交，等主键；Esc / Backspace / Delete 取消绑定（设为无）；
        // 「打开播放器」按键不允许为空，按 Esc 只退出录制、保留原绑定
        val v = bindingKey ?: return false
        when {
            // 只按 Shift / Ctrl / Alt / Win 不算绑定：继续等真正的目标键，同时按钮上实时显示
            Keys.isModifier(keyCode) -> return true
            keyCode == Keys.ESCAPE -> {
                // Esc 直接清除当前绑定的快捷键：设成无绑定状态
                if (v.allowEmpty) v.clear()
                bindingKey = null
                escapeConsumedAt = System.currentTimeMillis()
            }
            keyCode == Keys.BACK || keyCode == Keys.DELETE -> {
                if (v.allowEmpty) v.clear()
                bindingKey = null
            }
            keyCode != 0 -> {
                v.setCombo(heldModifiers(), keyCode)
                bindingKey = null
            }
        }
        persist()
        return true
    }
}
