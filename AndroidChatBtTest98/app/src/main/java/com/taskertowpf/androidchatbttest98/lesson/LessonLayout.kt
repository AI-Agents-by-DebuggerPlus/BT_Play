package com.taskertowpf.androidchatbttest98.lesson

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LessonLayout {

    fun pagerConfig(
        enFontSp: Float,
        ruFontSp: Float,
        landscape: Boolean,
    ): PagerConfig {
        val fontScale = ((enFontSp + ruFontSp) / 31f).coerceAtLeast(1f)
        val wordCols = if (landscape) 3 else 2
        val wordsPerPage = when {
            fontScale >= 1.45f -> if (landscape) 6 else 4
            fontScale >= 1.2f -> if (landscape) 6 else 6
            landscape -> 9
            else -> 6
        }
        val otherPerPage = when {
            fontScale >= 1.45f -> 2
            fontScale >= 1.2f -> 2
            else -> 3
        }
        return PagerConfig(
            wordsPerPage = wordsPerPage,
            otherPerPage = otherPerPage,
            wordColumns = wordCols,
        )
    }

    fun cardMinHeight(
        enFontSp: Float,
        ruFontSp: Float,
        section: LessonSection,
        landscape: Boolean,
    ): Dp {
        val enLines = when (section) {
            LessonSection.Lyrics -> 4
            LessonSection.Phrases -> 3
            else -> 2
        }
        val ruLines = enLines
        val padding = if (landscape) 14f else 12f
        val heightSp = enFontSp * enLines + ruFontSp * ruLines + padding
        return heightSp.dp.coerceAtLeast(if (landscape) 64.dp else 72.dp)
    }
}
