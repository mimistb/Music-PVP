package me.sorihiko.dango.musicui

import me.sorihiko.dango.music.LoginAccounts
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 左侧玻璃侧栏：搜索框 / 推荐歌单 · 每日推荐 · 免费听 · 正在播放 · 音乐语音大厅 / 我的歌单 / 设置 / 账号。
 * 选中项下面有一块"果冻"选中块：上下两条边分开动，前面那条边 220ms、后面那条 380ms，拉伸着滑过去。
 */
internal class Sidebar(private val screen: MusicScreen) {
    private val ui = screen.ui

    // 搜索框：输入状态交给共用的 TextInput（Ctrl+C / V / X / A 全支持）
    val search = TextInput(80)
    val searchText: String get() = search.text
    var searchFocused = false
    var searchRect = LayoutBounds()
        private set

    // 歌单列表：滚动目标（行）和平滑后的当前位置（关掉界面再打开从上次的位置继续）
    var listArea = LayoutBounds()
        private set
    private var scroll = ScreenState.sidebarScroll
    private var scrollPos = scroll.toFloat()

    // 选中块：本帧算出的目标，下一帧往那里动
    private var selKey = ""
    private var selTarget: LayoutBounds? = null
    private var pillKey = ""
    private var pillTop = 0f
    private var pillBot = 0f
    private var pillAlpha = 0f
    private var pillFromTop = 0f
    private var pillFromBot = 0f
    private var pillAt = 0L
    private var pillDown = false

    fun scrollBy(rows: Int) {
        scroll += rows
    }

    fun draw(side: LayoutBounds) {
        val ctx = ui.ctx
        val page = ScreenState.page
        ctx.drawGlass(side, Theme.GLASS_SIDEBAR)
        drawSelectionPill(side)
        var newKey = ""
        var newTarget: LayoutBounds? = null
        val x = side.x + 12f
        val iw = side.width - 24f
        var y = side.y + 12f

        // 搜索框：放大镜 + 输入
        searchRect = LayoutBounds(x, y, iw, 32f)
        ui.panel(searchRect, 10f)
        if (searchFocused) ctx.drawShape(searchRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED))
        ctx.drawShape(LayoutBounds(x + 11f, y + 10f, 9f, 9f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4.5f, 1.4f, Theme.TEXT_SECONDARY))
        ctx.drawShape(LayoutBounds(x + 18.5f, y + 18f, 4.5f, 1.6f), ShapeStyle(Theme.TEXT_SECONDARY, 0.8f))
        val shown = if (searchText.isEmpty() && !searchFocused) "搜索歌曲…" else search.display(searchFocused && caretVisible())
        val shownColor = if (searchText.isEmpty() && !searchFocused) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY
        val maxW = searchRect.right - 10f - (x + 30f)
        var visible = shown
        while (visible.length > 1 && ui.width(visible, 12f) > maxW) visible = visible.substring(1)   // 太长时显示末尾
        ui.text(visible, x + 30f, y + 10f, 12f, shownColor)
        ui.hits += searchRect to { searchFocused = true }
        y += 42f

