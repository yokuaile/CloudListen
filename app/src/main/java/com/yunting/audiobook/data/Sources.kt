package com.yunting.audiobook.data

import android.util.Xml
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.util.TreeMap
import java.util.concurrent.TimeUnit

// ============================================================
// 源 1：iTunes 播客搜索（免费有声内容，RSS 单集直链）
// ============================================================

object PodcastSource {
    const val ID = "podcast"
    private val gson = Gson()

    fun search(term: String): List<BookResult> {
        val url = "https://itunes.apple.com/search?term=" +
            URLEncoder.encode(term, "UTF-8") + "&media=podcast&limit=30"
        Http.get(url).use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val arr = root.getAsJsonArray("results") ?: return emptyList()
            // iTunes 会对同一个 feed 返回多条记录（不同剧集/分季指向同一 feedUrl），
            // 必须按 feedUrl 去重，否则发现页列表 key 冲突会直接闪退
            val seen = HashSet<String>()
            return arr.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                val feed = o.get("feedUrl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                if (!seen.add(feed)) return@mapNotNull null
                val title = o.get("collectionName")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                val author = o.get("artistName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                var cover = o.get("artworkUrl600")?.takeIf { !it.isJsonNull }?.asString
                    ?: o.get("artworkUrl100")?.takeIf { !it.isJsonNull }?.asString?.replace("100x100", "600x600")
                val genre = o.get("primaryGenreName")?.takeIf { !it.isJsonNull }?.asString
                BookResult(
                    source = ID, key = feed, title = title, author = author,
                    coverUrl = cover, feedUrl = feed,
                    desc = listOfNotNull(genre).joinToString(" · ")
                )
            }
        }
    }

    /** 解析 RSS/Atom，返回剧集列表 */
    fun loadFeed(feedUrl: String): List<Track> = loadFeedFrom { Http.get(feedUrl) }

    fun loadFeedFrom(open: () -> okhttp3.Response): List<Track> {
        open().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return RssParser.parse(body)
        }
    }
}

// ============================================================
// RSS / Atom 解析（含 media:content、itunes:duration）
// ============================================================

object RssParser {
    private val AUDIO_EXT = listOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "m4b", "m3u8")

    fun isAudioType(type: String?): Boolean = type != null && type.startsWith("audio")

    fun isAudioUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return AUDIO_EXT.any { clean.endsWith(".$it") }
    }

    fun parse(xml: String): List<Track> {
        val tracks = ArrayList<Track>()
        try {
            val p = Xml.newPullParser()
            p.setInput(xml.reader())
            var event = p.eventType
            var inItem = false
            var title: String? = null
            var enclosureUrl: String? = null
            var duration: Long = 0
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        val name = p.name ?: ""
                        when {
                            name.equals("item", true) || name.equals("entry", true) -> {
                                inItem = true; title = null; enclosureUrl = null; duration = 0
                            }
                            name.equals("title", true) && inItem && title == null -> {
                                title = p.nextText().trim()
                            }
                            name.equals("enclosure", true) -> {
                                val u = p.getAttributeValue(null, "url")
                                    ?: p.getAttributeValue(null, "href")
                                if (u != null && (isAudioType(p.getAttributeValue(null, "type")) || isAudioUrl(u))) {
                                    enclosureUrl = u
                                }
                            }
                            name.equals("content", true) -> {
                                val type = p.getAttributeValue(null, "type")
                                val u = p.getAttributeValue(null, "url")
                                if (u != null && isAudioType(type)) enclosureUrl = u
                            }
                            name.equals("link", true) && inItem -> {
                                val rel = p.getAttributeValue(null, "rel")
                                if (rel == "enclosure") {
                                    val u = p.getAttributeValue(null, "href")
                                    if (u != null && isAudioUrl(u)) enclosureUrl = u
                                }
                            }
                            name.endsWith("duration", true) && inItem -> {
                                runCatching {
                                    val t = p.nextText().trim()
                                    duration = parseDuration(t)
                                }
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        val name = p.name ?: ""
                        if (name.equals("item", true) || name.equals("entry", true)) {
                            inItem = false
                            val u = enclosureUrl
                            if (u != null) {
                                tracks.add(Track(title = title ?: "第 ${tracks.size + 1} 集", url = u, durationMs = duration))
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
        }
        return tracks
    }

    fun parseDuration(text: String): Long {
        val t = text.trim()
        // 纯数字且较短：部分 feed 是秒，部分是 hh:mm:ss
        val parts = t.split(":").map { it.trim() }
        return try {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600) + (parts[1].toLong() * 60) + parts[2].toLong()) * 1000
                2 -> ((parts[0].toLong() * 60) + parts[1].toLong()) * 1000
                1 -> {
                    val n = parts[0].toLong()
                    if (n in 1..3600) n * 1000 else 0L
                }
                else -> 0L
            }
        } catch (_: Exception) { 0L }
    }
}

