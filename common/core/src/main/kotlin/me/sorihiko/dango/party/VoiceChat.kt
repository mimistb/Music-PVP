package me.sorihiko.dango.party

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

import java.lang.reflect.Field
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

/**
 * 音乐语音大厅的语音聊天：8 kHz / 16-bit / 单声道，20 ms 一帧，G.711 µ-law 压缩。
 *
 * 传输只借用 [ListenParty] 的公共 MQTT 中继（主题 `dango/party/{code}/v/{uid}`，QoS 0、二进制、
 * 不 retain），只用 JDK 自带的 `javax.sound.sampled`，不新增第三方依赖、不使用 Mixin。
 * 采集 / 播放两个守护线程只在 [start] 之后存在，[stop] 会全部关闭并释放声卡设备；
 * 不额外常驻线程，[sync] 由界面每帧调用。
 *
 * 已知限制（别期待"电竞级"低延迟）：
 *   - 走公共 MQTT 中继、服务器在公网，跨公网不可能做到 <10 ms；
 *   - 实际预期：同一局域网 30–60 ms，公网 150–450 ms（再叠加 20–120 ms 抖动缓冲）；
 *   - [latencyMs] 是接收端按"抖动缓冲当前排队帧数 × 20 ms"算出的真实估算（不是 RTT，
 *     也不是伪造的固定小值）；没有收到语音帧时就是 0。
 *
 * 设置读取：本文件在 common/core，而 core 的构建不依赖 common/ui（ui 反过来
 * `api(project(":core"))`），所以这里不能直接 import `NeteaseMusicModule`。改为按模块名
 * "NeteaseMusic" 找到模块对象，再按 @ModuleValue 反射读那 8 个私有设置字段（和
 * ConfigManager 的做法一致，字段名 = config.json 里的键）。读不到就视为语音未开启。
 */
object VoiceChat {

    // ---------------------------------------------------------------- 对外状态（界面读这些）

    @Volatile
    var status: String = "未开启"

    @Volatile
    var active: Boolean = false

    /** 延迟估算（毫秒）；按抖动缓冲排队帧数 × 20 ms，没有语音帧时为 0 */
    @Volatile
    var latencyMs: Int = 0

    // ---------------------------------------------------------------- 常量

    private const val MODULE_NAME = "NeteaseMusic"

    private const val SAMPLE_RATE = 8_000f
    /** 20 ms = 160 个样本 */
    private const val SAMPLES_PER_FRAME = 160
    private const val FRAME_MS = 20
    /** 帧头 2 字节（seq + flags）+ 160 字节 µ-law */
    private const val FRAME_BYTES = 2 + SAMPLES_PER_FRAME
    /** 采集 / 播放的声卡内部缓冲：4 帧（80 ms），越小越不容易累积延迟 */
    private const val MIC_BUFFER_BYTES = SAMPLES_PER_FRAME * 2 * 4
    private const val SPK_BUFFER_BYTES = SAMPLES_PER_FRAME * 2 * 4

    private const val LATENCY_UPDATE_NANOS = 200_000_000L
    /** 超过这么久没收到语音帧，就把延迟归零 */
    private const val SILENCE_RESET_NANOS = 1_000_000_000L

    /** 一阶高通系数（8 kHz 下约 90 Hz 截止），固定不放设置里 */
    private const val HP_ALPHA = 0.93f

    /** G.711 µ-law 编码参数 */
    private const val ULAW_CLIP = 32_635
    private const val ULAW_BIAS = 0x84

    // ---------------------------------------------------------------- 设置镜像（sync 写入，音频线程读）

    @Volatile private var pushToTalk = true
    @Volatile private var keyCode = 0
    @Volatile private var inputGain = 1f
    @Volatile private var outputVolume = 1f
    /** 0–100 的噪声门，0 = 关闭 */
    @Volatile private var gate = 0
    @Volatile private var bufferMs = 60
    @Volatile private var micMuted = false
    /** 当前是否在"说话"（sync 在主线程算好，采集线程只读） */
    @Volatile private var pttTalking = false

    // ---------------------------------------------------------------- 音频 / 线程

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    /** 每次 start/stop 自增；线程只有看到自己的代次才继续跑 */
    @Volatile private var generation = 0

