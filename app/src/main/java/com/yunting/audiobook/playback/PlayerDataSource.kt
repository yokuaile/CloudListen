package com.yunting.audiobook.playback

import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.yunting.audiobook.data.Http
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 播放器取流数据源。
 *
 * 关键点：哔哩哔哩的音频 CDN（*.bilivideo.com / *.hdslb.com 等）强制校验 Referer，
 * 不带 Referer 会直接返回 403，ExoPlayer 默认的 HttpDataSource 不携带任何防盗链头，
 * 于是表现为「进度条不动 / 没有任何声音」。这里用 OkHttp 拦截器按域名补
 * Referer + UA，重定向后的新域名同样会经过拦截器，因此各跳都能带上正确的头。
 */
object PlayerDataSource {

    private val REFERER_HOSTS = listOf(
        "bilivideo.com", "bilibili.com", "hdslb.com", "biliapi.com", "biliapi.net",
        "bilibili.co", "acgvideo.com"
    )
    private const val BILI_REFERER = "https://www.bilibili.com/"
    private const val BILI_UA = Http.DESKTOP_UA

    private val headerInterceptor = Interceptor { chain ->
        val req = chain.request()
        val host = req.url.host.lowercase()
        val needReferer = REFERER_HOSTS.any { host == it || host.endsWith(".$it") }
        if (needReferer && req.header("Referer") == null) {
            chain.proceed(
                req.newBuilder()
                    .header("Referer", BILI_REFERER)
                    .header("User-Agent", BILI_UA)
                    .build()
            )
        } else {
            chain.proceed(req)
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(headerInterceptor)
            .build()
    }

    /** 带防盗链头的 ExoPlayer 数据源工厂 */
    private fun dataSourceFactory(): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).setUserAgent(Http.MOBILE_UA)

    /**
     * 默认媒体源工厂：
     * - 走上面带防盗链的数据源；
     * - 开启恒定码率 seek，解决 B 站 DASH 音频（fMP4，无完整索引表）拖动进度后卡住的问题。
     */
    fun mediaSourceFactory(): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(
            dataSourceFactory(),
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        )
}
