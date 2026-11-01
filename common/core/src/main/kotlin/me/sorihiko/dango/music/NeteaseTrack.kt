package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 网易云里的一首歌（ID、时长、封面、收费类型、热度都是网易云自己的数据） */
class NeteaseTrack(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUrl: String,
    /** 收费类型：0 免费，1 会员（VIP），4 付费专辑，8 标准音质免费（高音质要会员）；-1 未知 */
    val fee: Int = -1,
    /** 热度 0..100；-1 未知 */
    val popularity: Int = -1,
    val album: String = "",
) {
    /** 用详情接口的数据补上缺的字段（歌名 / 歌手 / 时长保持原样） */
    fun fillFrom(detail: NeteaseTrack): NeteaseTrack = NeteaseTrack(
        id, name, artists, durationMs,
        coverUrl.ifEmpty { detail.coverUrl },
        if (fee >= 0) fee else detail.fee,
        if (popularity > 0) popularity else detail.popularity,
        album.ifEmpty { detail.album },
    )

    companion object {
        /** 解析网易云接口返回的歌曲 JSON（新旧两种字段名都认：artists/ar、duration/dt、album/al、pop/popularity） */
        fun parse(track: JsonObject): NeteaseTrack? {
            val id = track.get("id")?.let { if (it.isJsonNull) null else it.asString.toLongOrNull() } ?: return null
            val name = str(track, "name")
            val artistsEl: JsonElement? = track.get("artists") ?: track.get("ar")
            val artists = if (artistsEl != null && artistsEl.isJsonArray) {
                artistsEl.asJsonArray.mapNotNull { a ->
                    if (a.isJsonObject) str(a.asJsonObject, "name").takeIf { it.isNotEmpty() } else null
                }
            } else emptyList()
            val dur = (track.get("duration") ?: track.get("dt"))
                ?.let { if (it.isJsonNull) 0L else try { it.asLong } catch (_: Exception) { 0L } } ?: 0L
            val album = (track.get("album") ?: track.get("al")) as? JsonObject
            val cover = if (album != null) {
                str(album, "picUrl").ifEmpty { str(album, "cover") }
            } else ""
            // 收费类型：有的接口放在 privilege 里；热度有的叫 pop、有的叫 popularity
            val privilege = track.get("privilege") as? JsonObject
            val fee = int(track, "fee", if (privilege != null) int(privilege, "fee", -1) else -1)
            val popularity = maxOf(int(track, "pop", -1), int(track, "popularity", -1))
            val albumName = album?.let { str(it, "name") } ?: ""
            return NeteaseTrack(id, name, artists, dur, cover, fee, popularity, albumName)
        }

        private fun str(o: JsonObject, name: String): String {
            val e = o.get(name) ?: return ""
            return if (e.isJsonNull || !e.isJsonPrimitive) "" else e.asString
        }

        /** 取整数字段；缺失 / 不是数字时用 [def]（热度可能是 100.0 这种浮点，asInt 也能取） */
        private fun int(o: JsonObject, name: String, def: Int): Int {
            val e = o.get(name) ?: return def
            if (e.isJsonNull || !e.isJsonPrimitive) return def
            return try {
                e.asInt
            } catch (_: Exception) {
                def
            }
        }
    }
}