        fun section(label: String) {
            ui.text(label, x + 2f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
        }

        fun nav(glyph: String, label: String, active: Boolean, redIcon: Boolean, action: () -> Unit) {
            val row = LayoutBounds(side.x + 6f, y, side.width - 12f, 30f)
            if (active) {
                newKey = "nav:$glyph"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            ui.icon(glyph, row.x + 18f, row.y + 15f, 12f, if (active || redIcon) RED else Theme.TEXT_SECONDARY)
            ui.text(label, row.x + 34f, row.y + 10f, 12f, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            ui.hits += row to action
            y += 32f
        }

        section("DANGO MUSIC")
        nav("▤", "推荐歌单", page == Page.HOME, true) { screen.go(Page.HOME) }
        section("发现")
        nav("◈", "每日推荐", page == Page.DAILY, false) { screen.go(Page.DAILY) }
        nav("♪", "免费听", page == Page.FREE, false) { screen.go(Page.FREE) }
        nav("▣", "本地音乐", page == Page.LOCAL, false) { screen.go(Page.LOCAL) }
        nav("♫", "正在播放", page == Page.QUEUE, false) { screen.go(Page.QUEUE) }
        nav("♬", if (ListenParty.role == ListenParty.Role.NONE) "音乐语音大厅" else "音乐语音大厅 · " + ListenParty.members.size,
            page == Page.PARTY, ListenParty.role != ListenParty.Role.NONE) { screen.go(Page.PARTY) }
        y += 4f
        section("我的歌单")

        // 歌单列表（底部留出按钮和账号的位置）
        val rowH = 30f
        val footerH = 150f
        listArea = LayoutBounds(side.x + 6f, y, side.width - 12f, side.bottom - footerH - y)
        val rows = max(1, (listArea.height / rowH).toInt())
        val list = Library.playlists
        scroll = scroll.coerceIn(0, max(0, list.size - rows))
        ScreenState.sidebarScroll = scroll
        scrollPos = ui.easeScroll(scrollPos, scroll, rows * 3)
        val firstOwned = list.indexOfFirst { it.owned }
        val first = kotlin.math.floor(scrollPos).toInt()
        val frac = scrollPos - first
        ui.beginClip(listArea)
        for (r in 0..rows) {
            val i = first + r
            if (i < 0) continue
            if (i >= list.size) break
            val p = list[i]
            val row = LayoutBounds(listArea.x, listArea.y + (r - frac) * rowH, listArea.width, rowH - 2f)
            val active = page == Page.PLAYLIST && ScreenState.opened?.id == p.id
            if (active) {
                newKey = "pl:${p.id}"
                newTarget = row
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }
            val liked = i == firstOwned   // 第一个自己的歌单就是"我喜欢的音乐"
            ui.icon(if (liked) "♥" else "≡", row.x + 18f, row.y + 14f, 12f, if (liked || active) RED else Theme.TEXT_SECONDARY)
            val c = if (active) Theme.TEXT_PRIMARY else if (p.owned) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            ui.text(ui.trim(p.name, row.right - 8f - (row.x + 34f), 12f), row.x + 34f, row.y + 9f, 12f, c)
            ui.hits += row.intersect(listArea) to { screen.openPlaylist(p, null) }
        }
        ui.endClip()
        if (list.isEmpty()) {
            if (Library.playlistsLoading) {
                // 首次读取歌单：骨架行 + 统一流光，加载完成后直接替换成真实列表
                val skeletonRows = min(4, max(1, (listArea.height / 32f).toInt()))
                for (i in 0 until skeletonRows) {
                    ui.skeleton(LayoutBounds(x, listArea.y + i * 32f, iw, 24f), 8f)
                }
                ui.text("正在读取歌单…", x + 2f, listArea.y + skeletonRows * 32f + 4f, 10f, Theme.TEXT_TERTIARY)
            } else {
                val msg = if (NeteaseApi.loggedIn) "暂无歌单" else "登录后查看你的歌单"
                ui.text(msg, x + 2f, listArea.y + 6f, 11f, Theme.TEXT_TERTIARY)
            }
        }

        // 设置按钮（歌单会在打开播放器时自动刷新，不需要刷新按钮）
        val by = side.bottom - footerH + 8f
        val sb = LayoutBounds(x, by, iw, 32f)
        val on = page == Page.SETTINGS
        ui.panel(sb, 10f, active = on)
        if (on) ctx.drawShape(sb, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED.copy(a = 0.6f)))
        val sCol = if (on) RED else Theme.TEXT_PRIMARY
        val sLabel = "设置"
        val sw = 14f + 6f + ui.width(sLabel, 12f, Theme.FONT_SEMIBOLD)
        val sx = sb.x + (sb.width - sw) / 2f
        ui.icon("⚙", sx + 7f, sb.y + 16f, 14f, sCol)
        ui.text(sLabel, sx + 20f, sb.y + 16f - 12f * 0.39f - 0.5f, 12f, sCol, Theme.FONT_SEMIBOLD)
        ui.hits += sb to { screen.toggleSettings() }

        drawAccount(side)

        // 下一帧选中块往这里动
        selKey = newKey
        selTarget = newTarget
    }

    /**
     * 账号区：网易云 / QQ 音乐 / 酷狗三行登录态。
     * 已登录的行点击即切换当前平台（复用本地令牌，不会再走扫码）；未登录的行点击打开登录弹窗。
     */
    private fun drawAccount(side: LayoutBounds) {
        val qq = LoginAccounts.qqAccount()
        val kugou = LoginAccounts.kugouAccount()

        fun loggedIn(provider: String): Boolean = when (provider) {
            "netease" -> NeteaseApi.loggedIn
            "qq" -> qq != null
            else -> kugou != null
        }

        val stored = LoginAccounts.activePlatform()
        // 记录的平台退出登录后自动回退到仍可用的账号，不写回配置
        val active = when {
            loggedIn(stored) -> stored
            NeteaseApi.loggedIn -> "netease"
            qq != null -> "qq"
            kugou != null -> "kugou"
            else -> stored
        }

        val entries = listOf(
            Triple("netease", "网易云", NeteaseApi.nickname.ifEmpty { if (NeteaseApi.loggedIn) "已登录" else "未登录" }),
            Triple("qq", "QQ音乐", qq?.display ?: "未登录"),
            Triple("kugou", "酷狗", kugou?.display ?: "未登录"),
        )

        val x = side.x + 6f
        val w = side.width - 12f
        val top = side.bottom - 98f
        val rowH = 28f
        val gap = 2f

        entries.forEachIndexed { i, entry ->
            val provider = entry.first
            val name = entry.second
            val status = entry.third
            val logged = loggedIn(provider)
            val on = provider == active
            val row = LayoutBounds(x, top + i * (rowH + gap), w, rowH)
            val accent = when (provider) {
                "netease" -> RED
                "qq" -> Color.fromHex("#31C27C")
                else -> Color.fromHex("#2AA6FF")
            }

            if (on) {
                ui.ctx.drawShape(row, ShapeStyle(Color(0f, 0f, 0f, 0f), 9f, 1f, RED.copy(a = 0.5f)))
                // 左侧短竖条：和选中歌单的果冻块同一套强调色
                ui.ctx.drawShape(LayoutBounds(row.x + 2f, row.y + 8f, 2f, row.height - 16f), ShapeStyle(RED, 1f))
            } else if (ui.hover(row)) {
                ui.card(row, Theme.CARD_HOVER, 9f)
            }

            val badge = LayoutBounds(row.x + 10f, row.y + 4f, 20f, 20f)
            ui.ctx.drawShape(badge, ShapeStyle(accent.copy(a = 0.18f), 7f))
            ui.text(
                when (provider) {
                    "netease" -> "N"
                    "qq" -> "Q"
                    else -> "K"
                },
                badge.x + 6.5f, badge.y + 4f, 10f, accent, Theme.FONT_SEMIBOLD,
            )

            val actionLabel = if (logged) "退出" else "登录"
            val actionW = ui.width(actionLabel, 10f, Theme.FONT_MEDIUM) + 10f
            val action = LayoutBounds(row.right - actionW - 4f, row.y + 4f, actionW, 20f)

            val textW = action.x - 6f - (badge.right + 8f)
            ui.text(name, badge.right + 8f, row.y + 4f, 11f,
                if (logged) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            ui.text(ui.trim(status, textW, 9f), badge.right + 8f, row.y + 16f, 9f, Theme.TEXT_TERTIARY)

            if (ui.hover(action)) ui.card(action, Theme.CARD_HOVER, 6f)
            ui.text(actionLabel, action.x + 5f, action.y + 6f, 10f,
                if (on) RED else Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)

            // 行主体：已登录 = 切换平台（不重新扫码）；未登录 = 打开登录弹窗
            val body = LayoutBounds(row.x, row.y, max(1f, action.x - row.x - 4f), row.height)
            ui.hits += body to {
                if (logged) {
                    LoginAccounts.setActivePlatform(provider)
                } else {
                    LoginAccounts.setActivePlatform(provider)
                    screen.login.open()
                }
            }
            // 右侧小按钮：已登录 = 退出；未登录 = 登录
            ui.hits += action to {
                if (logged) {
                    when (provider) {
                        "netease" -> NeteaseApi.logout()
                        else -> LoginAccounts.logout(provider)
                    }
                    Library.refreshAll()
                } else {
                    screen.login.open()
                }
            }
        }
    }

    /** 侧栏选中块（用上一帧记下的选中行位置） */
    private fun drawSelectionPill(side: LayoutBounds) {
        val now = System.currentTimeMillis()
        val tgt = selTarget
        if (tgt != null) {
            if (pillAlpha < 0.02f || pillKey.isEmpty()) {
                pillTop = tgt.y
                pillBot = tgt.bottom
                pillKey = selKey
                pillAt = 0L
            } else if (selKey != pillKey) {
                pillFromTop = pillTop
                pillFromBot = pillBot
                pillDown = tgt.y > pillTop
                pillAt = now
                pillKey = selKey
            }
            if (pillAt != 0L) {
                val e = (now - pillAt).toFloat()
                val pt = e / (if (pillDown) 380f else 220f)
                val pb = e / (if (pillDown) 220f else 380f)
                pillTop = pillFromTop + (tgt.y - pillFromTop) * ui.easeOut(pt)
                pillBot = pillFromBot + (tgt.bottom - pillFromBot) * ui.easeOut(pb)
                if (pt >= 1f && pb >= 1f) pillAt = 0L
            } else {
                pillTop = tgt.y
                pillBot = tgt.bottom
            }
            pillAlpha = min(1f, pillAlpha + ui.frameDt / 120f)
        } else {
            pillAlpha = max(0f, pillAlpha - ui.frameDt / 120f)
            if (pillAlpha <= 0f) pillKey = ""
        }
        if (pillAlpha > 0.01f && pillBot - pillTop > 2f) {
            ui.ctx.pushOpacity(pillAlpha)
            ui.ctx.drawGlass(LayoutBounds(side.x + 6f, pillTop, side.width - 12f, pillBot - pillTop), Theme.GLASS_SELECTION)
            ui.ctx.popOpacity()
        }
    }

    /** 搜索框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!searchFocused) return false
        when {
            keyCode == Keys.ESCAPE -> searchFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> screen.doSearch()
            else -> search.handleKey(typedChar, keyCode)
        }
        return true
    }
}
