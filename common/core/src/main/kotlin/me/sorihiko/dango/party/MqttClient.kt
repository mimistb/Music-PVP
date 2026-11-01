package me.sorihiko.dango.party

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 MQTT 3.1.1 客户端（只用到 QoS 0 + retain）。
 *
 * "一起听"不需要任何账号或配置：连上公共 MQTT 服务器，开一个随机主题就能互相同步。
 * 断线会自动重连并重新订阅；断线期间 publish 直接丢弃（上层按固定间隔重发，不会积压）。
 *
 * 只在本项目内部使用，不支持 TLS / QoS 1、2 / 遗嘱消息等高级特性。
 */
class MqttClient(
    private val clientId: String,
    private val onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
    /** 连接状态变化（给界面显示"正在重连"用） */
    private val onConnected: (Boolean) -> Unit = {},
) {

    companion object {
        /** 公共服务器（按顺序尝试：连不上或者连续掉线就换下一个） */
        private val BROKERS = arrayOf("broker.emqx.io" to 1883, "broker.hivemq.com" to 1883)
        private const val KEEPALIVE = 45
        private const val IDLE_TIMEOUT_MS = 25_000
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_PACKET = 1_000_000
    }

    private val running = AtomicBoolean(false)
    private val subs = LinkedHashSet<String>()
    private val writeLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var input: InputStream? = null
    @Volatile var connected = false
        private set

    private var brokerIndex = 0
    private var failsOnBroker = 0
    @Volatile private var packetId = 0

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ loop() }, "Dango-Mqtt").apply { isDaemon = true }.start()
    }

    /** 订阅一个主题；断线重连后会自动重新订阅 */
    fun subscribe(filter: String) {
        synchronized(subs) { subs.add(filter) }
        if (connected) {
            try {
                sendSubscribe(listOf(filter))
            } catch (e: Exception) {
                dropConnection()
            }
        }
    }

    fun publish(topic: String, text: String, retain: Boolean) =
        publish(topic, text.toByteArray(StandardCharsets.UTF_8), retain)

    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        if (!connected) return
        val body = ByteArrayOutputStream(topic.length + payload.size + 8)
        writeString(body, topic)
        body.write(payload)
        try {
            sendPacket(3, if (retain) 1 else 0, body.toByteArray())
        } catch (e: Exception) {
            // 写失败说明连接已经废了：关掉，让读线程重连
            dropConnection()
        }
    }

    /**
     * 二进制发布（语音帧用）。
     *
     * 本项目只实现了 MQTT 3.1.1 的 QoS 0，[qos] 仅为接口对齐保留：传非 0 也按 QoS 0 发送，
     * 不保留（retain 默认 false）。断线时静默丢弃，上层不积压。
     */
    fun publishBytes(topic: String, payload: ByteArray, qos: Int = 0, retain: Boolean = false) {
        publish(topic, payload, retain)
    }

    fun close() {
        if (!running.getAndSet(false)) return
        try {
            sendPacket(14, 0, ByteArray(0))      // DISCONNECT
        } catch (_: Exception) {
        }
        dropConnection()
    }

    // ---------------------------------------------------------------- 连接 / 读循环

    private fun loop() {
        while (running.get()) {
            try {
                connectOnce()
                readLoop()
            } catch (e: Throwable) {
                if (running.get()) {
                    failsOnBroker++
                    if (failsOnBroker >= 2) {
                        failsOnBroker = 0
                        brokerIndex = (brokerIndex + 1) % BROKERS.size
                    }
                }
            } finally {
                dropConnection()
            }
            if (!running.get()) break
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun connectOnce() {
        val (host, port) = BROKERS[brokerIndex]
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = IDLE_TIMEOUT_MS
        val os = s.outputStream
        socket = s
        out = os
        input = s.inputStream

        // CONNECT：协议名 + 级别 4 + clean session + keepalive + client id
        val body = ByteArrayOutputStream(64)
        writeString(body, "MQTT")
        body.write(4)
        body.write(0x02)
        body.write(KEEPALIVE ushr 8)
        body.write(KEEPALIVE and 0xFF)
        writeString(body, clientId)
        sendPacket(1, 0, body.toByteArray())

        val ins = input ?: throw IOException("no stream")
        val type = ins.read()
        if (type < 0) throw IOException("connect eof")
        val len = readRemainingLength(ins)
        val ack = readFully(ins, len)
        if ((type ushr 4) != 2 || ack.size < 2 || (ack[1].toInt() and 0xFF) != 0) {
            throw IOException("connack rejected")
        }
        failsOnBroker = 0
        connected = true
        onConnected(true)
        val filters = synchronized(subs) { subs.toList() }
        if (filters.isNotEmpty()) sendSubscribe(filters)
    }

    private fun readLoop() {
        val ins = input ?: return
        while (running.get() && connected) {
            val first: Int
            try {
                first = ins.read()
            } catch (e: SocketTimeoutException) {
                // 空闲太久：发个心跳，顺便确认连接还活着
                sendPacket(12, 0, ByteArray(0))
                continue
            }
            if (first < 0) throw IOException("eof")
            val len = readRemainingLength(ins)
            val body = readFully(ins, len)
            if ((first ushr 4) == 3) handlePublish(first and 0x0F, body)
        }
    }

    private fun handlePublish(flags: Int, body: ByteArray) {
        val retain = (flags and 0x01) != 0
        val qos = (flags ushr 1) and 0x03
        if (body.size < 2) return
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var i = 2
        if (i + topicLen > body.size) return
        val topic = String(body, i, topicLen, StandardCharsets.UTF_8)
        i += topicLen
        if (qos > 0) i += 2                      // 我们只订阅 QoS 0，这里仅作保护
        if (i > body.size) return
        // 载荷原样交给回调：文本消息由上层按 JSON 解析，语音帧直接当二进制切片用，
        // 这里不做任何编码转换。
        val payload = body.copyOfRange(i, body.size)
        onMessage(topic, payload, retain)
    }

    private fun dropConnection() {
        val was = connected
        connected = false
        val s = socket
        socket = null
        out = null
        input = null
        try {
            s?.close()
        } catch (_: Exception) {
        }
        if (was) onConnected(false)
    }

    // ---------------------------------------------------------------- 包编码 / 解码

    private fun sendSubscribe(filters: List<String>) {
        if (filters.isEmpty()) return
        val body = ByteArrayOutputStream(64)
        val id = nextPacketId()
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        for (f in filters) {
            writeString(body, f)
            body.write(0)                        // 只要 QoS 0
        }
        sendPacket(8, 2, body.toByteArray())
    }

    private fun nextPacketId(): Int {
        val id = if (packetId >= 0xFFFF || packetId <= 0) 1 else packetId + 1
        packetId = id
        return id
    }

    private fun sendPacket(type: Int, flags: Int, body: ByteArray) {
        val os = out ?: throw IOException("not connected")
        synchronized(writeLock) {
            os.write((type shl 4) or flags)
            writeRemainingLength(os, body.size)
            if (body.isNotEmpty()) os.write(body)
            os.flush()
        }
    }

    private fun writeString(o: OutputStream, s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        o.write((b.size ushr 8) and 0xFF)
        o.write(b.size and 0xFF)
        o.write(b)
    }

    private fun writeRemainingLength(o: OutputStream, n: Int) {
        var x = n
        while (true) {
            val d = x % 128
            x /= 128
            o.write(if (x > 0) d or 0x80 else d)
            if (x == 0) break
        }
    }

    private fun readRemainingLength(ins: InputStream): Int {
        var mul = 1
        var value = 0
        var i = 0
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("eof")
            value += (b and 0x7F) * mul
            if (value > MAX_PACKET) throw IOException("packet too large")
            if ((b and 0x80) == 0) return value
            mul *= 128
            if (++i >= 4) throw IOException("bad remaining length")
        }
    }

    private fun readFully(ins: InputStream, len: Int): ByteArray {
        if (len < 0 || len > MAX_PACKET) throw IOException("bad length")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
        return buf
    }
}
