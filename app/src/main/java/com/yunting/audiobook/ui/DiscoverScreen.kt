package com.yunting.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import coil.compose.AsyncImage
import com.yunting.audiobook.data.BookResult
import com.yunting.audiobook.data.PodcastSource
import com.yunting.audiobook.data.SearchStateStore
import com.yunting.audiobook.ui.theme.WeChatGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 发现页：聚合搜索 + 链接解析（搜索状态常驻：回桌面/切页不丢，清空文字才清空） */
@Composable
fun DiscoverScreen(onOpenBook: (BookResult) -> Unit) {
    // 常驻搜索状态（SearchStateStore 进程内单例 + 本地持久化）
    val keyword = SearchStateStore.keyword
    val sourceFilter = SearchStateStore.sourceFilter
    val results = SearchStateStore.results
    var searching by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScopeCompat()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 大标题（微信风格）
        Text(
            "发现",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )

        // 搜索栏（胶囊一体式：图标 + 输入 + 清空 + 搜索按钮，微信搜索条风格）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Search, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (keyword.isEmpty()) {
                    Text(
                        "搜索有声书 / 播客 / 全网资源",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = { SearchStateStore.updateKeyword(it) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(WeChatGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                )
            }
            if (keyword.isNotEmpty()) {
                IconButton(
                    onClick = { SearchStateStore.updateKeyword("") },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close, contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                "搜索",
                color = if (keyword.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else WeChatGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable(enabled = keyword.isNotBlank() && !searching) {
                        val term = keyword.trim()
                        searching = true
                        errorMsg = null
                        scope.launch {
                            // 全网并行搜索：播客 / 哔哩哔哩 / LibriVox，任一源失败不影响其他源
                            val podcastDeferred = scope.async {
                                withContext(Dispatchers.IO) {
                                    runCatching { PodcastSource.search(term) }.getOrElse { emptyList() }
                                }
                            }
                            val biliDeferred = scope.async {
                                withContext(Dispatchers.IO) {
                                    runCatching { com.yunting.audiobook.data.BilibiliSource.search(term) }.getOrElse { emptyList() }
                                }
                            }
                            val librivoxDeferred = scope.async {
                                withContext(Dispatchers.IO) {
                                    runCatching { com.yunting.audiobook.data.LibriVoxSource.search(term) }.getOrElse { emptyList() }
                                }
                            }
                            val podcasts = podcastDeferred.await()
                            val bili = biliDeferred.await()
                            val libres = librivoxDeferred.await()
                            // 交替合并，聚合展示
                            val merged = ArrayList<Pair<BookResult, String>>()
                            var i = 0
                            while (merged.size < podcasts.size + bili.size + libres.size) {
                                if (i < podcasts.size) merged.add(podcasts[i] to "播客")
                                if (i < bili.size) merged.add(bili[i] to "哔哩哔哩")
                                if (i < libres.size) merged.add(libres[i] to "公版书")
                                i++
                            }
                            SearchStateStore.publishResults(merged)
                            if (merged.isEmpty()) errorMsg = "没有找到相关资源，换个关键词试试"
                            searching = false
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        // 来源筛选（按本次搜索命中的源动态生成，并显示各源结果数）
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val counts = results.groupingBy { it.first.source }.eachCount()
            val chipList = buildList {
                add("全部" to null)
                if (counts.containsKey(PodcastSource.ID)) add("播客(${counts[PodcastSource.ID]})" to PodcastSource.ID)
                if (counts.containsKey(com.yunting.audiobook.data.BilibiliSource.ID))
                    add("哔哩哔哩(${counts[com.yunting.audiobook.data.BilibiliSource.ID]})" to com.yunting.audiobook.data.BilibiliSource.ID)
                if (counts.containsKey(com.yunting.audiobook.data.LibriVoxSource.ID))
                    add("公版书(${counts[com.yunting.audiobook.data.LibriVoxSource.ID]})" to com.yunting.audiobook.data.LibriVoxSource.ID)
            }
            items(chipList.size) { idx ->
                val (label, v) = chipList[idx]
                AssistChip(
                    onClick = { SearchStateStore.setFilter(v) },
                    label = { Text(label) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (sourceFilter == v) WeChatGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                        labelColor = if (sourceFilter == v) WeChatGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        errorMsg?.let {
            Text(
                it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }

        // 结果列表
        if (searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WeChatGreen)
            }
            return@Column
        }

        val filtered = results.filter { (book, _) ->
            sourceFilter == null || book.source == sourceFilter
        }
        LazyColumn(
            state = SearchStateStore.listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // key 带下标兜底：任何重复条目都不会再触发 LazyColumn 的 key 冲突异常
            itemsIndexed(filtered, key = { idx, item -> item.first.source + item.first.key + "#" + idx }) { _, (book, tag) ->
                SearchRow(book, tag, onClick = { onOpenBook(book) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SearchRow(book: BookResult, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (book.coverUrl != null) {
                AsyncImage(
                    model = book.coverUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                book.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tag,
                    fontSize = 10.sp,
                    color = WeChatGreen,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(WeChatGreen.copy(alpha = 0.1f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    book.author, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun rememberCoroutineScopeCompat(): CoroutineScope =
    androidx.compose.runtime.remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
