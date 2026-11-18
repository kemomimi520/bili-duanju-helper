package com.bili.drama.core

data class EpisodeProgress(
    val current: Int,
    val total: Int?
) {
    val isLastEpisode: Boolean
        get() = total != null && total > 0 && current >= total

    override fun toString(): String =
        if (total != null && total > 0) "第${current}/${total}集" else "第${current}集"
}

/**
 * Extracts current/total episode numbers from Bilibili playlet UI nodes.
 */
object EpisodeParser {
    // Matches "选集(36/38)" or "(36/38)"
    private val selectProgressRegex = Regex("""\((\d{1,4})\s*/\s*(\d{1,4})\)""")
    // Matches "第36集"
    private val titleEpisodeRegex = Regex("""第\s*(\d{1,4})\s*集""")
    // Matches "共38集" or "全38集"
    private val totalEpisodeRegex = Regex("""[共全]\s*(\d{1,4})\s*集""")

    fun parse(snapshot: UiSnapshot): EpisodeProgress? {
        val selectText = snapshot.firstById(BiliNodes.ID_ALBUM_SELECT_TEXT)?.displayLabel.orEmpty()
        selectProgressRegex.find(selectText)?.let { match ->
            val cur = match.groupValues[1].toIntOrNull()
            val tot = match.groupValues[2].toIntOrNull()
            if (cur != null && cur > 0) {
                return EpisodeProgress(cur, tot)
            }
        }

        val toolbarText = snapshot.firstById(BiliNodes.ID_TOOLBAR_TITLE)?.displayLabel.orEmpty()
        val curFromTitle = titleEpisodeRegex.find(toolbarText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        var cur = curFromTitle
        var total: Int? = null
        for (text in snapshot.allTexts()) {
            if (cur == null) {
                selectProgressRegex.find(text)?.let { match ->
                    cur = match.groupValues[1].toIntOrNull()
                    total = match.groupValues[2].toIntOrNull()
                }
            }
            if (cur == null) {
                cur = titleEpisodeRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            if (total == null) {
                total = totalEpisodeRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
        return cur?.let { EpisodeProgress(it, total) }
    }
}
