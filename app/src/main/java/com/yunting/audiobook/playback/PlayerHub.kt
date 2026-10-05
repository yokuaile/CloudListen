package com.yunting.audiobook.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.yunting.audiobook.data.BookInLibrary
import com.yunting.audiobook.data.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 与后台播放服务通信的单例，向 Compose 暴露可观察的播放状态。
 *
 *  关键改动：控制器连接【延迟】到用户首次播放时才建立，避免应用启动即拉起
 *  MediaSessionService 导致的启动期崩溃。首屏（书架/发现）完全不触碰 Media3。 */
object PlayerHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private val connecting = AtomicBoolean(false)

    var controller: MediaController? = null
        private set

    // —— Compose 可观察状态 ——
    var isPlaying by mutableStateOf(false)
        private set
    var hasQueue by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var currentIndex by mutableStateOf(-1)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set
    var currentBookTitle by mutableStateOf<String?>(null)
        private set
    var currentCover by mutableStateOf<String?>(null)
        private set

    // —— 播放页扩展功能状态 ——
    /** 播放速度（0.5x ~ 3.0x） */
    var playbackSpeed by mutableStateOf(1.0f)
        private set
    /** 定时关闭剩余毫秒；0 = 未开启 */
    var sleepRemainingMs by mutableLongStateOf(0L)
        private set
    /** 音量增强倍数（1.0 ~ 2.0） */
    var volumeBoost by mutableStateOf(1.0f)
        private set
    /** 与其他应用同时播放（关闭音频焦点抢占） */
    var coexistMode by mutableStateOf(false)
        private set

    /** 正在播放的书（用于书架高亮、进度保存、详情页定位） */
    var currentBook: BookInLibrary? = null
        private set

    private var errorCount = 0          // 连续跳过计数，避免整本书都在报错时死循环
    private var errorRetry = 0          // 当前集的重新解析重试次数（直链失效时重新拿地址）
    private var sleepJob: Job? = null
    private var resolveJob: Job? = null // 当前集解析
    private var preloadJob: Job? = null // 下一集预解析
    private val resolvedIndexes = mutableSetOf<Int>()

    private val playbackAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    /** 仅保存上下文，不在启动时连接控制器 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 确保控制器已就绪；未连接则建立连接，就绪后回调 onReady */
    private fun ensureController(onReady: (MediaController) -> Unit) {
        val existing = controller
        if (existing != null) {
            onReady(existing)
            return
        }
        val ctx = appContext ?: return
        if (!connecting.compareAndSet(false, true)) return
        try {
            val token = SessionToken(
                ctx,
                ComponentName(ctx, PlaybackService::class.java)
            )
            val future = MediaController.Builder(ctx, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                            if (!playing) saveNow(force = true)
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            errorRetry = 0
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                            ) {
                                saveNow()
                            }
                            syncCurrent(c)
                            // 切集后立刻解析当前集（未解析的 bili:// 占位地址），并预解析下一集
                            resolveCurrentIfNeeded(c)
                            preloadNextBili(c)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            syncCurrent(c)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.w("PlayerHub", "播放失败: ${error.errorCodeName}", error)
                            // 有解析任务在飞：等它替换地址后会自动重新播放
                            if (resolveJob != null) return
                            scope.launch {
                                val idx = c.currentMediaItemIndex
                                // 哔哩哔哩集：直链过期或解析失效 → 重新解析再重试，避免误跳过
                                val biliUri = biliUriFor(idx)
                                if (biliUri != null && errorRetry < 2) {
                                    errorRetry++
                                    val real = resolveIfBili(biliUri)
                                    val item = runCatching { c.getMediaItemAt(idx) }.getOrNull()
                                    if (real != null && item != null) {
                                        c.replaceMediaItem(idx, item.buildUpon().setUri(real).build())
                                        resolvedIndexes.add(idx)
                                        c.prepare()
                                        c.play()
                                        return@launch
                                    }
                                }
                                handleError(c)
                            }
                        }
                    })
                    syncCurrent(c)
                    tick()
                    applyStoredSettings(c)
                    onReady(c)
                } catch (e: Exception) {
                    connecting.set(false)
                    toast("播放器初始化失败：${e.message}")
                }
            }, Dispatchers.Main.asExecutor())
        } catch (e: Exception) {
            connecting.set(false)
            toast("播放器初始化失败：${e.message}")
        }
    }

    private fun syncCurrent(c: MediaController) {
        hasQueue = c.mediaItemCount > 0
        currentIndex = c.currentMediaItemIndex
        val item = runCatching { c.currentMediaItem }.getOrNull()
        currentTitle = item?.mediaMetadata?.title?.toString()
        currentBookTitle = item?.mediaMetadata?.artist?.toString()
        currentCover = item?.mediaMetadata?.artworkUri?.toString()
        durationMs = runCatching { c.duration }.getOrDefault(C.TIME_UNSET).let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0L) }
        positionMs = runCatching { c.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
    }

    /** 500ms 心跳刷新进度；每 5 秒落盘一次进度与收听记录；快播完时预解析下一集（哔哩哔哩） */
    private fun tick() {
        scope.launch {
            var lastSave = 0L
            var lastTick = 0L
            while (true) {
                controller?.let { c ->
                    if (c.mediaItemCount > 0) {
                        positionMs = runCatching { c.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                        durationMs = runCatching { c.duration }.getOrDefault(C.TIME_UNSET).let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0L) }
                    }
                    val now = System.currentTimeMillis()
                    // 累计听书：按真实播放时长累加（限幅 3 秒，避免息屏/进程挂起把空档算进去）
                    val dt = if (lastTick == 0L) 0L else now - lastTick
                    lastTick = now
                    if (isPlaying && dt in 1..3000) {
                        com.yunting.audiobook.data.PlayStatsStore.addListening(
                            dt, currentBook?.let { it.source + "|" + it.key }
                        )
                    }
                    if (isPlaying && now - lastSave > 5000) {
                        lastSave = now
                        saveNow()
                    }
                    preloadNextBili(c)
                }
                delay(500)
            }
        }
    }

    /** 当前集是否仍是未解析的 bili:// 占位地址；是则后台解析并替换 */
    private fun resolveCurrentIfNeeded(c: MediaController) {
        if (resolveJob != null) return
        val idx = c.currentMediaItemIndex
        if (idx < 0) return
        val item = runCatching { c.getMediaItemAt(idx) }.getOrNull() ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        if (!uri.startsWith("bili://")) return
        resolveJob = scope.launch {
            val real = resolveIfBili(uri)
            resolveJob = null
            val cc = controller ?: return@launch
            if (real == null) {
                toast("哔哩哔哩解析失败，请检查网络后重试")
                return@launch
            }
            val cur = runCatching { cc.getMediaItemAt(idx) }.getOrNull() ?: return@launch
            if (cur.localConfiguration?.uri?.toString() != uri) return@launch
            cc.replaceMediaItem(idx, cur.buildUpon().setUri(real).build())
            resolvedIndexes.add(idx)
            if (cc.currentMediaItemIndex == idx) {
                cc.prepare()
                cc.play()
            }
        }
    }

    /** 下一集是未解析的哔哩哔哩集时：后台解析并替换，连播无感 */
    private fun preloadNextBili(c: MediaController) {
        if (preloadJob != null) return
        val idx = c.currentMediaItemIndex
        val nextIdx = idx + 1
        if (idx < 0 || nextIdx >= c.mediaItemCount) return
        if (nextIdx in resolvedIndexes) return
        val dur = runCatching { c.duration }.getOrDefault(C.TIME_UNSET)
        val pos = runCatching { c.currentPosition }.getOrDefault(0L)
        // 时长未知（DASH 音频常见）时直接预解析；否则只在快播完时预解析
        if (dur != C.TIME_UNSET && dur > 0 && pos > 0 && dur - pos > 120_000) return
        val item = runCatching { c.getMediaItemAt(nextIdx) }.getOrNull() ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        if (!uri.startsWith("bili://")) return
        preloadJob = scope.launch {
            val real = resolveIfBili(uri)
            preloadJob = null
            val cc = controller ?: return@launch
            val target = runCatching { cc.getMediaItemAt(nextIdx) }.getOrNull() ?: return@launch
            if (real != null && target.localConfiguration?.uri?.toString() == uri) {
                cc.replaceMediaItem(nextIdx, target.buildUpon().setUri(real).build())
                resolvedIndexes.add(nextIdx)
            }
        }
    }

    /** 当前集在书里对应的原始 bili:// 地址（用于直链失效后重新解析） */
    private fun biliUriFor(index: Int): String? {
        if (index < 0) return null
        val track = currentBook?.tracks?.getOrNull(index) ?: return null
        return track.url.takeIf { it.startsWith("bili://") }
    }

    /** 版权受限 / 失效资源：自动跳到下一集 */
    private fun handleError(c: MediaController) {
        errorCount++
        val total = c.mediaItemCount
        if (errorCount < total && c.hasNextMediaItem()) {
            toast("当前资源无法播放，已自动跳过")
            c.seekToNextMediaItem()
            c.prepare()
            c.play()
        } else {
            toast("当前资源无法播放，请检查网络或换一个音源")
            c.pause()
        }
    }

    private fun saveNow(force: Boolean = false) {
        val book = currentBook ?: return
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        runCatching {
            val index = c.currentMediaItemIndex
            val pos = c.currentPosition
            LibraryStore.saveProgress(book.source, book.key, index, pos)
            // 收听记录：书架里的书、以及只在历史里播放过的书都会记
            com.yunting.audiobook.data.HistoryStore.record(
                source = book.source,
                key = book.key,
                title = book.title,
                author = book.author,
                coverUrl = book.coverUrl,
                feedUrl = book.feedUrl,
                index = index,
                posMs = pos,
                force = force
            )
            if (force) com.yunting.audiobook.data.PlayStatsStore.flush(true)
        }
    }

    // ============================================================
    // 对外操作（全部延迟到控制器就绪）
    // ============================================================

    /** 播放一本书（默认从上次进度继续） */
    fun playBook(book: BookInLibrary, startIndex: Int = book.lastIndex, startMs: Long = book.lastPosMs) {
        ensureController { c ->
            if (book.tracks.isEmpty()) {
                toast("没有可播放的剧集")
                return@ensureController
            }
            errorCount = 0
            errorRetry = 0
            currentBook = book
            resolvedIndexes.clear()
            val si = startIndex.coerceIn(0, book.tracks.size - 1)
            scope.launch {
                // 哔哩哔哩资源：先解析起始集真实音频地址，其余集播放时再解析
                var startRealUrl: String? = null
                val startTrack = book.tracks[si]
                if (startTrack.url.startsWith("bili://")) {
                    toast("正在解析哔哩哔哩音频…")
                    startRealUrl = resolveIfBili(startTrack.url)
                    if (startRealUrl == null) {
                        toast("哔哩哔哩解析失败，请稍后重试")
                        return@launch
                    }
                    resolvedIndexes.add(si)
                }
                val items = book.tracks.mapIndexed { idx, t ->
                    MediaItem.Builder()
                        .setUri(if (idx == si) (startRealUrl ?: t.url) else t.url)
                        .setMediaId("${book.source}::${book.key}::$idx")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(t.title)
                                .setArtist(book.title)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                                .apply { book.coverUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                                .build()
                        )
                        .build()
                }
                c.setMediaItems(items, si, if (startMs > 0) startMs else 0L)
                c.prepare()
                c.play()
                // 起播即写入收听记录（即使用户只听几秒就退出，也能在「收听记录」里找回）
                saveNow(force = true)
                preloadNextBili(c)
            }
        }
    }

    /** bili://<bvid>/<cid> → 真实音频直链；非 bili 原样返回 */
    private suspend fun resolveIfBili(url: String): String? {
        if (!url.startsWith("bili://")) return url
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val parts = url.removePrefix("bili://").split("/")
                if (parts.size < 2) return@runCatching null
                com.yunting.audiobook.data.BilibiliSource.resolvePlayUrl(parts[0], parts[1])
            }.getOrNull()
        }
    }

    /** 跳到指定集并播放（哔哩哔哩集先解析） */
    fun seekToIndex(index: Int) {
        ensureController { c ->
            if (index < 0 || index >= c.mediaItemCount) return@ensureController
            errorCount = 0
            errorRetry = 0
            scope.launch {
                val item = runCatching { c.getMediaItemAt(index) }.getOrNull() ?: return@launch
                val uri = item.localConfiguration?.uri?.toString() ?: ""
                val real = resolveIfBili(uri)
                if (real != null && real != uri) {
                    runCatching { c.replaceMediaItem(index, item.buildUpon().setUri(real).build()) }
                    resolvedIndexes.add(index)
                }
                c.seekTo(index, 0L)
                c.play()
            }
        }
    }

    fun toggle() {
        ensureController { c ->
            if (c.mediaItemCount == 0) {
                toast("还没有可播放的内容")
                return@ensureController
            }
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun next() {
        ensureController { c ->
            if (c.hasNextMediaItem()) { errorCount = 0; errorRetry = 0; c.seekToNextMediaItem(); c.play() }
        }
    }

    fun prev() {
        ensureController { c ->
            if (c.hasPreviousMediaItem()) { errorCount = 0; errorRetry = 0; c.seekToPreviousMediaItem(); c.play() }
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: ms))
    }

    fun stopAndClear() {
        controller?.run { stop(); clearMediaItems() }
        currentBook = null
        hasQueue = false
        currentTitle = null
        currentBookTitle = null
        currentCover = null
        currentIndex = -1
        positionMs = 0
        durationMs = 0
    }

    /** 当前队列标题列表（播放列表面板用） */
    fun queueTitles(): List<String> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).mapNotNull { idx ->
            runCatching { c.getMediaItemAt(idx).mediaMetadata.title?.toString() }.getOrNull() ?: ""
        }
    }

    // ============================================================
    // 播放页扩展：倍速 / 定时关闭 / 音量增强 / 与其他应用同时播放
    // ============================================================

    /** 设置播放速度（0.5x ~ 3.0x） */
    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 3.0f)
        controller?.setPlaybackSpeed(playbackSpeed)
    }

    /** 定时关闭：minutes 分钟后自动暂停；minutes <= 0 取消 */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepJob = scope.launch {
            var remainMs = minutes * 60_000L
            sleepRemainingMs = remainMs
            while (remainMs > 0) {
                delay(1000)
                remainMs -= 1000
                sleepRemainingMs = remainMs.coerceAtLeast(0L)
            }
            controller?.pause()
            sleepRemainingMs = 0L
            toast("定时时间到，已暂停播放")
        }
    }

    /** 取消定时关闭 */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepRemainingMs = 0L
    }

    /** 音量增强：mult 1.0 ~ 2.0（服务端 LoudnessEnhancer 提升增益） */
    fun applyVolumeBoost(mult: Float) {
        volumeBoost = mult.coerceIn(1.0f, 2.0f)
        val gainMb = ((volumeBoost - 1f) * 1500f).toInt()
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SET_VOLUME_BOOST, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.EXTRA_GAIN_MB, gainMb) }
        )
    }

    /** 与其他应用同时播放：关闭音频焦点抢占（开启后不会被其他应用打断，也不打断别人） */
    fun setCoexist(on: Boolean) {
        coexistMode = on
        controller?.setAudioAttributes(playbackAttrs, /* handleAudioFocus = */ !on)
    }

    /** 「通知中心控制」里改了设置：通知播放服务立即重建通知 */
    fun refreshNotification() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_REFRESH_NOTIF, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    /** 控制器就绪后补发本地已保存的设置（连接前设置过的也会生效） */
    private fun applyStoredSettings(c: MediaController) {
        runCatching {
            c.setPlaybackSpeed(playbackSpeed)
            c.setAudioAttributes(playbackAttrs, !coexistMode)
            val gainMb = ((volumeBoost - 1f) * 1500f).toInt()
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SET_VOLUME_BOOST, Bundle.EMPTY),
                Bundle().apply { putInt(PlaybackService.EXTRA_GAIN_MB, gainMb) }
            )
        }
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        runCatching {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } else {
                // 非 Looper 线程：切主线程再弹，避免 Can't toast on a thread that has not called Looper.prepare()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
