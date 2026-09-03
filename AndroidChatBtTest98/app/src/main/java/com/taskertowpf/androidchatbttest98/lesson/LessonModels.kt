package com.taskertowpf.androidchatbttest98.lesson

data class CardPair(
    val en: String,
    val ru: String,
)

enum class LessonSection {
    Title,
    Words,
    Phrases,
    Lyrics,
}

data class LessonScreen(
    val section: LessonSection,
    val sectionLabel: String,
    val cards: List<CardPair>,
    val columnCount: Int = 1,
)

data class LessonDocument(
    val titleEn: String = "",
    val titleRu: String = "",
    val artist: String = "",
    val titleCards: List<CardPair> = emptyList(),
    val words: List<CardPair> = emptyList(),
    val phrases: List<CardPair> = emptyList(),
    val lyrics: List<CardPair> = emptyList(),
)

data class PagerConfig(
    val wordsPerPage: Int = 9,
    val otherPerPage: Int = 3,
    val wordColumns: Int = 3,
) {
    companion object {
        val LandscapePhone = PagerConfig(
            wordsPerPage = 9,
            otherPerPage = 3,
            wordColumns = 3,
        )
    }
}

enum class LessonSessionPhase {
    /** Ждём Play → запись темы. */
    Idle,
    ListeningRequest,
    /** Озвучивается вопрос подтверждения. */
    AwaitingConfirm,
    /** Ждём голосовое сообщение: пустое = подтверждение, иначе новая тема. */
    ListeningConfirm,
    Generating,
    /** Урок на экране, Play = next (или pause TTS). */
    Browsing,
    /** Idle-навигация по страницам: пустой голос = Play → next. */
    ListeningBrowseNav,
}
