package com.taskertowpf.androidchatbttest98

import com.taskertowpf.androidchatbttest98.data.FileMessageFormat
import com.taskertowpf.androidchatbttest98.data.FileMessagePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class VoiceSegment(
    /** "ru" or "en" */
    val lang: String,
    val text: String,
)

sealed class SpeakStep {
    data class Speak(val lang: String, val text: String) : SpeakStep()
    data class Pause(val millis: Long) : SpeakStep()
}

/** Meta от Hermes.EnglishLearning.Xp при загрузке урока. */
data class EnglishLessonMeta(
    val totalCards: Int,
    val totalScreens: Int,
    val title: String = "",
)

data class EnglishLessonCard(
    val en: String,
    val ru: String,
    val isLast: Boolean = false,
)

object MessageDisplayFormatter {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ordered ru/en fragments (duplicate keys allowed). */
    private val voiceKeyRegex = Regex(
        """"(ru|en)"\s*:\s*"((?:\\.|[^"\\])*)"""",
    )

    private val voiceOpenTag = Regex("""\[Voice\]""", RegexOption.IGNORE_CASE)
    private val voiceCloseTag = Regex("""\[/Voice\]""", RegexOption.IGNORE_CASE)

    private const val TYPE_ENGLISH_LESSON_META = "english_lesson_meta"

    private data class BraceObject(val start: Int, val endExclusive: Int, val text: String)

    fun toDisplayText(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }

        parseEnglishLessonMeta(trimmed)?.let { meta ->
            val titlePart = meta.title.takeIf { it.isNotBlank() }?.let { " «$it»" }.orEmpty()
            return "Урок$titlePart: ${meta.totalScreens} экр., ${meta.totalCards} карт."
        }

        if (HermesStderrSpeakNormalizer.containsStderrMarker(trimmed)) {
            return trimmed.replace(
                Regex("""\[\s*(?:stderr|system\s+error)\s*\]""", RegexOption.IGNORE_CASE),
                "",
            )
                .replace(Regex("""\n{2,}"""), "\n")
                .trim()
                .ifEmpty { trimmed }
        }

        FileMessageFormat.tryParse(trimmed)?.let { payload ->
            return FileMessageFormat.toDisplayText(payload)
        }

        val voiceText = voiceSegmentsToPlain(trimmed)
        if (voiceText != null) {
            return voiceText
        }

        if (!trimmed.startsWith("{")) {
            return trimmed
        }

