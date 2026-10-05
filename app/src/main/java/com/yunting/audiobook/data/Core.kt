package com.yunting.audiobook.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit

// ============================================================
// 数据模型
// ============================================================

/** 搜索结果中的一本书（播客 / 网易云专辑 / 解析链接） */
data class BookResult(
    val source: String,      // podcast / netease / url
    val key: String,         // 源内唯一标识
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val feedUrl: String? = null, // RSS 地址 / 专辑 id 走 netease 分支 / 网页或直链地址
    val desc: String? = null
)

/** 一集音频 */
data class Track(
    val title: String,
    val url: String,
    val durationMs: Long = 0L
)

/**
 * 封面地址归一化：第三方接口常给协议相对地址（`//i0.hdslb.com/…`）或明文 http，
 * Coil 遇到这类地址会加载失败（表现为"没有封面"），统一转成 https 绝对地址。
 */
fun normalizeCover(url: String?): String? {
    val u = url?.trim().orEmpty()
    if (u.isEmpty()) return null
    return when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        u.startsWith("https://") -> u
        else -> "https://$u"
    }
}

/** 加入书架的书（含全部剧集与进度） */
data class BookInLibrary(
    val source: String,
    val key: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val feedUrl: String?,
    val tracks: List<Track>,
    var lastIndex: Int = 0,
    var lastPosMs: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

// ============================================================
// HTTP
// ============================================================

object Http {
    /** 进程内 Cookie 容器：B 站等站点需要先拿 buvid3 / b_nut 才能稳定取流，
     *  没有 Cookie 时接口仍有概率被风控拦截，故统一持久化在内存里。 */
    private val cookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()

        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isEmpty()) return
            val merged = LinkedHashMap<String, okhttp3.Cookie>()
            store[url.host]?.forEach { merged[it.name] = it }
            cookies.forEach { merged[it.name] = it }
            store[url.host] = merged.values.toList()
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host] ?: emptyList()
    }

    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()

    fun get(url: String, referer: String? = null, ua: String = MOBILE_UA): okhttp3.Response {
        val b = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", ua)
        if (referer != null) b.header("Referer", referer)
        return client.newCall(b.build()).execute()
    }

    /** 预热 B 站 Cookie（buvid3 / b_nut），降低后续接口被风控的概率 */
    fun warmupCookies(host: String) {
        runCatching { get(host).use { } }
    }
}

// ============================================================
// 书架存储（SharedPreferences + Gson）
// ============================================================

object LibraryStore {
    private const val PREF = "cloud_listen_library"
    private val gson = Gson()
    private lateinit var pref: SharedPreferences
    private val _books = androidx.compose.runtime.mutableStateListOf<BookInLibrary>()

    /** Compose 可观察的书架列表 */
    val books: List<BookInLibrary> get() = _books

    fun init(context: Context) {
        pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = pref.getString("books", null) ?: return
        runCatching {
            val type = object : TypeToken<MutableList<BookInLibrary>>() {}.type
            val list: MutableList<BookInLibrary> = gson.fromJson(json, type)
            _books.clear()
            // 老版本可能存过 `//host/…` 形式的封面，恢复时就地修正，否则会一直不显示
            _books.addAll(list.map { if (it.coverUrl != null) it.copy(coverUrl = normalizeCover(it.coverUrl)) else it })
        }
    }

    @Synchronized
    fun upsert(book: BookInLibrary) {
        val idx = _books.indexOfFirst { it.source == book.source && it.key == book.key }
        if (idx >= 0) {
            val old = _books[idx]
            _books[idx] = book.copy(lastIndex = old.lastIndex, lastPosMs = old.lastPosMs)
        } else {
            _books.add(0, book)
        }
        persist()
    }

    @Synchronized
    fun remove(source: String, key: String) {
        _books.removeAll { it.source == source && it.key == key }
        persist()
    }

    @Synchronized
    fun find(source: String, key: String): BookInLibrary? =
        _books.firstOrNull { it.source == source && it.key == key }

    @Synchronized
    fun saveProgress(source: String, key: String, index: Int, posMs: Long) {
        val idx = _books.indexOfFirst { it.source == source && it.key == key }
        if (idx < 0) return
        val old = _books[idx]
        _books[idx] = old.copy(lastIndex = index, lastPosMs = posMs)
        persist()
    }

