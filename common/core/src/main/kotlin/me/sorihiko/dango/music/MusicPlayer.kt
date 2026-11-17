package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import me.sorihiko.dango.DangoLogger

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出。
 * 播放进度 = 起始偏移 + 声卡实际已经播放的时长，所以歌词同步是精确的，拖动进度条也没问题。
 */
object MusicPlayer {

    enum class State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var current: NeteaseTrack? = null
        private set
    @Volatile var message: String = ""
        private set

    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var index = -1
        private set

    @Volatile var shuffle = false
    /** 0..1 */
    @Volatile var volume = 0.5f

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }

    /** 失败后延时换首：单线程复用，避免每次失败都新建线程 */
    private val retry = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Dango-MusicRetry").apply { isDaemon = true }
    }
    private val random = Random()

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    /** 连续加载 / 播放失败的次数（真正开始出声后清零） */
    @Volatile private var failStreak = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var seekBaseMs = 0L
    @Volatile private var pausedAtMs = 0L

    val durationMs: Long get() = current?.durationMs ?: 0L

    // ---------------------------------------------------------------- 鼓点检测（文闪用）

    /** (歌曲内时间 ms, 强度 0..1)，解码时根据低频能量突增检测出来 */
    private class Beat(val timeMs: Long, val strength: Float)
    private val beats = ArrayDeque<Beat>()

    /**
     * 当前的"鼓点冲击"强度：最近一个鼓点之后迅速衰减，0..1。
     * posMs 用歌曲内时间（和歌词同一条时间线）。
     */
    fun beatPulse(posMs: Long): Float {
        var best = 0f
        synchronized(beats) {
            for (b in beats) {
                val dt = posMs - b.timeMs
                if (dt < 0 || dt > 600) continue
                best = max(best, b.strength * kotlin.math.exp(-dt / 110f))
            }
        }
        return best
    }

    private fun clearBeats() = synchronized(beats) { beats.clear() }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> seekBaseMs + (line?.microsecondPosition ?: 0L) / 1000
            State.PAUSED -> pausedAtMs
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int) {
        queue = tracks
        failStreak = 0
        playIndex(startIndex)
    }

    fun playIndex(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        index = idx
        val track = q[idx]
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track) }
    }

    /** 一起听：正在跟随房主（歌放完不自动下一首，等房主切） */
    @Volatile var followMode = false

    /**
     * 一起听：跟随房主播放某首歌。
     * 起始位置在下载完成、真正开始播放前才计算（下载要好几秒，提前算会落后）。
     */
    fun playTrackAt(track: NeteaseTrack, startAt: () -> Long, paused: Boolean) {
        queue = listOf(track)
        index = 0
        val gen = ++generation
        stopLine()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    /** 一起听：跳到 ms 并设成播放 / 暂停（当前这首已经下载好时用） */
    fun syncTo(ms: Long, play: Boolean) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        pausedAtMs = target
        state = if (play) State.PLAYING else State.PAUSED   // 解码线程据此决定声卡是开着还是停着
        val gen = ++generation
        stopLine()
        if (wavCurrent()) startDecodeWav(gen, bytes, target) else startDecode(gen, bytes, target)
    }

    /** 当前这首是不是本地 wav（wav 不能用 JLayer 解） */
    private fun wavCurrent(): Boolean {
        val t = current ?: return false
        if (!LocalLibrary.isLocal(t.id)) return false
        return LocalLibrary.fileFor(t.id)?.fileName?.toString()?.lowercase()?.endsWith(".wav") == true
    }

    fun next() {
        val q = queue
        if (q.isEmpty()) return
        playIndex(if (shuffle && q.size > 1) pickRandom(q.size) else index + 1)
    }

    fun prev() {
        if (positionMs > 3000) {
            seek(0)
            return
        }
        playIndex(index - 1)
    }

    private fun pickRandom(size: Int): Int {
        var r: Int
        do { r = random.nextInt(size) } while (r == index)
        return r
    }

    /** 幂等暂停：只在真的在播时停下，供"房主停止跟随"这类异步指令使用 */
    fun pause() {
        if (state == State.PLAYING) togglePause()
    }

    fun togglePause() {
        when (state) {
            State.PLAYING -> {
                pausedAtMs = positionMs
                state = State.PAUSED
                line?.stop()
            }
            State.PAUSED -> {
                val l = line
                if (l != null) {
                    // 声卡输出只是停住了，接着放就行；解码线程看到状态变回 PLAYING 会继续写数据
                    state = State.PLAYING
                    l.start()
                } else {
                    // BUG-05: 暂停时输出流还没打开（曲终 / 打开中）→ seek 会保持暂停，
                    // 解码线程停在暂停等待里，播放键看起来永久失灵。先回到播放态再定位。
                    state = State.PLAYING
                    seek(pausedAtMs)
                }
            }
            else -> {}
        }
    }

    fun seek(ms: Long) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (state == State.PAUSED) pausedAtMs = target
        val gen = ++generation
        stopLine()
        if (wavCurrent()) startDecodeWav(gen, bytes, target) else startDecode(gen, bytes, target)
    }

    fun stop() {
        ++generation
        stopLine()
        audio = null
        current = null
        state = State.STOPPED
        message = ""
    }

    // ---------------------------------------------------------------- 加载 / 解码

    private fun load(gen: Int, track: NeteaseTrack, startAt: (() -> Long)? = null, paused: Boolean = false) {
        try {
            // 本地曲目（id < 0）：直接读文件，mp3 走 JLayer，wav 走 Java Sound
            if (LocalLibrary.isLocal(track.id)) {
                val file = LocalLibrary.fileFor(track.id) ?: return fail(gen, "找不到本地文件")
                val bytes = Files.readAllBytes(file)
                if (gen != generation) return
                audio = bytes
                message = ""
                val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
                if (paused) {
                    pausedAtMs = start
                    state = State.PAUSED
                }
                if (file.fileName.toString().lowercase().endsWith(".wav")) {
                    startDecodeWav(gen, bytes, start)
                } else {
                    startDecode(gen, bytes, start)
                }
                return
            }
            // 1) 先用原生音源
            val native = try {
                when (track.provider) {
                    "qq" -> QqMusicApi.songUrl(track.providerId)
                    "kugou" -> KugouApi.songUrl(track.providerId)
                    else -> NeteaseApi.songUrl(track.id)
                }
            } catch (_: Exception) {
                null
            }
            if (gen != generation) return
            var bytes = native?.let { url -> try { NeteaseApi.request(url.url) } catch (_: Exception) { null } }
            if (gen != generation) return
            if (native != null && bytes != null && bytes.size >= 10_000) {
                audio = bytes
                message = if (native.trialOnly) "仅试听片段" else ""
                val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
                if (paused) {
                    pausedAtMs = start
                    state = State.PAUSED
                }
                startDecode(gen, bytes, start)
                return
            }

            // 2) 原生地址为空 / 下载失败（VIP、下架、地区限制）：按歌名 + 歌手跨平台换源
            val alt = crossSource(track, gen)
            if (gen != generation) return
            if (alt != null) {
                bytes = try { NeteaseApi.request(alt.url) } catch (_: Exception) { null }
                if (gen != generation) return
                if (bytes != null && bytes.size >= 10_000) {
                    audio = bytes
                    message = "已换源：" + alt.sourceName + (if (alt.trialOnly) "（试听）" else "")
                    val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
                    if (paused) {
                        pausedAtMs = start
                        state = State.PAUSED
                    }
                    startDecode(gen, bytes, start)
                    return
                }
            }
            if (gen != generation) return
            fail(
                gen,
                when {
                    native == null && track.provider == "netease" -> "拿不到播放地址"
                    track.provider != "netease" -> "该曲目暂无免费音源（可能为 VIP / 付费）"
                    NeteaseApi.loggedIn -> "这首歌没有播放权限"
                    else -> "需要登录才能播放（VIP 歌曲）"
                },
            )
        } catch (e: Throwable) {
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            DangoLogger.warn("Music load failed: {}", e.toString())
            fail(gen, "加载失败：" + describe(e))
        }
    }

    /** 换源结果：可播放地址 + 来源名（提示用） */
    private class PlaybackSource(val url: String, val trialOnly: Boolean, val sourceName: String)

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** 匹配度：歌名完全相同 100、互相包含 60，歌手有交集再 +30 */
    private fun matchScore(track: NeteaseTrack, cand: NeteaseTrack): Int {
        val want = norm(track.name)
        val got = norm(cand.name)
        if (want.isEmpty() || got.isEmpty()) return 0
        var s = when {
            want == got -> 100
            want.contains(got) || got.contains(want) -> 60
            else -> 0
        }
        if (s > 0 && track.artists.isNotEmpty()) {
            val mine = track.artists.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
            if (mine.isNotEmpty() && cand.artists.any { norm(it) in mine }) s += 30
        }
        return s
    }

    /**
     * 原生音源不可用时，用「歌名 + 歌手」在其它平台检索同一首歌。
     * 只接受匹配度 >= 60 的结果，按匹配度从高到低逐个尝试拿播放地址。
     */
    private fun crossSource(track: NeteaseTrack, gen: Int): PlaybackSource? {
        val keyword = (listOf(track.name) + track.artists.take(1))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (keyword.isBlank()) return null
        val candidates = ArrayList<Pair<String, NeteaseTrack>>()
        if (track.provider != "netease") {
            try {
                NeteaseApi.search(keyword, 10).forEach { candidates += "netease" to it }
            } catch (_: Exception) {
            }
        }
        if (track.provider != "qq") {
            try {
                QqMusicApi.search(keyword, 10).forEach { candidates += "qq" to it }
            } catch (_: Exception) {
            }
        }
        if (track.provider != "kugou") {
            try {
                KugouApi.search(keyword, 10).forEach { candidates += "kugou" to it }
            } catch (_: Exception) {
            }
        }
        if (gen != generation) return null
        val ranked = candidates
            .map { (provider, cand) -> Triple(provider, cand, matchScore(track, cand)) }
            .filter { it.third >= 60 }
            .sortedByDescending { it.third }
        for ((provider, cand, _) in ranked) {
            if (gen != generation) return null
            val url = try {
                when (provider) {
                    "qq" -> QqMusicApi.songUrl(cand.providerId)
                    "kugou" -> KugouApi.songUrl(cand.providerId)
                    else -> NeteaseApi.songUrl(cand.id)
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val name = when (provider) {
                "qq" -> "QQ 音乐"
                "kugou" -> "酷狗音乐"
                else -> "网易云"
            }
            return PlaybackSource(url.url, url.trialOnly, name)
        }
        return null
    }

    /** 放不了就提示一下，1.5 秒后自动跳下一首；连着放不了 5 首就停下来，免得整张 VIP 歌单无限重试 */
    private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        failStreak++
        state = State.ERROR
        message = msg
        val giveUp = failStreak >= 5
        if (giveUp) message = "连续 ${failStreak} 首无法播放，已停止"
        // 不要占用唯一的 loader 线程睡觉：否则失败后的 1.5 秒内用户点的歌全部排队卡住
        // 也不能每次失败都新建线程（一首坏歌反复重试会不断增大线程数），统一走一个延时调度线程
        retry.schedule({
            if (gen == generation && !giveUp && queue.size > 1) next()
        }, 1500L, TimeUnit.MILLISECONDS)
    }

    /** 真正开始出声时清零连续失败计数 */
    private fun markPlaybackStarted() {
        failStreak = 0
        state = State.PLAYING
    }

    private fun describe(e: Throwable): String {
        val m = e.message?.take(60)
        return e.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        var opened: SourceDataLine? = null
        try {
            opened = AudioSystem.getSourceDataLine(format)
            try {
                opened.open(format, bufferBytes)
            } catch (e: Exception) {
                opened.open(format)
            }
            return opened
        } catch (e: Throwable) {
            // 打不开的 line 也要关：否则每次换歌失败都会留下一个声卡设备句柄
            closeQuietly(opened)
            last = e
            DangoLogger.warn("Default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mi in AudioSystem.getMixerInfo()) {
            var candidate: SourceDataLine? = null
            try {
                val mixer = AudioSystem.getMixer(mi)
                if (!mixer.isLineSupported(info)) continue
                candidate = mixer.getLine(info) as SourceDataLine
                candidate.open(format)
                DangoLogger.info("Using audio device: {}", mi.name)
                return candidate
            } catch (e: Throwable) {
                closeQuietly(candidate)
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }

    /** 关掉一个可能没成功打开的 line，异常全部吞掉 */
    private fun closeQuietly(l: SourceDataLine?) {
        if (l == null) return
        try {
            l.stop()
        } catch (_: Throwable) {
        }
        try {
            l.flush()
        } catch (_: Throwable) {
        }
        try {
            l.close()
        } catch (_: Throwable) {
        }
    }

    private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long) {
        Thread({ decodeLoop(gen, bytes, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop(gen: Int, bytes: ByteArray, startMs: Long) {
        var out: SourceDataLine? = null
        var finished = false
        try {
            val bitstream = Bitstream(ByteArrayInputStream(bytes))
            val decoder = Decoder()

            // 跳到目标位置：只读帧头不解码，很快
            var skipped = 0f
            while (skipped < startMs) {
                val h = bitstream.readFrame() ?: break
                skipped += h.ms_per_frame()
                bitstream.closeFrame()
            }
            if (gen != generation) return
            seekBaseMs = skipped.toLong()
            clearBeats()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f

            var appliedVolume = -1f
            while (gen == generation) {
                val header = bitstream.readFrame()
                if (header == null) {
                    finished = true
                    break
                }
                val sb = try {
                    decoder.decodeFrame(header, bitstream) as SampleBuffer
                } catch (_: Exception) {
                    bitstream.closeFrame()
                    continue
                }
                if (out == null) {
                    val format = AudioFormat(header.frequency().toFloat(), 16, sb.channelCount, true, false)
                    val opened = openLine(format, header.frequency() * sb.channelCount * 2 / 4)   // 约 0.25 秒缓冲
                    // openLine 在默认设备失败时会遍历所有 mixer，可能阻塞几百毫秒；
                    // 这段时间里用户可能已经 seek / 切歌，旧线程不能再抢回 line 并改回 PLAYING。
                    if (gen != generation) {
                        try {
                            opened.close()
                        } catch (_: Exception) {
                        }
                        return
                    }
                    out = opened
                    line = out
                    if (state == State.PAUSED) {
                        out.stop()
                    } else {
                        out.start()
                        markPlaybackStarted()
                    }
                }
                // 暂停：不再往声卡写数据，原地等待。
                // （Java Sound 在输出停止时被写数据会自动重新开始或直接返回，
                //   不这样等的话会一口气把整首歌"读完"，然后误以为放完了跳下一首）
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()   // 写数据时可能被自动重新开始，确保停住
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    applyVolume(out, volume)
                    appliedVolume = volume
                }
                val len = sb.bufferLength
                val pcm = sb.buffer

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = header.ms_per_frame()
                val ch = sb.channelCount.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < len) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (len / ch).coerceAtLeast(1)
                if (avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(data, 0, data.size)
                bitstream.closeFrame()
            }
            if (finished && gen == generation) {
                out?.drain()
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                state = State.ERROR
                message = "播放失败：" + describe(e)
            }
        } finally {
            if (line === out) line = null
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished && gen == generation) onTrackEnd()
    }

    /** 一首放完：跟随房主时停在结尾，否则自动下一首 */
    private fun onTrackEnd() {
        if (followMode) {
            pausedAtMs = durationMs
            state = State.PAUSED
        } else {
            next()
        }
    }

    // ---------------------------------------------------------------- WAV（Java Sound 直接输出 PCM）

    private fun startDecodeWav(gen: Int, bytes: ByteArray, startMs: Long) {
        Thread({ decodeWavLoop(gen, bytes, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeWavLoop(gen: Int, bytes: ByteArray, startMs: Long) {
        var out: SourceDataLine? = null
        var finished = false
        try {
            val base = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            // 统一转成 16 位小端 PCM，声卡格式简单、拖动进度也好算
            val pcm = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.format.sampleRate, 16, base.format.channels,
                base.format.channels * 2, base.format.sampleRate, false,
            )
            val src: AudioInputStream = AudioSystem.getAudioInputStream(pcm, base)
            val frameSize = pcm.frameSize.coerceAtLeast(1)
            val frameRate = pcm.frameRate.toLong().coerceAtLeast(1)

            // 跳到目标位置：丢掉前面的字节
            val skipTarget = startMs * frameRate / 1000 * frameSize
            if (skipTarget > 0) {
                val tmp = ByteArray(min(skipTarget, 64 * 1024L).toInt())
                var left = skipTarget
                while (left > 0 && gen == generation) {
                    val r = src.read(tmp, 0, min(tmp.size.toLong(), left).toInt())
                    if (r <= 0) break
                    left -= r
                }
            }
            if (gen != generation) return
            seekBaseMs = (skipTarget / frameSize) * 1000 / frameRate
            clearBeats()

            out = openLine(pcm, (pcm.sampleRate.toInt() * frameSize / 4).coerceAtLeast(4096))
            line = out
            if (state == State.PAUSED) {
                out.stop()
            } else {
                out.start()
                markPlaybackStarted()
            }

            var appliedVolume = -1f
            val buf = ByteArray(frameSize * 1024)
            while (gen == generation) {
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()
                    Thread.sleep(15)
                }
                if (gen != generation) break
                if (volume != appliedVolume) {
                    applyVolume(out, volume)
                    appliedVolume = volume
                }
                val n = src.read(buf, 0, buf.size)
                if (n < 0) {
                    finished = true
                    break
                }
                if (n > 0) out.write(buf, 0, n)
            }
            if (finished && gen == generation) out.drain()
        } catch (e: Throwable) {
            DangoLogger.warn("Wav decode failed: {}", e.toString())
            if (gen == generation) {
                state = State.ERROR
                message = "播放失败：" + describe(e)
            }
        } finally {
            if (line === out) line = null
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished && gen == generation) onTrackEnd()
    }

    private fun applyVolume(out: SourceDataLine, v: Float) {
        try {
            val gain = out.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (v <= 0.0001f) gain.minimum else (20.0 * log10(v.toDouble())).toFloat()
            gain.value = db.coerceIn(gain.minimum, gain.maximum)
        } catch (_: Exception) {
        }
    }

    private fun stopLine() {
        val l = line
        line = null
        try {
            l?.stop()
            l?.flush()
            l?.close()
        } catch (_: Exception) {
        }
    }
}
