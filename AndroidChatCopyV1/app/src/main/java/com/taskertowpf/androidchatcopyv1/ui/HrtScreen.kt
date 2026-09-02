package com.taskertowpf.androidchatcopyv1.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.taskertowpf.androidchatcopyv1.R
import com.taskertowpf.androidchatcopyv1.hrt.HrtUiState
import com.taskertowpf.androidchatcopyv1.hrt.HwtStatus

@Composable
fun HrtDashboard(
    state: HrtUiState,
    onRefresh: () -> Unit,
    onScreenshot: () -> Unit,
    onRepeat: () -> Unit,
    onPullSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRefresh,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.hrt_cmd_refresh))
            }
            Button(
                onClick = onScreenshot,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.hrt_cmd_screenshot))
            }
            OutlinedButton(
                onClick = onRepeat,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.hrt_cmd_repeat))
            }
        }
        OutlinedButton(
            onClick = onPullSnapshot,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.hrt_pull_snapshot))
        }

        AccountCard(status)
        TickerCard(status)
        PositionsCard(status)
        FeedCard(state.feed)
    }
}

@Composable
fun HrtScreenshotOverlay(
    bytes: ByteArray,
    label: String,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val image = remember(bytes) {
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .zIndex(40f)
            .clickable(onClick = onTap),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = stringResource(R.string.hrt_screenshot_decode_fail),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 36.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label.ifBlank { stringResource(R.string.hrt_screenshot_title) },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.hrt_screenshot_hint),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.hrt_screenshot_close))
        }
    }
}

@Composable
private fun AccountCard(status: HwtStatus?) {
    HrtPanel(title = stringResource(R.string.hrt_account_title)) {
        Text(
            text = status?.accountDisplay ?: "—",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TickerCard(status: HwtStatus?) {
    HrtPanel(title = stringResource(R.string.hrt_ticker_title)) {
        Text(
            text = status?.symbol?.ifBlank { "—" } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PriceBlock("BID", status?.bid)
            PriceBlock("ASK", status?.ask)
            PriceBlock("LOT", status?.lot)
        }
        val market = status?.marketStatus.orEmpty()
        if (market.isNotBlank()) {
            Text(
                text = market,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val real = if (status?.realTrading == true) "ON" else "OFF"
            val auto = if (status?.autoTrade == true) "ON" else "OFF"
            Text(
                text = "Real trading: $real   Auto: $auto",
                style = MaterialTheme.typography.labelSmall,
            )
            val src = status?.source.orEmpty()
            if (src.isNotBlank()) {
                Text("[$src]", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PositionsCard(status: HwtStatus?) {
    val open = status?.openPositions.orEmpty()
    val pending = status?.resolvedPending.orEmpty()
    val openText = when {
        open.isNotEmpty() -> open.joinToString("\n")
        !status?.positionsHeader.isNullOrBlank() ->
            "${status?.positionsHeader}\n(нет строк)"
        else -> stringResource(R.string.hrt_no_positions)
    }
    val pendingText = if (pending.isEmpty()) {
        stringResource(R.string.hrt_no_pending)
    } else {
        pending.joinToString("\n")
    }
    HrtPanel(title = stringResource(R.string.hrt_positions_title)) {
        Text(
            text = openText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            text = stringResource(R.string.hrt_pending_title),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = pendingText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FeedCard(feed: List<String>) {
    HrtPanel(title = stringResource(R.string.hrt_feed_title)) {
        Text(
            text = if (feed.isEmpty()) stringResource(R.string.hrt_feed_empty) else feed.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.heightIn(min = 72.dp),
        )
    }
}

@Composable
private fun PriceBlock(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value?.ifBlank { "—" } ?: "—",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun HrtPanel(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            content()
        }
    }
}
