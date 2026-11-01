package me.sorihiko.dango.musicui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal enum class Page { HOME, DAILY, FREE, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS, LOCAL }

/** 关掉播放器再打开时还保留的界面状态 */
internal object ScreenState {
    var page = Page.HOME
    /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
    var settingsTab = 0
    var settingsBack = Page.HOME
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
    private val tracksCache = ConcurrentHashMap<Long, List<NeteaseTrack>>()
    private val tracksLoading = ConcurrentHashMap.newKeySet<Long>()
    /** 每个歌单最近一次读取失败的时间（用它做重试冷却） */
    private val tracksFailedAt = ConcurrentHashMap<Long, Long>()

    @Volatile var recommend: List<NeteasePlaylist>? = null
        private set
    @Volatile var recommendLoading = false
        private set

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

    fun tracks(playlistId: Long): List<NeteaseTrack>? = tracksCache[playlistId]

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
        if (recommend != null || recommendLoading) return
        recommendLoading = true
        worker.execute {
            // 读失败保持 null：下次进首页还能再试，不会被一次网络抖动永久定成空列表
            val list = try { NeteaseApi.recommendPlaylists() } catch (_: Exception) { null }
            if (list != null) recommend = list
            recommendLoading = false
        }
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

    /** 读一个歌单里的歌（已经读过或正在读就跳过） */
    fun loadTracks(playlistId: Long) {
        if (tracksCache.containsKey(playlistId) || !tracksLoading.add(playlistId)) return
        worker.execute {
            try {
                tracksCache[playlistId] = NeteaseApi.playlistTracks(playlistId)
                tracksFailedAt.remove(playlistId)
            } catch (_: Exception) {
                // 读失败不要写空列表缓存，否则这个歌单会一直显示 0 首，重新打开也不会再读
                tracksFailedAt[playlistId] = System.currentTimeMillis()
                status = "读取歌单里的歌失败"
            } finally {
                tracksLoading.remove(playlistId)
            }
        }
    }

    /** 页面上没有数据时调用：读失败过就隔 5 秒再自动重试一次 */
    fun ensureTracks(playlistId: Long) {
        if (tracksCache.containsKey(playlistId) || playlistId in tracksLoading) return
        val failedAt = tracksFailedAt[playlistId] ?: 0L
        if (System.currentTimeMillis() - failedAt < 5000L) return
        loadTracks(playlistId)
    }

    fun search(q: String) {
        // 每发一次搜索代际 +1：连续搜索时，旧请求的结果不会覆盖新结果
        val gen = ++searchGeneration
        searching = true
        searchResults = null
        searchPlaylists = emptyList()
        // 歌单和歌曲同时搜（同一个关键词），结果一起显示在搜索页
        worker.execute {
            val lists = try { NeteaseApi.searchPlaylists(q) } catch (_: Exception) { emptyList() }
            if (gen == searchGeneration) searchPlaylists = lists
        }
        worker.execute {
            var list: List<NeteaseTrack> = emptyList()
            try {
                list = NeteaseApi.search(q)
            } catch (_: Exception) {
            }
            if (gen != searchGeneration) return@execute
            searchResults = list
            // 搜索接口偶尔不给收费类型 / 热度：再用详情接口批量补一轮（失败不影响列表）
            if (list.any { it.fee < 0 || it.popularity <= 0 }) {
                val detail = try {
                    NeteaseApi.songDetails(list.map { it.id })
                } catch (_: Exception) {
                    emptyMap()
                }
                if (gen == searchGeneration && detail.isNotEmpty()) {
                    searchResults = list.map { t -> detail[t.id]?.let { t.fillFrom(it) } ?: t }
                }
            }
            if (gen == searchGeneration) searching = false
        }
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
            for (id in tracksCache.keys.toList()) {
                try {
                    val tracks = NeteaseApi.playlistTracks(id)
                    if (tracks.isNotEmpty()) tracksCache[id] = tracks
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
