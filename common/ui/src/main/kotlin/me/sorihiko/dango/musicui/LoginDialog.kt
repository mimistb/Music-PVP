package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.music.LoginAccounts
import me.sorihiko.dango.music.LoginProviders
import me.sorihiko.dango.music.LoginState
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.QrLoginProvider
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/**
 * 登录弹窗：先选平台（网易云 / QQ音乐 / 酷狗音乐三个入口），再进入对应平台的扫码页。
 * 玻璃面板、圆角、按下反馈和播放器其它界面完全一致。
 *
 * 三个平台的二维码、状态、错误提示都来自 [QrLoginProvider]，
 * 界面只负责展示，不碰任何网络或凭证逻辑。
 */
internal class LoginDialog(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val hits = mutableListOf<Hit>()

    companion object {
        // 关掉播放器再打开，弹窗状态还在（扫码可能还没完成）
        private var showing = false
        private var selectedId = ""
        /** 是否已经处理过上一次的成功状态，避免重开弹窗时立刻又被关掉 */
        private var successSeen = false
        private var copiedAt = 0L
        private val qrTextures = mutableMapOf<String, Pair<Texture, Int>>()
    }

    val visible: Boolean get() = showing

    fun open() {
        LoginAccounts.ensureConfig()
        showing = true
        selectedId = ""
        successSeen = false
        hits.clear()
    }

    fun close() {
        showing = false
        LoginProviders.all.forEach { it.cancel() }
        // 二维码贴图只在弹窗期间用得到；关闭时释放，避免每次登录都留一张 GL 贴图
        try {
            val textures = DangoClient.gfx.textures
            qrTextures.values.forEach { textures.delete(it.first) }
        } catch (_: Throwable) {
            // 渲染层未就绪时忽略
        }
        qrTextures.clear()
    }

    /** 新二维码生成后传成贴图（每帧调用，内容没变化就什么都不做） */
    fun uploadQr() {
        if (selectedId.isEmpty()) return
        val provider = LoginProviders.byId(selectedId) ?: return
        val img = provider.qrImage ?: return
        val current = qrTextures[selectedId]
        if (current != null && current.second == provider.qrVersion) return
        val textures = DangoClient.gfx.textures
        current?.first?.let { textures.delete(it) }
        qrTextures[selectedId] = textures.upload("dango_login_qr_$selectedId", img) to provider.qrVersion
    }

    fun draw() {
        val ctx = ui.ctx
        hits.clear()

        val active = LoginProviders.byId(selectedId)
        // 登录成功：网易云沿用原来的刷新逻辑，QQ / 酷狗直接更新侧栏展示
        if (active != null && active.state == LoginState.SUCCESS && !successSeen) {
            successSeen = true
            LoginAccounts.setActivePlatform(active.id)
            if (active.id == "netease") Library.refreshAll()
            close()
            return
        }

        ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        if (active == null) drawPicker() else drawProvider(active)
    }

    // ---------------------------------------------------------------- 平台选择

    private fun drawPicker() {
        val w = 336f
        val h = 288f
        val box = LayoutBounds((ui.lw - w) / 2f, (ui.lh - h) / 2f, w, h)
        ui.ctx.drawGlass(box, Theme.GLASS_WINDOW)

        ui.text("登录音乐账号", box.x + 20f, box.y + 18f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        drawClose(box.right - 38f, box.y + 14f)

        var y = box.y + 54f
        for (p in LoginProviders.all) {
            val row = LayoutBounds(box.x + 16f, y, box.width - 32f, 56f)
            ui.panel(row, 12f)
            val accent = accentOf(p.id)
            val iconBox = LayoutBounds(row.x + 12f, row.y + 14f, 28f, 28f)
            ui.ctx.drawShape(iconBox, ShapeStyle(accent.copy(a = 0.16f), 14f))
            ui.icon(glyphOf(p.id), iconBox.x + 14f, iconBox.y + 14f, 13f, accent)

            ui.text(p.title, row.x + 52f, row.y + 12f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val logged = p.accountName.isNotEmpty()
            val sub = when {
                logged -> p.accountName
                !p.configured -> "未配置 · 点击查看"
                p.state == LoginState.ERROR -> "上次登录失败 · 点击重试"
                else -> p.entryHint
            }
            val maxW = row.right - 30f - (row.x + 52f)
            ui.text(ui.trim(sub, maxW, 11f), row.x + 52f, row.y + 32f, 11f,
                if (logged) Theme.GREEN else Theme.TEXT_TERTIARY)
            ui.icon("›", row.right - 16f, row.y + row.height / 2f, 16f, Theme.TEXT_TERTIARY)
            hits += row to {
                ui.markPress(row)
                select(p.id)
            }
            y += 62f
        }

        ui.text("登录信息只保存在本机，不会上传到任何第三方", box.x + 18f, box.bottom - 22f, 10f, Theme.TEXT_TERTIARY)
    }

    private fun select(id: String) {
        val p = LoginProviders.byId(id) ?: return
        selectedId = id
        successSeen = p.state == LoginState.SUCCESS
        // 已登录平台直接切为当前平台，复用本地令牌，不重新扫码
        if (p.accountName.isNotEmpty()) {
            LoginAccounts.setActivePlatform(id)
            return
        }
        p.start()
    }

    // ---------------------------------------------------------------- 单个平台扫码页

    private fun drawProvider(p: QrLoginProvider) {
        val w = 336f
        val h = 396f
        val box = LayoutBounds((ui.lw - w) / 2f, (ui.lh - h) / 2f, w, h)
        ui.ctx.drawGlass(box, Theme.GLASS_WINDOW)

        // 返回 + 标题 + 关闭
        val back = LayoutBounds(box.x + 14f, box.y + 12f, 24f, 24f)
        ui.panel(back, 12f)
        ui.icon("<", back.x + 12f, back.y + 12f, 12f, Theme.TEXT_PRIMARY)
        hits += back to {
            ui.markPress(back)
            p.cancel()
            selectedId = ""
        }
        ui.text("登录${p.title}", box.x + 50f, box.y + 18f, 16f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        drawClose(box.right - 38f, box.y + 14f)

        val qs = 196f
        val qrBox = LayoutBounds(box.x + (w - qs) / 2f, box.y + 58f, qs, qs)
        ui.ctx.drawShape(qrBox.expand(8f, 8f), ShapeStyle(Color.WHITE, 14f))

        val state = p.state
        val tex = qrTextures[p.id]
        val showQr = p.qrImage != null && tex != null && when (state) {
            LoginState.WAITING, LoginState.SCANNED, LoginState.EXPIRED,
            LoginState.CANCELLED, LoginState.ERROR -> true
            else -> false
        }
        if (showQr) {
            val dim = state == LoginState.EXPIRED || state == LoginState.CANCELLED
            ui.ctx.pushOpacity(if (dim) 0.22f else 1f)
            ui.ctx.drawImage(qrBox, tex!!.first)
            ui.ctx.popOpacity()
        } else {
            drawQrPlaceholder(qrBox, p)
        }

        val headline = when (state) {
            LoginState.IDLE -> if (p.accountName.isNotEmpty()) "当前账号已登录" else "点击下方按钮开始扫码"
            LoginState.LOADING -> "正在获取二维码…"
            LoginState.EXPIRED -> "二维码已过期"
            LoginState.CANCELLED -> "已取消登录"
            LoginState.ERROR -> "登录未完成，请重试"
            LoginState.UNCONFIGURED -> "需要完成配置"
            LoginState.SUCCESS -> "当前账号已登录"
            else -> p.message
        }
        val headlineColor = when (state) {
            LoginState.SCANNED, LoginState.SUCCESS -> Theme.GREEN
            LoginState.EXPIRED, LoginState.CANCELLED, LoginState.ERROR -> RED
            else -> Theme.TEXT_PRIMARY
        }
        val headlineLines = ui.wrap2(headline, w - 48f, 12.5f)
        var ty = qrBox.bottom + 16f
        for (line in headlineLines) {
            ui.text(line, box.x + (w - ui.width(line, 12.5f)) / 2f, ty, 12.5f, headlineColor)
            ty += 17f
        }

        val detail = when (state) {
            LoginState.ERROR -> p.message
            LoginState.UNCONFIGURED -> p.configHint
            else -> ""
        }
        if (detail.isNotEmpty()) {
            ty += 4f
            for (line in ui.wrap2(detail, w - 44f, 10f)) {
                ui.text(line, box.x + (w - ui.width(line, 10f)) / 2f, ty, 10f, Theme.TEXT_TERTIARY)
                ty += 14f
            }
        }

        drawActions(p, box, w)

        if (System.currentTimeMillis() - copiedAt in 1..1600 && state == LoginState.UNCONFIGURED) {
            ui.text("配置路径已复制", box.x + (w - ui.width("配置路径已复制", 10f)) / 2f,
                box.bottom - 18f, 10f, Theme.GREEN)
        } else {
            ui.text("登录信息只保存在本机", box.x + (w - ui.width("登录信息只保存在本机", 10f)) / 2f,
                box.bottom - 18f, 10f, Theme.TEXT_TERTIARY)
        }
    }

    private fun drawQrPlaceholder(qrBox: LayoutBounds, p: QrLoginProvider) {
        val centerX = qrBox.x + qrBox.width / 2f
        if (p.accountName.isNotEmpty() &&
            (p.state == LoginState.IDLE || p.state == LoginState.SUCCESS)
        ) {
            ui.icon("✓", centerX, qrBox.y + qrBox.height / 2f - 14f, 32f, Theme.GREEN)
            val t = "当前账号已登录"
            ui.text(t, qrBox.x + (qrBox.width - ui.width(t, 11f)) / 2f, qrBox.y + qrBox.height / 2f + 16f,
                11f, Color(0.33f, 0.33f, 0.33f, 1f))
            return
        }
        if (p.state == LoginState.UNCONFIGURED) {
            ui.icon("⚙", centerX, qrBox.y + qrBox.height / 2f - 14f, 34f, Color(0.45f, 0.45f, 0.48f, 1f))
            val t = "登录功能暂不可用"
            ui.text(t, qrBox.x + (qrBox.width - ui.width(t, 11f)) / 2f, qrBox.y + qrBox.height / 2f + 14f,
                11f, Color(0.33f, 0.33f, 0.33f, 1f))
            return
        }
        if (p.state == LoginState.IDLE) {
            val t = "尚未开始扫码"
            ui.text(t, qrBox.x + (qrBox.width - ui.width(t, 12f)) / 2f, qrBox.y + qrBox.height / 2f - 12f,
                12f, Color(0.33f, 0.33f, 0.33f, 1f))
            return
        }
        val loading = "正在获取二维码…"
        ui.text(loading, qrBox.x + (qrBox.width - ui.width(loading, 12f)) / 2f,
            qrBox.y + qrBox.height / 2f - 12f, 12f, Color(0.33f, 0.33f, 0.33f, 1f))
        ui.progressBar(LayoutBounds(qrBox.x + 34f, qrBox.y + qrBox.height / 2f + 8f, qrBox.width - 68f, 3f), RED)
    }

    private fun drawActions(p: QrLoginProvider, box: LayoutBounds, w: Float) {
        val y = box.bottom - 56f
        val state = p.state
        val logged = p.accountName.isNotEmpty()
        when {
            logged -> {
                val again = "重新扫码"
                val out = "退出登录"
                val aw = ui.width(again, 12f, Theme.FONT_SEMIBOLD) + 28f
                val ow = ui.width(out, 12f, Theme.FONT_SEMIBOLD) + 28f
                val gap = 10f
                val startX = box.x + (w - aw - ow - gap) / 2f
                button(LayoutBounds(startX, y, aw, 30f), again, RED) {
                    successSeen = false
                    p.start()
                }
                button(LayoutBounds(startX + aw + gap, y, ow, 30f), out, Color(1f, 1f, 1f, 0.10f)) {
                    p.logout()
                    successSeen = false
                }
            }
            state == LoginState.UNCONFIGURED -> {
                val label = "复制配置文件路径"
                val bw = ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f
                button(LayoutBounds(box.x + (w - bw) / 2f, y, bw, 30f), label, Color(1f, 1f, 1f, 0.10f)) {
                    DangoClient.platform.clipboard = LoginAccounts.configFilePath()
                    copiedAt = System.currentTimeMillis()
                }
            }
            state == LoginState.IDLE -> {
                val label = "开始扫码"
                val bw = ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f
                button(LayoutBounds(box.x + (w - bw) / 2f, y, bw, 30f), label, RED) {
                    successSeen = false
                    p.start()
                }
            }
            state == LoginState.EXPIRED || state == LoginState.CANCELLED || state == LoginState.ERROR -> {
                val label = "刷新二维码"
                val bw = ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f
                button(LayoutBounds(box.x + (w - bw) / 2f, y, bw, 30f), label, RED) {
                    successSeen = false
                    p.start()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 小部件

    private fun drawClose(x: Float, y: Float) {
        val b = LayoutBounds(x, y, 24f, 24f)
        ui.panel(b, 12f)
        ui.text("x", b.x + 8.5f, b.y + 7f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        hits += b to {
            ui.markPress(b)
            close()
        }
    }

    /** 胶囊按钮，和播放器里的 pill 保持同一套悬停 / 按下反馈 */
    private fun button(b: LayoutBounds, label: String, fill: Color, action: () -> Unit) {
        val col = when {
            ui.pressed(b) -> fill.copy(a = fill.a * 0.72f)
            ui.hover(b) -> fill.copy(a = fill.a * 0.85f)
            else -> fill
        }
        ui.ctx.drawShape(b, ShapeStyle(col, b.height / 2f))
        ui.text(label, b.x + (b.width - ui.width(label, 12f, Theme.FONT_SEMIBOLD)) / 2f,
            b.y + b.height / 2f - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        hits += b to {
            ui.markPress(b)
            action()
        }
    }

    private fun accentOf(id: String): Color = when (id) {
        "netease" -> RED
        "qq" -> Color.fromHex("#31C27C")
        else -> Color.fromHex("#2AA6FF")
    }

    private fun glyphOf(id: String): String = when (id) {
        "netease" -> "♪"
        "qq" -> "♫"
        else -> "♬"
    }

    fun mouseClicked(mx: Int, my: Int, button: Int) {
        if (button == 0) hits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
    }

    fun keyTyped(keyCode: Int) {
        if (keyCode == Keys.ESCAPE) {
            if (selectedId.isNotEmpty()) {
                LoginProviders.byId(selectedId)?.cancel()
                selectedId = ""
            } else {
                close()
            }
        }
    }
}
