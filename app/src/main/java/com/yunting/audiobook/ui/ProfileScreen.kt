package com.yunting.audiobook.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yunting.audiobook.data.BookResult
import com.yunting.audiobook.data.FavoriteItem
import com.yunting.audiobook.data.FavoriteStore
import com.yunting.audiobook.data.HistoryItem
import com.yunting.audiobook.data.HistoryStore
import com.yunting.audiobook.data.PlayStatsStore
import com.yunting.audiobook.ui.theme.WeChatGreen

/**
 * 「我的」：累计听书统计 + 我的收藏 + 收听记录。
 *
 * 页内自带二级页（收藏列表 / 记录列表），返回键先退回「我的」主页，
 * 主页时把返回交给上层（AppRoot 会切回「书架」）。
 */
@Composable
fun ProfileScreen(
    onOpenBook: (BookResult) -> Unit,
    onResumeHistory: (HistoryItem) -> Unit
) {
    // 0 = 主页，1 = 我的收藏，2 = 收听记录
    var page by remember { mutableIntStateOf(0) }

    BackHandler(enabled = page != 0) { page = 0 }

    when (page) {
        1 -> FavoritePage(onBack = { page = 0 }, onOpenBook = onOpenBook)
        2 -> HistoryPage(onBack = { page = 0 }, onResume = onResumeHistory)
        else -> ProfileHome(onOpenFavorites = { page = 1 }, onOpenHistory = { page = 2 })
    }
}

// ============================================================
// 主页
// ============================================================

@Composable
private fun ProfileHome(onOpenFavorites: () -> Unit, onOpenHistory: () -> Unit) {
    val favCount = FavoriteStore.items.size
    val histCount = HistoryStore.items.size

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "我的",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )

        StatsCard()

        Spacer(Modifier.height(12.dp))

        EntryRow(
            icon = { Icon(Icons.Rounded.Favorite, null, tint = WeChatGreen, modifier = Modifier.size(22.dp)) },
            title = "我的收藏",
            subtitle = if (favCount > 0) "$favCount 部作品" else "还没有收藏",
            onClick = onOpenFavorites
        )
        Spacer(Modifier.height(8.dp))
        EntryRow(
            icon = { Icon(Icons.Rounded.History, null, tint = WeChatGreen, modifier = Modifier.size(22.dp)) },
            title = "收听记录",
            subtitle = if (histCount > 0) "$histCount 条记录" else "还没有收听记录",
            onClick = onOpenHistory
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 累计听书统计卡（微信绿渐变） */
@Composable
private fun StatsCard() {
    val totalMs = PlayStatsStore.totalMs
    val todayMs = PlayStatsStore.todayMs
    val dayCount = PlayStatsStore.dayCount
    val bookCount = PlayStatsStore.bookCount

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF07C160), Color(0xFF41D27E))
                )
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Headset, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("累计听书", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    fmtDuration(totalMs),
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCell("今日收听", fmtDuration(todayMs), Modifier.weight(1f))
            StatDivider()
            StatCell("收听天数", "$dayCount 天", Modifier.weight(1f))
            StatDivider()
            StatCell("听过的书", "$bookCount 本", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(22.dp)
            .background(Color.White.copy(alpha = 0.25f))
    )
}

/** 功能入口行 */
@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(WeChatGreen.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Rounded.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================
// 我的收藏
// ============================================================

@Composable
private fun FavoritePage(onBack: () -> Unit, onOpenBook: (BookResult) -> Unit) {
    val items = FavoriteStore.items

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        SubPageHeader(title = "我的收藏", count = items.size, onBack = onBack)

        if (items.isEmpty()) {
            EmptyHint(Icons.Rounded.FavoriteBorder, "还没有收藏", "在书籍详情页点右上角 ♡ 收藏喜欢的作品")
            return@Column
        }

        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            // key 带下标：同一 source+key 即使重复也不会让 LazyColumn 抛异常
            itemsIndexed(items, key = { idx, it -> it.source + it.key + "#" + idx }) { _, fav ->
                FavoriteRow(
                    fav = fav,
                    onClick = {
                        onOpenBook(
                            BookResult(
                                source = fav.source,
                                key = fav.key,
                                title = fav.title,
                                author = fav.author,
                                coverUrl = fav.coverUrl,
                                feedUrl = fav.feedUrl
                            )
                        )
                    },
                    onRemove = { FavoriteStore.remove(fav.source, fav.key) }
                )
            }
        }
    }
}

@Composable
private fun FavoriteRow(fav: FavoriteItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverBox(fav.coverUrl, 56.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(fav.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                fav.author.ifBlank { "未知来源" },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete, contentDescription = "取消收藏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============================================================
// 收听记录
// ============================================================

@Composable
private fun HistoryPage(onBack: () -> Unit, onResume: (HistoryItem) -> Unit) {
    val items = HistoryStore.items

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        SubPageHeader(
            title = "收听记录",
            count = items.size,
            onBack = onBack,
            action = if (items.isNotEmpty()) {
                { TextButton(onClick = { HistoryStore.clear() }) { Text("清空", color = WeChatGreen, fontSize = 13.sp) } }
            } else null
        )

        if (items.isEmpty()) {
            EmptyHint(Icons.Rounded.History, "还没有收听记录", "播放任意作品后会自动记录，方便继续收听")
            return@Column
        }

        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            itemsIndexed(items, key = { idx, it -> it.source + it.key + "#" + idx }) { _, his ->
                HistoryRow(
                    his = his,
                    onClick = { onResume(his) },
                    onRemove = { HistoryStore.remove(his.source, his.key) }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(his: HistoryItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverBox(his.coverUrl, 56.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(his.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                "上次听到 第 ${his.lastIndex + 1} 集 · ${fmtMs(his.lastPosMs)}",
                fontSize = 12.sp, color = WeChatGreen,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                fmtRelative(his.playedAt),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Delete, contentDescription = "删除记录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============================================================
// 复用小件
// ============================================================

@Composable
private fun SubPageHeader(
    title: String,
    count: Int,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun CoverBox(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Rounded.PlayCircle, null, tint = WeChatGreen.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun EmptyHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            hint, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ============================================================
// 格式化
// ============================================================

/** 时长文案：3 小时 12 分 / 12 分钟 / 不足 1 分钟 */
internal fun fmtDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分"
        h > 0 -> "$h 小时"
        m > 0 -> "$m 分钟"
        totalSec > 0 -> "不足 1 分钟"
        else -> "0 分钟"
    }
}

/** 相对时间：刚刚 / 12 分钟前 / 3 小时前 / 昨天 / 09-28 */
private fun fmtRelative(ts: Long): String {
    if (ts <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    val cal = java.util.Calendar.getInstance()
    val nowDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val nowYear = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = ts
    val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == nowDay &&
        cal.get(java.util.Calendar.YEAR) == nowYear
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3600_000L -> "${diff / 60_000L} 分钟前"
        sameDay -> "${diff / 3600_000L} 小时前"
        now - ts < 48 * 3600_000L -> "昨天"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}
