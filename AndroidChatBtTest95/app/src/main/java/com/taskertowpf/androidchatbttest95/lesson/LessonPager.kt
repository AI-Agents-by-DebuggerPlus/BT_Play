package com.taskertowpf.androidchatbttest95.lesson

object LessonPager {

    fun build(
        doc: LessonDocument,
        cfg: PagerConfig = PagerConfig.LandscapePhone,
    ): List<LessonScreen> {
        val out = mutableListOf<LessonScreen>()
        val wordsPerPage = cfg.wordsPerPage.coerceAtLeast(2)
        val otherPerPage = cfg.otherPerPage.coerceAtLeast(1)
        val wordCols = cfg.wordColumns.coerceIn(1, 4)

        fun add(
            section: LessonSection,
            label: String,
            cards: List<CardPair>,
            perPage: Int,
            cols: Int,
        ) {
            if (cards.isEmpty()) return
            cards.chunked(perPage).forEach { page ->
                out += LessonScreen(section, label, page, cols)
            }
        }

        add(LessonSection.Title, "Title", doc.titleCards, otherPerPage, 1)
        add(LessonSection.Words, "Words", doc.words, wordsPerPage, wordCols)
        add(LessonSection.Phrases, "Phrases", doc.phrases, otherPerPage, 1)
        add(LessonSection.Lyrics, "Sentences", doc.lyrics, otherPerPage, 1)
        return out
    }
}