// ============================================================
// 源 2：哔哩哔哩（视频搜索 + 分P 音频直链播放）
// 播放链路：搜索（App API 签名 / Web Wbi 兜底）→ view 取分P cid
//          → Wbi playurl(fnval=16) 取 DASH 音频直链（游客可用，CDN 不校验请求头）
// 剧集 URL 用伪协议 bili://<bvid>/<cid> 占位，播放时由 PlayerHub 实时解析
// ============================================================

object BilibiliSource {
    const val ID = "bilibili"
    private const val APP_KEY = "1d8b6e7d45233436"
    private const val APP_SEC = "560c52ccd288fed045859ed18bffd973"
    private const val SITE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val APP_UA = "Mozilla/5.0 BiliDroid/7.27.0 (bbcallen@gmail.com) os/android model/PCRT00 mobi_app/android build/7270300 channel/yingyongbao"
    private const val REFERER = "https://www.bilibili.com/"
    private val gson = Gson()

    private val WBI_MASK = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    @Volatile private var mixinKey: String? = null

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encoded(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    /** App API 签名 URL（参数按 key 排序后 md5(query + appsec)） */
    private fun signedUrl(base: String, params: Map<String, String>, key: String, sec: String): String {
        val p = TreeMap(params)
        p["appkey"] = key
        p["ts"] = (System.currentTimeMillis() / 1000).toString()
        val q = encoded(p)
        return "$base?$q&sign=${md5(q + sec)}"
    }

    /** Wbi 签名 URL（nav 接口取 key，缓存 mixin） */
    private fun wbiSigned(base: String, params: Map<String, String>): String {
        val mk = mixinKey ?: fetchMixinKey()
        val p = TreeMap(params)
        p["wts"] = (System.currentTimeMillis() / 1000).toString()
        val q = encoded(p)
        val rid = md5(q + (mk ?: ""))
        return "$base?$q&w_rid=$rid"
    }

    private fun fetchMixinKey(): String? {
        return runCatching {
            // 先取首页 cookie（buvid3 / b_nut），再取 nav 的 wbi key
            Http.warmupCookies("https://www.bilibili.com/")
            Http.get(
                "https://api.bilibili.com/x/web-interface/nav",
                referer = "https://www.bilibili.com/",
                ua = Http.DESKTOP_UA
            ).use { resp ->
                val body = resp.body?.string() ?: return@use null
                val root = gson.fromJson(body, JsonObject::class.java) ?: return@use null
                val img = root.getAsJsonObject("data")?.getAsJsonObject("wbi_img") ?: return@use null
                val imgKey = img.get("img_url")?.asString?.substringAfterLast('/')?.substringBefore('.')
                val subKey = img.get("sub_url")?.asString?.substringAfterLast('/')?.substringBefore('.')
                if (imgKey.isNullOrEmpty() || subKey.isNullOrEmpty()) return@use null
                val raw = imgKey + subKey
                mixinKey = WBI_MASK.map { raw[it] }.joinToString("")
                mixinKey
            }
        }.getOrNull()
    }

    fun search(term: String): List<BookResult> {
        // Web Wbi 为主（App 端的旧 appkey 已逐步失效，会返回空列表），App 接口仅作兜底
        val viaWeb = searchViaWeb(term)
        if (viaWeb.isNotEmpty()) return viaWeb
        return searchViaApp(term)
    }

    /** 主路：App API（移动端签名，手机网络下稳定） */
    private fun searchViaApp(term: String): List<BookResult> {
        val url = signedUrl(
            "https://app.bilibili.com/x/v2/search/type",
            mapOf(
                "keyword" to term, "search_type" to "video", "pn" to "1", "ps" to "20",
                "build" to "7270300", "mobi_app" to "android", "platform" to "android"
            ),
            APP_KEY, APP_SEC
        )
        return runCatching {
            Http.get(url).use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseSearchItems(body, "items")
            }
        }.getOrDefault(emptyList())
    }

