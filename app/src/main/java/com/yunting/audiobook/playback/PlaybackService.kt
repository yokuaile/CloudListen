package com.yunting.audiobook.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yunting.audiobook.MainActivity
import com.yunting.audiobook.R
import com.yunting.audiobook.data.NotifSettings

/** 后台播放服务：Media3 前台服务 + 通知栏控制（使用应用自带的单色耳机通知图标）。
 *  额外能力：音量增强（LoudnessEnhancer，客户端经自定义会话命令调节）。 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var enhancer: LoudnessEnhancer? = null

    /** 音量增强等扩展命令的会话回调 */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // 向控制器声明本服务支持的扩展命令
            return MediaSession.ConnectionResult.accept(
                sessionCommands(),
                playerCommands()
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_VOLUME_BOOST -> {
                    val gainMb = args.getInt(EXTRA_GAIN_MB, 0)
                    runCatching { enhancer?.setTargetGain(gainMb) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_CLOSE -> {
                    // 通知栏「关闭」：停止播放并让播放通知消失
                    runCatching { session.player.stop() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_REFRESH_NOTIF -> {
                    runCatching { applyNotifSettings(session); refreshNotification(session) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    /** 会话级可用命令（含自定义命令） */
    private fun sessionCommands() =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(CMD_SET_VOLUME_BOOST, Bundle.EMPTY))
            .add(SessionCommand(CMD_CLOSE, Bundle.EMPTY))
            .add(SessionCommand(CMD_REFRESH_NOTIF, Bundle.EMPTY))
            .build()

    /** 播放器级可用命令：关闭「上一集/下一集」后，系统媒体控制（通知栏/蓝牙/锁屏）也不再显示 */
    private fun playerCommands(): Player.Commands {
        val builder = Player.Commands.Builder()
            .addAll(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        if (!NotifSettings.showPrevNext) {
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        return builder.build()
    }

    /** 把「通知中心控制」的设置同步到会话：自定义布局 + 各控制器可用命令 */
    private fun applyNotifSettings(session: MediaSession) {
        runCatching {
            session.setCustomLayout(
                if (NotifSettings.showClose) {
                    listOf(
                        CommandButton.Builder()
                            .setSessionCommand(SessionCommand(CMD_CLOSE, Bundle.EMPTY))
                            .setIconResId(R.drawable.ic_notif_close)
                            .setDisplayName("关闭")
                            .setEnabled(true)
                            .build()
                    )
                } else emptyList()
            )
        }
        runCatching {
            val pc = playerCommands()
            val sc = sessionCommands()
            session.connectedControllers.forEach { c ->
                runCatching { session.setAvailableCommands(c, sc, pc) }
            }
        }
    }

    /** 立即用最新设置重建通知（不影响前台服务状态） */
    private fun refreshNotification(session: MediaSession) {
        runCatching { onUpdateNotification(session, false) }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            NotifSettings.init(this)
            createChannel()

            // 取流必须走带防盗链头的数据源，否则哔哩哔哩音频直链会被 CDN 判 403
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(PlayerDataSource.mediaSourceFactory())
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

            // 音量增强：跟随音频会话 ID（初始或变化时重建增强器）
            runCatching { setupEnhancer(player.audioSessionId) }
            player.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(sessionId: Int) {
                    setupEnhancer(sessionId)
                }
            })

            val sessionActivity = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(sessionCallback)
                .build()

            // 通知样式：单色耳机小图标（ic_stat_notify）+ 可按「通知中心控制」定制的按钮/封面/锁屏可见性
            setMediaNotificationProvider(CloudNotificationProvider(this))
            runCatching { applyNotifSettings(mediaSession!!) }
        } catch (e: Throwable) {
            // 任何初始化异常都安全退出，绝不冒泡到系统崩溃
            android.util.Log.e("PlaybackService", "init failed", e)
            stopSelf()
        }
    }

    /** 按音频会话 ID 建立音量增强器（targetGain 由客户端经自定义命令设置） */
    private fun setupEnhancer(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            enhancer?.release()
            enhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 只要队列里还有内容就保留服务：从最近任务划掉卡片时，
        // 用户通常只是想退出界面而不是清空播放列表。
        val player = mediaSession?.player
        if (player == null || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { enhancer?.release() }
        enhancer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)

            // 静默通道：用户关闭「显示播放通知」时使用（不在状态栏显示图标、不发声）
            val silent = NotificationChannel(
                CHANNEL_ID_SILENT, "后台播放（静默）", NotificationManager.IMPORTANCE_MIN
            )
            silent.setShowBadge(false)
            silent.setSound(null, null)
            silent.enableVibration(false)
            nm.createNotificationChannel(silent)
        }
    }

    companion object {
        const val CHANNEL_ID = "cloud_listen_playback"
        const val CHANNEL_ID_SILENT = "cloud_listen_playback_silent"
        const val NOTIFICATION_ID = 2001

        /** 音量增强自定义会话命令（毫贝为单位的目标增益） */
        const val CMD_SET_VOLUME_BOOST = "com.yunting.audiobook.SET_VOLUME_BOOST"
        const val EXTRA_GAIN_MB = "gain_mb"
        /** 通知栏「关闭」按钮 */
        const val CMD_CLOSE = "com.yunting.audiobook.CLOSE"
        /** 通知设置变更：立即用新设置重建通知 */
        const val CMD_REFRESH_NOTIF = "com.yunting.audiobook.REFRESH_NOTIF"
    }
}
