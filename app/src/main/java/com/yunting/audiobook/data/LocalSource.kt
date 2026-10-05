package com.yunting.audiobook.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

// ============================================================
// 源 4：本地有声书（MediaStore 扫描手机音频文件，按文件夹聚合为"书"）
// 播放 URI 为 content://media/external/audio/media/<id>，
// ExoPlayer 原生支持，且规避 Android 11+ 分区存储的文件路径限制。
// ============================================================

object LocalSource {
    const val ID = "local"

    /** 本地一首音频 */
    data class LocalTrack(
        val title: String,
        val uri: String,
        val durationMs: Long,
        val albumArtUri: String?
    )

    /** 一个文件夹 = 一本书 */
    data class LocalFolder(
        val key: String,        // 文件夹路径（书架唯一 key）
        val name: String,       // 文件夹名（作为书名）
        val tracks: List<LocalTrack>
    )

    /** 扫描结果（Compose 可观察） */
    var folders by mutableStateOf<List<LocalFolder>>(emptyList())
    var scanned by mutableStateOf(false)

    /** 最短时长：过滤掉系统提示音等零碎文件 */
    private const val MIN_DURATION_MS = 15_000L

    /** 扫描外部存储全部音频；须先取得 READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE 权限 */
    fun scan(context: Context): List<LocalFolder> {
        val result = ArrayList<LocalFolder>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,            // 0
                MediaStore.Audio.Media.TITLE,          // 1
                MediaStore.Audio.Media.DURATION,       // 2
                MediaStore.Audio.Media.DATA,           // 3
                MediaStore.Audio.Media.ALBUM_ID,       // 4
                MediaStore.Audio.Media.IS_RINGTONE,    // 5
                MediaStore.Audio.Media.IS_ALARM,       // 6
                MediaStore.Audio.Media.IS_NOTIFICATION // 7
            )
            val useRelPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            if (useRelPath) projection.add(MediaStore.Audio.Media.RELATIVE_PATH) // 8

            val buckets = LinkedHashMap<String, MutableList<LocalTrack>>()
            context.contentResolver.query(
                collection, projection.toTypedArray(), null, null,
                MediaStore.Audio.Media.DATA + " ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    // 跳过铃声 / 闹钟 / 通知音
                    if (cur.getInt(5) == 1 || cur.getInt(6) == 1 || cur.getInt(7) == 1) continue
                    val id = cur.getLong(0)
                    val rawTitle = cur.getString(1) ?: continue
                    val duration = cur.getLong(2)
                    val data = cur.getString(3) ?: continue
                    val albumId = cur.getLong(4)
                    // 过短音频（铃声残留等）但时长未知的保留
                    if (duration in 1 until MIN_DURATION_MS) continue

                    val folderKey: String
                    val folderName: String
                    if (useRelPath) {
                        val rel = (cur.getString(8) ?: "").trim('/').trim()
                        if (rel.isEmpty()) {
                            folderKey = File(data).parent ?: "root"
                            folderName = folderKey.substringAfterLast('/').ifEmpty { "根目录" }
                        } else {
                            folderKey = rel
                            folderName = rel.substringAfterLast('/')
                        }
                    } else {
                        folderKey = File(data).parent ?: "root"
                        folderName = folderKey.substringAfterLast('/').ifEmpty { "根目录" }
                    }

                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val art = "content://media/external/audio/albumart/$albumId"
                    val cleanTitle = rawTitle.substringBeforeLast('.').ifEmpty { rawTitle }
                    buckets.getOrPut(folderKey) { ArrayList() }.add(
                        LocalTrack(cleanTitle, uri, duration, art)
                    )
                }
            }

            result.addAll(buckets.map { (k, v) ->
                LocalFolder(k, k.substringAfterLast('/').ifEmpty { k }, v.sortedBy { it.title })
            })
            result.sortBy { it.name.lowercase() }
        } catch (_: Exception) {
        }
        folders = result
        scanned = true
        return result
    }
}