    /** 兜底：Web Wbi 签名搜索 */
    private fun searchViaWeb(term: String): List<BookResult> {
        return runCatching {
            val url = wbiSigned(
                "https://api.bilibili.com/x/web-interface/wbi/search/type",
                mapOf("keyword" to term, "search_type" to "video", "page" to "1")
            )
            Http.get(url, referer = REFERER, ua = SITE_UA).use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseSearchItems(body, "result")
            }
        }.getOrDefault(emptyList())
    }

    /** 解析两种搜索响应里 results 数组 */
    private fun parseSearchItems(body: String, field: String): List<BookResult> {
        val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
        if (root.get("code")?.takeIf { !it.isJsonNull }?.asInt != 0) return emptyList()
        val data = root.getAsJsonObject("data") ?: return emptyList()
        val items = when (field) {
            "items" -> data.getAsJsonArray("items")
            else -> data.getAsJsonArray("result")
        } ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el.asJsonObject ?: return@mapNotNull null
            val bvid = o.get("bvid")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val title = (o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "")
                .replace(Regex("<[^>]+>"), "").ifEmpty { bvid }
            val author = o.get("author")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val play = o.get("play")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            BookResult(
                source = ID, key = bvid, title = title, author = author,
                coverUrl = normalizePic(o.get("pic")?.takeIf { !it.isJsonNull }?.asString),
                feedUrl = bvid,
                desc = "哔哩哔哩" + (if (play > 0) " · ${play}次播放" else "")
            )
        }
    }

    /**
     * B 站接口返回的封面地址有两种坑：
     * 1) 协议相对形式 `//i0.hdslb.com/...` —— Coil 拿到会当成相对路径，直接加载失败；
     * 2) 明文 `http://i0.hdslb.com/...` —— Android 9+ 默认禁止明文，且 http 图床会 302。
     * 统一归一化成 https 绝对地址（图床本身支持 https）。
     */
    private fun normalizePic(url: String?): String? = normalizeCover(url)

    /** 视频分P → 剧集列表（bili:// 占位，播放时解析） */
    fun loadTracks(bvid: String): List<Track> {
        return runCatching {
            Http.get(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
                referer = REFERER, ua = SITE_UA
            ).use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
                if (root.get("code")?.takeIf { !it.isJsonNull }?.asInt != 0) return emptyList()
                val pages = root.getAsJsonObject("data")?.getAsJsonArray("pages") ?: return emptyList()
                pages.mapIndexed { i, el ->
                    val p = el.asJsonObject
                    val cid = p.get("cid")?.takeIf { !it.isJsonNull }?.asString ?: return@mapIndexed null
                    val part = p.get("part")?.takeIf { !it.isJsonNull }?.asString
                    Track(
                        title = part?.ifEmpty { null } ?: "第 ${i + 1} 集",
                        url = "bili://$bvid/$cid"
                    )
                }.filterNotNull()
            }
        }.getOrDefault(emptyList())
    }

    /** bili://<bvid>/<cid> → DASH 音频直链（游客可用，约 64kbps AAC） */
    fun resolvePlayUrl(bvid: String, cid: String): String? {
        return runCatching {
            val url = wbiSigned(
                "https://api.bilibili.com/x/player/wbi/playurl",
                mapOf("bvid" to bvid, "cid" to cid, "fnval" to "16", "fourk" to "1", "qn" to "16", "platform" to "pc")
            )
            Http.get(url, referer = REFERER, ua = SITE_UA).use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val root = gson.fromJson(body, JsonObject::class.java) ?: return@use null
                if (root.get("code")?.takeIf { !it.isJsonNull }?.asInt != 0) return@use null
                val data = root.getAsJsonObject("data") ?: return@use null
                val dash = data.getAsJsonObject("dash")
                if (dash != null) {
                    val aud = dash.getAsJsonArray("audio")
                    if (aud != null && aud.size() > 0) {
                        // 优先取码率最高的一条（数组按码率倒序），失败时回退第一条
                        val pick = (0 until aud.size()).asSequence()
                            .mapNotNull { aud[it].asJsonObject }
                            .firstOrNull { (it.get("baseUrl") ?: it.get("base_url")) != null }
                        pick?.let { (it.get("baseUrl") ?: it.get("base_url")).takeIf { v -> !v.isJsonNull }?.asString }
                    } else null
                } else {
                    data.getAsJsonArray("durl")?.firstOrNull()?.asJsonObject
                        ?.get("url")?.takeIf { !it.isJsonNull }?.asString
                }
            }
        }.getOrNull()
    }

    /** 从任意 B 站链接（含 b23.tv 短链）提取 BV 号 */
    fun extractBvid(url: String): String? {
        Regex("BV[0-9A-Za-z]{10}").find(url)?.let { return it.value }
        if (url.contains("b23.tv")) {
            return runCatching {
                Http.get(url).use { resp ->
                    Regex("BV[0-9A-Za-z]{10}").find(resp.request.url.toString())?.value
                }
            }.getOrNull()
        }
        return null
    }
}

