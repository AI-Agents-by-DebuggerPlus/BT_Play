package com.taskertowpf.androidchatbttest95

/**
 * Turns Hermes CLI system-error blobs into short phrases for TTS.
 * Accepts legacy `[stderr]` and `[System error]` prefixes.
 * Spec: Docs/MD_Files/AndroidChat-Stderr-Speak-Instruction-2026-07-16.md
 */
object HermesStderrSpeakNormalizer {

    private val marker = Regex(
        """\[\s*(?:stderr|system\s+error)\s*\]""",
        RegexOption.IGNORE_CASE,
    )

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""cannot find the path|не уда[её]тся найти|путь не найден|find the path specified""", RegexOption.IGNORE_CASE)
            to "could not find the required file or folder",
        Regex("""browser_vision|браузер""", RegexOption.IGNORE_CASE)
            to "the browser or screen capture tool failed",
        Regex("""access is denied|отказано в доступе|permission denied""", RegexOption.IGNORE_CASE)
            to "access to a file or folder was denied",
        Regex("""timed?\s*out|таймаут""", RegexOption.IGNORE_CASE)
            to "the operation timed out",
        Regex("""econnrefused|connection refused|сетев""", RegexOption.IGNORE_CASE)
            to "there was a network or connection problem",
        Regex("""\bwsl\b""", RegexOption.IGNORE_CASE)
            to "the WSL environment on the PC failed",
        Regex("""exit\s*(code)?\s*\d+|ошибка CLI""", RegexOption.IGNORE_CASE)
            to "the Hermes program exited with an error",
        Regex("""image|изображен|screenshot|скриншот""", RegexOption.IGNORE_CASE)
            to "an image could not be saved or opened",
        Regex("""not found""", RegexOption.IGNORE_CASE)
            to "a required resource was not found",
    )

    fun containsStderrMarker(content: String): Boolean =
        marker.containsMatchIn(content)

    /**
     * @return short English phrase for TTS, or null if not a system-error payload.
     */
    fun toHumanSpeechOrNull(content: String): String? {
        val raw = content.trim()
        if (raw.isEmpty() || !containsStderrMarker(raw)) {
            return null
        }

        val body = marker.replace(raw, " ")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{2,}"""), "\n")
            .trim()

        val reasons = linkedSetOf<String>()
        for ((re, phrase) in rules) {
            if (re.containsMatchIn(body) || re.containsMatchIn(raw)) {
                reasons.add(phrase)
            }
            if (reasons.size >= 2) break
        }

        val detail = when {
            reasons.isNotEmpty() -> reasons.joinToString(" and ")
            body.isNotEmpty() -> shortenForSpeech(stripTechnicalNoise(body))
            else -> "Details are only available on the computer"
        }

        return "System error from Hermes. $detail."
    }

    private fun stripTechnicalNoise(text: String): String {
        var t = text
        t = t.replace(Regex("""[A-Za-z]:\\[^\s]+"""), " ")
        t = t.replace(Regex("""/mnt/[^\s]+"""), " ")
        t = t.replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t
    }

    private fun shortenForSpeech(text: String, maxChars: Int = 120): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 40) cut.substring(0, lastSpace) else cut).trimEnd('.', ',', ';', ':')
    }
}
