package com.yunting.audiobook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.yunting.audiobook.data.BookResult
import com.yunting.audiobook.data.LibraryStore
import com.yunting.audiobook.data.Track
import com.yunting.audiobook.playback.PlayerHub
import com.yunting.audiobook.ui.BookDetailScreen
import com.yunting.audiobook.ui.DiscoverScreen
import com.yunting.audiobook.ui.LibraryScreen
import com.yunting.audiobook.ui.PlayerScreen
import com.yunting.audiobook.ui.ProfileScreen
import com.yunting.audiobook.ui.theme.CloudListenTheme
import com.yunting.audiobook.ui.theme.WeChatGreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) { /* 个别 ROM 不支持则跳过，不影响内容 */ }
        setContent {
            CloudListenTheme {
                StatusBars()
                AppRoot()
            }
        }
    }
}

@Composable
private fun StatusBars() {
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(dark) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
}

private fun Color.luminance(): Float = (red * 0.299f + green * 0.587f + blue * 0.114f)

/** 详情页数据状态 */
class DetailState(
    val book: BookResult,
    var loading: Boolean = true,
    var tracks: List<Track> = emptyList(),
    var error: String? = null
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PlayerHub.init(context)
        com.yunting.audiobook.data.NotifSettings.init(context)
        LibraryOps.appContext = context.applicationContext
    }

    // Android 13+ 需动态申请通知权限，否则前台播放通知不显示、媒体控制按钮失效
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<DetailState?>(null) }
    var playerOpen by remember { mutableStateOf(false) }

    /*
     * 系统返回键分级返回（不再「一按就退出」）：
     *   播放页 → 回到上一级页面
     *   书籍详情 → 回到列表
     *   非首个 tab → 回到「书架」
     * 只有停在书架根页面时，返回键才交回系统（此时退出应用）。
     */
    BackHandler(enabled = playerOpen || detail != null || tab != 0) {
        when {
            playerOpen -> playerOpen = false
            detail != null -> detail = null
            else -> tab = 0
        }
    }

    // 启动期崩溃自诊断：读取上次异常并弹出，便于反馈
    var crashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("cloud_crash", android.content.Context.MODE_PRIVATE)
        val last = sp.getString("last", null)
        if (!last.isNullOrBlank()) {
            crashText = last
            sp.edit().remove("last").apply()
        }
    }

    // 播放器全屏
    if (playerOpen) {
        PlayerScreen(onCollapse = { playerOpen = false })
        return
    }

    // 书籍详情
    detail?.let { d ->
        BookDetailScreen(
            state = d,
            onBack = { detail = null },
            onPlay = { index ->
                // 未加入书架也能直接播放：自动补进书架后再起播，避免点了没反应
                val book = com.yunting.audiobook.data.LibraryStore.find(d.book.source, d.book.key)
                    ?: run {
                        val created = com.yunting.audiobook.data.BookInLibrary(
                            source = d.book.source,
                            key = d.book.key,
                            title = d.book.title,
                            author = d.book.author,
                            coverUrl = d.book.coverUrl,
                            feedUrl = d.book.feedUrl,
                            tracks = d.tracks
                        )
                        com.yunting.audiobook.data.LibraryStore.upsert(created)
                        com.yunting.audiobook.data.LibraryStore.find(d.book.source, d.book.key) ?: created
                    }
                if (book.tracks.isNotEmpty()) {
                    PlayerHub.playBook(book, index, 0L)
                } else {
                    android.widget.Toast.makeText(context, "还没有解析到剧集", android.widget.Toast.LENGTH_SHORT).show()
                }
                playerOpen = true
            },
            onAddToLibrary = { LibraryOps.addFromDetail(d) }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { WeChatBottomBar(tab) { tab = it } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (PlayerHub.hasQueue) {
                MiniPlayer(onOpen = { playerOpen = true })
            }
            when (tab) {
                0 -> LibraryScreen(
                    onOpenBook = { book ->
                        PlayerHub.playBook(book)
                        playerOpen = true
                    },
                    onRemoveBook = { book ->
                        LibraryStore.remove(book.source, book.key)
                        if (PlayerHub.currentBook?.let { it.source == book.source && it.key == book.key } == true) {
                            PlayerHub.stopAndClear()
                        }
                    }
                )
                1 -> DiscoverScreen(onOpenBook = { result ->
                    detail = DetailState(result)
                    LibraryOps.loadDetail(result) { loaded ->
                        detail = loaded
                    }
                })
                2 -> ProfileScreen(
                    onOpenBook = { result ->
                        detail = DetailState(result)
                        LibraryOps.loadDetail(result) { loaded ->
                            detail = loaded
                        }
                    },
                    onResumeHistory = { his ->
                        LibraryOps.resumeFromHistory(his) { started -> if (started) playerOpen = true }
                    }
                )
            }
        }
    }

    crashText?.let { txt ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { crashText = null },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(txt))
                    crashText = null
                }) { Text("复制并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { crashText = null }) { Text("关闭") }
            },
            title = { Text("检测到上次崩溃（已记录）") },
            text = {
                Text(
                    txt,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        )
    }
}

