package me.sorihiko.dango

import bedwars.Team
import bedwarsstats.BedWarsHttp
import bedwarsstats.BedWarsStats
import bedwarsstats.HypixelGameMode
import bedwarsstats.HypixelStatsClient
import bedwarsstats.RosterStatsService
import bedwarsstats.SkyWarsStars
import bedwarsstats.StarText
import com.sun.net.httpserver.HttpServer
import me.sorihiko.dango.gfx.CjkRun
import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.gfx.GlowPass
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.HudCanvas
import me.sorihiko.dango.gfx.TextCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures
import me.sorihiko.dango.gfx.WorldView
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.impls.music.ScoreboardLine
import me.sorihiko.dango.music.CoverCache

import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 缺陷修复回归测试（无头，不启动 Minecraft、不访问外网）。
 *
 * 每个用例对应一条本轮修复的缺陷，修复前应当失败：
 * 1. CoverCache：纹理上传成功后不释放在途标记 -> 封面被 LRU 淘汰后永久空白 + 标记集合无界增长；
 * 2. ScoreboardLine.pickVisible：先截 15 行再过滤 `#` 占位行 -> 少显示真实行；
 * 3. RosterStatsService：单线程 executor 里 Thread.sleep(1100) -> 取消 / 停赛被堵住；
 * 4. HypixelStatsClient：状态与文案分两次写入 -> 回调里读到不一致的中间态；缓存命中不重复请求；
 * 5. BedWarsHttp：maxBytes / timeout 为 0 时被判成"超大响应" / 无限等待；
 * 6. BedWarsData.statsVersion：非原子自增。
 *
 * 运行：legacy 目录下 `gradlew :common:ui:defectRegressionTest --offline`
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun verify(label: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
    } else {
        failures += if (detail.isEmpty()) label else "$label ($detail)"
        println("  [FAIL] $label${if (detail.isEmpty()) "" else " -> $detail"}")
    }
}

private fun section(title: String) {
    println("\n== $title ==")
}

private fun safe(label: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        failures += "$label: $t"
        println("  [FAIL] $label -> $t")
    }
}

/** 往同一个单线程 executor 塞一个空任务并等它跑完：用来当"前面的任务都做完了"的栅栏。 */
private fun flush(executor: Executor) {
    val latch = CountDownLatch(1)
    executor.execute { latch.countDown() }
    latch.await(5, TimeUnit.SECONDS)
}

// ---------------------------------------------------------------- 假 Gfx

private class RecordingTextures : Textures {
    val uploads = AtomicInteger()
    val deletes = AtomicInteger()

    override fun upload(name: String, image: BufferedImage): Texture {
        uploads.incrementAndGet()
        return object : Texture {}
    }

    override fun delete(texture: Texture) {
        deletes.incrementAndGet()
    }
}

private class FakeGfx(override val textures: Textures) : Gfx {
    override fun newCanvas(): GuiCanvas = error("unused")
    override fun uiTextWidth(text: String, size: Float, font: String): Float = 0f
    override fun cjkWidth(text: String, size: Float, style: Int): Float = 0f
    override fun cjkPreload(c: Char, style: Int) {}
    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {}
    override fun guarded(block: () -> Unit) = block()
    override val text: TextCanvas get() = error("unused")
    override val world: WorldView get() = error("unused")
    override val glow: GlowPass get() = error("unused")
    override val hud: HudCanvas get() = error("unused")
}

private fun coverField(name: String): java.lang.reflect.Field =
    CoverCache::class.java.getDeclaredField(name).apply { isAccessible = true }

/** DangoClient.gfx 是 private set 的 lateinit，只有平台启动时才会赋值；测试里直接写字段。 */
private fun installGfx(gfx: Gfx) {
    DangoClient::class.java.getDeclaredField("gfx").apply { isAccessible = true }.set(DangoClient, gfx)
}

// ---------------------------------------------------------------- 假传输层

private const val IDENTITY_BODY =
    "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alpha\"}"
private const val STATS_BODY =
    "{\"success\":true,\"player\":{\"displayname\":\"Alpha\",\"stats\":{\"Bedwars\":{\"wins_bedwars\":7}}}}"

private fun isIdentityUrl(url: String): Boolean =
    url.contains("/lookup/name/") || url.contains("/profiles/minecraft/") || url.contains("playerdb.co")

/** 立刻返回的假传输层：身份解析与统计都成功，并分别计数。 */
private class InstantTransport : BedWarsHttp.Transport {
    val identityCalls = AtomicInteger()
    val statsCalls = AtomicInteger()

    override fun get(
        url: String,
        apiKey: String?,
        userAgent: String?,
        maxBytes: Int,
        timeoutMillis: Int,
    ): BedWarsHttp.Response {
        return if (isIdentityUrl(url)) {
            identityCalls.incrementAndGet()
            BedWarsHttp.Response(200, IDENTITY_BODY, false, null, -1, -1, -1)
        } else {
            statsCalls.incrementAndGet()
            BedWarsHttp.Response(200, STATS_BODY, false, null, -1, -1, -1)
        }
    }
}

