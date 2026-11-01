package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.party.VoiceChat
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/** 音乐语音大厅：开房间 / 输房间码加入 / 房间里有谁 / 语音开关 */
internal class PartyPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    // 房间码：只收字母数字并统一转大写（TextInput 负责光标、选区与剪贴板）
    private val code = TextInput(8) { c ->
        if (c.isLetterOrDigit() && c.code < 128) c.uppercaseChar() else null
    }
    val codeInput: String get() = code.text
    var codeFocused = false
    var codeRect = LayoutBounds()
        private set
    private var copiedAt = 0L

    fun draw(area: LayoutBounds) {
        // 每帧同步一次语音设置（内部可安全重复调用）
        VoiceChat.sync()
        val ctx = ui.ctx
        val x = area.x
        var y = area.y
        val role = ListenParty.role
        ui.text("音乐语音大厅", x, y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when (role) {
            ListenParty.Role.NONE -> "和同样安装本模组的朋友同步听同一首歌"
            ListenParty.Role.HOST -> "你是房主 · 房间内所有人跟随你的音乐"
            ListenParty.Role.MEMBER -> "正在跟随 " + ListenParty.hostName
        }
        ui.text(sub, x, y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 66f

        if (role == ListenParty.Role.NONE) {
            ui.pill(LayoutBounds(x, y, 190f, 38f), RED, if (ListenParty.busy) "处理中…" else "创建房间", "♬") { ListenParty.create() }
            y += 62f
            ui.text("或使用房间码加入", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            codeRect = LayoutBounds(x, y, 190f, 38f)
            ui.card(codeRect, if (codeFocused) Theme.CARD_HOVER else Theme.CARD, 12f)
            if (codeFocused) ctx.drawShape(codeRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 12f, 1f, RED))
            if (codeInput.isEmpty() && !codeFocused) {
                ui.text("房间码", codeRect.x + 14f, codeRect.y + 12f, 14f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            } else {
                ui.text(code.display(codeFocused && caretVisible()), codeRect.x + 14f, codeRect.y + 10f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            }
            ui.hits += codeRect to {
                codeFocused = true
                screen.sidebar.searchFocused = false
                screen.tracks.filterFocused = false
            }
            ui.pill(LayoutBounds(codeRect.right + 10f, y, 90f, 38f), Color(1f, 1f, 1f, 0.12f), "加入") {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            y += 56f
        } else {
            ui.text("房间码", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
            val code = ListenParty.code
            ui.text(code, x, y, 40f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val cw = ui.width(code, 40f, Theme.FONT_DISPLAY)
            val copied = System.currentTimeMillis() - copiedAt < 1500
            ui.pill(LayoutBounds(x + cw + 18f, y + 8f, 80f, 28f), Color(1f, 1f, 1f, 0.12f), if (copied) "已复制" else "复制") {
                DangoClient.platform.clipboard = code
                copiedAt = System.currentTimeMillis()
            }
            y += 66f

            val list = ListenParty.members
            ui.text("房间成员 · " + list.size, x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            for (m in list.take(10)) {
                val row = LayoutBounds(x, y, 320f, 34f)
                ui.card(row, Theme.CARD, 10f)
                val av = LayoutBounds(row.x + 8f, row.y + 6f, 22f, 22f)
                ctx.drawShape(av, ShapeStyle(if (m.uid == ListenParty.hostUid) RED else Theme.TRACK, 11f))
                ui.icon(m.name.take(1).uppercase(), av.x + 11f, av.y + 11f, 11f, Theme.TEXT_PRIMARY)
                val label = m.name + if (m.uid == ListenParty.myUid) "（你）" else ""
                ui.text(ui.trim(label, 200f, 12f), av.right + 10f, row.y + 11f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                if (m.uid == ListenParty.hostUid) {
                    val hb = LayoutBounds(row.right - 54f, row.y + 8f, 44f, 18f)
                    ctx.drawShape(hb, ShapeStyle(RED.copy(a = 0.25f), 9f))
                    ui.text("房主", hb.x + (hb.width - ui.width("房主", 9f, Theme.FONT_SEMIBOLD)) / 2f, hb.y + 5f, 9f, RED, Theme.FONT_SEMIBOLD)
                }
                y += 40f
            }
            y += 10f
            ui.pill(LayoutBounds(x, y, 140f, 34f), Color(1f, 1f, 1f, 0.12f), if (role == ListenParty.Role.HOST) "关闭房间" else "离开房间") {
                ListenParty.leave()
            }
            if (role == ListenParty.Role.MEMBER) {
                ui.pill(LayoutBounds(x + 150f, y, 120f, 34f), Color(1f, 1f, 1f, 0.12f), "重新同步", "↻") { ListenParty.resync() }
            }
            y += 50f

            // 语音：开关按钮 + 一行状态文本（复用现有胶囊 / 文本样式，不新建面板）
            val voiceOn = VoiceChat.active
            ui.pill(LayoutBounds(x, y, 140f, 34f), Color(1f, 1f, 1f, 0.12f),
                if (voiceOn) "关闭语音" else "开启语音", "♪") {
                if (voiceOn) VoiceChat.stop() else VoiceChat.start()
            }
            y += 44f
            val voiceState = VoiceChat.status
            // 正在传输语音时补上估算延迟（状态文本里已经带了就不再重复）
            val voiceLine = if (VoiceChat.active && voiceState.isNotEmpty() && !voiceState.contains("延迟")) {
                "$voiceState · 延迟 ~${VoiceChat.latencyMs} ms"
            } else voiceState
            if (voiceLine.isNotEmpty()) ui.text(voiceLine, x, y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            y += 26f
        }

        val st = ListenParty.status
        if (st.isNotEmpty()) ui.text(st, x, y, 12f, RED, Theme.FONT_MEDIUM)
        ui.text("每个人使用自己的网易云账号串流，VIP 歌曲需要双方都有 VIP。房间码 5 位，通过公共中继同步，无需额外配置。",
            x, area.bottom - 20f, 10f, Theme.TEXT_TERTIARY)
    }

    /** 房间码输入框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!codeFocused) return false
        when {
            keyCode == Keys.ESCAPE -> codeFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            else -> code.handleKey(typedChar, keyCode)
        }
        return true
    }
}
