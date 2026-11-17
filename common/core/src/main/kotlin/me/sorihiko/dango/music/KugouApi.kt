package me.sorihiko.dango.music

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 酷狗音乐内置音源：搜索 + 播放地址，全部直接走酷狗公开网页接口，
 * 不依赖 Kungo-API / 任何本地服务，也不需要用户额外开软件。
 *
 * 登录与否只影响「能不能拿到 VIP 音质」，免费歌曲直接用匿名设备标识即可播放。
 */
object KugouApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun deviceMid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject? {
        val bytes = NeteaseApi.request(url, accept = "application/json", headers = headers) ?: return null
        return try {
            JsonParser().parse(String(bytes, StandardCharsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** 搜索歌曲；返回的 track 带 provider=kugou，播放地址用 providerId（hash|albumId） */
    fun search(keyword: String, limit: Int = 30): List<NeteaseTrack> {
        if (keyword.isBlank()) return emptyList()
        val url = "https://mobilecdn.kugou.com/api/v3/search/song?format=json" +
            "&keyword=${enc(keyword.trim())}&page=1&pagesize=${limit.coerceIn(1, 50)}&showtype=1"
        val root = getJson(url, mapOf("Referer" to "https://www.kugou.com/")) ?: return emptyList()
        val data = root.get("data") as? JsonObject ?: return emptyList()
        val info = data.get("info") as? JsonArray ?: return emptyList()
        return info.mapNotNull { el -> (el as? JsonObject)?.let { trackOf(it) } }
    }

    /** 首页推荐歌单（酷狗「精选歌单」移动端公开接口，无需登录）；该接口没有稳定分页参数，offset 仅占位 */
    fun recommendPlaylists(limit: Int = 30, offset: Int = 0): List<NeteasePlaylist> {
        val root = getJson(
            "https://m.kugou.com/plist/index&json=true",
            mapOf("Referer" to "https://m.kugou.com/"),
        ) ?: return emptyList()
        return parseRecommend(root, limit)
    }

    /** 解析推荐歌单返回；info 可能是数组，也可能是以序号为键的对象 */
    internal fun parseRecommend(root: JsonObject, limit: Int = 30): List<NeteasePlaylist> {
        val plist = root.get("plist") as? JsonObject ?: return emptyList()
        val list = plist.get("list") as? JsonObject ?: return emptyList()
        val infoEl: JsonElement = list.get("info") ?: return emptyList()
        val items: List<JsonElement> = when {
            infoEl.isJsonArray -> infoEl.asJsonArray.toList()
            infoEl.isJsonObject -> infoEl.asJsonObject.entrySet()
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .map { it.value }
            else -> emptyList()
        }
        val out = ArrayList<NeteasePlaylist>(items.size)
        for (el in items) {
            val p = el as? JsonObject ?: continue
            val id = p.strOr("specialid").toLongOrNull() ?: continue
            val name = p.strOr("specialname")
            if (name.isEmpty()) continue
            out.add(
                NeteasePlaylist(
                    id = id,
                    name = name,
                    trackCount = p.intOr("songcount", 0),
                    coverUrl = p.strOr("imgurl").replaceFirst("http://", "https://"),
                    owned = false,
                    provider = "kugou",
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /** 歌单里的歌曲（酷狗移动端歌单详情接口） */
    fun playlistTracks(specialId: Long, limit: Int = 300): List<NeteaseTrack> {
        val url = "https://mobilecdn.kugou.com/api/v3/special/song?specialid=$specialId" +
            "&page=1&pagesize=${limit.coerceIn(1, 300)}&format=json&showtype=1"
        val root = getJson(url, mapOf("Referer" to "https://www.kugou.com/")) ?: return emptyList()
        return parsePlaylistTracks(root, limit)
    }

    /** 解析歌单详情返回（data.info，字段与搜索结果一致） */
    internal fun parsePlaylistTracks(root: JsonObject, limit: Int = 300): List<NeteaseTrack> {
        val data = root.get("data") as? JsonObject ?: return emptyList()
        val info = data.get("info") as? JsonArray ?: return emptyList()
        val out = ArrayList<NeteaseTrack>(info.size())
        for (el in info) {
            val track = (el as? JsonObject)?.let { trackOf(it) } ?: continue
            out.add(track)
            if (out.size >= limit) break
        }
        return out
    }

    /** 酷狗歌曲 JSON → 统一曲目（搜索结果与歌单详情共用） */
    private fun trackOf(song: JsonObject): NeteaseTrack? {
        val hash = song.strOr("hash")
        if (hash.isEmpty()) return null
        val name = song.strOr("songname").ifEmpty { song.strOr("filename") }
        if (name.isEmpty()) return null
        val artists = song.strOr("singername")
            .split('&', '、', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val albumId = song.strOr("album_id")
        val durationSec = song.longOr("duration", 0L)
        return NeteaseTrack(
            id = stableProviderId("kugou", hash),
            name = name,
            artists = artists,
            durationMs = if (durationSec > 0) durationSec * 1000 else 0L,
            coverUrl = "",
            fee = -1,
            popularity = -1,
            album = song.strOr("album_name"),
            provider = "kugou",
            providerId = "$hash|$albumId",
        )
    }

    /** 播放地址；失败返回 null（UI 会提示无权限 / 换歌） */
    fun songUrl(providerId: String): NeteaseApi.SongUrl? {
        val parts = providerId.split('|')
        val hash = parts.getOrNull(0).orEmpty()
        if (hash.isEmpty()) return null
        val albumId = parts.getOrNull(1).orEmpty()

        // 优先走移动端公开接口：免费歌曲无需登录 / 设备标识就能直接拿到播放地址
        val mobileUrl = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=${enc(hash)}"
        val mobile = getJson(mobileUrl, mapOf("Referer" to "https://m.kugou.com/"))
        if (mobile != null) {
            val direct = mobile.strOr("url")
            if (direct.isNotEmpty()) return NeteaseApi.SongUrl(direct, false)
            mobile.getAsJsonArray("backup_url")?.firstOrNull()
                ?.let { runCatching { it.asString }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return NeteaseApi.SongUrl(it, false) }
        }

        // 再走网页版接口（需要设备标识；对部分版权歌曲仍可能返回空）
        val mid = deviceMid()
        val account = LoginAccounts.kugouAccount()
        val dfid = LoginAccounts.kugouDfid()
        val cookie = buildString {
            append("kg_mid=").append(mid)
            if (dfid.isNotEmpty()) append("; kg_dfid=").append(dfid)
            if (account != null) {
                append("; token=").append(account.token)
                append("; userid=").append(account.id)
            }
        }
        val url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata" +
            "&hash=${enc(hash)}&album_id=${enc(albumId)}&mid=$mid&platid=4&dfid=${enc(dfid)}"
        val root = getJson(
            url,
            mapOf(
                "Referer" to "https://www.kugou.com/",
                "User-Agent" to UA,
                "Cookie" to cookie,
            ),
        ) ?: return null
        val data = root.getAsJsonObject("data") ?: return null
        val playUrl = data.strOr("play_url")
        if (playUrl.isNotEmpty()) return NeteaseApi.SongUrl(playUrl, false)
        val backup = data.getAsJsonArray("play_backup_url")?.firstOrNull()
            ?.let { runCatching { it.asString }.getOrNull() }
            .orEmpty()
        return if (backup.isNotEmpty()) NeteaseApi.SongUrl(backup, false) else null
    }
}
