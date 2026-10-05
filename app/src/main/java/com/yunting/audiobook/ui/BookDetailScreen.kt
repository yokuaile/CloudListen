package com.yunting.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yunting.audiobook.DetailState
import com.yunting.audiobook.playback.PlayerHub
import com.yunting.audiobook.ui.theme.WeChatGreen

/** 详情页：封面信息 + 剧集列表，加入书架 / 立即播放 */
@Composable
fun BookDetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onAddToLibrary: () -> Boolean
) {
    val inLibrary = remember(state.book.key) {
        com.yunting.audiobook.data.LibraryStore.find(state.book.source, state.book.key) != null
    }
    var added by remember(state.book.key) { mutableStateOf(inLibrary) }
    var descExpanded by remember { mutableStateOf(false) }
    // 收藏（星标）：与「书架」相互独立，收藏列表在「我的 → 我的收藏」
    var favorite by remember(state.book.key) {
        mutableStateOf(com.yunting.audiobook.data.FavoriteStore.isFavorite(state.book.source, state.book.key))
    }

    val current = PlayerHub.currentBook
    val isCurrentBook = current?.let { it.source == state.book.source && it.key == state.book.key } == true
    val playingIndex = if (isCurrentBook) PlayerHub.currentIndex else -1

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("书籍详情", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                favorite = com.yunting.audiobook.data.FavoriteStore.toggle(
                    com.yunting.audiobook.data.FavoriteItem(
                        source = state.book.source,
                        key = state.book.key,
                        title = state.book.title,
                        author = state.book.author,
                        coverUrl = state.book.coverUrl,
                        feedUrl = state.book.feedUrl
                    )
                )
            }) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) WeChatGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        // 头部信息卡
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            Box(
                Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (state.book.coverUrl != null) {
                    AsyncImage(
                        model = state.book.coverUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.PlayCircle, null, tint = WeChatGreen.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    state.book.title, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.book.author, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                state.book.desc?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                if (state.tracks.isNotEmpty()) {
                    Text("共 ${state.tracks.size} 集", fontSize = 12.sp, color = WeChatGreen)
                }
            }
        }

        // 操作按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { if (onAddToLibrary()) added = true },
                enabled = state.tracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = WeChatGreen
                )
            ) {
                Icon(if (added) Icons.Rounded.Check else Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (added) "已在书架" else "加入书架")
            }
            Button(
                onClick = { onPlay(if (playingIndex >= 0) playingIndex else 0) },
                enabled = state.tracks.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen, contentColor = androidx.compose.ui.graphics.Color.White)
            ) {
                Icon(Icons.Rounded.PlayCircle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("立即播放")
            }
        }

        // 剧集列表
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WeChatGreen)
                }
            }
            state.error != null -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            else -> {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(state.tracks) { idx, track ->
                        val isPlayingThis = idx == playingIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(idx) }
                                .background(
                                    if (isPlayingThis) WeChatGreen.copy(alpha = 0.08f)
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${idx + 1}",
                                modifier = Modifier.width(32.dp),
                                fontSize = 12.sp,
                                color = if (isPlayingThis) WeChatGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Normal
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    fontSize = 14.sp,
                                    color = if (isPlayingThis) WeChatGreen else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isPlayingThis) FontWeight.Medium else FontWeight.Normal
                                )
                                if (track.durationMs > 0) {
                                    Text(
                                        fmtMs(track.durationMs), fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
