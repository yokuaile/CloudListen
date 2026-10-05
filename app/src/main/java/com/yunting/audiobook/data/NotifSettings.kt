package com.yunting.audiobook.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 通知中心控制的本地设置：SharedPreferences 持久化 + Compose 可观察。
 *  播放服务每次创建通知时读取，界面改动后即时下发刷新命令。 */
object NotifSettings {

    private const val PREF = "cloud_notif"
    private const val K_SHOW = "show"            // 显示播放通知
    private const val K_COVER = "cover"          // 显示书籍封面
    private const val K_PREV_NEXT = "prev_next"  // 上一集 / 下一集
    private const val K_CLOSE = "close"          // 关闭按钮
    private const val K_LOCK = "lock"            // 锁屏显示播放信息
    private const val K_TAP = "tap"              // 点击通知打开播放页

    /** 关闭后改为静默通道：不在状态栏显示图标、不发声（Android 后台播放必须保留通知） */
    var showNotification by mutableStateOf(true)
        private set
    var showCover by mutableStateOf(true)
        private set
    var showPrevNext by mutableStateOf(true)
        private set
    var showClose by mutableStateOf(true)
        private set
    var showOnLockScreen by mutableStateOf(true)
        private set
    var tapOpensPlayer by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        showNotification = sp.getBoolean(K_SHOW, true)
        showCover = sp.getBoolean(K_COVER, true)
        showPrevNext = sp.getBoolean(K_PREV_NEXT, true)
        showClose = sp.getBoolean(K_CLOSE, true)
        showOnLockScreen = sp.getBoolean(K_LOCK, true)
        tapOpensPlayer = sp.getBoolean(K_TAP, true)
    }

    private fun put(context: Context, key: String, value: Boolean) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        }
    }

    fun setShowNotification(context: Context, v: Boolean) {
        showNotification = v
        put(context, K_SHOW, v)
    }

    fun setShowCover(context: Context, v: Boolean) {
        showCover = v
        put(context, K_COVER, v)
    }

    fun setShowPrevNext(context: Context, v: Boolean) {
        showPrevNext = v
        put(context, K_PREV_NEXT, v)
    }

    fun setShowClose(context: Context, v: Boolean) {
        showClose = v
        put(context, K_CLOSE, v)
    }

    fun setShowOnLockScreen(context: Context, v: Boolean) {
        showOnLockScreen = v
        put(context, K_LOCK, v)
    }

    fun setTapOpensPlayer(context: Context, v: Boolean) {
        tapOpensPlayer = v
        put(context, K_TAP, v)
    }
}
