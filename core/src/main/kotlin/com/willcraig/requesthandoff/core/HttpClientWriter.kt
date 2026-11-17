package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.obj
import com.willcraig.requesthandoff.core.json.render

internal data class WrittenRequest(
    val name: String,
    val comments: List<String>,
    val preScripts: List<ScriptSource>,
    val method: String,
    val url: String,
    val headers: List<Header>,
    val body: Body,
    val responseScripts: List<ScriptSource>,
)

internal data class HeaderAdjustment(
    val headers: List<Header>,
    val notes: List<String>,
)

internal fun headersForBody(headers: List<Header>, body: Body, method: String): HeaderAdjustment {
    val next = headers.toMutableList()
    val notes = mutableListOf<String>()
    when (body) {
        is Body.UrlEncoded -> {
            val hasType = next.any {
                it.name.equals("Content-Type", ignoreCase = true) &&
                    it.value.lowercase().contains("application/x-www-form-urlencoded")
            }
            if (!hasType) {
                upsertHeader(next, "Content-Type", "application/x-www-form-urlencoded")
                notes += "Content-Type was set to application/x-www-form-urlencoded so the form body can be parsed."
            }
        }
        is Body.Multipart -> {
            val value = "multipart/form-data; boundary=${boundaryFor(body.parts)}"
            val current = next.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
            if (current != value) {
                upsertHeader(next, "Content-Type", value)
                notes += "Content-Type was set to multipart/form-data so the form body can be parsed."
            }
        }
        is Body.Raw -> {
            val mime = mimeForLanguage(body.language)
            if (mime != null && next.none { it.name.equals("Content-Type", ignoreCase = true) }) {
                next += Header("Content-Type", mime)
                notes += "Content-Type was set to $mime to keep the raw body language."
            }
        }
        is Body.GraphQl -> {
            val graphqlMethod = method.equals("POST", ignoreCase = true) || method.equals("GRAPHQL", ignoreCase = true)
            if (!graphqlMethod && next.none { it.name.equals("X-REQUEST-TYPE", ignoreCase = true) }) {
                next += Header("X-REQUEST-TYPE", "GraphQL")
                notes += "X-REQUEST-TYPE was set to GraphQL so the body is parsed as GraphQL."
            }
        }
        Body.None, is Body.FileRef -> Unit
    }
    return HeaderAdjustment(next, notes)
}

internal fun renderHttpFile(
    comments: List<String>,
    variables: List<Pair<String, String>>,
    requests: List<WrittenRequest>,
): String = buildString {
    comments.forEach { appendComment(it) }
    if (comments.isNotEmpty() && (variables.isNotEmpty() || requests.isNotEmpty())) append('\n')
    variables.forEach { (key, value) ->
        append('@')
        append(key)
        append(" = ")
        append(value)
        append('\n')
    }
    if (variables.isNotEmpty() && requests.isNotEmpty()) append('\n')
    requests.forEachIndexed { index, request ->
        if (index > 0) append('\n')
        renderRequest(request)
        if (!endsWith('\n')) append('\n')
    }
    if (isEmpty()) append('\n')
    else if (!endsWith('\n')) append('\n')
}

private fun StringBuilder.renderRequest(request: WrittenRequest) {
    val title = request.name.replace('\n', ' ').replace('\r', ' ').trim().ifEmpty {
        "${request.method} ${request.url}"
    }
    append("### ")
    append(title)
    append('\n')
    request.comments.forEach { appendComment(it) }
    request.preScripts.forEach { append(renderScript('<', it)) }
    append(requestLine(request))
    append('\n')
    request.headers.forEach { header ->
        append(header.name)
        append(": ")
        append(header.value.replace('\n', ' ').replace('\r', ' '))
        append('\n')
    }
    val body = renderBody(request)
    if (body != null) {
        append('\n')
        append(body)
        if (!body.endsWith('\n')) append('\n')
    }
    request.responseScripts.forEach { append(renderScript('>', it)) }
}

private fun requestLine(request: WrittenRequest): String {
    val method = if (request.body is Body.GraphQl && request.method.equals("POST", ignoreCase = true)) {
        "GRAPHQL"
    } else {
        request.method
    }
    return "$method ${request.url}"
}

private fun renderBody(request: WrittenRequest): String? = when (val body = request.body) {
    Body.None -> null
    is Body.Raw -> body.text.trimEnd().ifEmpty { null }
    is Body.FileRef -> "< ${body.src.trim()}"
    is Body.UrlEncoded -> body.fields.joinToString("&") { (key, value) -> "$key=$value" }
    is Body.Multipart -> renderMultipart(request.headers, body)
    is Body.GraphQl -> renderGraphQl(body)
}

private fun renderGraphQl(body: Body.GraphQl): String {
    val value = obj {
        put("query", body.query)
        put("variables", body.variables ?: "")
    }
    return render(value).trimEnd()
}

private fun renderMultipart(headers: List<Header>, body: Body.Multipart): String {
    val boundary = boundaryOf(headers, body.parts)
    return buildString {
        for (part in body.parts) {
            append("--")
            append(boundary)
            append('\n')
            append("Content-Disposition: form-data; name=\"")
            append(part.name.replace("\"", "'"))
            append('"')
            if (part.src != null) {
                val filename = part.src.substringAfterLast('/').substringAfterLast('\\').ifEmpty { part.src }
                append("; filename=\"")
                append(filename.replace("\"", "'"))
                append('"')
            }
            append('\n')
            if (!part.contentType.isNullOrBlank()) {
                append("Content-Type: ")
                append(part.contentType)
                append('\n')
            }
            append('\n')
            if (part.src != null) append("< ").append(part.src.trim()) else append(part.text ?: "")
            append('\n')
        }
        append("--")
        append(boundary)
        append("--")
    }
}

private fun renderScript(marker: Char, script: ScriptSource): String = buildString {
    if (script.file != null) {
        append(marker)
        append(' ')
        append(script.file)
        append('\n')
    }
    if (script.inline != null) {
        append(marker)
        append(" {%\n")
        append(script.inline)
        if (!script.inline.endsWith('\n')) append('\n')
        append("%}\n")
    }
}

private fun StringBuilder.appendComment(line: String) {
    val text = line.replace("\r\n", "\n").replace('\r', '\n')
    text.split('\n').forEach { part ->
        when {
            part.startsWith("//") -> append(part)
            part.isEmpty() -> append("//")
            else -> {
                append("// ")
                append(part)
            }
        }
        append('\n')
    }
}

private fun upsertHeader(headers: MutableList<Header>, name: String, value: String) {
    val index = headers.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    if (index >= 0) headers[index] = Header(headers[index].name, value)
    else headers += Header(name, value)
}

private fun mimeForLanguage(language: String): String? = when (language.lowercase()) {
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html" -> "text/html"
    "javascript" -> "application/javascript"
    else -> null
}

internal fun boundaryFor(parts: List<Part>): String {
    val haystack = parts.joinToString("\n") { (it.text ?: "") + "\n" + (it.src ?: "") }
    var boundary = "RequestHandoffBoundary"
    var suffix = 2
    while (haystack.contains(boundary)) {
        boundary = "RequestHandoffBoundary$suffix"
        suffix++
    }
    return boundary
}

private fun boundaryOf(headers: List<Header>, parts: List<Part>): String {
    val header = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
    val match = Regex("""boundary="?([^";]+)""").find(header)
    val fromHeader = match?.groupValues?.get(1)?.trim()
    if (!fromHeader.isNullOrEmpty()) return fromHeader
    return boundaryFor(parts)
}