// ============================================================
// 源 3：LibriVox（全球最大公版有声书库，RSS 单集直链）
// ============================================================

object LibriVoxSource {
    const val ID = "librivox"
    private val gson = Gson()

    fun search(term: String): List<BookResult> {
        val url = "https://librivox.org/api/feed/audiobooks/?format=json&title=" +
            URLEncoder.encode(term, "UTF-8") + "&limit=20"
        Http.get(url).use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java) ?: return emptyList()
            val books = root.getAsJsonArray("books") ?: return emptyList()
            return books.mapNotNull { el ->
                val o = el.asJsonObject ?: return@mapNotNull null
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                val authors = o.getAsJsonArray("authors")?.firstOrNull()?.asJsonObject
                val author = listOfNotNull(
                    authors?.get("first_name")?.takeIf { !it.isJsonNull }?.asString,
                    authors?.get("last_name")?.takeIf { !it.isJsonNull }?.asString
                ).joinToString(" ").ifEmpty { "LibriVox" }
                val lang = o.get("language")?.takeIf { !it.isJsonNull }?.asString ?: ""
                BookResult(
                    source = ID, key = id, title = title, author = author,
                    coverUrl = null, feedUrl = "https://librivox.org/rss/$id",
                    desc = "LibriVox · 公版有声书" + (if (lang.isNotBlank()) " · $lang" else "")
                )
            }
        }
    }

    /** LibriVox 的剧集 RSS，与播客共用解析器 */
    fun loadTracks(id: String): List<Track> =
        PodcastSource.loadFeed("https://librivox.org/rss/$id")
}

// ============================================================
// 通用链接解析：直链音频 / RSS / m3u 播放列表 / 网页音频
// ============================================================

object UrlParser {
    data class ParseResult(val title: String?, val tracks: List<Track>)

