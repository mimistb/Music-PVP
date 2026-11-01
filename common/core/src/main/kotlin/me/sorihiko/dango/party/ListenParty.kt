package me.sorihiko.dango.party

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 一起听歌（公共 MQTT 中继，开箱即用：不需要账号，也不需要任何配置文件）：
 *   房主：开房间 → 得到 5 位房间码；自己正常放歌，状态每 2.5 秒推一次
 *   成员：输入房间码加入 → 自动放同一首歌、跳到同一位置、跟着暂停 / 继续
 * 每个人都是自己从网易云拉歌，只同步"哪首歌、第几秒、在不在放"。
 *
 * 主题（都在公共 MQTT 服务器上，retain 让后进来的人立刻拿到最新状态）：
 *   dango/party/{房间码}/s        房主状态（retain）
 *   dango/party/{房间码}/m/{id}   成员在场（retain，离开时清空）
 *   dango/party/{房间码}/v/{id}   语音帧（二进制、QoS 0、不 retain；见 VoiceChat）
 *
 * 进度用"收到消息时的本机时间"推算，不依赖两台电脑的时钟一致。
 */
object ListenParty {

    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 5
    private const val TOPIC_ROOT = "dango/party"

    /** 房主广播状态的间隔 */
    private const val STATE_INTERVAL_MS = 2_500L
    /** 成员广播"我在场"的间隔 */
    private const val PRESENCE_INTERVAL_MS = 5_000L
    /** 超过这么久没再广播的成员就不显示 */
    private const val PRESENCE_STALE_MS = 40_000L
    /** 超过这么久没收到房主状态，就认为房主不在线（不跟随） */
    private const val STATE_STALE_MS = 12_000L
    /** 成员请求加载同一首歌的最小间隔 */
    private const val REQUEST_GAP_MS = 4_000L
    /** 成员纠正进度的最小间隔 */
    private const val CORRECTION_GAP_MS = 2_000L
    private const val CORRECTION_DRIFT_MS = 800L
    private const val CORRECTION_DRIFT_PAUSED_MS = 1_500L

    /** 单帧语音上限（正常一帧 162 字节；留点余量，过滤异常/恶意载荷） */
    private const val MAX_VOICE_FRAME = 1_024

    enum class Role { NONE, HOST, MEMBER }

    class Member(val uid: String, val name: String, val lastSeen: Long)

    private class RemoteState(
        val trackId: Long, val name: String, val artists: List<String>, val cover: String, val duration: Long,
        val pos: Long, val playing: Boolean,
        /** 这条状态在本机的接收时间（进度据此往后推算，不用对时） */
        val at: Long,
    )

    private class Presence(val name: String, val at: Long)

    @Volatile var role = Role.NONE
        private set
    @Volatile var code = ""
        private set
    @Volatile var hostName = ""
        private set
    @Volatile var hostUid = ""
        private set
    /** 本机的固定 ID（写进 config/Music PVP/party_id.txt），用来区分房间里的成员 */
    @Volatile var myUid = ""
        private set
    @Volatile var members: List<Member> = emptyList()
        private set
    /** 给界面看的状态 / 错误信息 */
    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set

    @Volatile private var client: MqttClient? = null
    /** 语音回调（由 VoiceChat 注册）；收到他人语音帧时在 MQTT 读线程直接调用 */
    @Volatile private var voiceListener: ((uid: String, payload: ByteArray) -> Unit)? = null
    @Volatile private var remote: RemoteState? = null
    /** 收到过实时（非 retain）状态，说明房主确实在线；只收到 retain 的旧消息时先不跟随 */
    @Volatile private var stateLive = false
    /** 最后一条状态消息的本机接收时间 */
    @Volatile private var stateAt = 0L

    private val presences = LinkedHashMap<String, Presence>()
    private val presenceLock = Any()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Party").apply { isDaemon = true } }
    private val rng = SecureRandom()

    private var lastStateAt = 0L
    private var lastPresenceAt = 0L
    private var lastRequestAt = 0L
    private var lastCorrectionAt = 0L
    private var lastMembersAt = 0L
    private var linkDown = false
    private var hostOffline = false

