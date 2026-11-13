package com.willcraig.requesthandoff.core

internal fun normalizeNewlines(text: String): String =
    text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

internal fun splitOutside(input: String, delimiter: Char): List<String> {
    if (input.isEmpty()) return emptyList()
    val parts = mutableListOf<String>()
    var start = 0
    var depth = 0
    var i = 0
    while (i < input.length) {
        if (input.startsWith("{{", i)) {
            depth++
            i += 2
            continue
        }
        if (input.startsWith("}}", i)) {
            depth = (depth - 1).coerceAtLeast(0)
            i += 2
            continue
        }
        if (depth == 0 && input[i] == delimiter) {
            parts += input.substring(start, i)
            start = i + 1
        }
        i++
    }
    parts += input.substring(start)
    return parts
}

internal fun splitOnceOutside(input: String, delimiter: Char): Pair<String, String?> {
    var depth = 0
    var i = 0
    while (i < input.length) {
        if (input.startsWith("{{", i)) {
            depth++
            i += 2
            continue
        }
        if (input.startsWith("}}", i)) {
            depth = (depth - 1).coerceAtLeast(0)
            i += 2
            continue
        }
        if (depth == 0 && input[i] == delimiter) {
            return input.substring(0, i) to input.substring(i + 1)
        }
        i++
    }
    return input to null
}

private val SLUG_UNSAFE = Regex("""[<>:"/\\|?*\u0000]""")
private val SLUG_DASHES = Regex("""-+""")

fun fileSlug(name: String): String {
    val slug = name.trim()
        .replace(Regex("""\s+"""), "-")
        .replace(SLUG_UNSAFE, "-")
        .let { SLUG_DASHES.replace(it, "-") }
        .trim('-', '.', ' ')
    return slug.ifEmpty { "collection" }
}

internal fun fileStem(relativePath: String): String {
    val base = relativePath.substringAfterLast('/').substringBeforeLast('.')
    return base.ifEmpty { relativePath.substringAfterLast('/') }
}
