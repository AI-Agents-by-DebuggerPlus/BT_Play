package com.taskertowpf.androidchatbttest98.lesson

/**
 * Парсер MD-урока в формате Hermes.EnglishLearning.Xp
 * (front matter + ## title/words/phrases/lyrics).
 */
object LessonMarkdownParser {

    fun parse(markdown: String): LessonDocument {
        val text = markdown.trim().removeSurrounding("```markdown", "```").trim()
            .removeSurrounding("```", "```").trim()
        val (meta, body) = splitFrontMatter(text)
        val sections = splitSections(body)

        val titleCards = parseSectionCards(sections["title"].orEmpty(), allowBlocks = false)
        val words = parseSectionCards(sections["words"].orEmpty(), allowBlocks = false)
        val phrases = parseSectionCards(sections["phrases"].orEmpty(), allowBlocks = true)
        val lyrics = parseSectionCards(sections["lyrics"].orEmpty(), allowBlocks = true)

        val titleEn = meta["title"] ?: meta["title_en"].orEmpty()
        val titleRu = meta["title_ru"].orEmpty()
        val artist = meta["artist"].orEmpty()

        val resolvedTitle = titleCards.ifEmpty {
            buildList {
                if (titleEn.isNotBlank() || titleRu.isNotBlank()) {
                    add(CardPair(titleEn, titleRu))
                }
                if (artist.isNotBlank()) {
                    add(CardPair(artist, ""))
                }
            }
        }

        return LessonDocument(
            titleEn = titleEn,
            titleRu = titleRu,
            artist = artist,
            titleCards = resolvedTitle,
            words = words,
            phrases = phrases,
            lyrics = lyrics,
        )
    }

    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) {
            return emptyMap<String, String>() to text
        }
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) {
            return emptyMap<String, String>() to text
        }
        val rawMeta = text.substring(3, end).trim()
        val body = text.substring(end + 4).trimStart('\n', '\r')
        val meta = linkedMapOf<String, String>()
        rawMeta.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isNotEmpty()) {
                    meta[key] = value
                }
            }
        }
        return meta to body
    }

    private fun splitSections(body: String): Map<String, String> {
        val result = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val heading = parseHeading(line)
            if (heading != null) {
                current = heading
                result.getOrPut(heading) { StringBuilder() }
            } else if (current != null) {
                result[current]!!.appendLine(line)
            }
        }
        return result.mapValues { it.value.toString().trim() }
    }

    private fun parseHeading(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("##")) return null
        val name = trimmed.removePrefix("##").trim().lowercase()
        return when {
            name.startsWith("title") -> "title"
            name.startsWith("word") || name.startsWith("vocab") -> "words"
            name.startsWith("phrase") || name.startsWith("example") -> "phrases"
            name.startsWith("lyric") || name.startsWith("sentence") || name.startsWith("line") -> "lyrics"
            else -> null
        }
    }

    private fun parseSectionCards(sectionBody: String, allowBlocks: Boolean): List<CardPair> {
        if (sectionBody.isBlank()) return emptyList()
        if (!allowBlocks) {
            return parsePipeLines(sectionBody)
        }
        val blocks = sectionBody.split(Regex("""(?m)^\s*---\s*$"""))
        return blocks.flatMap { parsePipeLines(it) }
    }

    private fun parsePipeLines(block: String): List<CardPair> {
        val lines = block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "---" }
        val cards = mutableListOf<CardPair>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains('|')) {
                val parts = line.split('|', limit = 2)
                cards += CardPair(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
                i++
            } else {
                val en = line
                val ru = lines.getOrNull(i + 1)?.takeIf { !it.contains('|') && !it.startsWith("#") }.orEmpty()
                if (ru.isNotEmpty()) {
                    cards += CardPair(en, ru)
                    i += 2
                } else {
                    cards += CardPair(en, "")
                    i++
                }
            }
        }
        return cards.filter { it.en.isNotBlank() || it.ru.isNotBlank() }
    }
}
