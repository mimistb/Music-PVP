package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.LoginAccounts
import me.sorihiko.dango.musicui.GridPage
import me.sorihiko.dango.musicui.Library
import me.sorihiko.dango.musicui.LoginDialog
import me.sorihiko.dango.musicui.Page
import me.sorihiko.dango.musicui.PartyPage
import me.sorihiko.dango.musicui.PlayerBar
import me.sorihiko.dango.musicui.RED
import me.sorihiko.dango.musicui.ScreenState
import me.sorihiko.dango.musicui.SettingsPage
import me.sorihiko.dango.musicui.Sidebar
import me.sorihiko.dango.musicui.TracksPage
import me.sorihiko.dango.musicui.Ui
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import kotlin.math.max
import kotlin.math.min

/**
 * 内置网易云播放器界面（液态玻璃风格，排版参考 MuoniumPlayer）。
 * 这个类只是"外壳"：打开 / 关闭、每帧的准备工作、页面切换、把鼠标键盘分发给各部分。
 * 它不依赖任何 Minecraft 版本：各版本的平台层用一个很薄的游戏界面类把事件转发进来
 * （[opened] / [render] / [mouseClicked] / [keyTyped] …），鼠标和窗口大小从 [DangoClient.platform] 读。
 * 具体的东西在 musicui 包里：
 *   [Sidebar] 左侧栏 · [PlayerBar] 底部播放栏 · [LoginDialog] 扫码登录
 *   [GridPage] 推荐歌单墙 · [TracksPage] 歌单 / 搜索 / 每日推荐 / 免费听 / 队列 · [PartyPage] 一起听 · [SettingsPage] 设置
 *   [Library] 从网易云读来的数据 · [ScreenState] 关掉再打开还保留的界面状态 · [Ui] 绘制工具
 *
 * 菜单字体（Inter）只有拉丁字符，所以中文（歌名、歌手、提示）和图标符号用 Minecraft 自带字体画。
 */
