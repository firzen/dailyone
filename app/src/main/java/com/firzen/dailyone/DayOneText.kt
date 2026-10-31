package com.firzen.dailyone

// Day One media links refer to attachments already copied into our gallery, not readable prose.
private val dayOneMediaLink = Regex("""!\[([^\]\r\n]*)\]\(dayone?-moment:/{1,3}(?:video/)?[0-9a-f-]+\)""", RegexOption.IGNORE_CASE)
fun cleanDayOneText(text: String): String {
    if (!dayOneMediaLink.containsMatchIn(text)) return text
    return text.split('\n').mapNotNull { line ->
        val cleaned = dayOneMediaLink.replace(line) { it.groupValues[1] }
        if (cleaned.isBlank() && dayOneMediaLink.containsMatchIn(line)) null else cleaned
    }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n', '\r')
}