    private fun persist() {
        if (!this::pref.isInitialized) return
        pref.edit().putString("books", gson.toJson(_books.toList())).apply()
    }
}

// ============================================================
// 发现页搜索状态（进程内常驻 + 本地持久化；清空搜索文字才清空结果）
// ============================================================

object SearchStateStore {
    private const val PREF = "cloud_listen_search"
    private val gson = Gson()
    private lateinit var pref: SharedPreferences

    /** 搜索关键词：回到桌面/切页后保留，清空即清空结果 */
    var keyword by androidx.compose.runtime.mutableStateOf("")

    /** 来源筛选：null=全部 */
    var sourceFilter by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** 上次搜索的聚合结果（book + 来源标签） */
    var results by androidx.compose.runtime.mutableStateOf<List<Pair<BookResult, String>>>(emptyList())

    /** 结果列表滚动位置：点击进详情返回后保持在点击内容处 */
    val listState = androidx.compose.foundation.lazy.LazyListState()

    fun init(context: Context) {
        pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        runCatching {
            keyword = pref.getString("keyword", "") ?: ""
            sourceFilter = pref.getString("filter", null)
            val json = pref.getString("results", null)
            if (json != null) {
                val type = object : TypeToken<MutableList<SearchItem>>() {}.type
                val list: MutableList<SearchItem> = gson.fromJson(json, type)
                results = dedupe(list.map { it.book to it.tag })
            }
        }
    }

    fun updateKeyword(text: String) {
        keyword = text
        if (text.isBlank()) {
            results = emptyList()
            sourceFilter = null
        }
        save()
    }

    fun publishResults(list: List<Pair<BookResult, String>>) {
        results = dedupe(list)
        save()
    }

    /** 同一 source+key 只保留一条：列表 key 重复会让 LazyColumn 抛异常闪退 */
    private fun dedupe(list: List<Pair<BookResult, String>>): List<Pair<BookResult, String>> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.first.source + "|" + it.first.key) }
    }

    fun setFilter(v: String?) {
        sourceFilter = v
        save()
    }

    private fun save() {
        if (!this::pref.isInitialized) return
        val items = results.map { SearchItem(it.first, it.second) }
        pref.edit()
            .putString("keyword", keyword)
            .putString("filter", sourceFilter)
            .putString("results", gson.toJson(items))
            .apply()
    }

    private data class SearchItem(val book: BookResult, val tag: String)
}

/** 应用入口：初始化书架存储，并挂全局崩溃兜底（写入 crash.log 便于反馈） */
class CloudApp : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        LibraryStore.init(this)
        SearchStateStore.init(this)
        // 「我的」页数据：收藏 / 收听记录 / 累计听书统计
        FavoriteStore.init(this)
        HistoryStore.init(this)
        PlayStatsStore.init(this)
        installCrashHandler()
    }

    /**
     * 全局图片加载器：B 站图床（*.hdslb.com / *.biliimg.com）强制校验 Referer，
     * Coil 默认不带任何防盗链头 → 图片 403，表现为「哔哩哔哩没有封面」。
     * 这里复用 Http.client（含 Cookie 容器）并统一补 Referer + 桌面 UA。
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .okHttpClient {
                Http.client.newBuilder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val host = req.url.host.lowercase()
                        val needReferer = host.endsWith("hdslb.com") ||
                            host.endsWith("biliimg.com") ||
                            host.endsWith("bilivideo.com") ||
                            host.endsWith("bilibili.com")
                        if (needReferer) {
                            chain.proceed(
                                req.newBuilder()
                                    .header("Referer", "https://www.bilibili.com/")
                                    .header("User-Agent", Http.DESKTOP_UA)
                                    .build()
                            )
                        } else {
                            chain.proceed(req)
                        }
                    }
                    .build()
            }
            .build()

    private fun installCrashHandler() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val text = "[$ts] ${throwable.javaClass.name}: ${throwable.message}\n" +
                    throwable.stackTraceToString() + "\n\n"
                // 写文件（便于 adb 取）
                java.io.File(filesDir, "crash.log").appendText(text)
                // 同时落 SharedPreferences，下次启动弹出对话框展示
                getSharedPreferences("cloud_crash", Context.MODE_PRIVATE)
                    .edit().putString("last", text).apply()
            }
            def?.uncaughtException(thread, throwable)
        }
    }
}