/** 详情加载 / 加书架的协程封装 */
object LibraryOps {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /** 供「收听记录」续播失败时提示用（AppRoot 启动时注入） */
    var appContext: android.content.Context? = null

    /**
     * 从「收听记录」继续收听：
     *  - 书在书架里 → 直接按记录里的集与进度续播（不用重新解析）；
     *  - 不在书架（例如只在发现页听过一次）→ 现场解析剧集后再续播。
     */
    fun resumeFromHistory(
        item: com.yunting.audiobook.data.HistoryItem,
        onStart: (Boolean) -> Unit
    ) {
        scope.launch {
            val existing = LibraryStore.find(item.source, item.key)
            if (existing != null && existing.tracks.isNotEmpty()) {
                PlayerHub.playBook(existing, item.lastIndex, item.lastPosMs)
                onStart(true)
                return@launch
            }
            val result = BookResult(
                source = item.source, key = item.key, title = item.title,
                author = item.author, coverUrl = item.coverUrl, feedUrl = item.feedUrl
            )
            val tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.yunting.audiobook.data.TrackLoader.load(result) }.getOrDefault(emptyList())
            }
            if (tracks.isEmpty()) {
                appContext?.let {
                    android.widget.Toast.makeText(
                        it, "该资源暂时解析不到音频，请重新搜索", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                onStart(false)
                return@launch
            }
            val book = com.yunting.audiobook.data.BookInLibrary(
                source = item.source, key = item.key, title = item.title,
                author = item.author, coverUrl = item.coverUrl,
                feedUrl = item.feedUrl, tracks = tracks
            )
            PlayerHub.playBook(book, item.lastIndex, item.lastPosMs)
            onStart(true)
        }
    }

    fun loadDetail(result: BookResult, onDone: (DetailState) -> Unit) {
        scope.launch {
            val state = DetailState(result)
            try {
                state.tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.yunting.audiobook.data.TrackLoader.load(result)
                }
                state.loading = false
                if (state.tracks.isEmpty()) state.error = "未解析到可播放的音频，请换一个链接或关键词"
            } catch (e: Exception) {
                state.loading = false
                state.error = "解析失败：${e.message ?: "未知错误"}"
            }
            onDone(state)
        }
    }

    fun addFromDetail(d: DetailState): Boolean {
        if (d.tracks.isEmpty()) return false
        LibraryStore.upsert(
            com.yunting.audiobook.data.BookInLibrary(
                source = d.book.source,
                key = d.book.key,
                title = d.book.title,
                author = d.book.author,
                coverUrl = d.book.coverUrl,
                feedUrl = d.book.feedUrl,
                tracks = d.tracks
            )
        )
        return true
    }
}

@Composable
private fun WeChatBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        val items = listOf(
            "书架" to Icons.Rounded.LibraryMusic,
            "发现" to Icons.Rounded.Explore,
            "我的" to Icons.Rounded.Person
        )
        items.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp)) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WeChatGreen,
                    selectedTextColor = WeChatGreen,
                    indicatorColor = MaterialTheme.colorScheme.surface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

/** 迷你播放条（微信绿点缀） */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            PlayerHub.currentCover?.let { cover ->
                AsyncImage(
                    model = cover, contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } ?: Icon(
                Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                PlayerHub.currentTitle ?: "未在播放",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                PlayerHub.currentBookTitle ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { PlayerHub.toggle() }) {
            Icon(
                if (PlayerHub.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "播放/暂停",
                tint = WeChatGreen,
                modifier = Modifier.size(30.dp)
            )
        }
        IconButton(onClick = { PlayerHub.next() }) {
            Icon(
                Icons.Rounded.SkipNext, contentDescription = "下一集",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
