package com.yunting.audiobook.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 「我的」：收藏 / 收听记录 / 累计听书统计
//   三份数据都独立于书架：不在书架里的书也能被收藏、也会进收听记录。
// ============================================================

/** 收藏的书（星标，跨源） */
data class FavoriteItem(
    val source: String,
    val key: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val feedUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/** 收听记录条目（含上次听到的集与进度，用于「继续收听」） */
data class HistoryItem(
    val source: String,
    val key: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val feedUrl: String? = null,
    var lastIndex: Int = 0,
    var lastPosMs: Long = 0L,
    var playedAt: Long = System.currentTimeMillis()
)

/** 收藏：SharedPreferences + Gson，Compose 可观察 */
object FavoriteStore {
    private const val PREF = "cloud_listen_favorite"
    private const val MAX = 300
    private val gson = Gson()
    private lateinit var pref: SharedPreferences

    private val _items = mutableStateListOf<FavoriteItem>()
    val items: List<FavoriteItem> get() = _items

    fun init(context: Context) {
        pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = pref.getString("items", null) ?: return
        runCatching {
            val type = object : TypeToken<MutableList<FavoriteItem>>() {}.type
            val list: MutableList<FavoriteItem> = gson.fromJson(json, type) ?: return
            _items.clear()
            // 老数据里的封面可能是 `//host/…`（第三方接口原样存的），恢复时统一归一化
            _items.addAll(list.map { it.copy(coverUrl = normalizeCover(it.coverUrl)) })
        }
    }

    fun isFavorite(source: String, key: String): Boolean =
        _items.any { it.source == source && it.key == key }

    /** 切换收藏，返回切换后的状态：true = 已收藏 */
    @Synchronized
    fun toggle(item: FavoriteItem): Boolean {
        val idx = _items.indexOfFirst { it.source == item.source && it.key == item.key }
        if (idx >= 0) {
            _items.removeAt(idx)
            persist()
            return false
        }
        _items.add(0, item.copy(coverUrl = normalizeCover(item.coverUrl)))
        while (_items.size > MAX) _items.removeAt(_items.size - 1)
        persist()
        return true
    }

    @Synchronized
    fun remove(source: String, key: String) {
        _items.removeAll { it.source == source && it.key == key }
        persist()
    }

    private fun persist() {
        if (!this::pref.isInitialized) return
        pref.edit().putString("items", gson.toJson(_items.toList())).apply()
    }
}

/** 收听记录：按最近收听倒序，自动记录（含未加入书架的书） */
object HistoryStore {
    private const val PREF = "cloud_listen_history"
    private const val MAX = 200
    private const val FLUSH_INTERVAL_MS = 10_000L

    private val gson = Gson()
    private lateinit var pref: SharedPreferences

    private val _items = mutableStateListOf<HistoryItem>()
    val items: List<HistoryItem> get() = _items

    private var lastFlush = 0L

    fun init(context: Context) {
        pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = pref.getString("items", null) ?: return
        runCatching {
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            val list: MutableList<HistoryItem> = gson.fromJson(json, type) ?: return
            _items.clear()
            _items.addAll(
                list.sortedByDescending { it.playedAt }
                    .map { it.copy(coverUrl = normalizeCover(it.coverUrl)) }
            )
        }
    }

    /**
     * 记录 / 更新一条收听记录。
     * 内存即时更新（界面立刻可见），落盘按 [FLUSH_INTERVAL_MS] 节流，避免播放中每 5 秒写一次盘。
     */
    @Synchronized
    fun record(
        source: String,
        key: String,
        title: String,
        author: String,
        coverUrl: String?,
        feedUrl: String?,
        index: Int,
        posMs: Long,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val idx = _items.indexOfFirst { it.source == source && it.key == key }
        if (idx >= 0) {
            val old = _items[idx]
            val updated = old.copy(
                title = title.ifBlank { old.title },
                author = author.ifBlank { old.author },
                coverUrl = normalizeCover(coverUrl) ?: old.coverUrl,
                feedUrl = feedUrl ?: old.feedUrl,
                lastIndex = index,
                lastPosMs = posMs,
                playedAt = now
            )
            if (idx == 0) {
                _items[0] = updated
            } else {
                _items.removeAt(idx)
                _items.add(0, updated)
            }
        } else {
            _items.add(
                0,
                HistoryItem(
                    source = source, key = key, title = title, author = author,
                    coverUrl = normalizeCover(coverUrl), feedUrl = feedUrl,
                    lastIndex = index, lastPosMs = posMs, playedAt = now
                )
            )
            while (_items.size > MAX) _items.removeAt(_items.size - 1)
        }
        if (force || now - lastFlush > FLUSH_INTERVAL_MS) {
            lastFlush = now
            persist()
        }
    }

    @Synchronized
    fun remove(source: String, key: String) {
        _items.removeAll { it.source == source && it.key == key }
        persist()
    }

    @Synchronized
    fun clear() {
        _items.clear()
        persist()
    }

    fun find(source: String, key: String): HistoryItem? =
        _items.firstOrNull { it.source == source && it.key == key }

    private fun persist() {
        if (!this::pref.isInitialized) return
        pref.edit().putString("items", gson.toJson(_items.toList())).apply()
    }
}

/** 累计听书统计：总时长 / 今日时长 / 收听天数 / 听过的书 */
object PlayStatsStore {
    private const val PREF = "cloud_listen_stats"
    private const val FLUSH_INTERVAL_MS = 20_000L

    private val gson = Gson()
    private lateinit var pref: SharedPreferences
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 累计收听时长（毫秒） */
    var totalMs by mutableLongStateOf(0L)
        private set

    /** 今日收听时长（毫秒） */
    var todayMs by mutableLongStateOf(0L)
        private set

    /** 累计收听天数 */
    var dayCount by mutableIntStateOf(0)
        private set

    /** 累计听过的书（按 source|key 去重） */
    var bookCount by mutableIntStateOf(0)
        private set

    private val days = LinkedHashSet<String>()
    private val books = LinkedHashSet<String>()
    private var today = ""
    private var pendingMs = 0L
    private var lastFlush = 0L

    fun init(context: Context) {
        pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        runCatching {
            totalMs = pref.getLong("totalMs", 0L)
            todayMs = pref.getLong("todayMs", 0L)
            today = pref.getString("today", "") ?: ""
            days.clear()
            books.clear()
            readSet("days")?.let { days.addAll(it) }
            readSet("books")?.let { books.addAll(it) }
            dayCount = days.size
            bookCount = books.size
            rollToday()
        }
    }

    /**
     * 累加收听时长。播放器心跳每 500ms 调一次，这里攒够 1 秒才写状态，
     * 既保证精度又避免界面每 0.5 秒重组一次。
     *
     * @param bookKey "source|key"，用于统计「听过的书」
     */
    fun addListening(ms: Long, bookKey: String?) {
        if (ms <= 0L) return
        if (bookKey != null && books.add(bookKey)) bookCount = books.size
        pendingMs += ms
        if (pendingMs < 1000L) return
        val add = pendingMs
        pendingMs = 0L
        rollToday()
        totalMs += add
        todayMs += add
        if (days.add(today)) dayCount = days.size
        flush(false)
    }

    /** 跨天时把「今日」清零 */
    private fun rollToday() {
        val d = dayFmt.format(Date())
        if (d != today) {
            today = d
            todayMs = 0L
        }
    }

    /** 落盘（消耗 pending，避免丢秒） */
    @Synchronized
    fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < FLUSH_INTERVAL_MS) return
        if (pendingMs > 0L) {
            rollToday()
            totalMs += pendingMs
            todayMs += pendingMs
            if (days.add(today)) dayCount = days.size
            pendingMs = 0L
        }
        lastFlush = now
        if (!this::pref.isInitialized) return
        pref.edit()
            .putLong("totalMs", totalMs)
            .putLong("todayMs", todayMs)
            .putString("today", today)
            .putString("days", gson.toJson(days.toList()))
            .putString("books", gson.toJson(books.toList()))
            .apply()
    }

    private fun readSet(name: String): List<String>? {
        val json = pref.getString(name, null) ?: return null
        val type = object : TypeToken<MutableList<String>>() {}.type
        return gson.fromJson(json, type)
    }
}