    fun parse(urlStr: String): ParseResult {
        // 哔哩哔哩视频链接：按 BV 号解析分P
        BilibiliSource.extractBvid(urlStr)?.let { bvid ->
            val tracks = BilibiliSource.loadTracks(bvid)
            if (tracks.isNotEmpty()) return ParseResult(null, tracks)
        }
        // 直链音频，直接返回
        if (RssParser.isAudioUrl(urlStr) && !urlStr.substringBefore('?').endsWith(".m3u8")) {
            val name = urlStr.substringAfterLast('/').substringBefore('?').ifEmpty { "音频" }
            return ParseResult(null, listOf(Track(decodeName(name), urlStr)))
        }
        Http.get(urlStr).use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val contentType = resp.header("Content-Type") ?: ""
            val body = resp.body?.string() ?: throw java.io.IOException("空响应")

            // m3u 播放列表
            if (contentType.contains("mpegurl", true) || body.trimStart().startsWith("#EXTM3U")) {
                return parseM3u(body, urlStr)
            }
            // RSS / Atom
            val head = body.trimStart().take(400).lowercase()
            if (head.contains("<rss") || head.contains("<feed") ||
                body.contains("<enclosure", true) || body.contains("<item>", true)
            ) {
                val tracks = RssParser.parse(body)
                if (tracks.isNotEmpty()) {
                    return ParseResult(feedTitle(body), tracks)
                }
            }
            // 网页中提取音频链接
            return parseHtml(body, urlStr)
        }
    }

    private fun parseM3u(body: String, base: String): ParseResult {
        val baseUri = runCatching { java.net.URI(base) }.getOrNull()
        val tracks = ArrayList<Track>()
        var pendingTitle: String? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    pendingTitle = line.substringAfter(',').trim().ifEmpty { null }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val resolved = try {
                        baseUri?.resolve(line).toString()
                    } catch (_: Exception) { line }
                    tracks.add(Track(pendingTitle ?: "第 ${tracks.size + 1} 集", resolved))
                    pendingTitle = null
                }
            }
        }
        return ParseResult(null, tracks)
    }

    private fun feedTitle(xml: String): String? {
        // 取 <channel><title> 或 <feed><title>（跳过 <item> 内的 title）
        val idxItem = xml.indexOf("<item")
        val scope = if (idxItem > 0) xml.substring(0, idxItem) else xml.substring(0, minOf(xml.length, 8000))
        val m = Regex("<title[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</title>", RegexOption.DOT_MATCHES_ALL)
            .find(scope)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val AUDIO_SRC = Regex(
        """(?:src|href|data-src|data-url)\s*=\s*["']([^"']+\.(?:mp3|m4a|aac|ogg|oga|opus|wav|flac|m4b)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE
    )
    private val MEDIA_TAG = Regex(
        """<(?:audio|source|embed)[^>]+src\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private fun parseHtml(html: String, pageUrl: String): ParseResult {
        val baseUri = runCatching { java.net.URI(pageUrl) }.getOrNull()
        val found = LinkedHashSet<String>()
        AUDIO_SRC.findAll(html).forEach { found.add(it.groupValues[1]) }
        MEDIA_TAG.findAll(html).forEach { found.add(it.groupValues[1]) }
        val tracks = found.mapNotNull { raw ->
            val abs = try {
                baseUri?.resolve(raw.trim())?.toString() ?: raw.trim()
            } catch (_: Exception) { return@mapNotNull null }
            if (RssParser.isAudioUrl(abs) || abs.contains(".m3u8")) abs else null
        }.distinct().mapIndexed { i, abs ->
            Track("第 ${i + 1} 集 · ${decodeName(abs.substringAfterLast('/').substringBefore('?'))}", abs)
        }
        return ParseResult(null, tracks)
    }

    private fun decodeName(name: String): String =
        runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name).ifEmpty { "音频" }
}

/** 统一加载一本书的全部剧集 */
object TrackLoader {
    fun load(book: BookResult): List<Track> = when (book.source) {
        PodcastSource.ID -> book.feedUrl?.let { PodcastSource.loadFeed(it) } ?: emptyList()
        BilibiliSource.ID -> BilibiliSource.loadTracks(book.key)
        LibriVoxSource.ID -> LibriVoxSource.loadTracks(book.key)
        else -> {
            // 兼容粘贴的 B 站视频链接
            val bvid = BilibiliSource.extractBvid(book.feedUrl ?: "")
            if (bvid != null) BilibiliSource.loadTracks(bvid)
            else book.feedUrl?.let { UrlParser.parse(it).tracks } ?: emptyList()
        }
    }
}
