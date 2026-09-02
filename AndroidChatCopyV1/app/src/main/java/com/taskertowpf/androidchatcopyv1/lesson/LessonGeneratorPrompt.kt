package com.taskertowpf.androidchatcopyv1.lesson

object LessonAffirmation {
    private val yesWords = setOf(
        "да", "ага", "угу", "ок", "окей", "хорошо", "верно", "правильно",
        "точно", "подтверждаю", "согласен", "согласна", "генерируй", "создай",
        "yes", "yep", "yeah", "ok", "okay", "sure", "correct", "right",
    )

    fun isAffirmative(text: String): Boolean {
        val normalized = text.trim().lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isEmpty()) return false
        if (normalized in yesWords) return true
        val tokens = normalized.split(' ')
        if (tokens.any { it in yesWords }) return true
        return normalized.startsWith("да ") || normalized.startsWith("yes ")
    }
}

object LessonGeneratorPrompt {
    const val DEFAULT_WORD_BUDGET = 100

    fun systemInstruction(wordBudget: Int = DEFAULT_WORD_BUDGET): String = """
        Ты — генератор уроков English Learning для Android.
        Отвечай ТОЛЬКО markdown-уроком без пояснений и без обёртки ```.
        Формат строго:

        ---
        title: English title
        title_ru: Русский заголовок
        type: english_lesson
        version: 1
        ---

        ## title
        English title | Русский заголовок

        ## words
        word | перевод
        (список пар EN | RU)

        ## phrases
        phrase | перевод
        ---
        another phrase | перевод

        ## lyrics
        English sentence
        Русский перевод
        ---
        Another sentence
        Другой перевод

        Объём: около $wordBudget слов английского контента суммарно (words+phrases+lyrics).
        Тема задаётся пользователем. Язык карточек: EN + RU.
    """.trimIndent()

    fun userRequest(topic: String, wordBudget: Int = DEFAULT_WORD_BUDGET): String =
        "Сгенерируй урок по теме: «$topic». Объём ≈ $wordBudget слов."
}