class MusicScreen(
    private val module: NeteaseMusicModule,
    /** 关掉这个界面（由平台层提供） */
    private val closeScreen: () -> Unit,
) {
    private val platform get() = DangoClient.platform

    internal val ui = Ui()
    internal val sidebar = Sidebar(this)
    internal val tracks = TracksPage(this)
    internal val login = LoginDialog(this)
    private val grid = GridPage(this)
    private val party = PartyPage(this)
    private val settings = SettingsPage(this, module)
    private val bar = PlayerBar(this, module)

    private var openedAt = 0L
    private var lastNanos = 0L

    /**
     * 悬浮面板模式：整块界面由平台层渲染进纹理再以透视画到画面上。
     * 这种模式下不画全屏暗色遮罩（世界要能正常看见），鼠标位置也由平台层投影后传进来。
     */
    var floating = false

    /** 悬浮面板下，鼠标投影到界面平面后的窗口像素坐标（[pointerActive] 为 false 时忽略） */
    var pointerActive = false
    var pointerX = 0
    var pointerY = 0
    /** 换页（含换歌单 / 设置换标签 / 新搜索 / 列表↔封面墙）就重新播入场动画；逐项比较，避免每帧拼字符串 */
    private var pageKeyPage: Page? = null
    private var pageKeyOpened = 0L
    private var pageKeyTab = -1
    private var pageKeyCover = false
    private var pageKeyQuery: String? = null

    /** 界面打开时调用 */
    /** 界面是否已经做过一次打开初始化（窗口 resize 会重新走平台层的 initGui） */
    private var openedOnce = false

    fun opened() {
        if (ScreenState.context == null) ScreenState.context = DangoClient.gfx.newCanvas()
        openedAt = System.currentTimeMillis()
        // resize 会重新调用 initGui：只重排版，不要重复发网络请求、也不要重播入场动画
        if (openedOnce) return
        openedOnce = true
        if (Library.playlists.isEmpty()) Library.reloadPlaylists()
        else if (Library.stale) Library.softRefresh()
        Library.loadRecommend()
        // 停在本地音乐页时，重新打开界面顺手重扫一次目录（扫描中重复调用会被忽略）
        if (ScreenState.page == Page.LOCAL) LocalLibrary.scan()
        // 停在每日推荐 / 免费听时，数据还没读就读一次
        if (ScreenState.page == Page.DAILY) Library.loadDaily()
        else if (ScreenState.page == Page.FREE) Library.loadFree()
    }

    private fun close() {
        closed()
        closeScreen()
    }

    /**
     * 界面被关闭时的收尾（不负责切界面）：提交正在输入的内容并落盘。
     * 平台层在 onGuiClosed 里也要调用，否则被断线 / 其它界面顶掉时输入会丢。
     */
    fun closed() {
        settings.cancelInput()
        try {
            me.sorihiko.dango.DangoMusic.instance.configManager.saveIfChanged()
        } catch (_: Throwable) {
            // 写盘失败不应该阻止关闭界面
        }
    }

    // ---------------------------------------------------------------- 导航

    internal fun go(p: Page) {
        if (ScreenState.page == Page.SETTINGS || ScreenState.page == Page.SETTINGS_DETAIL) settings.cancelInput()
        // 只保留"当前就在详情页"的情况；任何其它跳转都退回列表态
        if (p != Page.SETTINGS_DETAIL) ScreenState.settingsDetail = null
        tracks.clearFilter()
        ScreenState.page = p
        ScreenState.backPage = null
        tracks.resetScroll()
        grid.resetScroll()
        when (p) {
            Page.HOME -> Library.loadRecommend()
            Page.DAILY -> Library.loadDaily()
            Page.FREE -> Library.loadFree()
            Page.LOCAL -> if (LocalLibrary.tracks.isEmpty()) LocalLibrary.scan()
            Page.QUEUE -> if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
            else -> {}
        }
    }

    internal fun goBack() {
        // 详情页只回退一层：详情 -> 设置列表；再退才是离开设置
        if (ScreenState.page == Page.SETTINGS_DETAIL) {
            settings.cancelInput()
            ScreenState.settingsDetail = null
            ScreenState.page = Page.SETTINGS
            return
        }
        if (ScreenState.page == Page.SETTINGS) {
            settings.cancelInput()
            ScreenState.page = ScreenState.settingsBack
            return
        }
        val b = ScreenState.backPage ?: return
        ScreenState.page = b
        ScreenState.backPage = null
    }

    internal fun goBackOrHome() {
        if (ScreenState.backPage != null) goBack() else go(Page.HOME)
    }

    internal fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        val prev = ScreenState.opened
        if (prev == null || prev.id != p.id || prev.provider != p.provider) tracks.clearFilter()
        ScreenState.opened = p
        ScreenState.page = Page.PLAYLIST
        ScreenState.backPage = from
        tracks.resetScroll()
        tracks.resetCoverflow()
        Library.loadTracks(p)
    }

    internal fun doSearch() {
        val q = sidebar.searchText.trim()
        if (q.isEmpty()) return
        sidebar.searchFocused = false
        tracks.clearFilter()
        if (ScreenState.page != Page.SEARCH) ScreenState.backPage = ScreenState.page
        ScreenState.page = Page.SEARCH
        tracks.resetScroll()
        tracks.resetCoverflow()
        ScreenState.lastQuery = q
        Library.search(q)
    }

    internal fun toggleSettings() {
        prepareSettingsSwitch()
        if (ScreenState.page == Page.SETTINGS || ScreenState.page == Page.SETTINGS_DETAIL) {
            ScreenState.page = ScreenState.settingsBack
        } else {
            ScreenState.settingsBack = ScreenState.page
            ScreenState.page = Page.SETTINGS
        }
    }

    /** 进出设置页前的统一收尾：提交正在编辑的值、释放文本焦点、取消快捷键录制 */
    private fun prepareSettingsSwitch() {
        settings.commitEdit()
        blurTextFields()
        settings.bindingKey = null
        // 从任何入口重新进设置页都落到列表，而不是停在某个模块详情
        ScreenState.settingsDetail = null
    }

    internal fun toggleCoverView() {
        ScreenState.coverView = !ScreenState.coverView
        if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
    }

    /** 取消所有输入框的焦点（搜索、筛选、房间码） */
    internal fun blurTextFields() {
        sidebar.searchFocused = false
        tracks.filterFocused = false
        party.codeFocused = false
    }

    // ---------------------------------------------------------------- 绘制

    /** 每帧调用 */
    fun render() {
        val scale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        ui.scale = scale
        ui.lw = platform.windowWidth / scale
        ui.lh = platform.windowHeight / scale
        val rawMx = if (floating && pointerActive) pointerX else platform.mouseX
        val rawMy = if (floating && pointerActive) pointerY else platform.mouseY
        ui.mx = (rawMx / scale).toInt()
        ui.my = (rawMy / scale).toInt()
        val ctx = ui.ctx
        val alpha = ui.easeOut((System.currentTimeMillis() - openedAt) / Theme.DUR_SLOW.toFloat())
        ctx.begin(true, ui.mx, ui.my)
        try {
            val now = System.nanoTime()
            ui.frameDt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
            lastNanos = now
            grid.tick()
            tracks.tick()
            settings.tick()

            ui.hits.clear()
            settings.beginFrame()
            ui.mt.clear()
            tracks.beginFrame()
            CoverCache.upload()
            login.uploadQr()

            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            // 悬浮面板要能看见后面的世界，不铺全屏遮罩
            if (!floating) ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Theme.OVERLAY))
            drawWindow(alpha)
            ctx.popOpacity()
            ctx.popTransform()
            ctx.noScissor()
            ui.mt.flush(scale)

            if (login.visible) {
                ctx.pushScale(scale, scale, 1f)
                login.draw()
                ctx.popTransform()
                ui.mt.flush(scale)
            }
        } finally {
            ctx.end()
        }
    }

    private fun windowBounds(alpha: Float): LayoutBounds {
        // 桌面端铺满窗口；超宽屏时限制最大宽度并居中，避免内容被拉得过于松散
        val mX = max(16f, min(26f, ui.lw * 0.02f))
        val mY = max(14f, min(20f, ui.lh * 0.025f))
        val winW = min(ui.lw - mX * 2, 1680f)
        val winX = (ui.lw - winW) / 2f
        return LayoutBounds(winX, mY + (1f - alpha) * 14f, winW, ui.lh - mY * 2)
    }

    /** 直接打开播放器设置页（主菜单右上角的功能入口用） */
    fun openSettings() {
        prepareSettingsSwitch()
        ScreenState.settingsBack = ScreenState.page
        ScreenState.page = Page.SETTINGS
    }

    private fun drawWindow(alpha: Float) {
        val win = windowBounds(alpha)
        ui.ctx.drawGlass(win, Theme.GLASS_WINDOW)

        val pad = Theme.SPACE_10
        val sideW = 224f
        val barH = 74f
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val mainX = side.right + pad + Theme.SPACE_8
        val mainW = win.right - pad - Theme.SPACE_8 - mainX
        val barRect = LayoutBounds(side.right + pad, win.bottom - pad - barH, win.right - pad - (side.right + pad), barH)
        val content = LayoutBounds(mainX, win.y + pad + Theme.SPACE_14, mainW, barRect.y - Theme.SPACE_10 - (win.y + pad + Theme.SPACE_14))

        sidebar.draw(side)
        drawContent(content)
        bar.draw(barRect)
    }

    private fun drawContent(area: LayoutBounds) {
        // 底部小字只在有提示（读取失败等）时显示
        val foot = Library.status
        if (foot.isNotEmpty()) ui.text(foot, area.x, area.bottom - 11f, 10f, RED, Theme.FONT_SEMIBOLD)
        val body = LayoutBounds(area.x, area.y, area.width, area.height - (if (foot.isNotEmpty()) 18f else 4f))
        // 滚轮区域：哪一页画了，哪一页才有
        grid.area = LayoutBounds()
        tracks.area = LayoutBounds()
        settings.area = LayoutBounds()

        val s = ScreenState
        val openedId = s.opened?.id ?: 0L
        if (s.page != pageKeyPage || openedId != pageKeyOpened || s.settingsTab != pageKeyTab ||
            s.coverView != pageKeyCover || s.lastQuery != pageKeyQuery
        ) {
            pageKeyPage = s.page
            pageKeyOpened = openedId
            pageKeyTab = s.settingsTab
            pageKeyCover = s.coverView
            pageKeyQuery = s.lastQuery
            ui.pageAt = System.currentTimeMillis()
            tracks.onPageChanged()
        }

        when (s.page) {
            Page.HOME -> {
                val rec = Library.recommend
                // 非网易云平台不拿网易云个人歌单兜底，避免切换后首页短暂显示别的平台数据
                val fallback = if (LoginAccounts.activePlatform() == "netease") Library.playlists else emptyList()
                val items = if (rec.isNullOrEmpty()) fallback else rec
                val label = when {
                    !rec.isNullOrEmpty() -> "推荐歌单"
                    fallback.isNotEmpty() -> "你的歌单"
                    else -> "推荐歌单"
                }
                grid.draw(body, "推荐歌单", "$label · ${items.size}", items, Library.recommendLoading && items.isEmpty())
            }
            Page.PARTY -> ui.itemAnim(0) { party.draw(body) }
            Page.SETTINGS, Page.SETTINGS_DETAIL -> settings.draw(body)
            else -> tracks.draw(body)
        }
    }

    // ---------------------------------------------------------------- 鼠标

    fun mouseClicked(mouseButton: Int) {
        val mx = ui.mx
        val my = ui.my
        if (login.visible) {
            login.mouseClicked(mx, my, mouseButton)
            return
        }
        // 鼠标侧键 / 右键
        if (mouseButton == 1 || mouseButton == 3) {
            // 设置列表：右键模块 -> 打开专用设置页；设置详情页：右键控件 -> 恢复默认，
            // 其它位置右键一律回退一层
            if ((ScreenState.page == Page.SETTINGS || ScreenState.page == Page.SETTINGS_DETAIL) &&
                settings.mouseRightClicked(mx, my)
            ) {
                return
            }
            goBack()
            return
        }
        if (mouseButton != 0) return
        // 播放栏 / 滚动条有自己的几何判定，但它们同样属于"页面外点击"：
        // 先提交并释放输入焦点，否则录制中的快捷键会继续吞按键。
        if (bar.mouseDown(mx, my) || tracks.scrollbarDown(mx, my)) {
            settings.onClickElsewhere(null)
            sidebar.searchFocused = false
            tracks.filterFocused = false
            party.codeFocused = false
            return
        }
        val hit = ui.hits.firstOrNull { it.first.contains(mx, my) }
        settings.onClickElsewhere(hit)
        if (hit == null || hit.first !== sidebar.searchRect) sidebar.searchFocused = false
        if (hit == null || hit.first !== tracks.filterRect) tracks.filterFocused = false
        if (hit == null || hit.first !== party.codeRect) party.codeFocused = false
        if (hit != null) {
            ui.markPress(hit.first)
            hit.second.invoke()
        }
    }

    /** 按住鼠标拖动时调用 */
    fun mouseDragged() {
        if (tracks.sbDragging) {
            tracks.scrollbarDrag(ui.my)
            return
        }
        if (!login.visible) bar.mouseDrag(ui.mx, ui.my)
    }

    fun mouseReleased() {
        tracks.scrollbarUp()
        settings.stopDrag()
    }

    /** 滚轮：[wheel] 正数向上；(x, y) 是滚动时鼠标的窗口像素位置 */
    fun scroll(wheel: Int, x: Int, y: Int) {
        if (wheel == 0 || login.visible) return
        val lx = (x / ui.scale).toInt()
        val ly = (y / ui.scale).toInt()
        // 按住 Ctrl 滚得快十倍
        val step = (if (wheel > 0) -3 else 3) * (if (platform.ctrlDown) 10 else 1)
        when {
            settings.area.contains(lx, ly) -> settings.scroll(wheel)
            sidebar.listArea.contains(lx, ly) -> sidebar.scrollBy(step)
            grid.area.contains(lx, ly) -> grid.scroll(wheel)
            tracks.area.contains(lx, ly) -> tracks.scroll(wheel, step)
        }
    }

    // ---------------------------------------------------------------- 键盘

    /** 按键：[keyCode] 是 [Keys] 键码 */
    fun keyTyped(typedChar: Char, keyCode: Int) {
        if (login.visible) {
            login.keyTyped(keyCode)
            return
        }
        // 正在输入 / 录按键的地方先拿走按键
        if (settings.keyTyped(typedChar, keyCode)) return
        if (sidebar.keyTyped(typedChar, keyCode)) return
        if (party.keyTyped(typedChar, keyCode)) return
        if (tracks.filterKeyTyped(typedChar, keyCode)) return
        // 自己设的快捷键在播放器里也能用
        if (keyCode != Keys.ESCAPE && !module.openKey.matchesPress(keyCode, platform) && module.handleHotkey(keyCode)) return
        if (tracks.shortcutBefore(keyCode)) return
        when {
            // 其它页面 Ctrl+F / Ctrl+K 是全网聚合搜索（歌曲 + 歌单 + 本地音乐）
            platform.ctrlDown && (keyCode == Keys.F || keyCode == Keys.K) -> sidebar.searchFocused = true
            keyCode == Keys.SPACE -> MusicPlayer.togglePause()
            keyCode == Keys.BACK -> goBack()
            tracks.coverflowKey(keyCode) -> {}
            keyCode == Keys.RIGHT -> MusicPlayer.seek(MusicPlayer.positionMs + 5000)
            keyCode == Keys.LEFT -> MusicPlayer.seek(MusicPlayer.positionMs - 5000)
            keyCode == Keys.ESCAPE -> when {
                // 刚用 Esc 取消过输入 / 清除过快捷键：这一下不算
                settings.escapeCloseBlocked() -> {}
                // 模块专用设置页只退一层，回到设置列表
                ScreenState.page == Page.SETTINGS_DETAIL -> goBack()
                else -> close()
            }
            // 按快捷键打开时，同一次按键事件会紧接着传进这个界面，刚打开的一小会儿里不能把它当成"关闭"
            keyCode == module.openKey.primary && module.openKey.matchesPress(keyCode, platform) &&
                System.currentTimeMillis() - openedAt > 300L -> close()
        }
    }
}