    @Volatile private var mic: TargetDataLine? = null
    @Volatile private var speaker: SourceDataLine? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var playThread: Thread? = null

    // ---------------------------------------------------------------- 抖动缓冲（每 uid 一个）

    private val bufferLock = Any()
    private val buffers = HashMap<String, ArrayDeque<ShortArray>>()
    /** 每个 uid 这一段连续语音第一帧的到达时间（用于"攒够 bufferMs 再开始播"） */
    private val burstStart = HashMap<String, Long>()
    /** 最近一次收到语音帧的 nanoTime（0 = 从未收到） */
    @Volatile private var lastFrameNanos = 0L
    @Volatile private var lastLatencyNanos = 0L

    /** 固定实例，sync 里反复注册不会额外分配 */
    private val voiceListener: (String, ByteArray) -> Unit = { uid, payload -> onVoiceFrame(uid, payload) }

    // ---------------------------------------------------------------- 设置读取（反射）

    private class Settings(
        val enabled: Boolean,
        val pushToTalk: Boolean,
        val key: Int,
        val inputGain: Float,
        val outputVolume: Float,
        val gate: Int,
        val bufferMs: Int,
        val micMuted: Boolean,
    )

    @Volatile private var fieldsOwner: Class<*>? = null
    @Volatile private var fieldsCache: Map<String, Field> = emptyMap()

    private fun settingsModule(): Module? = try {
        DangoMusic.instance.moduleManager.modules.values
            .asSequence()
            .flatten()
            .firstOrNull { it.name == MODULE_NAME }
    } catch (_: Throwable) {
        null
    }

    /** 按类缓存 @ModuleValue 字段（反射 getDeclaredFields 每帧都做会白白分配数组） */
    private fun fieldsOf(m: Module): Map<String, Field> {
        val cls = m.javaClass
        if (fieldsOwner === cls) return fieldsCache
        val map = HashMap<String, Field>()
        for (f in cls.declaredFields) {
            if (!f.isAnnotationPresent(ModuleValue::class.java)) continue
            try {
                f.isAccessible = true
                map[f.name] = f
            } catch (_: Throwable) {
                // 单个字段拿不到就算了，用默认值
            }
        }
        fieldsCache = map
        fieldsOwner = cls
        return map
    }

    private fun rawSetting(m: Module, fields: Map<String, Field>, name: String): Any? {
        val f = fields[name] ?: return null
        return try {
            f.get(m)
        } catch (_: Throwable) {
            null
        }
    }

    private fun boolSetting(m: Module, fields: Map<String, Field>, name: String): Boolean? =
        (rawSetting(m, fields, name) as? BooleanValue)?.get()

    private fun intSetting(m: Module, fields: Map<String, Field>, name: String): Int? =
        (rawSetting(m, fields, name) as? IntValue)?.value

    private fun floatSetting(m: Module, fields: Map<String, Field>, name: String): Float? =
        (rawSetting(m, fields, name) as? FloatValue)?.value

    private fun readSettings(): Settings? {
        val m = settingsModule() ?: return null
        val f = fieldsOf(m)
        val enabled = boolSetting(m, f, "voiceEnabled") ?: return null
        return Settings(
            enabled = enabled,
            pushToTalk = boolSetting(m, f, "voicePushToTalk") ?: true,
            key = intSetting(m, f, "voiceKey") ?: 0,
            inputGain = floatSetting(m, f, "voiceInputGain") ?: 1f,
            outputVolume = floatSetting(m, f, "voiceOutputVolume") ?: 1f,
            gate = intSetting(m, f, "voiceGate") ?: 0,
            bufferMs = intSetting(m, f, "voiceBufferMs") ?: 60,
            micMuted = boolSetting(m, f, "voiceMicMuted") ?: false,
        )
    }

    private fun mirror(s: Settings) {
        pushToTalk = s.pushToTalk
        keyCode = s.key.coerceIn(0, 255)
        inputGain = s.inputGain.coerceIn(0.1f, 4f)
        outputVolume = s.outputVolume.coerceIn(0f, 2f)
        gate = s.gate.coerceIn(0, 100)
        bufferMs = s.bufferMs.coerceIn(20, 120)
        micMuted = s.micMuted
    }

    // ---------------------------------------------------------------- 生命周期

