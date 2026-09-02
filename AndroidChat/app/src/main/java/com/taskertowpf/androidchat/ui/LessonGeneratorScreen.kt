package com.taskertowpf.androidchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskertowpf.androidchat.R
import com.taskertowpf.androidchat.lesson.CardPair
import com.taskertowpf.androidchat.lesson.LessonLayout
import com.taskertowpf.androidchat.lesson.LessonScreen as LessonPage
import com.taskertowpf.androidchat.lesson.LessonSection
import com.taskertowpf.androidchat.lesson.LessonSessionPhase
import com.taskertowpf.androidchat.lesson.SavedLessonMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LessonBg = Color(0xFF0B0E11)
private val LessonChrome = Color(0xFF121A28)
private val LessonMuted = Color(0xFFAAB2C0)
private val LessonEn = Color(0xFFF8D12F)
private val LessonRu = Color(0xFFD0D4DC)
private val LessonUi = Color(0xFFEAECEF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonGeneratorScreen(
    phase: LessonSessionPhase,
    statusText: String,
    topicDraft: String,
    confirmQuestion: String,
    screens: List<LessonPage>,
    screenIndex: Int,
    listening: Boolean,
    isBusy: Boolean,
    fullscreen: Boolean,
    enFontSp: Float,
    ruFontSp: Float,
    savedLessons: List<SavedLessonMeta>,
    onPlayAction: () -> Unit,
    onClarifyTopic: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSelectSavedLesson: (String) -> Unit,
) {
    val current = screens.getOrNull(screenIndex)
    val phaseText = phaseHint(phase)

    if (fullscreen) {
        FullscreenLessonContent(
            current = current,
            screenIndex = screenIndex,
            screens = screens,
            enFontSp = enFontSp,
            ruFontSp = ruFontSp,
            onToggleFullscreen = onToggleFullscreen,
            onPrev = onPrev,
            onNext = onNext,
            isBusy = isBusy,
        )
        return
    }

    Scaffold(
        containerColor = LessonBg,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(stringResource(R.string.lesson_title), color = LessonUi)
                        Text(
                            text = statusText.ifBlank { phaseText },
                            style = MaterialTheme.typography.labelSmall,
                            color = LessonMuted,
                            maxLines = 2,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = LessonUi)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.lesson_fullscreen), tint = LessonUi)
                    }
                    IconButton(onClick = onRestart, enabled = !isBusy) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.lesson_restart),
                            tint = LessonUi,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LessonChrome),
            )
        },
        bottomBar = {
            LessonBottomBar(
                phase = phase,
                phaseText = phaseText,
                screens = screens,
                screenIndex = screenIndex,
                listening = listening,
                isBusy = isBusy,
                onPrev = onPrev,
                onNext = onNext,
                onPlayAction = onPlayAction,
                onClarifyTopic = onClarifyTopic,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(LessonBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SavedLessonsDropdown(
                savedLessons = savedLessons,
                enabled = !isBusy,
                onSelect = onSelectSavedLesson,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
            ) {
                when {
                    current != null -> LessonPageContent(
                        screen = current,
                        enFontSp = enFontSp,
                        ruFontSp = ruFontSp,
                    )
                    phase == LessonSessionPhase.Generating -> {
                        CenterMessage(stringResource(R.string.lesson_generating))
                    }
                    confirmQuestion.isNotBlank() -> {
                        ConfirmPanel(topicDraft = topicDraft, confirmQuestion = confirmQuestion)
                    }
                    else -> CenterMessage(stringResource(R.string.lesson_idle_hint))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedLessonsDropdown(
    savedLessons: List<SavedLessonMeta>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled && savedLessons.isNotEmpty()) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = if (savedLessons.isEmpty()) {
                stringResource(R.string.lesson_saved_empty)
            } else {
                stringResource(R.string.lesson_saved_count, savedLessons.size)
            },
            onValueChange = {},
            readOnly = true,
            enabled = enabled && savedLessons.isNotEmpty(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            label = { Text(stringResource(R.string.lesson_saved_label)) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            savedLessons.forEach { meta ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(meta.topic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                dateFmt.format(Date(meta.savedAtMillis)),
                                style = MaterialTheme.typography.labelSmall,
                                color = LessonMuted,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(meta.topicKey)
                    },
                )
            }
        }
    }
}

@Composable
private fun LessonBottomBar(
    phase: LessonSessionPhase,
    phaseText: String,
    screens: List<LessonPage>,
    screenIndex: Int,
    listening: Boolean,
    isBusy: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlayAction: () -> Unit,
    onClarifyTopic: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LessonChrome)
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (screens.isEmpty()) "—" else "${screenIndex + 1} / ${screens.size}",
                color = LessonMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (listening) stringResource(R.string.lesson_listening) else phaseText,
                color = LessonUi,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onPrev,
                enabled = screens.isNotEmpty() && screenIndex > 0 && !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                Text(stringResource(R.string.lesson_prev))
            }
            OutlinedButton(
                onClick = if (
                    phase == LessonSessionPhase.AwaitingConfirm ||
                    phase == LessonSessionPhase.ListeningConfirm
                ) {
                    onClarifyTopic
                } else {
                    onPlayAction
                },
                enabled = !isBusy || listening,
                modifier = Modifier.weight(1.2f),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text(
                    text = if (
                        phase == LessonSessionPhase.AwaitingConfirm ||
                        phase == LessonSessionPhase.ListeningConfirm
                    ) {
                        stringResource(R.string.lesson_clarify)
                    } else {
                        stringResource(R.string.lesson_play_mic)
                    },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            OutlinedButton(
                onClick = onNext,
                enabled = screens.isNotEmpty() && screenIndex < screens.lastIndex && !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.lesson_next))
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
            }
        }
    }
}