fun main() {
    println("缺陷修复回归测试")

    // ---------------------------------------------------------------- CoverCache
    section("CoverCache 在途标记释放")
    safe("上传成功后不再占用 requested") {
        val textures = RecordingTextures()
        installGfx(FakeGfx(textures))
        val readyField = coverField("ready")
        val texturesField = coverField("textures")
        val requestedField = coverField("requested")
        @Suppress("UNCHECKED_CAST")
        val ready = readyField.get(CoverCache) as java.util.concurrent.ConcurrentLinkedQueue<Pair<String, BufferedImage>>
        @Suppress("UNCHECKED_CAST")
        val textureMap = texturesField.get(CoverCache) as LinkedHashMap<String, Texture>
        @Suppress("UNCHECKED_CAST")
        val requested = requestedField.get(CoverCache) as MutableSet<String>

        val key = "defect-regression-cover"
        ready.add(key to BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB))
        requested.add(key)                       // 模拟"下载已完成、纹理还没提交"的状态
        coverField("lastUploadVersion").set(CoverCache, -1)   // 强制这一帧真的走上传

        CoverCache.upload()

        verify("纹理已上传", textures.uploads.get() == 1, "uploads=${textures.uploads.get()}")
        verify("纹理已入表", textureMap.containsKey(key))
        verify(
            "在途标记已释放（否则淘汰后永远拿不回来）",
            !requested.contains(key),
            "requested=$requested",
        )
        verify("requested 不会无界增长", requested.size == 0, "size=${requested.size}")
    }

    // ---------------------------------------------------------------- 计分板行窗
    section("计分板 15 行窗口")
    safe("先过滤占位行再截断") {
        val all = ArrayList<ScoreboardLine>()
        for (i in 1..20) {
            // 末尾塞 5 条占位行：旧实现先截最后 15 行再过滤，会只剩 10 行真实数据
            all.add(ScoreboardLine(if (i > 15) "#hidden$i" else "line$i", i))
        }
        val picked = ScoreboardLine.pickVisible(all, 15)
        verify("行数补满 15", picked.size == 15, "实际 ${picked.size}")
        verify("没有占位行", picked.none { it.name.startsWith("#") })
        // 20 行里有 5 条末尾占位行 -> 真实数据只有 1..15，全部显示；
        // 旧实现先截最后 15 行（6..20）再过滤，只剩 10 行。
        verify("保持分数升序", picked.map { it.points } == (1..15).toList(), picked.map { it.points }.toString())
        verify("不会被末尾占位行挤掉真实数据", picked.first().points == 1 && picked.last().points == 15)
    }
    safe("全占位行返回空") {
        val all = (1..4).map { ScoreboardLine("#h$it", it) }
        verify("无可见行", ScoreboardLine.pickVisible(all, 15).isEmpty())
    }
    safe("空输入 / 非正上限") {
        verify("空输入", ScoreboardLine.pickVisible(emptyList(), 15).isEmpty())
        verify("max=0", ScoreboardLine.pickVisible(listOf(ScoreboardLine("a", 1)), 0).isEmpty())
    }

    // ---------------------------------------------------------------- RosterStatsService
    section("名单统计管线取消响应性")
    safe("取消不被 1.1s 请求间隔堵住") {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "defect-roster").apply { isDaemon = true }
        }
        val transport = InstantTransport()
        val firstReady = CountDownLatch(1)
        val service = RosterStatsService(
            object : RosterStatsService.Listener {
                override fun onStatsReady(playerName: String, teamPrefix: String, stats: BedWarsStats) {
                    if (playerName == "Alpha") firstReady.countDown()
                }

                override fun onStatsFailed(playerName: String, reason: String) {}
            },
            transport,
            executor,
        )
        try {
            service.setMatchActive(true)
            service.enqueuePlayer("Alpha", Team.WHITE, System.currentTimeMillis())
            service.enqueuePlayer("Bravo", Team.WHITE, System.currentTimeMillis())

            verify("第一个玩家取数完成", firstReady.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 3000
            while (transport.identityCalls.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            verify("第二个玩家已进入请求间隔", transport.identityCalls.get() >= 2,
                "identity=${transport.identityCalls.get()}")

            val statsBefore = transport.statsCalls.get()
            val startedAt = System.nanoTime()
            service.cancel()
            flush(executor)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            // 修复前：executor 线程正睡在 1100ms 的间隔里，cancel 任务要等它睡完
            verify("取消在 400ms 内生效", elapsedMs < 400, "${elapsedMs}ms")

            Thread.sleep(150)
            verify(
                "取消后不再发起统计请求",
                transport.statsCalls.get() == statsBefore,
                "before=$statsBefore after=${transport.statsCalls.get()}",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- HypixelStatsClient
    section("手动统计查询状态一致性")
    safe("状态与文案同批发布 + 缓存命中") {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "defect-stats").apply { isDaemon = true }
        }
        val transport = InstantTransport()
        val snapshots = ArrayList<String>()
        val readyLatch = CountDownLatch(1)
        val uuid = "0123456789abcdef0123456789abcdef"
        val client = HypixelStatsClient(
            { c ->
                val message = if (c.getErrorMessage().isNotEmpty()) c.getErrorMessage() else c.getStatusMessage()
                synchronized(snapshots) { snapshots.add(c.getState().name + "|" + message) }
                if (c.getState() == HypixelStatsClient.State.READY) readyLatch.countDown()
            },
            transport,
            executor,
        )
        try {
            client.query(uuid, System.currentTimeMillis())
            verify("查询完成", readyLatch.await(5, TimeUnit.SECONDS))
            verify("状态为 READY", client.getState() == HypixelStatsClient.State.READY)
            verify("统计数据已写入", client.getStats().wins == 7L, "wins=${client.getStats().wins}")

            synchronized(snapshots) {
                val loading = snapshots.filter { it.startsWith("LOADING|") }
                val emptyLoading = loading.filter { it.substringAfter('|').isEmpty() }
                // 修复前：state 先改成 LOADING 再改文案，第一次回调里文案还是空的
                verify("LOADING 通知都带文案", emptyLoading.isEmpty(), emptyLoading.toString())
            }

            val callsBefore = transport.statsCalls.get()
            client.query(uuid, System.currentTimeMillis() + 10_000L)
            flush(executor)
            verify(
                "六小时缓存命中不重复请求",
                transport.statsCalls.get() == callsBefore,
                "before=$callsBefore after=${transport.statsCalls.get()}",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- BedWarsHttp
    section("HTTP 边界参数")
    safe("maxBytes / timeout 传 0 不再误判") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/small") { exchange ->
            val body = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/big") { exchange ->
            val body = ByteArray(4096) { 'a'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:" + server.address.port
            // 修复前：maxBytes=0 会把所有响应都判成 tooLarge
            val small = BedWarsHttp.get(base + "/small", "", "defect-test", 0, 0)
            verify("小响应正常读取", !small.tooLarge && small.status == 200, "status=${small.status} tooLarge=${small.tooLarge}")

            val big = BedWarsHttp.get(base + "/big", "", "defect-test", 1024, 5000)
            verify("超过上限被截断", big.tooLarge)
            verify("超限时不保留服务端内容", big.body.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- 统计版本号
    section("统计版本号原子自增")
    safe("并发写入不丢计数") {
        BedWarsData.clearStats()
        val base = BedWarsData.statsVersion
        val stats = BedWarsStats(
            "u", "Alpha", 1L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 1L,
        )
        val threads = 8
        val perThread = 200
        val latch = CountDownLatch(threads)
        repeat(threads) { i ->
            Thread({
                try {
                    repeat(perThread) { j -> BedWarsData.putStats("p$i-$j", stats) }
                } finally {
                    latch.countDown()
                }
            }, "defect-version-$i").start()
        }
        latch.await(10, TimeUnit.SECONDS)
        val delta = BedWarsData.statsVersion - base
        verify("增量等于写入次数", delta == threads * perThread, "delta=$delta")
    }

    // ---------------------------------------------------------------- 名牌统计纯逻辑
    section("名牌统计纯逻辑")
    safe("SkyWars 星级阈值") {
        verify("0 经验 = 1 星", SkyWarsStars.fromExperience(0.0) == 1)
        verify("10 经验 = 2 星", SkyWarsStars.fromExperience(10.0) == 2)
        verify("35 经验 = 3 星", SkyWarsStars.fromExperience(35.0) == 3)
        verify("26800 经验 = 19 星", SkyWarsStars.fromExperience(26800.0) == 19)
        verify("31799 经验 = 19 星", SkyWarsStars.fromExperience(31799.0) == 19)
        verify("31800 经验 = 20 星", SkyWarsStars.fromExperience(31800.0) == 20)
    }
    safe("计分板模式判定") {
        verify("BedWars", HypixelGameMode.fromScoreboard("§e§lBED WARS") == HypixelGameMode.BEDWARS)
        verify("SkyWars", HypixelGameMode.fromScoreboard("§b§lSKYWARS") == HypixelGameMode.SKYWARS)
        verify("Hypixel 大厅", HypixelGameMode.fromScoreboard("§6§lHYPIXEL") == HypixelGameMode.UNKNOWN)
        verify("空标题", HypixelGameMode.fromScoreboard(null) == HypixelGameMode.UNKNOWN)
    }
    safe("星级文本配色") {
        fun plain(s: String) = s.replace(Regex("§."), "")
        val skywars = StarText.skywars(1)
        verify("SkyWars 含括号星", plain(skywars).contains("[1✯]"), skywars)
        val bedwars = StarText.bedwars(1000)
        verify("BedWars 含括号星", plain(bedwars).contains("[1000✫]"), bedwars)
        verify("以颜色码开头", bedwars.startsWith("§"), bedwars)
        verify("混淆档带 §k", StarText.skywars(200).contains("§k"), StarText.skywars(200))
    }

    // ---------------------------------------------------------------- 汇总
    println("\n==============================================")
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
        exitProcess(0)
    } else {
        println("FAILED ${failures.size} / ${passed + failures.size}")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