    /** 开启语音（要求已在音乐语音大厅且中继已连接）。任何失败都只写 [status]，不抛异常。 */
    fun start() {
        try {
            val s = readSettings()
            if (s == null) {
                status = "找不到语音设置"
                return
            }
            if (!s.enabled) {
                status = "请在设置里打开语音聊天"
                return
            }
            if (ListenParty.currentCode.isEmpty()) {
                status = "请先进入音乐语音大厅"
                return
            }
            if (!ListenParty.relayConnected) {
                status = "中继未连接"
                return
            }
            mirror(s)
            synchronized(lifecycleLock) {
                if (active) return
                teardown()   // 清掉上一次残留的设备 / 线程（不覆盖 status）
                // 常开麦模式：不用等下一次 sync 就能出声（teardown 会把 pttTalking 清零）
                pttTalking = !pushToTalk
                status = "正在打开麦克风…"
                val fmt = AudioFormat(SAMPLE_RATE, 16, 1, true, false)

                val micLine = openMic(fmt)
                if (micLine == null) {
                    status = "未检测到麦克风"
                    active = false
                    return
                }
                val spkLine = openSpeaker(fmt)
                if (spkLine == null) {
                    closeLine(micLine)
                    status = "未检测到扬声器"
                    active = false
                    return
                }
                try {
                    micLine.start()
                    spkLine.start()
                } catch (_: Throwable) {
                    closeLine(micLine)
                    closeLine(spkLine)
                    status = "音频设备打不开"
                    active = false
                    return
                }

                generation++
                val gen = generation
                mic = micLine
                speaker = spkLine
                running.set(true)
                active = true
                latencyMs = 0
                ListenParty.setVoiceListener(voiceListener)
                // 先赋值再启动，避免线程秒挂时把已清理的字段又写回去
                val capThread = Thread({ captureLoop(gen, micLine) }, "dango-voice-capture").apply {
                    isDaemon = true
                }
                val playT = Thread({ playLoop(gen, spkLine) }, "dango-voice-play").apply {
                    isDaemon = true
                }
                micThread = capThread
                playThread = playT
                capThread.start()
                playT.start()
                status = "语音中"
            }
        } catch (_: Throwable) {
            try {
                synchronized(lifecycleLock) {
                    if (active) teardown()
                }
            } catch (_: Throwable) {
            }
            status = "语音启动失败"
        }
    }

