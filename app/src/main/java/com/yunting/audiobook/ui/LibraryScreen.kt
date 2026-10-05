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
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yunting.audiobook.data.BookInLibrary
import com.yunting.audiobook.playback.PlayerHub
import com.yunting.audiobook.ui.theme.WeChatGreen

/** 书架页：已加入的播放列表 */
@Composable
fun LibraryScreen(
    onOpenBook: (BookInLibrary) -> Unit,
    onRemoveBook: (BookInLibrary) -> Unit
) {
    val books = com.yunting.audiobook.data.LibraryStore.books

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Text(
            "书架",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )

        if (books.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.Bookmarks, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("书架还是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "去「发现」搜索或粘贴链接，加入书架开始收听",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            itemsIndexed(books, key = { idx, b -> b.source + b.key + "#" + idx }) { _, book ->
                LibraryRow(book, onOpenBook, onRemoveBook)
            }
        }
    }
}

@Composable
private fun LibraryRow(
    book: BookInLibrary,
    onOpenBook: (BookInLibrary) -> Unit,
    onRemoveBook: (BookInLibrary) -> Unit
) {
    val isCurrent = PlayerHub.currentBook?.let { it.source == book.source && it.key == book.key } == true
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) WeChatGreen.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface
            )
            .clickable { onOpenBook(book) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
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
                Icon(Icons.Rounded.PlayCircle, null, tint = WeChatGreen.copy(alpha = 0.6f))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                book.title, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "共 ${book.tracks.size} 集 · ${book.author}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (book.lastPosMs > 0 || book.lastIndex > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "上次听到 第 ${book.lastIndex + 1} 集 · ${fmtMs(book.lastPosMs)}",
                    fontSize = 11.sp, color = WeChatGreen
                )
            }
        }
        IconButton(onClick = { onRemoveBook(book) }) {
            Icon(
                Icons.Rounded.Delete, contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
