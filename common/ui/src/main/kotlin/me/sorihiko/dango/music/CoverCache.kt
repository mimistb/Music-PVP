package me.sorihiko.dango.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.cache.MediaCache
import me.sorihiko.dango.gfx.Texture

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 多张封面的缓存（封面墙、列表缩略图用）：
 * 后台下载 + 裁成正方形，渲染线程上传成纹理。
 *
 * - 纹理大约保留 [MAX] 张，超出时删最久没画过的；但最近几帧还在画的不删
 *   （排行榜 + 大歌单一屏的封面可能比 [MAX] 多，以前会一边删一边重新下载，来回折腾）。
 * - 下载好的图片另外在内存里多留一些（[MAX_SOURCES] 张）：纹理删了以后再滚回来，直接重新上传，不用再下载。
 */
object CoverCache {

    /** 纹理大约保留多少张（128×128，一张 64 KB 显存） */
    private const val MAX = 160
    /** 下载好的图片保留多少张（一张 64 KB 内存） */
    private const val MAX_SOURCES = 400
    /** 最近这么久（毫秒）里画过的封面算"正在用"，超出上限也不删 */
    private const val IN_USE_MS = 500L
    /** 每次 upload() 最多上传几张 */
    private const val UPLOADS_PER_CALL = 8
    /**
     * 下载线程：后加的任务先做（屏幕上正在看的封面优先，滚过去的旧请求排后面）
     */
    private val worker = ThreadPoolExecutor(
        3, 3, 0L, TimeUnit.MILLISECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean = offerFirst(e)
        },
    ) { r -> Thread(r, "Dango-CoverCache").apply { isDaemon = true } }

    /**
     * 磁盘缓存：存的是已经降采样到档位的正方形缩略图（PNG），不是原图。
     * 下次开游戏 / 纹理被 LRU 淘汰后重进歌单，都不用再走一次网络。
     */
    private const val DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private val diskWrites = AtomicInteger()

    private val requested = ConcurrentHashMap.newKeySet<String>()
    /** 下载失败的时间：过一会儿再重试（以前失败一次就再也不试了，封面会一直空着） */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 8000L
    private val failLogs = AtomicInteger()
    private val ready = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()

    // 只在渲染线程访问
    private val textures = object : LinkedHashMap<String, Texture>(16, 0.75f, true) {}
    /** 每张纹理最后一次被画的时间（毫秒）。按时间算，不按帧：upload() 每帧可能被调好几次 */
    private val lastUsed = HashMap<String, Long>()
    private var now = 0L
    /** 已下载的正方形原图：纹理删了以后重新上传 / 做圆角版本用。按最久没用的先删 */
    private val sources = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>) = size > MAX_SOURCES
    }
    private val roundQueued = ConcurrentHashMap.newKeySet<String>()

    /** 上传队列版本号：只有新图片进队才 +1，每帧的 [upload] 版本没变就直接跳过 */
    private val uploadVersion = AtomicInteger(0)
    /** 上一次 upload() 看到的版本 */
    private var lastUploadVersion = -1

    /** 圆角半径占边长的比例（Song card / 灵动岛用） */
    private const val ROUND = 0.2f

    /** 渲染线程每帧调用：把下载好的图片上传成纹理，超出上限就删掉最久没画的 */
    fun upload() {
        now = System.currentTimeMillis()
        // 版本没变、也没超过纹理上限：这一帧没有新图，直接返回（不轮询队列也不上传）
        if (uploadVersion.get() == lastUploadVersion && textures.size <= MAX) return
        lastUploadVersion = uploadVersion.get()
        // 一次最多传几张，一下子滚出很多封面时不会卡一下
        for (i in 0 until UPLOADS_PER_CALL) {
            val (key, img) = ready.poll() ?: break
            val tex = DangoClient.gfx.textures.upload("dango_cover_c", img)
            textures.put(key, tex)?.let { DangoClient.gfx.textures.delete(it) }
            lastUsed[key] = now
            // 纹理已经到位，把"在途请求"标记还回去：
            // 否则这张封面被 LRU 淘汰后 get() 会在 requested.add() 处直接返回 null，
            // 永远拿不回来（requested 也会随浏览过的封面无界增长）。
            requested.remove(key)
            roundQueued.remove(key)
            if (ROUND_SUFFIX !in key) sources[key] = img
        }
        // 最久没画的排在最前面；它要是最近还在画，说明全都在用，先不删（下一帧再看）
        while (textures.size > MAX) {
            val eldest = textures.entries.iterator().next()
            if (now - (lastUsed[eldest.key] ?: 0L) <= IN_USE_MS) break
            DangoClient.gfx.textures.delete(eldest.value)
            requested.remove(eldest.key)
            roundQueued.remove(eldest.key)
            lastUsed.remove(eldest.key)
            textures.remove(eldest.key)
        }
    }

    /** 取封面；还没有就去下载，先返回 null（画占位）。[size] 只取 64 / 128 / 256 三档，避免下载过多尺寸 */
    fun get(key: String, url: String, size: Int = 128): Texture? {
        // now 只在 upload() 里刷新，但 get() 可能在同一帧更早被调用：
        // 用上一次刷新留下的旧时间会让"刚画过"的封面被误判成很久没用而删掉
        now = System.currentTimeMillis()
        val tier = tierOf(size)
        val k = tieredKey(key, tier)
        textures[k]?.let {
            lastUsed[k] = now
            return it
        }
        if (url.isEmpty()) return null
        val failed = failedAt[k]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
        if (!requested.add(k)) return null
        // 以前下载过、只是纹理被删了：直接拿内存里的图重新上传（下一帧就有）
        sources[k]?.let {
            ready.add(k to it)
            uploadVersion.incrementAndGet()
            return null
        }
        worker.execute {
            var ok = false
            var why = ""
            try {
                // 先看磁盘（缩略图直接可用，省掉一次下载 + 解码 + 缩放）
                val cached = MediaCache.readBytes("cover", k, DISK_TTL_MS)
                if (cached != null) {
                    val img = ImageIO.read(ByteArrayInputStream(cached))
                    if (img != null) {
                        ready.add(k to img)
                        uploadVersion.incrementAndGet()
                        failedAt.remove(k)
                        MediaCache.touch("cover", k)
                        ok = true
                    } else {
                        // 文件损坏 / 被截断：删掉，继续走网络
                        MediaCache.delete("cover", k)
                    }
                }
                if (!ok) {
                    val full = if ("?" in url) url else "$url?param=${tier}y${tier}"
                    val bytes = NeteaseApi.request(full, "image/jpeg,image/png,image/*;q=0.8")
                    if (bytes == null) {
                        why = "download failed"
                    } else {
                        val img = ImageIO.read(ByteArrayInputStream(bytes))
                        if (img == null) {
                            why = "unreadable image (${bytes.size} bytes)"
                        } else {
                            val thumb = square(img, tier)
                            ready.add(k to thumb)
                            uploadVersion.incrementAndGet()
                            failedAt.remove(k)
                            ok = true
                            saveToDisk(k, thumb)
                        }
                    }
                }
            } catch (e: Throwable) {
                why = e.toString()
            } finally {
                if (!ok) {
                    requested.remove(k)
                    // 失败记录按 key 累积，长期运行要设个上限，否则会一直涨
                    if (failedAt.size > 512) failedAt.clear()
                    failedAt[k] = System.currentTimeMillis()
                    if (failLogs.incrementAndGet() <= 20) DangoLogger.warn("Cover {} failed: {} ({})", k, why, url)
                }
            }
        }
        return null
    }

    /** 把降采样后的缩略图写进磁盘缓存（PNG，无损且体积可控） */
    private fun saveToDisk(key: String, img: BufferedImage) {
        try {
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "png", out)
            MediaCache.writeBytes("cover", key, out.toByteArray())
            diskWrites.incrementAndGet()
        } catch (_: Throwable) {
            // 写盘失败不影响显示：纹理已经进队列了
        }
    }

    /** 封面尺寸按 64 / 128 / 256 三档取整 */
    private fun tierOf(size: Int): Int = when {
        size <= 64 -> 64
        size <= 128 -> 128
        else -> 256
    }

    /** 128 档沿用旧键名，64 / 256 加后缀，互不覆盖 */
    private fun tieredKey(key: String, tier: Int): String = if (tier == 128) key else "$key@$tier"

    fun track(t: NeteaseTrack, size: Int = 128) = get("t:${t.id}", t.coverUrl, size)

    private const val ROUND_SUFFIX = "#r"

    /** 圆角版封面（渲染线程调用）：原图下好后再生成一张四角透明的纹理。ratio = 圆角半径 / 边长 */
    fun rounded(key: String, url: String, ratio: Float = ROUND): Texture? {
        now = System.currentTimeMillis()
        val rk = key + ROUND_SUFFIX + (ratio * 100).toInt()
        textures[rk]?.let {
            lastUsed[rk] = now
            return it
        }
        get(key, url)                                  // 确保原图在下载 / 已缓存
        val src = sources[key] ?: return null
        if (roundQueued.add(rk)) {
            worker.execute {
                try {
                    ready.add(rk to roundCorners(src, ratio))
                    uploadVersion.incrementAndGet()
                } catch (_: Throwable) {
                    // 生成失败就把占位清掉，下一帧还能重新排队；否则这张封面永远没有圆角版
                    roundQueued.remove(rk)
                }
            }
        }
        return null
    }

    fun trackRounded(t: NeteaseTrack, ratio: Float = ROUND) = rounded("t:${t.id}", t.coverUrl, ratio)

    /** 四个角挖成圆角（抗锯齿：边缘按覆盖率给透明度） */
    private fun roundCorners(src: BufferedImage, ratio: Float): BufferedImage {
        val w = src.width
        val h = src.height
        val r = min(w, h) * ratio
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = x + 0.5f
                val py = y + 0.5f
                // 到最近的圆角圆心的距离（不在角落区域时为 0）
                val dx = kotlin.math.max(kotlin.math.max(r - px, px - (w - r)), 0f)
                val dy = kotlin.math.max(kotlin.math.max(r - py, py - (h - r)), 0f)
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val cover = (r - d + 0.5f).coerceIn(0f, 1f)
                val argb = src.getRGB(x, y)
                val a = (((argb ushr 24) and 0xFF) * cover).toInt()
                out.setRGB(x, y, (a shl 24) or (argb and 0xFFFFFF))
            }
        }
        return out
    }
    fun playlist(p: NeteasePlaylist, size: Int = 128) = get("p:${p.provider}:${p.id}", p.coverUrl, size)

    private fun square(src: BufferedImage, size: Int): BufferedImage {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null)
        g.dispose()
        return out
    }
}
