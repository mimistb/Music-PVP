package me.sorihiko.dango.musicui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.cache.MediaCache
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.KugouApi
import me.sorihiko.dango.music.LocalLibrary
import me.sorihiko.dango.music.LoginAccounts
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.QqMusicApi

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal enum class Page { HOME, DAILY, FREE, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS, SETTINGS_DETAIL, LOCAL }

/** 关掉播放器再打开时还保留的界面状态 */
internal object ScreenState {
    var page = Page.HOME
    /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
    var settingsTab = 0
    var settingsBack = Page.HOME
    /** 从设置列表右键打开的模块详情页：分组键（段名/标题），非空即处于 [Page.SETTINGS_DETAIL] */
    var settingsDetail: String? = null
    var settingsDetailTab = 0
    var settingsDetailScroll = 0f
    var backPage: Page? = null
    var opened: NeteasePlaylist? = null
    /** 歌单页显示方式：false = 列表，true = 封面墙 */
    var coverView = false
    var lastQuery = ""

    // 关掉播放器再打开时恢复的滚动位置（页面本身由 page 保留）
    var homeScroll = 0f
    var tracksScroll = 0
    var tracksCover = 0
    var settingsScroll = 0f
    var sidebarScroll = 0

    // 液态玻璃画布（和菜单同一套），第一次打开播放器时创建
    var context: GuiCanvas? = null
}

/**
 * 从网易云读来的数据：我的歌单、歌单里的歌、推荐歌单、每日推荐、免费听、搜索结果（歌 + 歌单）。
 * 都在后台线程读，读完直接替换，界面每帧读最新的。
 */