    /** 关闭并释放所有音频线程与设备；重复调用安全。 */
    fun stop() {
        try {
            synchronized(lifecycleLock) {
                if (active || mic != null || speaker != null || micThread != null || playThread != null || running.get()) {
                    teardown()
                } else {
                    synchronized(bufferLock) {
                        buffers.clear()
                        burstStart.clear()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        active = false
        latencyMs = 0
        status = "未开启"
    }

    /** 每帧 / 每 tick 调用：读设置、处理按键说话、更新状态与延迟。任何异常都吞掉。 */
    fun sync() {
        try {
            val s = readSettings()
            if (s == null || !s.enabled) {
                // 模块不存在或语音关掉：自动关闭
                stop()
                return
            }
            mirror(s)
            // 用户没点"开启"：只镜像设置，不自动开麦
            if (!running.get()) return
            if (ListenParty.currentCode.isEmpty() || !ListenParty.relayConnected) {
                stop()
                status = "已离开房间，语音已关闭"
                return
            }
            // listen party 的 leave() 会清掉回调，这里补回来（同一个 lambda 实例）
            ListenParty.setVoiceListener(voiceListener)

            // 按键说话：聊天栏打开时不生效，避免打字时误开麦
            val down = keyCode in 1..255 && isVoiceKeyDown(keyCode)
            val chat = chatOpen()
            pttTalking = !pushToTalk || (down && !chat)

            if (active) {
                status = when {
                    micMuted -> "麦克风静音"
                    pushToTalk && !pttTalking -> "按住按键说话"
                    else -> "语音中"
                }
            }
        } catch (_: Throwable) {
            // 不能把异常带到游戏主循环
        }
    }

    private fun isVoiceKeyDown(key: Int): Boolean = try {
        DangoMusic.instance.platform.isKeyDown(key)
    } catch (_: Throwable) {
        false
    }

    private fun chatOpen(): Boolean = try {
        DangoMusic.instance.platform.chatOpen
    } catch (_: Throwable) {
        false
    }

    /** 停掉全部线程 / 设备，但不改 status（由调用方决定提示语）。 */
    private fun teardown() {
        generation++
        running.set(false)
        active = false
        pttTalking = false

        val mt = micThread
        val pt = playThread
        micThread = null
        playThread = null

        val micLine = mic
        val spkLine = speaker
        mic = null
        speaker = null
        closeLine(micLine)
        closeLine(spkLine)

        try {
            mt?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            pt?.interrupt()
        } catch (_: Throwable) {
        }

        synchronized(bufferLock) {
            buffers.clear()
            burstStart.clear()
        }
        latencyMs = 0
        lastFrameNanos = 0L
        lastLatencyNanos = 0L
        ListenParty.setVoiceListener(null)
    }

    /** 音频线程出错时的兜底：释放资源并写状态，不改其它状态以外的东西。 */
    private fun audioFailed(msg: String) {
        try {
            synchronized(lifecycleLock) {
                if (active) {
                    teardown()
                    status = msg
                    DangoLogger.warn("Voice chat stopped: {}", msg)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun closeLine(line: DataLine?) {
        if (line == null) return
        try {
            line.stop()
        } catch (_: Throwable) {
        }
        try {
            line.flush()
        } catch (_: Throwable) {
        }
        try {
            line.close()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 设备

    private fun openMic(fmt: AudioFormat): TargetDataLine? = try {
        val line = AudioSystem.getTargetDataLine(fmt)
        try {
            line.open(fmt, MIC_BUFFER_BYTES)
        } catch (_: Throwable) {
            line.open(fmt)
        }
        line
    } catch (_: Throwable) {
        null
    }

    private fun openSpeaker(fmt: AudioFormat): SourceDataLine? = try {
        val line = AudioSystem.getSourceDataLine(fmt)
        try {
            line.open(fmt, SPK_BUFFER_BYTES)
        } catch (_: Throwable) {
            line.open(fmt)
        }
        line
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 采集 / 编码 / 发送

    private fun captureLoop(gen: Int, line: TargetDataLine) {
        val raw = ByteArray(SAMPLES_PER_FRAME * 2)
        val filtered = FloatArray(SAMPLES_PER_FRAME)
        val frame = ByteArray(FRAME_BYTES)
        var seq = 0
        var hpPrevX = 0f
        var hpPrevY = 0f
        try {
            while (gen == generation && running.get()) {
                if (!readFully(line, raw)) {
                    audioFailed("麦克风已断开")
                    return
                }
                var sum = 0f
                for (i in 0 until SAMPLES_PER_FRAME) {
                    val lo = raw[i * 2].toInt() and 0xFF
                    val hi = raw[i * 2 + 1].toInt() shl 8
                    val x = (lo or hi).toShort() / 32768f
                    // 一阶高通（去直流 / 低频嗡嗡声）
                    val y = HP_ALPHA * (hpPrevY + x - hpPrevX)
                    hpPrevX = x
                    hpPrevY = y
                    filtered[i] = y
                    sum += abs(y)
                }

                // 静音 / 麦克风静音 / 没按住 PTT：不发帧（靠 active 表示在线，省流量）
                if (micMuted || !pttTalking) continue
                // 噪声门：0–100 映射到平均振幅阈值 0–0.2
                val threshold = gate / 100f * 0.2f
                if (threshold > 0f && sum / SAMPLES_PER_FRAME < threshold) continue

                val gain = inputGain
                frame[0] = seq.toByte()
                seq = (seq + 1) and 0xFF
                frame[1] = 0x01   // bit0 = 说话中
                for (i in 0 until SAMPLES_PER_FRAME) {
                    val v = filtered[i] * gain * 32767f
                    val pcm = when {
                        v > 32767f -> 32767
                        v < -32768f -> -32768
                        else -> v.toInt()
                    }
                    frame[2 + i] = mulawEncode(pcm)
                }
                ListenParty.publishVoice(frame)
            }
        } catch (_: Throwable) {
            audioFailed("麦克风已断开")
        }
    }

    private fun readFully(line: TargetDataLine, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = line.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    // ---------------------------------------------------------------- 接收 / 抖动缓冲 / 播放

    private fun onVoiceFrame(uid: String, payload: ByteArray) {
        try {
            if (payload.size < 3) return
            val flags = payload[1].toInt() and 0xFF
            if ((flags and 0x01) == 0) return   // 非"说话中"帧不播
            val count = (payload.size - 2).coerceAtMost(SAMPLES_PER_FRAME)
            val pcm = ShortArray(SAMPLES_PER_FRAME)
            for (i in 0 until count) pcm[i] = mulawDecode(payload[2 + i])

            val maxFrames = bufferFrames() * 2
            synchronized(bufferLock) {
                val q = buffers.getOrPut(uid) { ArrayDeque<ShortArray>() }
                if (q.isEmpty()) burstStart[uid] = System.nanoTime()
                q.addLast(pcm)
                // 积压超过 2 倍缓冲时长：丢最旧的，避免越拖越晚
                while (q.size > maxFrames) q.removeFirst()
            }
            lastFrameNanos = System.nanoTime()
        } catch (_: Throwable) {
            // 坏帧直接忽略，不影响其它人
        }
    }

    private fun bufferFrames(): Int = (bufferMs / FRAME_MS).coerceIn(1, 6)

    private fun playLoop(gen: Int, line: SourceDataLine) {
        val acc = IntArray(SAMPLES_PER_FRAME)
        val bytes = ByteArray(SAMPLES_PER_FRAME * 2)
        try {
            while (gen == generation && running.get()) {
                val now = System.nanoTime()
                val target = bufferFrames()
                val targetNanos = target.toLong() * FRAME_MS * 1_000_000L
                java.util.Arrays.fill(acc, 0)
                // 每 20 ms 从每个人的抖动缓冲各取一帧，多人时逐样本相加
                synchronized(bufferLock) {
                    for ((uid, q) in buffers) {
                        if (q.isEmpty()) continue
                        // 先攒够 bufferMs 再开始播；说话时间太短（没攒够）也按到达时间强制放出
                        val start = burstStart[uid] ?: now
                        if (q.size < target && now - start < targetNanos) continue
                        val f = q.pollFirst() ?: continue
                        if (q.isEmpty()) burstStart.remove(uid)
                        for (i in 0 until SAMPLES_PER_FRAME) acc[i] += f[i].toInt()
                    }
                }
                val vol = outputVolume
                for (i in 0 until SAMPLES_PER_FRAME) {
                    var s = (acc[i] * vol).toInt()
                    if (s > 32767) s = 32767
                    if (s < -32768) s = -32768
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                // write 会在声卡缓冲满时阻塞：写入节奏由声卡自身驱动，不需要额外 sleep
                line.write(bytes, 0, bytes.size)
                updateLatency()
            }
        } catch (_: Throwable) {
            audioFailed("扬声器已断开")
        }
    }

    /** 延迟估算：排队帧数 × 20 ms；约每 200 ms 更新一次，久没收到帧就归零。 */
    private fun updateLatency() {
        val now = System.nanoTime()
        if (now - lastLatencyNanos < LATENCY_UPDATE_NANOS) return
        lastLatencyNanos = now
        latencyMs = if (now - lastFrameNanos > SILENCE_RESET_NANOS) 0 else queuedFrames() * FRAME_MS
    }

    private fun queuedFrames(): Int = synchronized(bufferLock) {
        var max = 0
        for (q in buffers.values) {
            if (q.size > max) max = q.size
        }
        max
    }

    // ---------------------------------------------------------------- G.711 µ-law

    /** 16-bit PCM → µ-law 8-bit（标准 G.711 算法） */
    private fun mulawEncode(pcm: Int): Byte {
        var sample = pcm
        val sign = if (sample < 0) 0x80 else 0
        if (sample < 0) sample = -sample
        if (sample > ULAW_CLIP) sample = ULAW_CLIP
        sample += ULAW_BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (sample and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
    }

    /** µ-law 8-bit → 16-bit PCM */
    private fun mulawDecode(value: Byte): Short {
        val u = (value.toInt() and 0xFF).inv() and 0xFF
        val t = ((((u and 0x0F) shl 3) + ULAW_BIAS) shl ((u and 0x70) shr 4))
        return if ((u and 0x80) != 0) (ULAW_BIAS - t).toShort() else (t - ULAW_BIAS).toShort()
    }
}
