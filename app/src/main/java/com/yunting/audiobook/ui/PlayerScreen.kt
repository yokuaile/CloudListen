package com.yunting.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yunting.audiobook.playback.PlayerHub
import com.yunting.audiobook.ui.theme.WeChatGreen

/** 全屏播放页（聚焦式布局）：
 *  上半屏只保留「封面 + 标题 + 进度 + 控制键」，倍速/定时/增强/共存收敛为
 *  一排功能卡片，点击弹出底部面板；播放列表同样收入弹层，页面不再冗长。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(onCollapse: () -> Unit) {
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showBoost by remember { mutableStateOf(false) }

    // 系统返回键：先关掉打开的弹层，没有弹层时才收起播放页（不直接退出 App）
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showQueue -> showQueue = false
            showSpeed -> showSpeed = false
            showSleep -> showSleep = false
            showBoost -> showBoost = false
            else -> onCollapse()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // —— 顶栏 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Rounded.KeyboardArrowDown, "收起", modifier = Modifier.size(28.dp))
            }
            Text(
                PlayerHub.currentBookTitle?.takeIf { it.isNotBlank() } ?: "正在播放",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.Rounded.QueueMusic, "播放列表", modifier = Modifier.size(24.dp))
            }
        }

        // —— 封面（垂直居中、带环境光晕与投影，视觉焦点） ——
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            // 环境光晕：封面后方一圈淡绿径向渐变，让画面不那么"平"
            Box(
                Modifier
                    .fillMaxWidth(0.98f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(WeChatGreen.copy(alpha = 0.12f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth(0.74f)
                    .aspectRatio(1f)
                    .shadow(24.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                val cover = PlayerHub.currentCover
                if (cover != null) {
                    AsyncImage(
                        model = cover, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayArrow, null,
                        tint = WeChatGreen.copy(alpha = 0.5f), modifier = Modifier.size(72.dp)
                    )
                }
            }
        }

        // —— 标题区 ——
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                PlayerHub.currentTitle ?: "未在播放",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                PlayerHub.currentBookTitle ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.basicMarquee()
            )
        }

        // —— 进度 ——
        var dragging by remember { mutableStateOf(false) }
        var dragPos by remember { mutableFloatStateOf(0f) }
        val sliderValue = if (dragging) dragPos else PlayerHub.positionMs.toFloat()

        Column(Modifier.padding(horizontal = 26.dp, vertical = 4.dp)) {
            Slider(
                value = sliderValue,
                onValueChange = { dragging = true; dragPos = it },
                onValueChangeFinished = {
                    PlayerHub.seekTo(dragPos.toLong())
                    dragging = false
                },
                valueRange = 0f..(PlayerHub.durationMs.takeIf { it > 0 } ?: 1L).toFloat(),
                // 无圆点 thumb：隐藏圆点，改为纯色细进度条，仍可拖动
                thumb = {},
                track = { state ->
                    val range = state.valueRange
                    val frac = ((state.value - range.start) /
                        (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .background(WeChatGreen)
                        )
                    }
                },
                colors = SliderDefaults.colors(
                    activeTrackColor = WeChatGreen,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtMs(dragPos.toLong().takeIf { dragging } ?: PlayerHub.positionMs),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("-" + fmtMs((PlayerHub.durationMs - PlayerHub.positionMs).coerceAtLeast(0)),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // —— 控制区 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { PlayerHub.seekTo(PlayerHub.positionMs - 10_000) }) {
                Icon(Icons.Rounded.Replay10, "回退10秒", modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { PlayerHub.prev() }) {
                Icon(Icons.Rounded.SkipPrevious, "上一集", modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            // 大绿圆播放键
            Box(
                Modifier
                    .size(76.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(WeChatGreen)
                    .clickable { PlayerHub.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (PlayerHub.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
            }
            IconButton(onClick = { PlayerHub.next() }) {
                Icon(Icons.Rounded.SkipNext, "下一集", modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { PlayerHub.seekTo(PlayerHub.positionMs + 30_000) }) {
                Icon(Icons.Rounded.Forward30, "快进30秒", modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // —— 功能卡片行：倍速 / 定时 / 增强 / 共存 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeatureCard(
                icon = Icons.Rounded.Speed,
                label = "倍速",
                value = fmtSpeed(PlayerHub.playbackSpeed),
                active = PlayerHub.playbackSpeed != 1.0f,
                modifier = Modifier.weight(1f)
            ) { showSpeed = true }
            FeatureCard(
                icon = Icons.Rounded.Bedtime,
                label = "定时",
                value = if (PlayerHub.sleepRemainingMs > 0)
                    "剩${(PlayerHub.sleepRemainingMs + 59_999) / 60_000}分" else "关闭",
                active = PlayerHub.sleepRemainingMs > 0,
                modifier = Modifier.weight(1f)
            ) { showSleep = true }
            FeatureCard(
                icon = Icons.Rounded.VolumeUp,
                label = "增强",
                value = fmtSpeed(PlayerHub.volumeBoost),
                active = PlayerHub.volumeBoost > 1.0f,
                modifier = Modifier.weight(1f)
            ) { showBoost = true }
            FeatureCard(
                icon = Icons.Rounded.Layers,
                label = "共存",
                value = if (PlayerHub.coexistMode) "开" else "关",
                active = PlayerHub.coexistMode,
                modifier = Modifier.weight(1f)
            ) { PlayerHub.setCoexist(!PlayerHub.coexistMode) }
        }

        Spacer(Modifier.height(10.dp))
    }

    // —— 播放列表面板 ——
    if (showQueue) {
        val listState = rememberLazyListState()
        LaunchedEffect(showQueue) {
            val cur = PlayerHub.currentIndex
            if (cur > 2) listState.scrollToItem(cur - 2)
        }
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val queue = PlayerHub.queueTitles()
            Text(
                "播放列表（${queue.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 36.dp
                )
            ) {
                itemsIndexed(queue) { idx, title ->
                    val active = idx == PlayerHub.currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) WeChatGreen.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .clickable {
                                PlayerHub.seekToIndex(idx)
                                showQueue = false
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${idx + 1}",
                            modifier = Modifier.width(34.dp),
                            fontSize = 12.sp,
                            color = if (active) WeChatGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = if (active) WeChatGreen else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.GraphicEq, "正在播放",
                                tint = WeChatGreen, modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // —— 倍速面板 ——
    if (showSpeed) {
        ModalBottomSheet(
            onDismissRequest = { showSpeed = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text(
                "播放速度",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
            Column(Modifier.padding(horizontal = 16.dp)) {
                speeds.chunked(3).forEach { rowSpeeds ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowSpeeds.forEach { s ->
                            val selected = PlayerHub.playbackSpeed == s
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) WeChatGreen
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable {
                                        PlayerHub.setSpeed(s)
                                        showSpeed = false
                                    }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    fmtSpeed(s),
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Color.White
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        // 末行不满 3 个时补空位
                        repeat(3 - rowSpeeds.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }

    // —— 定时关闭面板 ——
    if (showSleep) {
        ModalBottomSheet(
            onDismissRequest = { showSleep = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text(
                "定时关闭",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            listOf("关闭" to 0, "15 分钟" to 15, "30 分钟" to 30, "60 分钟" to 60, "90 分钟" to 90, "120 分钟" to 120)
                .forEach { (label, min) ->
                    val selected = if (min == 0) PlayerHub.sleepRemainingMs == 0L
                    else PlayerHub.sleepRemainingMs > 0 &&
                            (PlayerHub.sleepRemainingMs + 59_999) / 60_000 == min.toLong()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (min == 0) PlayerHub.cancelSleepTimer() else PlayerHub.setSleepTimer(min)
                                showSleep = false
                            }
                            .padding(horizontal = 24.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) WeChatGreen else MaterialTheme.colorScheme.onSurface
                        )
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check, "已选择",
                                tint = WeChatGreen, modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            Spacer(Modifier.height(24.dp))
        }
    }

    // —— 音量增强面板 ——
    if (showBoost) {
        ModalBottomSheet(
            onDismissRequest = { showBoost = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            var pct by remember { mutableFloatStateOf(PlayerHub.volumeBoost * 100f) }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "音量增强",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${pct.toInt()}%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = WeChatGreen
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "100% ~ 200%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = pct,
                    onValueChange = { pct = it },
                    onValueChangeFinished = { PlayerHub.applyVolumeBoost(pct / 100f) },
                    valueRange = 100f..200f,
                    // 无圆点 thumb：与播放进度条样式统一，纯色细进度条，仍可拖动
                    thumb = {},
                    track = { state ->
                        val range = state.valueRange
                        val frac = ((state.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(frac)
                                    .background(WeChatGreen)
                            )
                        }
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = WeChatGreen,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    )
                )
                Text(
                    "最高可增强至 2 倍音量，过高可能出现破音，请酌情调节",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 功能卡片：图标 + 名称 + 当前值；激活时绿色描边高亮 */
@Composable
private fun FeatureCard(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) WeChatGreen.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, label,
            tint = if (active) WeChatGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) WeChatGreen else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 速度/倍数统一格式：去掉多余的 0（1.0x、1.5x、0.75x） */
private fun fmtSpeed(v: Float): String {
    val rounded = (v * 100).toInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else "${rounded}x"
}