@Composable
private fun FullscreenLessonContent(
    current: LessonPage?,
    screenIndex: Int,
    screens: List<LessonPage>,
    enFontSp: Float,
    ruFontSp: Float,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    isBusy: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LessonBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (current != null) {
            LessonPageContent(
                screen = current,
                enFontSp = enFontSp,
                ruFontSp = ruFontSp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (screens.isEmpty()) "" else "${screenIndex + 1}/${screens.size}",
                color = LessonMuted,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 8.dp),
            )
            IconButton(onClick = onToggleFullscreen) {
                Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.lesson_exit_fullscreen), tint = LessonUi)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onPrev, enabled = screenIndex > 0 && !isBusy) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
            }
            OutlinedButton(
                onClick = onNext,
                enabled = screens.isNotEmpty() && screenIndex < screens.lastIndex && !isBusy,
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ConfirmPanel(topicDraft: String, confirmQuestion: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (topicDraft.isNotBlank()) {
            Text(
                text = stringResource(R.string.lesson_topic, topicDraft),
                color = LessonMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Text(
            text = confirmQuestion,
            color = LessonEn,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = stringResource(R.string.lesson_confirm_hint),
            color = LessonMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = LessonUi, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LessonPageContent(
    screen: LessonPage,
    enFontSp: Float,
    ruFontSp: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = screen.sectionLabel,
            color = LessonMuted,
            fontSize = (ruFontSp + 1f).sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val cols = screen.columnCount.coerceAtLeast(1)
        val minHeight = LessonLayout.cardMinHeight(
            enFontSp = enFontSp,
            ruFontSp = ruFontSp,
            section = screen.section,
            landscape = true,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(screen.cards) { card ->
                LessonCardCell(
                    card = card,
                    enFontSp = enFontSp,
                    ruFontSp = ruFontSp,
                    minHeight = minHeight,
                )
            }
        }
    }
}

@Composable
private fun LessonCardCell(
    card: CardPair,
    enFontSp: Float,
    ruFontSp: Float,
    minHeight: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(Color(0xFF151A22), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = card.en,
            color = LessonEn,
            fontWeight = FontWeight.Bold,
            fontSize = enFontSp.sp,
            lineHeight = (enFontSp * 1.15f).sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.ru.isNotBlank()) {
            Text(
                text = card.ru,
                color = LessonRu,
                fontSize = ruFontSp.sp,
                lineHeight = (ruFontSp * 1.2f).sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun phaseHint(phase: LessonSessionPhase): String = when (phase) {
    LessonSessionPhase.Idle -> stringResource(R.string.lesson_phase_idle)
    LessonSessionPhase.ListeningRequest -> stringResource(R.string.lesson_phase_listen_topic)
    LessonSessionPhase.AwaitingConfirm -> stringResource(R.string.lesson_phase_await_confirm)
    LessonSessionPhase.ListeningConfirm -> stringResource(R.string.lesson_phase_listen_confirm)
    LessonSessionPhase.Generating -> stringResource(R.string.lesson_phase_generating)
    LessonSessionPhase.Browsing -> stringResource(R.string.lesson_phase_browsing)
    LessonSessionPhase.ListeningBrowseNav -> stringResource(R.string.lesson_phase_listen_browse)
}