internal object Library {
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }

    @Volatile var playlists: List<NeteasePlaylist> = emptyList()
        private set
    @Volatile var playlistsLoading = false
        private set
    /** 底部提示（读取失败等），空就不显示 */
    @Volatile var status = ""
        private set
    private val tracksCache = ConcurrentHashMap<String, List<NeteaseTrack>>()
    private val tracksLoading = ConcurrentHashMap.newKeySet<String>()
    /** 每个歌单最近一次读取失败的时间（用它做重试冷却） */
    private val tracksFailedAt = ConcurrentHashMap<String, Long>()

    @Volatile var recommend: List<NeteasePlaylist>? = null
        private set
    @Volatile var recommendLoading = false
        private set
    /** 当前 [recommend] 属于哪个平台，切换平台时据此判断要不要重读 */
    @Volatile var recommendPlatform = ""
        private set

    /** 推荐歌单的代际 / 正在读取的平台：切平台或手动刷新时旧请求直接作废 */
    @Volatile private var recommendGeneration = 0
    @Volatile private var recommendLoadingPlatform = ""

    /** 推荐歌单的翻页偏移：网易云同一天内 offset 不变就永远返回同一批 */
    @Volatile private var recommendOffset = 0
    @Volatile private var recommendOffsetPlatform = ""

    private const val RECOMMEND_PAGE = 30

    /** 每日推荐（需要登录）/ 免费听（不需要登录） */
    @Volatile var daily: List<NeteaseTrack>? = null
        private set
    @Volatile var dailyLoading = false
        private set
    @Volatile var free: List<NeteaseTrack>? = null
        private set
    @Volatile var freeLoading = false
        private set

    /** 搜索结果里的相关歌单（和搜歌同时发） */
    @Volatile var searchPlaylists: List<NeteasePlaylist> = emptyList()
        private set

    @Volatile var searchResults: List<NeteaseTrack>? = null
        private set
    @Volatile var searching = false
        private set

    /** 搜索代际：连发多次搜索时，旧请求返回的结果直接丢掉，不覆盖最新结果 */
    @Volatile private var searchGeneration = 0

    /** 上次从网易云读歌单的时间：打开播放器时超过 [STALE_MS] 就在后台自动刷新 */
    private var lastLoadAt = 0L
    private const val STALE_MS = 5 * 60_000L

    val stale: Boolean get() = System.currentTimeMillis() - lastLoadAt > STALE_MS

    fun tracks(playlist: NeteasePlaylist): List<NeteaseTrack>? = tracksCache[cacheKey(playlist)]

    private fun cacheKey(playlist: NeteasePlaylist): String = "${playlist.provider}:${playlist.id}"

    fun reloadPlaylists() {
        if (playlistsLoading) return
        playlistsLoading = true
        lastLoadAt = System.currentTimeMillis()
        status = "正在读取歌单…"
        worker.execute {
            try {
                NeteaseApi.ensureCookieFile()
                val list = NeteaseApi.userPlaylists()
                playlists = list
                status = if (list.isEmpty()) "没有读到歌单（检查网络或登录）" else ""
            } catch (e: Exception) {
                status = "读取歌单失败"
            } finally {
                playlistsLoading = false
            }
        }
    }

    fun loadRecommend() {
        val platform = LoginAccounts.activePlatform()
        if (recommend != null && recommendPlatform == platform) return
        if (recommendLoading && recommendLoadingPlatform == platform) return
        startRecommend(platform)
    }

    /**
     * 「刷新歌单」：翻到下一批推荐。网易云同一天内固定 offset 会返回同一批，
     * 所以每次刷新把 offset 前进一页，到末页后回到第一页。
     */
    fun refreshRecommend() {
        val platform = LoginAccounts.activePlatform()
        if (recommendOffsetPlatform != platform) {
            recommendOffset = 0
            recommendOffsetPlatform = platform
        }
        // 还没拿到过任何数据时，刷新等于首次加载，不要跳过第一页
        if (recommend != null) recommendOffset += RECOMMEND_PAGE
        startRecommend(platform)
    }

    private fun startRecommend(platform: String) {
        val gen = ++recommendGeneration
        recommendLoading = true
        recommendLoadingPlatform = platform
        val offset = if (recommendOffsetPlatform == platform) recommendOffset else 0
        worker.execute {
            // 读失败保持原数据：一次网络抖动不至于把首页清空，也方便用户再点刷新
            val list = try { fetchRecommend(platform, offset) } catch (_: Exception) { null }
            if (gen != recommendGeneration) return@execute
            if (!list.isNullOrEmpty()) {
                recommend = list
                recommendPlatform = platform
                recommendOffsetPlatform = platform
                // 拿到末页：下次刷新从第一页重新开始
                if (list.size < RECOMMEND_PAGE) recommendOffset = 0 else recommendOffset = offset
                // 之前那次失败留下的提示要清掉，否则页面底部会一直挂着旧错误
                if (status == "刷新推荐歌单失败") status = ""
            } else {
                // 失败 / 已经翻到底：offset 归零，下次刷新从第一页重新开始；旧数据保留不清空
                recommendOffset = 0
                status = "刷新推荐歌单失败"
            }
            recommendLoading = false
            recommendLoadingPlatform = ""
        }
    }

    /** 当前平台对应的推荐歌单接口 */
    private fun fetchRecommend(platform: String, offset: Int): List<NeteasePlaylist> = when (platform) {
        "qq" -> QqMusicApi.recommendPlaylists(RECOMMEND_PAGE, offset)
        "kugou" -> KugouApi.recommendPlaylists(RECOMMEND_PAGE, offset)
        else -> NeteaseApi.recommendPlaylists(offset)
    }

    /** 每日推荐（需要登录）：同一天里只读一次，失败保持 null 方便下次重试 */
    fun loadDaily() {
        if (daily != null || dailyLoading) return
        dailyLoading = true
        worker.execute {
            daily = try { NeteaseApi.dailyRecommend() } catch (_: Exception) { null }
            dailyLoading = false
        }
    }

    /** 免费听（不需要登录） */
    fun loadFree() {
        if (free != null || freeLoading) return
        freeLoading = true
        worker.execute {
            free = try { NeteaseApi.freeSongs() } catch (_: Exception) { null }
            freeLoading = false
        }
    }

    /** 读一个歌单里的歌（已经读过或正在读就跳过）；按平台区分缓存，避免 id 撞车 */
    fun loadTracks(playlist: NeteasePlaylist) {
        val key = cacheKey(playlist)
        if (tracksCache.containsKey(key) || !tracksLoading.add(key)) return
        worker.execute {
            try {
                // 先吃磁盘缓存（上次运行读到的歌单），命中就直接显示，不再等网络
                val cached = readDiskTracks(key)
                if (!cached.isNullOrEmpty()) {
                    tracksCache[key] = cached
                    tracksFailedAt.remove(key)
                    return@execute
                }
                val list = fetchTracks(playlist)
                tracksCache[key] = list
                if (playlist.provider != "local" && list.isNotEmpty()) writeDiskTracks(key, list)
                tracksFailedAt.remove(key)
                // 重试成功后就别再让页面底部挂着上一次的失败提示
                if (status == "读取歌单里的歌失败") status = ""
            } catch (_: Exception) {
                // 读失败不要写空列表缓存，否则这个歌单会一直显示 0 首，重新打开也不会再读
                tracksFailedAt[key] = System.currentTimeMillis()
                status = "读取歌单里的歌失败"
            } finally {
                tracksLoading.remove(key)
            }
        }
    }

    /** 页面上没有数据时调用：读失败过就隔 5 秒再自动重试一次 */
    fun ensureTracks(playlist: NeteasePlaylist) {
        val key = cacheKey(playlist)
        if (tracksCache.containsKey(key) || key in tracksLoading) return
        val failedAt = tracksFailedAt[key] ?: 0L
        if (System.currentTimeMillis() - failedAt < 5000L) return
        loadTracks(playlist)
    }

    private fun fetchTracks(playlist: NeteasePlaylist): List<NeteaseTrack> = when (playlist.provider) {
        "qq" -> QqMusicApi.playlistTracks(playlist.id)
        "kugou" -> KugouApi.playlistTracks(playlist.id)
        else -> NeteaseApi.playlistTracks(playlist.id)
    }

    /** 歌单曲目磁盘缓存有效期：12 小时内直接复用，过期就走网络重新读 */
    private const val TRACKS_TTL_MS = 12L * 60 * 60 * 1000

    /**
     * 读磁盘上的歌单曲目。字段手工读写（不用 Gson 反射），
     * 这样以后给 [NeteaseTrack] 加字段时，旧缓存最多是缺省值，不会解析失败。
     */
    private fun readDiskTracks(key: String): List<NeteaseTrack>? {
        val text = MediaCache.readText("playlist", key, TRACKS_TTL_MS) ?: return null
        return try {
            val root = JsonParser().parse(text)
            if (!root.isJsonArray) return null
            root.asJsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.get("id")?.asLong ?: return@mapNotNull null
                NeteaseTrack(
                    id = id,
                    name = o.str("name"),
                    artists = (o.get("artists") as? JsonArray)?.mapNotNull { a ->
                        if (a.isJsonPrimitive) a.asString else null
                    } ?: emptyList(),
                    durationMs = o.get("durationMs")?.asLong ?: 0L,
                    coverUrl = o.str("coverUrl"),
                    fee = o.get("fee")?.asInt ?: -1,
                    popularity = o.get("popularity")?.asInt ?: -1,
                    album = o.str("album"),
                    provider = o.str("provider").ifEmpty { "netease" },
                    providerId = o.str("providerId"),
                )
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskTracks(key: String, list: List<NeteaseTrack>) {
        try {
            val arr = JsonArray()
            for (t in list) {
                arr.add(JsonObject().apply {
                    addProperty("id", t.id)
                    addProperty("name", t.name)
                    add("artists", JsonArray().apply { t.artists.forEach { add(com.google.gson.JsonPrimitive(it)) } })
                    addProperty("durationMs", t.durationMs)
                    addProperty("coverUrl", t.coverUrl)
                    addProperty("fee", t.fee)
                    addProperty("popularity", t.popularity)
                    addProperty("album", t.album)
                    addProperty("provider", t.provider)
                    addProperty("providerId", t.providerId)
                })
            }
            MediaCache.writeText("playlist", key, arr.toString())
        } catch (_: Exception) {
            // 缓存写失败不影响播放
        }
    }

    private fun JsonObject.str(name: String): String {
        val el = get(name) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }

    /** 切换音源后：作废旧平台的推荐歌单并按新平台重读；搜索框里还有关键词就自动重搜 */
    fun onPlatformChanged() {
        val platform = LoginAccounts.activePlatform()
        if (recommendPlatform != platform) {
            recommend = null
            recommendPlatform = ""
            recommendGeneration++
            recommendLoadingPlatform = ""
            recommendLoading = false
            recommendOffset = 0
            recommendOffsetPlatform = ""
            loadRecommend()
        }
        val q = ScreenState.lastQuery
        if (q.isNotBlank()) search(q)
    }

    fun search(q: String) {
        ScreenState.lastQuery = q
        // 每发一次搜索代际 +1：连续搜索时，旧请求的结果不会覆盖新结果
        val gen = ++searchGeneration
        searching = true
        searchResults = null
        searchPlaylists = emptyList()
        val platform = LoginAccounts.activePlatform()
        // 歌单一律在网易云里搜：QQ / 酷狗没有歌单搜索接口，但网易云歌单与当前音源无关，可以正常打开播放
        worker.execute {
            var lists = try { NeteaseApi.searchPlaylists(q) } catch (_: Exception) { emptyList() }
            // 空结果再兜底试一次（接口偶发返回空 result）
            if (lists.isEmpty() && gen == searchGeneration) {
                lists = try { NeteaseApi.searchPlaylists(q) } catch (_: Exception) { emptyList() }
            }
            if (gen == searchGeneration) searchPlaylists = lists
        }
        worker.execute {
            try {
                var list: List<NeteaseTrack> = emptyList()
                try {
                    list = when (platform) {
                        "qq" -> QqMusicApi.search(q)
                        "kugou" -> KugouApi.search(q)
                        else -> NeteaseApi.search(q)
                    }
                } catch (_: Exception) {
                }
                if (gen != searchGeneration) return@execute
                if (platform == "netease" && list.any { it.fee < 0 || it.popularity <= 0 }) {
                    // 搜索接口偶尔不给收费类型 / 热度：再用详情接口批量补一轮（失败不影响列表）
                    val detail = try {
                        NeteaseApi.songDetails(list.map { it.id })
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    if (gen == searchGeneration && detail.isNotEmpty()) {
                        list = list.map { t -> detail[t.id]?.let { t.fillFrom(it) } ?: t }
                    }
                }
                if (gen != searchGeneration) return@execute
                // 聚合搜索：当前音源优先，再补其它平台的同名结果和本地音乐
                val extra = ArrayList<NeteaseTrack>()
                for (p in listOf("netease", "qq", "kugou")) {
                    if (p == platform) continue
                    try {
                        val sub = when (p) {
                            "qq" -> QqMusicApi.search(q, 15)
                            "kugou" -> KugouApi.search(q, 15)
                            else -> NeteaseApi.search(q, 15)
                        }
                        extra += sub
                    } catch (_: Exception) {
                    }
                }
                if (gen != searchGeneration) return@execute
                searchResults = mergeSearch(list, extra, localMatches(q))
            } finally {
                // 任何异常（含 Error）都要收尾；代际不同说明已经有更新的搜索接管了，不能置 false
                if (gen == searchGeneration) searching = false
            }
        }
    }

    /** 本地音乐里歌名 / 歌手包含关键词的曲目 */
    private fun localMatches(q: String): List<NeteaseTrack> {
        val key = q.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        return LocalLibrary.tracks.filter { t ->
            t.name.lowercase().contains(key) || t.artists.any { it.lowercase().contains(key) }
        }.take(20)
    }

    /** 合并三路结果并按 provider:id 去重（当前音源排最前，本地音乐排最后） */
    private fun mergeSearch(
        primary: List<NeteaseTrack>,
        extra: List<NeteaseTrack>,
        local: List<NeteaseTrack>,
    ): List<NeteaseTrack> {
        val seen = HashSet<String>()
        val out = ArrayList<NeteaseTrack>(primary.size + extra.size + local.size)
        for (t in primary + extra + local) {
            if (seen.add("${t.provider}:${t.id}")) out += t
        }
        return out
    }

    /**
     * 悄悄刷新：后台重新读歌单列表和已经打开过的歌单里的歌，读到了再替换，
     * 界面上不会出现"加载中"（游戏开着时在手机上新建歌单 / 加歌，下次打开播放器就能看到）
     */
    fun softRefresh() {
        lastLoadAt = System.currentTimeMillis()
        worker.execute {
            try {
                val list = NeteaseApi.userPlaylists()
                if (list.isNotEmpty()) playlists = list
            } catch (_: Exception) {
            }
            for ((key, _) in tracksCache.entries.toList()) {
                // 只有网易云的个人歌单会变；QQ / 酷狗的推荐歌单不需要悄悄重刷
                if (!key.startsWith("netease:")) continue
                val id = key.substringAfter(':').toLongOrNull() ?: continue
                try {
                    val tracks = NeteaseApi.playlistTracks(id)
                    if (tracks.isNotEmpty()) tracksCache[key] = tracks
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 登录 / 退出后全部重新读 */
    fun refreshAll() {
        playlists = emptyList()
        tracksCache.clear()
        tracksFailedAt.clear()
        recommend = null
        recommendPlatform = ""
        recommendGeneration++
        recommendLoadingPlatform = ""
        recommendLoading = false
        daily = null
        free = null
        reloadPlaylists()
        loadRecommend()
        when (ScreenState.page) {
            Page.DAILY -> loadDaily()
            Page.FREE -> loadFree()
            else -> {}
        }
    }
}