    init {
        Thread({
            while (true) {
                try {
                    Thread.sleep(300)
                    val r = role
                    if (r == Role.NONE) {
                        if (linkDown) linkDown = false
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (r == Role.HOST) {
                        if (now - lastStateAt >= STATE_INTERVAL_MS) publishState()
                    } else {
                        followTick(now)
                        // 房主一段时间没动静：提示一下，别让人以为卡住了
                        val stale = now - stateAt > STATE_STALE_MS
                        if (!busy && stale != hostOffline) {
                            hostOffline = stale
                            if (stale) {
                                if (status.isEmpty()) status = "房主不在线，等待中…"
                            } else if (status.startsWith("房主不在线")) {
                                status = ""
                            }
                        }
                    }
                    if (now - lastPresenceAt >= PRESENCE_INTERVAL_MS) publishPresence()
                    if (now - lastMembersAt >= 1_000L) {
                        lastMembersAt = now
                        prunePresence()
                    }
                    val down = client?.connected == false
                    if (down != linkDown) {
                        linkDown = down
                        if (down) {
                            status = "网络断开，正在重连…"
                        } else if (status.startsWith("网络断开")) {
                            status = ""
                        }
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    DangoLogger.warn("Listen party tick failed: {}", e.toString())
                }
            }
        }, "Dango-PartyTick").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { if (role != Role.NONE) leaveNow() })
    }

    private fun myName(): String = try {
        DangoMusic.instance.platform.playerName.ifBlank { "Player" }
    } catch (_: Exception) {
        "Player"
    }

    /** 每台电脑一个固定的匿名 ID（不涉及任何账号信息） */
    private fun deviceId(): String {
        val f = try {
            DangoMusic.instance.clientDir.resolve("party_id.txt").toFile()
        } catch (_: Throwable) {
            null
        }
        if (f != null && f.isFile) {
            try {
                val saved = f.readText(StandardCharsets.UTF_8).trim()
                if (saved.length in 6..24 && saved.all { it.isLetterOrDigit() }) return saved
            } catch (_: Exception) {
            }
        }
        val id = (1..10).map { CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        try {
            f?.parentFile?.mkdirs()
            f?.writeText(id, StandardCharsets.UTF_8)
        } catch (_: Exception) {
        }
        return id
    }

    private fun newCode(): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    private fun stateTopic(c: String) = "$TOPIC_ROOT/$c/s"

    private fun presenceTopic(c: String, uid: String) = "$TOPIC_ROOT/$c/m/$uid"

    private fun presenceFilter(c: String) = "$TOPIC_ROOT/$c/m/+"

    /** 语音帧主题（每个成员自己的 uid 做后缀）；QoS 0、不 retain */
    private fun voiceTopic(c: String, uid: String) = "$TOPIC_ROOT/$c/v/$uid"

    private fun voiceFilter(c: String) = "$TOPIC_ROOT/$c/v/+"

    // ---------------------------------------------------------------- 开房 / 加入 / 离开

    fun create() {
        if (busy || role != Role.NONE) return
        busy = true
        status = "正在创建房间…"
        worker.execute {
            try {
                myUid = deviceId()
                var done = false
                for (attempt in 0 until 3) {
                    val c = newCode()
                    hostUid = ""
                    hostName = ""
                    if (!openRelay(c)) {
                        code = ""
                        fail("连不上中继服务器（检查一下网络）")
                        return@execute
                    }
                    // 等一下：如果这个码上已经有别人的房间（保留消息会立刻推过来），就换一个
                    Thread.sleep(1000)
                    if (hostUid.isNotEmpty() && hostUid != myUid) {
                        closeRelay()
                        code = ""
                        continue
                    }
                    code = c
                    role = Role.HOST
                    hostUid = myUid
                    hostName = myName()
                    remote = null
                    stateLive = false
                    stateAt = 0L
                    MusicPlayer.followMode = false
                    synchronized(presenceLock) {
                        presences.clear()
                        presences[myUid] = Presence(hostName, System.currentTimeMillis())
                    }
                    rebuildMembers()
                    lastStateAt = 0L
                    lastPresenceAt = 0L
                    publishPresence()
                    publishState()
                    status = ""
                    done = true
                    break
                }
                if (!done) fail("创建房间失败（房间码冲突，请重试）")
            } catch (e: Exception) {
                fail("创建房间失败：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun join(input: String) {
        val c = input.trim().uppercase()
        if (busy || role != Role.NONE || c.isEmpty()) return
        busy = true
        status = "正在加入 $c …"
        worker.execute {
            try {
                myUid = deviceId()
                hostUid = ""
                hostName = ""
                if (!openRelay(c)) {
                    code = ""
                    fail("连不上中继服务器（检查一下网络）")
                    return@execute
                }
                code = c
                role = Role.MEMBER
                remote = null
                stateLive = false
                stateAt = 0L
                MusicPlayer.followMode = true
                lastRequestAt = 0L
                lastCorrectionAt = 0L
                lastPresenceAt = 0L
                synchronized(presenceLock) {
                    presences.clear()
                    presences[myUid] = Presence(myName(), System.currentTimeMillis())
                }
                rebuildMembers()
                publishPresence()
                // 等一会儿：收到房主的实时状态（或别的成员）才算房间真的存在。
                // 只看保留消息不够——房主崩溃 / 已关房时服务器上仍留着旧状态，会让人进到死房间。
                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    if (stateLive || members.size > 1) break
                    Thread.sleep(100)
                }
                if (!stateLive && members.size <= 1) {
                    closeRelay()
                    role = Role.NONE
                    code = ""
                    members = emptyList()
                    remote = null
                    MusicPlayer.followMode = false
                    synchronized(presenceLock) { presences.clear() }
                    fail("房间 $c 不存在或房主不在线")
                    return@execute
                }
                status = ""
            } catch (e: Exception) {
                fail("加入失败：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun leave() {
        if (role == Role.NONE) return
        worker.execute { leaveNow() }
    }

    private fun leaveNow() {
        val c = code
        val wasHost = role == Role.HOST
        val cl = client
        role = Role.NONE
        client = null
        // 退出房间时清掉语音回调：之后任何语音帧都会被丢弃，也不会再向这个房间发帧
        voiceListener = null
        if (cl != null && c.isNotEmpty()) {
            try {
                if (wasHost) cl.publish(stateTopic(c), ByteArray(0), true)
                if (myUid.isNotEmpty()) cl.publish(presenceTopic(c, myUid), ByteArray(0), true)
            } catch (_: Exception) {
            }
            cl.close()
        }
        code = ""
        hostUid = ""
        hostName = ""
        members = emptyList()
        remote = null
        stateLive = false
        stateAt = 0L
        linkDown = false
        hostOffline = false
        synchronized(presenceLock) { presences.clear() }
        MusicPlayer.followMode = false
    }

    /** 成员：重新和房主对齐（自己点了别的歌之后想回来时用） */
    fun resync() {
        if (role != Role.MEMBER) return
        lastRequestAt = 0L
        lastCorrectionAt = 0L
        MusicPlayer.followMode = true
        status = ""
    }

    private fun fail(msg: String) {
        status = msg
        busy = false
    }

    // ---------------------------------------------------------------- 语音传输通道（VoiceChat 用）

    /** 当前房间码（未加入时为空串）。 */
    val currentCode: String get() = code

    /** 中继是否已连接。 */
    val relayConnected: Boolean get() = client?.connected == true

    /**
     * 语音回调：收到他人语音载荷（第一参数为发送者 uid，第二参数为原始字节）。
     * 传 null 表示不再接收。回调在 MQTT 读线程执行，实现方自己保证线程安全。
     */
    fun setVoiceListener(listener: ((uid: String, payload: ByteArray) -> Unit)?) {
        voiceListener = listener
    }

    /** 发布一帧语音（QoS0、不 retain、二进制）。未连接或未在房间时静默丢弃。 */
    fun publishVoice(payload: ByteArray) {
        val cl = client ?: return
        val c = code
        val uid = myUid
        if (c.isEmpty() || uid.isEmpty() || role == Role.NONE) return
        if (!cl.connected || payload.isEmpty()) return
        // 单帧 20 ms 语音，正常只有 162 字节；太大的直接丢掉，避免占满公共中继
        if (payload.size > MAX_VOICE_FRAME) return
        cl.publishBytes(voiceTopic(c, uid), payload, 0, false)
    }

    // ---------------------------------------------------------------- 中继连接

    private fun openRelay(c: String): Boolean {
        closeRelay()
        code = c
        val cl = MqttClient(
            "dango-" + (if (myUid.isEmpty()) "x" else myUid) + "-" + rng.nextInt(100000),
            ::onMessage,
            ::onLinkChanged,
        )
        client = cl
        cl.subscribe(stateTopic(c))
        cl.subscribe(presenceFilter(c))
        // 语音帧（通配符订阅房间里所有人）；不 retain，只在连接期间有效
        cl.subscribe(voiceFilter(c))
        cl.start()
        // 等连接建立（最多 8 秒）
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (cl.connected) break
            if (!cl.isRunning) break
            Thread.sleep(100)
        }
        if (!cl.connected) {
            closeRelay()
            return false
        }
        // 订阅成功后给保留消息一点分发时间
        Thread.sleep(300)
        return true
    }

    private fun closeRelay() {
        val cl = client
        client = null
        if (cl != null) {
            try {
                cl.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun onLinkChanged(up: Boolean) {
        if (role == Role.NONE) return
        if (up) {
            if (status.startsWith("网络断开")) status = ""
            linkDown = false
        } else if (!linkDown) {
            linkDown = true
            status = "网络断开，正在重连…"
        }
    }

    // ---------------------------------------------------------------- 收到的消息

    private fun onMessage(topic: String, payload: ByteArray, retained: Boolean) {
        val c = code
        if (c.isEmpty()) return
        val prefix = "$TOPIC_ROOT/$c/"
        if (!topic.startsWith(prefix)) return
        val tail = topic.substring(prefix.length)

        // 语音帧是纯二进制，直接按原字节交给回调，绝不走 JSON 解析（也不打印日志）
        if (tail.startsWith("v/")) {
            val uid = tail.substring(2)
            if (uid.isEmpty() || uid == myUid) return
            if (payload.isEmpty()) return
            val listener = voiceListener ?: return
            try {
                listener(uid, payload)
            } catch (_: Throwable) {
                // 语音解码失败不能影响一起听的 JSON 流程
            }
            return
        }

        if (payload.isEmpty()) {
            if (tail == "s") {
                // 房主关掉了房间（保留消息被清空）
                if (role == Role.MEMBER) {
                    worker.execute {
                        if (role == Role.MEMBER) {
                            leaveNow()
                            status = "房主关闭了房间"
                        }
                    }
                }
            } else if (tail.startsWith("m/")) {
                val uid = tail.substring(2)
                synchronized(presenceLock) { presences.remove(uid) }
                rebuildMembers()
            }
            return
        }

        val o = try {
            JsonParser().parse(String(payload, StandardCharsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            return
        }
        when {
            tail == "s" -> onState(o, retained)
            tail.startsWith("m/") -> onPresence(tail.substring(2), o, retained)
        }
    }

    private fun onState(o: JsonObject, retained: Boolean) {
        val host = o.get("host")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        if (host.isEmpty()) return
        // 房主会收到自己发的保留消息，忽略掉
        if (role == Role.HOST && host == myUid) return
        hostUid = host
        hostName = o.get("hostName")?.asString ?: ""
        stateAt = System.currentTimeMillis()
        if (!retained) stateLive = true
        if (role != Role.MEMBER) return

        val id = o.get("trackId")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        if (id <= 0L) {
            remote = null
            // 房主没在放歌：成员也别继续放，免得各听各的
            if (MusicPlayer.state == MusicPlayer.State.PLAYING) {
                worker.execute { MusicPlayer.togglePause() }
            }
            return
        }
        remote = RemoteState(
            trackId = id,
            name = o.get("name")?.asString ?: "",
            artists = (o.get("artists")?.asString ?: "").split(" / ").filter { it.isNotEmpty() },
            cover = o.get("cover")?.asString ?: "",
            duration = o.get("duration")?.asLong ?: 0L,
            pos = o.get("pos")?.asLong ?: 0L,
            playing = o.get("playing")?.asBoolean ?: false,
            at = stateAt,
        )
    }

    private fun onPresence(uid: String, o: JsonObject, retained: Boolean) {
        if (uid.isEmpty() || uid == myUid) return
        // 服务器上留着的旧成员消息先不管，等下一次实时广播（5 秒内）再算
        if (retained) return
        val name = o.get("name")?.asString ?: "?"
        synchronized(presenceLock) { presences[uid] = Presence(name, System.currentTimeMillis()) }
        rebuildMembers()
    }

    private fun prunePresence() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(presenceLock) {
            val it = presences.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key != myUid && now - e.value.at > PRESENCE_STALE_MS) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) rebuildMembers()
    }

    private fun rebuildMembers() {
        val now = System.currentTimeMillis()
        val mine = myUid
        val host = if (role == Role.HOST) mine else hostUid
        val list = ArrayList<Member>()
        synchronized(presenceLock) {
            for ((uid, p) in presences) {
                if (uid != mine && now - p.at > PRESENCE_STALE_MS) continue
                list.add(Member(uid, p.name, p.at))
            }
        }
        list.sortWith(Comparator { a, b ->
            val ah = if (a.uid == host) 0 else 1
            val bh = if (b.uid == host) 0 else 1
            if (ah != bh) ah - bh else a.name.compareTo(b.name)
        })
        members = list
    }

    // ---------------------------------------------------------------- 房主：广播自己的播放状态

    private fun publishState() {
        val c = code
        val cl = client ?: return
        if (role != Role.HOST || c.isEmpty()) return
        lastStateAt = System.currentTimeMillis()
        val t = MusicPlayer.current
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("host", myUid)
        o.addProperty("hostName", hostName)
        if (t == null) {
            o.addProperty("trackId", 0L)
        } else {
            val st = MusicPlayer.state
            o.addProperty("trackId", t.id)
            o.addProperty("name", t.name)
            o.addProperty("artists", t.artists.joinToString(" / "))
            o.addProperty("cover", t.coverUrl)
            o.addProperty("duration", t.durationMs)
            o.addProperty("pos", MusicPlayer.positionMs.coerceAtLeast(0L))
            o.addProperty("playing", st == MusicPlayer.State.PLAYING)
        }
        cl.publish(stateTopic(c), o.toString(), true)
    }

    private fun publishPresence() {
        val c = code
        val cl = client ?: return
        if (c.isEmpty()) return
        if (myUid.isEmpty()) myUid = deviceId()
        lastPresenceAt = System.currentTimeMillis()
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("uid", myUid)
        o.addProperty("name", myName())
        cl.publish(presenceTopic(c, myUid), o.toString(), true)
        if (role == Role.HOST || role == Role.MEMBER) {
            synchronized(presenceLock) { presences[myUid] = Presence(myName(), System.currentTimeMillis()) }
            rebuildMembers()
        }
    }

    // ---------------------------------------------------------------- 成员：跟着房主放

    private fun expectedPos(s: RemoteState): Long {
        val base = s.pos.coerceAtLeast(0L)
        return if (!s.playing) base else base + (System.currentTimeMillis() - s.at).coerceAtLeast(0L)
    }

    private fun followTick(now: Long) {
        val s = remote ?: return
        if (!stateLive) return
        if (now - stateAt > STATE_STALE_MS) return
        val cur = MusicPlayer.current
        if (cur?.id != s.trackId) {
            val gap = if (MusicPlayer.state == MusicPlayer.State.ERROR) REQUEST_GAP_MS * 3 else REQUEST_GAP_MS
            if (now - lastRequestAt < gap) return
            lastRequestAt = now
            MusicPlayer.followMode = true
            val track = NeteaseTrack(s.trackId, s.name, s.artists, s.duration, s.cover)
            MusicPlayer.playTrackAt(track, { expectedPos(s) }, !s.playing)
            lastCorrectionAt = now
            return
        }
        val st = MusicPlayer.state
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR || st == MusicPlayer.State.STOPPED) return
        if (now - lastCorrectionAt < CORRECTION_GAP_MS) return

        val exp = expectedPos(s)
        val localPlaying = st == MusicPlayer.State.PLAYING
        val drift = abs(MusicPlayer.positionMs - exp)
        val limit = if (s.playing) CORRECTION_DRIFT_MS else CORRECTION_DRIFT_PAUSED_MS
        if (s.playing != localPlaying || drift > limit) {
            MusicPlayer.syncTo(exp, s.playing)
            lastCorrectionAt = now
        }
    }
}
