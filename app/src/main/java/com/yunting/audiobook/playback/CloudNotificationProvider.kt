package com.yunting.audiobook.playback

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import coil.request.ImageRequest
import com.google.common.collect.ImmutableList
import com.yunting.audiobook.R
import com.yunting.audiobook.data.NotifSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 通知中心播放通知：按「通知中心控制」里的设置项生成。
 *
 *  - 上一集/下一集、关闭按钮可独立开关；
 *  - 封面异步加载（Coil），关闭后仅显示文字；
 *  - 锁屏显示 → 通知可见性 PUBLIC/SECRET；
 *  - 关闭「显示播放通知」→ 切到静默通道（不在状态栏显示图标、不发声）。
 *
 *  所有逻辑均有兜底，任何异常都不会影响播放。 */
class CloudNotificationProvider(private val context: Context) : MediaNotification.Provider {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val covers = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()

    override fun createNotification(
        session: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val uri = coverUri(session)
        val cached = if (NotifSettings.showCover && uri != null) synchronized(this) { covers[uri] } else null
        val notification = build(session, actionFactory, cached)

        // 封面尚未就绪：后台加载完成后再回调刷新（不阻塞主线程）
        if (NotifSettings.showCover && uri != null && cached == null) {
            val already = synchronized(this) { !loading.add(uri) }
            if (!already) {
                scope.launch {
                    val bmp = runCatching { loadCover(uri) }.getOrNull()
                    synchronized(this@CloudNotificationProvider) { loading.remove(uri) }
                    if (bmp != null) {
                        synchronized(this@CloudNotificationProvider) { covers[uri] = bmp }
                        if (coverUri(session) == uri) {
                            mainHandler.post {
                                runCatching {
                                    callback.onNotificationChanged(build(session, actionFactory, bmp))
                                }
                            }
                        }
                    }
                }
            }
        }
        return notification
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: android.os.Bundle
    ): Boolean = false

    // ------------------------------------------------------------

    private fun build(
        session: MediaSession,
        actionFactory: MediaNotification.ActionFactory,
        cover: Bitmap?
    ): MediaNotification {
        val player = session.player
        val meta = runCatching { player.currentMediaItem?.mediaMetadata }.getOrNull()
            ?: MediaMetadata.EMPTY
        val title = (meta.title ?: meta.displayTitle)?.toString().takeIf { !it.isNullOrBlank() } ?: "云听书"
        val subtitle = (meta.artist ?: meta.albumTitle ?: meta.albumArtist)?.toString()
        val playing = runCatching { player.isPlaying }.getOrDefault(false)

        val channel = if (NotifSettings.showNotification) {
            PlaybackService.CHANNEL_ID
        } else {
            PlaybackService.CHANNEL_ID_SILENT
        }

        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        nb.setSmallIcon(R.drawable.ic_stat_notify)
        nb.setContentTitle(title)
        if (!subtitle.isNullOrBlank()) nb.setContentText(subtitle)
        nb.setShowWhen(false)
        nb.setOngoing(playing)
        nb.setOnlyAlertOnce(true)
        nb.setCategory(Notification.CATEGORY_TRANSPORT)
        nb.setVisibility(
            if (NotifSettings.showOnLockScreen) Notification.VISIBILITY_PUBLIC
            else Notification.VISIBILITY_SECRET
        )
        if (NotifSettings.tapOpensPlayer) {
            runCatching { nb.setContentIntent(session.sessionActivity) }
        }
        if (cover != null) nb.setLargeIcon(cover)

        val actions = ArrayList<Notification.Action>(4)
        if (NotifSettings.showPrevNext) {
            actions += mediaAction(actionFactory, session, R.drawable.ic_notif_prev, "上一集", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        actions += mediaAction(
            actionFactory, session,
            if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            if (playing) "暂停" else "播放",
            Player.COMMAND_PLAY_PAUSE
        )
        if (NotifSettings.showPrevNext) {
            actions += mediaAction(actionFactory, session, R.drawable.ic_notif_next, "下一集", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (NotifSettings.showClose) {
            actions += mediaAction(actionFactory, session, R.drawable.ic_notif_close, "关闭", Player.COMMAND_STOP)
        }
        actions.forEach { runCatching { nb.addAction(it) } }

        // 紧凑视图优先展示 上一集 / 播放 / 下一集
        val compact = when {
            NotifSettings.showPrevNext -> intArrayOf(0, 1, 2)
            else -> intArrayOf(0)
        }
        val style = Notification.MediaStyle()
        runCatching { style.setMediaSession(session.platformToken) }
        runCatching { style.setShowActionsInCompactView(*compact) }
        nb.setStyle(style)

        return MediaNotification(PlaybackService.NOTIFICATION_ID, nb.build())
    }

    /** 借助 Media3 的 ActionFactory 生成标准的媒体按钮（PendingIntent 由框架托管） */
    private fun mediaAction(
        factory: MediaNotification.ActionFactory,
        session: MediaSession,
        iconRes: Int,
        title: String,
        command: Int
    ): Notification.Action {
        val compat = factory.createMediaAction(
            session, IconCompat.createWithResource(context, iconRes), title, command
        )
        val tmp = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .addAction(compat)
            .build()
        return tmp.actions.first()
    }

    private fun coverUri(session: MediaSession): String? {
        val meta = runCatching { session.player.currentMediaItem?.mediaMetadata }.getOrNull() ?: return null
        return meta.artworkUri?.toString()
    }

    private suspend fun loadCover(uri: String): Bitmap? {
        val loader = coil.Coil.imageLoader(context)
        val req = ImageRequest.Builder(context)
            .data(uri)
            .size(256)
            .allowHardware(false)
            .build()
        val drawable = loader.execute(req).drawable ?: return null
        return (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }
}
