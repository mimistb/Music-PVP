package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * QQ 音乐内置音源：搜索 + 播放地址直接使用 QQ 音乐网页公开接口。
 *
 * 不依赖任何本地服务；免费歌曲无需登录即可播放，登录只影响 VIP / 高音质权限。
 * 登录凭证仍由 [QqMusicLogin] 负责（用户自行在开放平台申请，属于可选增强）。
 */
object QqMusicApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val random = Random()
    private val headers = mapOf(
        "Referer" to "https://y.qq.com/",
        "User-Agent" to UA,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun guid(): String = buildString {
        repeat(10) { append(random.nextInt(10)) }
    }

    private fun getJson(url: String): JsonObject? {
        val bytes = NeteaseApi.request(url, accept = "application/json", headers = headers) ?: return null
        return try {
            JsonParser().parse(String(bytes, StandardCharsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** 搜索歌曲；track.providerId = songmid，播放地址按它取 */
    fun search(keyword: String, limit: Int = 30): List<NeteaseTrack> {
        if (keyword.isBlank()) return emptyList()
        val body = JsonObject().apply {
            add("comm", JsonObject().apply {
                addProperty("ct", "19")
                addProperty("cv", "1859")
                addProperty("uin", "0")
            })
            add("req", JsonObject().apply {
                addProperty("method", "DoSearchForQQMusicDesktop")
                addProperty("module", "music.search.SearchCgiService")
                add("param", JsonObject().apply {
                    addProperty("search_type", 0)
                    addProperty("query", keyword.trim())
                    addProperty("page_num", 1)
                    addProperty("num_per_page", limit.coerceIn(1, 50))
                    addProperty("grp", 1)
                    addProperty("song_num", 0)
                    addProperty("searchid", "")
                })
            })
        }
        val root = getJson("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${enc(body.toString())}")
            ?: return emptyList()
        val data = root.getAsJsonObject("req")
            ?.getAsJsonObject("data")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("song")
            ?.getAsJsonArray("list") ?: return emptyList()
        return data.mapNotNull { el ->
            val song = el as? JsonObject ?: return@mapNotNull null
            val mid = song.strOr("mid").ifEmpty { song.strOr("songmid") }
            if (mid.isEmpty()) return@mapNotNull null
            val name = song.strOr("name").ifEmpty { song.strOr("songname") }
            if (name.isEmpty()) return@mapNotNull null
            val singers = song.getAsJsonArray("singer")?.mapNotNull { s ->
                (s as? JsonObject)?.strOr("name")?.takeIf { it.isNotEmpty() }
            } ?: emptyList()
            val album = song.getAsJsonObject("album")
            val albumMid = album?.strOr("mid").orEmpty()
            val albumName = album?.strOr("name").orEmpty()
            val interval = song.longOr("interval", 0L)
            val pay = song.getAsJsonObject("pay")
            val paid = pay?.intOr("pay_play", 0) ?: 0
            NeteaseTrack(
                id = stableProviderId("qq", mid),
                name = name,
                artists = singers,
                durationMs = if (interval > 0) interval * 1000 else 0L,
                coverUrl = if (albumMid.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                } else "",
                fee = if (paid == 0) 0 else 1,
                popularity = -1,
                album = albumName,
                provider = "qq",
                providerId = mid,
            )
        }
    }

    // ---------------------------------------------------------------- 推荐歌单 / 歌单歌曲

    /** 首页推荐歌单（QQ 音乐「分类歌单」公开接口，无需登录）；offset 用来翻页 */
    fun recommendPlaylists(limit: Int = 30, offset: Int = 0): List<NeteasePlaylist> {
        val n = limit.coerceIn(1, 60)
        val sin = offset.coerceIn(0, 100)
        val ein = (sin + n - 1).coerceAtMost(100)
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
            "?picmid=1&rnd=${System.currentTimeMillis()}&g_tk=5381&loginUin=0&hostUin=0" +
            "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0" +
            "&categoryId=10000000&sortId=5&sin=$sin&ein=$ein"
        val root = getJson(url) ?: return emptyList()
        return parseRecommend(root, n)
    }

    /** 解析推荐歌单返回；data.list / data.v_list 两种结构都兼容 */
    internal fun parseRecommend(root: JsonObject, limit: Int = 30): List<NeteasePlaylist> {
        val data = root.get("data") as? JsonObject ?: return emptyList()
        val arr = (data.get("list") as? JsonArray) ?: (data.get("v_list") as? JsonArray) ?: return emptyList()
        val out = ArrayList<NeteasePlaylist>(arr.size())
        for (el in arr) {
            val p = el as? JsonObject ?: continue
            val id = p.strOr("dissid").toLongOrNull() ?: continue
            val name = p.strOr("dissname")
            if (name.isEmpty()) continue
            out.add(
                NeteasePlaylist(
                    id = id,
                    name = name,
                    trackCount = p.intOr("song_count", p.intOr("listennum", 0)),
                    coverUrl = p.strOr("imgurl").replaceFirst("http://", "https://"),
                    owned = false,
                    provider = "qq",
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /** 歌单里的歌曲（QQ 音乐网页版歌单详情接口） */
    fun playlistTracks(dissId: Long, limit: Int = 300): List<NeteaseTrack> {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
            "?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$dissId&g_tk=5381" +
            "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
            "&platform=yqq.json&needNewCode=0"
        val root = getJson(url) ?: return emptyList()
        return parsePlaylistTracks(root, limit)
    }

    /** 解析歌单详情返回（cdlist[0].songlist） */
    internal fun parsePlaylistTracks(root: JsonObject, limit: Int = 300): List<NeteaseTrack> {
        val cdlist = root.get("cdlist") as? JsonArray ?: return emptyList()
        val cd = cdlist.firstOrNull() as? JsonObject ?: return emptyList()
        val songs = cd.get("songlist") as? JsonArray ?: return emptyList()
        val out = ArrayList<NeteaseTrack>(songs.size())
        for (el in songs) {
            val track = (el as? JsonObject)?.let { trackOf(it) } ?: continue
            out.add(track)
            if (out.size >= limit) break
        }
        return out
    }

    /** 歌单歌曲 → 统一曲目（字段兼容 songmid/mid、album.mid/albummid） */
    internal fun trackOf(song: JsonObject): NeteaseTrack? {
        val mid = song.strOr("songmid").ifEmpty { song.strOr("mid") }
        val name = song.strOr("songname").ifEmpty { song.strOr("name") }
        if (mid.isEmpty() || name.isEmpty()) return null
        val singers = (song.get("singer") as? JsonArray)?.mapNotNull { s ->
            (s as? JsonObject)?.strOr("name")?.takeIf { it.isNotEmpty() }
        } ?: emptyList()
        val album = song.get("album") as? JsonObject
        val albumMid = album?.strOr("mid").orEmpty().ifEmpty { song.strOr("albummid") }
        val albumName = album?.strOr("name").orEmpty()
        val interval = song.longOr("interval", 0L)
        val pay = song.get("pay") as? JsonObject
        val paid = pay?.intOr("pay_play", 0) ?: 0
        return NeteaseTrack(
            id = stableProviderId("qq", mid),
            name = name,
            artists = singers,
            durationMs = if (interval > 0) interval * 1000 else 0L,
            coverUrl = if (albumMid.isNotEmpty()) {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
            } else "",
            fee = if (paid == 0) 0 else 1,
            popularity = -1,
            album = albumName,
            provider = "qq",
            providerId = mid,
        )
    }

    /** 播放地址：匿名 uin=0 取 vkey；拿不到 purl 说明该曲目需要会员 / 付费 */
    fun songUrl(songMid: String): NeteaseApi.SongUrl? {
        if (songMid.isEmpty()) return null
        val guid = guid()
        val body = JsonObject().apply {
            add("req", JsonObject().apply {
                addProperty("module", "CDN.SrfCdnDispatchServer")
                addProperty("method", "GetCdnDispatch")
                add("param", JsonObject().apply {
                    addProperty("guid", guid)
                    addProperty("calltype", 0)
                    addProperty("userip", "")
                })
            })
            add("req_0", JsonObject().apply {
                addProperty("module", "vkey.GetVkeyServer")
                addProperty("method", "CgiGetVkey")
                add("param", JsonObject().apply {
                    addProperty("guid", guid)
                    add("songmid", JsonArray().apply { add(JsonPrimitive(songMid)) })
                    add("songtype", JsonArray().apply { add(JsonPrimitive(0)) })
                    addProperty("uin", "0")
                    addProperty("loginflag", 1)
                    addProperty("platform", "20")
                })
            })
            add("comm", JsonObject().apply {
                addProperty("uin", 0)
                addProperty("format", "json")
                addProperty("ct", 24)
                addProperty("cv", 0)
            })
        }
        val root = getJson("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=${enc(body.toString())}")
            ?: return null
        val inner = root.getAsJsonObject("req_0")?.getAsJsonObject("data") ?: return null
        val info = inner.getAsJsonArray("midurlinfo")?.firstOrNull() as? JsonObject ?: return null
        val purl = info.strOr("purl")
        if (purl.isEmpty()) return null
        if (purl.startsWith("http://") || purl.startsWith("https://")) {
            return NeteaseApi.SongUrl(purl, false)
        }
        val sip = inner.getAsJsonArray("sip")?.firstOrNull()
            ?.let { runCatching { it.asString }.getOrNull() }
            ?.ifEmpty { null }
            ?: "https://dl.stream.qqmusic.qq.com/"
        return NeteaseApi.SongUrl(sip + purl, false)
    }
}