        return parseSingleObjectFallback(trimmed) ?: trimmed
    }

    /**
     * План озвучки входящих: только внутри `[Voice]…[/Voice]`, далее объекты `{…}`
     * с ключами `"en"` / `"ru"`. Вне тегов и вне скобок — не озвучивается.
     */
    fun toSpeakPlan(content: String, pauseSeconds: Int = 3): List<SpeakStep> {
        val pauseMs = pauseSeconds.coerceIn(0, 30) * 1000L
        val segments = toVoiceSegments(content)
        if (segments.isEmpty()) {
            return emptyList()
        }
        return segments.flatMapIndexed { index, seg ->
            buildList {
                add(SpeakStep.Speak(seg.lang, seg.text))
                if (index < segments.lastIndex && pauseMs > 0) {
                    add(SpeakStep.Pause(pauseMs))
                }
            }
        }
    }

    fun parseEnglishLessonMeta(content: String): EnglishLessonMeta? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains(TYPE_ENGLISH_LESSON_META, ignoreCase = true)) {
            return null
        }
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!type.equals(TYPE_ENGLISH_LESSON_META, ignoreCase = true)) {
                return null
            }
            val totalCards = obj["total_cards"]?.jsonPrimitive?.intOrNull ?: 0
            val totalScreens = obj["total_screens"]?.jsonPrimitive?.intOrNull ?: 0
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            EnglishLessonMeta(
                totalCards = totalCards,
                totalScreens = totalScreens,
                title = title,
            )
        }.getOrNull()
    }

    /**
     * Сегменты TTS: только внутри `[Voice]…[/Voice]`, затем `"en"` / `"ru"` в `{…}`.
     * Без тегов Voice или без TTS-объектов внутри — пустой список.
     */
    fun toVoiceSegments(content: String): List<VoiceSegment> {
        if (content.isBlank()) {
            return emptyList()
        }
        return extractVoiceTaggedRegions(content)
            .flatMap { region ->
                extractBraceObjects(region)
                    .filter { looksLikeTtsObject(it.text) }
                    .flatMap { extractVoiceSegmentsFromObject(it.text) }
            }
            .mapNotNull { (lang, text) ->
                text.trim().takeIf { it.isNotEmpty() }?.let { VoiceSegment(lang, it) }
            }
    }

    /** @deprecated Prefer [toVoiceSegments]; kept for call sites needing a single string. */
    fun toSpeakText(content: String): String =
        toVoiceSegments(content).joinToString(" ") { it.text }

    fun extractEnRuPairs(content: String): List<Pair<String, String>> =
        extractEnglishCards(content).map { it.en to it.ru }

    fun extractEnglishCards(content: String): List<EnglishLessonCard> {
        val objects = extractVoiceTaggedRegions(content)
            .flatMap { extractBraceObjects(it) }
            .filter { looksLikeTtsObject(it.text) }
        if (objects.isEmpty()) {
            return emptyList()
        }

        val cards = objects.mapNotNull { obj ->
            val line = obj.text
            val segs = extractVoiceSegmentsFromObject(line)
            val en = segs.firstOrNull { it.first.equals("en", ignoreCase = true) }?.second?.trim().orEmpty()
            val ru = segs.firstOrNull { it.first.equals("ru", ignoreCase = true) }?.second?.trim().orEmpty()
            if (en.isEmpty() && ru.isEmpty()) {
                null
            } else {
                EnglishLessonCard(en = en, ru = ru, isLast = lineHasLastFlag(line))
            }
        }
        if (cards.none { it.en.isNotEmpty() && it.ru.isNotEmpty() }) {
            return emptyList()
        }
        return cards
    }

    /**
     * Bodies between `[Voice]` and `[/Voice]` (case-insensitive), in document order.
     * Unclosed / missing tags → no regions (nothing to speak).
     */
    private fun extractVoiceTaggedRegions(content: String): List<String> {
        val result = ArrayList<String>()
        var searchFrom = 0
        while (searchFrom < content.length) {
            val openMatch = voiceOpenTag.find(content, searchFrom) ?: break
            val closeMatch = voiceCloseTag.find(content, openMatch.range.last + 1) ?: break
            result.add(content.substring(openMatch.range.last + 1, closeMatch.range.first))
            searchFrom = closeMatch.range.last + 1
        }
        return result
    }

    /** Balances `{…}` respecting JSON strings; returns each top-level object. */
    private fun extractBraceObjects(content: String): List<BraceObject> {
        val result = ArrayList<BraceObject>()
        var i = 0
        while (i < content.length) {
            if (content[i] != '{') {
                i++
                continue
            }
            val start = i
            var depth = 0
            var inString = false
            var escape = false
            var closed = false
            while (i < content.length) {
                val c = content[i]
                when {
                    escape -> escape = false
                    inString && c == '\\' -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val end = i + 1
                            result.add(BraceObject(start, end, content.substring(start, end)))
                            closed = true
                            i = end
                            break
                        }
                    }
                }
                i++
            }
            if (!closed) {
                break
            }
        }
        return result
    }

    private fun lineHasLastFlag(line: String): Boolean {
        if (Regex(""""last"\s*:\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            return true
        }
        return runCatching {
            json.parseToJsonElement(line).jsonObject["last"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
    }

    private fun voiceSegmentsToPlain(content: String): String? {
        val regions = extractVoiceTaggedRegions(content)
        if (regions.isEmpty()) {
            return null
        }
        val allObjects = regions.flatMap { extractBraceObjects(it) }
        val ttsObjects = allObjects.filter { looksLikeTtsObject(it.text) }
        if (ttsObjects.isEmpty()) {
            return null
        }
        // Only rewrite bubble when whole message is Voice-tagged TTS (no outside text).
        val withoutVoice = content
            .replace(Regex("""\[Voice].*?\[/Voice]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
        if (withoutVoice.isNotEmpty()) {
            return null
        }
        return ttsObjects
            .flatMap { extractVoiceSegmentsFromObject(it.text) }
            .map { it.second.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
    }

    private fun looksLikeTtsObject(line: String): Boolean {
        val t = line.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) {
            return false
        }
        if (t.contains("\"type\"", ignoreCase = true) || t.contains("\"skill\"", ignoreCase = true)) {
            return false
        }
        return voiceKeyRegex.containsMatchIn(t)
    }

    private fun extractVoiceSegmentsFromObject(jsonObject: String): List<Pair<String, String>> {
        return voiceKeyRegex.findAll(jsonObject).map { match ->
            val lang = match.groupValues[1]
            val raw = match.groupValues[2]
            lang to unescapeJsonString(raw)
        }.toList()
    }

    private fun unescapeJsonString(raw: String): String =
        buildString(raw.length) {
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (val n = raw[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        '"', '\\', '/' -> append(n)
                        'u' -> {
                            if (i + 5 < raw.length) {
                                val hex = raw.substring(i + 2, i + 6)
                                append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            } else {
                                append(n)
                            }
                        }
                        else -> append(n)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

    private fun parseSingleObjectFallback(text: String): String? {
        return runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["type"]?.jsonPrimitive?.content.equals("file", ignoreCase = true)) {
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                return FileMessageFormat.toDisplayText(
                    FileMessagePayload(name = name, size = size),
                )
            }
            val ru = obj["ru"]?.jsonPrimitive?.content?.trim().orEmpty()
            val en = obj["en"]?.jsonPrimitive?.content?.trim().orEmpty()
            when {
                ru.isNotEmpty() && en.isNotEmpty() -> if (ru == en) ru else "$ru $en"
                ru.isNotEmpty() -> ru
                en.isNotEmpty() -> en
                else -> null
            }
        }.getOrNull()
    }
}
