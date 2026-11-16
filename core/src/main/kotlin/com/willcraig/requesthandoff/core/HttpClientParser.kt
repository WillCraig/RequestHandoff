package com.willcraig.requesthandoff.core

private val SEPARATOR = Regex("""^###(?:\s+(.*))?$""")
private val REQUEST_LINE = Regex("""^([A-Za-z][A-Za-z0-9+_.-]*)\s+(\S+)(?:\s+HTTP/\d(?:\.\d)?)?\s*$""")
private val FILE_VARIABLE = Regex("""^@([A-Za-z_][\w.\-]*)\s*=\s*(.*)$""")
private val DIRECTIVE = Regex("""^#\s*@([A-Za-z][\w-]*)\s*(.*)$""")
private val HEADER_LINE = Regex("""^([^:\s][^:]*)\s*:\s?(.*)$""")
private val QUERY_CONTINUATION = Regex("""^\s+[?&]\s*(.*)$""")
private val RESPONSE_LINE = Regex("""^HTTP/\d(?:\.\d)?\s+\d{3}\b.*$""", RegexOption.IGNORE_CASE)
private val RUN_LINE = Regex("""^run\s+(\S.*)$""", RegexOption.IGNORE_CASE)
private val FILE_BODY = Regex("""^<\s+(\S.*?)\s*$""")
private val INLINE_SCRIPT = Regex("""^[<>]\s*\{%(.*)$""")
private val FILE_SCRIPT = Regex("""^[<>]\s+(\S.*?)\s*$""")

private val KNOWN_METHODS = setOf(
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT",
    "GRAPHQL", "WEBSOCKET", "GRPC", "PROPFIND", "LOCK", "UNLOCK", "COPY", "PURGE",
    "VIEW", "LINK", "UNLINK", "MKCOL", "MOVE",
)

internal fun parseHttpFile(relativePath: String, content: String): ParsedFile {
    val lines = normalizeNewlines(content).split('\n')
    val blocks = splitBlocks(lines)
    val variables = mutableListOf<Pair<String, String>>()
    val requests = mutableListOf<HttpRequest>()
    val notes = mutableListOf<Note>()
    blocks.forEach { block ->
        val parsed = parseBlock(relativePath, block)
        variables += parsed.variables
        requests += parsed.requests
        notes += parsed.notes
    }
    return ParsedFile(variables, requests, notes)
}

private data class Block(val title: String?, val lines: List<Line>)
private data class Line(val number: Int, val text: String)

private fun splitBlocks(lines: List<String>): List<Block> {
    val blocks = mutableListOf<Block>()
    var title: String? = null
    var current = mutableListOf<Line>()
    var started = false
    fun flush() {
        if (current.isEmpty() && title == null) return
        blocks += Block(title, current)
        current = mutableListOf()
        title = null
    }
    lines.forEachIndexed { index, text ->
        val match = SEPARATOR.matchEntire(text)
        if (match != null) {
            if (started || current.isNotEmpty()) flush()
            started = true
            title = match.groupValues[1].trim().ifEmpty { null }
        } else {
            current += Line(index + 1, text)
        }
    }
    if (started || current.isNotEmpty()) flush()
    return blocks
}

private data class BlockResult(
    val variables: List<Pair<String, String>> = emptyList(),
    val requests: List<HttpRequest> = emptyList(),
    val notes: List<Note> = emptyList(),
)

private enum class State { PREAMBLE, HEADERS, BODY, HANDLER }

private fun parseBlock(file: String, block: Block): BlockResult {
    if (block.lines.all { it.text.isBlank() || isComment(it.text.trim()) }) {
        return BlockResult()
    }
    val variables = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<Note>()
    val preScripts = mutableListOf<ScriptSource>()
    val responseScripts = mutableListOf<ScriptSource>()
    val redirects = mutableListOf<String>()
    val flags = mutableListOf<String>()
    var atName: String? = null
    var requestLine: Line? = null
    var method: String? = null
    var url: String? = null
    val headers = mutableListOf<Header>()
    val bodyLines = mutableListOf<String>()
    var state = State.PREAMBLE
    var index = 0
    val lines = block.lines

    fun warn(line: Int, message: String) {
        notes += Note(Severity.WARNING, file, atName ?: block.title, message, line)
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.text.trim()
        when (state) {
            State.PREAMBLE -> {
                when {
                    trimmed.isEmpty() || isComment(trimmed) && DIRECTIVE.matchEntire(trimmed) == null -> index++
                    DIRECTIVE.matchEntire(trimmed) != null -> {
                        val match = DIRECTIVE.matchEntire(trimmed)!!
                        val name = match.groupValues[1]
                        val rest = match.groupValues[2].trim()
                        if (name.equals("name", ignoreCase = true)) {
                            atName = rest.ifEmpty { atName }
                        } else {
                            val rendered = if (rest.isEmpty()) "@$name" else "@$name $rest"
                            flags += rendered
                        }
                        index++
                    }
                    FILE_VARIABLE.matchEntire(trimmed) != null -> {
                        val match = FILE_VARIABLE.matchEntire(trimmed)!!
                        variables += match.groupValues[1] to match.groupValues[2].trim()
                        index++
                    }
                    RUN_LINE.matchEntire(trimmed) != null -> {
                        val target = RUN_LINE.matchEntire(trimmed)!!.groupValues[1].trim()
                        notes += Note(
                            Severity.WARNING,
                            file,
                            null,
                            "Import 'run $target' was not converted.",
                            line.number,
                        )
                        index++
                    }
                    trimmed.startsWith("<") -> {
                        val (script, next) = readScript(lines, index)
                        preScripts += script
                        index = next
                    }
                    RESPONSE_LINE.matches(trimmed) -> {
                        notes += Note(
                            Severity.WARNING,
                            file,
                            block.title,
                            "Saved HTTP response example was not converted.",
                            line.number,
                        )
                        return BlockResult(variables, emptyList(), notes)
                    }
                    requestLineOf(trimmed) != null -> {
                        val (parsedMethod, parsedUrl) = requestLineOf(trimmed)!!
                        requestLine = line
                        method = parsedMethod
                        url = parsedUrl
                        state = State.HEADERS
                        index++
                    }
                    else -> {
                        warn(line.number, "Could not parse '${trimmed.take(80)}' before the request line.")
                        index++
                    }
                }
            }
            State.HEADERS -> {
                when {
                    trimmed.isEmpty() -> {
                        state = State.BODY
                        index++
                    }
                    QUERY_CONTINUATION.matches(line.text) -> {
                        url = appendQuery(url.orEmpty(), QUERY_CONTINUATION.matchEntire(line.text)!!.groupValues[1])
                        index++
                    }
                    trimmed.startsWith(">") -> state = State.HANDLER
                    isComment(trimmed) -> index++
                    HEADER_LINE.matches(trimmed) -> {
                        val match = HEADER_LINE.matchEntire(trimmed)!!
                        headers += Header(match.groupValues[1].trim(), match.groupValues[2])
                        index++
                    }
                    else -> {
                        warn(line.number, "Ignored unrecognized header line '${trimmed.take(80)}'.")
                        index++
                    }
                }
            }
            State.BODY -> {
                if (trimmed.startsWith(">")) {
                    state = State.HANDLER
                } else {
                    bodyLines += line.text
                    index++
                }
            }
            State.HANDLER -> {
                when {
                    trimmed.isEmpty() || isComment(trimmed) -> index++
                    trimmed.startsWith(">>") -> {
                        redirects += trimmed
                        index++
                    }
                    trimmed.startsWith(">") -> {
                        val (script, next) = readScript(lines, index)
                        responseScripts += script
                        index = next
                    }
                    else -> {
                        warn(line.number, "Ignored trailing line '${trimmed.take(80)}' after the request.")
                        index++
                    }
                }
            }
        }
    }

    if (method == null || url == null || requestLine == null) {
        if (variables.isNotEmpty() && preScripts.isEmpty() && flags.isEmpty() && notes.isEmpty()) {
            return BlockResult(variables)
        }
        if (notes.isEmpty()) {
            notes += Note(Severity.WARNING, file, block.title, "No HTTP request found.", block.lines.firstOrNull()?.number ?: 1)
        }
        return BlockResult(variables, emptyList(), notes)
    }

    val displayName = block.title?.takeIf { it.isNotBlank() }
        ?: atName?.takeIf { it.isNotBlank() }
        ?: fallbackName(method, url)
    val kind = skipKind(method, url)
    if (kind != null) {
        notes += Note(Severity.SKIPPED, file, displayName, kind, requestLine.number)
        flags.forEach { flag ->
            notes += Note(Severity.WARNING, file, displayName, "Request flag '$flag' was not converted.", requestLine.number)
        }
        return BlockResult(variables, emptyList(), notes)
    }

    val exportMethod = if (method.equals("GRAPHQL", ignoreCase = true)) "POST" else method.uppercase()
    val body = buildBody(file, displayName, headers, bodyLines.joinToString("\n").trimEnd(), method, notes)
    flags.forEach { flag ->
        notes += Note(Severity.WARNING, file, displayName, "Request flag '$flag' was not converted.", requestLine.number)
    }
    redirects.forEach { redirect ->
        notes += Note(
            Severity.WARNING,
            file,
            displayName,
            "Response output redirect '$redirect' was not converted.",
            requestLine.number,
        )
    }
    scriptNotes(file, displayName, requestLine.number, preScripts, responseScripts, notes)
    dynamicVariableNotes(file, displayName, requestLine.number, url, headers, body, notes)

    val request = HttpRequest(
        name = displayName,
        method = exportMethod,
        url = url,
        headers = headers,
        body = body,
        preRequestScripts = preScripts,
        responseScripts = responseScripts,
        line = requestLine.number,
    )
    return BlockResult(variables, listOf(request), notes)
}

private fun isComment(trimmed: String): Boolean = trimmed.startsWith("//") || trimmed.startsWith("#")

private fun requestLineOf(trimmed: String): Pair<String, String>? {
    val match = REQUEST_LINE.matchEntire(trimmed) ?: return null
    val method = match.groupValues[1]
    val url = match.groupValues[2]
    val upper = method.uppercase()
    val known = upper in KNOWN_METHODS
    val custom = method.all { it.isUpperCase() || it.isDigit() || it == '_' || it == '-' || it == '+' } &&
        (url.contains("://") || url.startsWith("{{") || url.startsWith("/"))
    if (!known && !custom) return null
    return upper to url
}

private fun appendQuery(url: String, rawPart: String): String {
    val part = rawPart.trim()
    if (part.isEmpty()) return url
    val (key, value) = splitOnceOutside(part, '=')
    val keyTrim = key.trim()
    if (keyTrim.isEmpty()) return url
    val encoded = if (value == null) keyTrim else "$keyTrim=${value.trim()}"
    val hashSplit = splitOnceOutside(url, '#')
    val base = hashSplit.first
    val hash = hashSplit.second
    val joined = if (splitOnceOutside(base, '?').second != null) "$base&$encoded" else "$base?$encoded"
    return if (hash == null) joined else "$joined#$hash"
}

private fun skipKind(method: String, url: String): String? {
    val scheme = url.substringBefore("://", "").lowercase()
    return when {
        method.equals("WEBSOCKET", ignoreCase = true) || scheme == "ws" || scheme == "wss" ->
            "WebSocket requests are skipped in v0.1."
        method.equals("GRPC", ignoreCase = true) || scheme == "grpc" ->
            "gRPC requests are skipped in v0.1."
        else -> null
    }
}

private fun fallbackName(method: String, url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val short = if (path.length > 80) path.take(77) + "..." else path
    return "$method $short"
}

private fun readScript(lines: List<Line>, start: Int): Pair<ScriptSource, Int> {
    val trimmed = lines[start].text.trim()
    val inline = INLINE_SCRIPT.matchEntire(trimmed)
    if (inline != null) {
        val first = inline.groupValues[1]
        if (first.contains("%}")) {
            val code = first.substringBefore("%}")
            return ScriptSource(inline = code.trim('\n')) to start + 1
        }
        val buf = mutableListOf<String>()
        if (first.isNotEmpty()) buf += first
        var index = start + 1
        while (index < lines.size) {
            val text = lines[index].text
            val end = text.indexOf("%}")
            if (end >= 0) {
                val before = text.substring(0, end)
                if (before.isNotEmpty()) buf += before
                index++
                break
            }
            buf += text
            index++
        }
        return ScriptSource(inline = buf.joinToString("\n").trim('\n')) to index
    }
    val file = FILE_SCRIPT.matchEntire(trimmed)
    if (file != null) {
        return ScriptSource(file = file.groupValues[1]) to start + 1
    }
    return ScriptSource(inline = trimmed.removePrefix(">").removePrefix("<").trim()) to start + 1
}

private fun buildBody(
    file: String,
    request: String,
    headers: List<Header>,
    body: String,
    method: String,
    notes: MutableList<Note>,
): Body {
    if (body.isEmpty()) return Body.None
    val fileBody = FILE_BODY.matchEntire(body.trim())
    if (fileBody != null && !body.trim().startsWith("< {%")) {
        return Body.FileRef(fileBody.groupValues[1])
    }
    val graphql = method.equals("GRAPHQL", ignoreCase = true) || headers.any {
        it.name.equals("X-REQUEST-TYPE", ignoreCase = true) && it.value.trim().equals("GraphQL", ignoreCase = true)
    }
    if (graphql) {
        val (query, variables) = splitGraphQl(body)
        return Body.GraphQl(query, variables)
    }
    val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        mime.contains("multipart/form-data") -> parseMultipart(file, request, body, contentType, notes)
        mime.contains("application/x-www-form-urlencoded") -> Body.UrlEncoded(parseUrlEncoded(body))
        mime.contains("json") || (mime.isEmpty() && looksLikeJson(body)) -> Body.Raw(body.trim(), "json")
        mime.contains("xml") -> Body.Raw(body.trim(), "xml")
        mime.contains("html") -> Body.Raw(body.trim(), "html")
        else -> Body.Raw(body.trim(), "text")
    }
}

private fun looksLikeJson(body: String): Boolean {
    val trimmed = body.trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

private fun splitGraphQl(body: String): Pair<String, String?> {
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        val parsed = runCatching { com.willcraig.requesthandoff.core.json.parseJson(trimmed) }.getOrNull()
        if (parsed is com.willcraig.requesthandoff.core.json.Jv.Obj && parsed.fields.containsKey("query")) {
            val query = jsonText(parsed.fields.getValue("query"))
            val variables = parsed.fields["variables"]?.let { variableText(it) }
            return query to variables
        }
    }
    val lines = trimmed.split('\n')
    for (i in lines.indices) {
        if (lines[i].isBlank()) {
            val head = lines.take(i).joinToString("\n").trim()
            val rest = lines.drop(i + 1).joinToString("\n").trim()
            if (head.isNotEmpty() && (rest.startsWith("{") || rest.startsWith("["))) {
                return head to rest
            }
        }
    }
    return trimmed to null
}

private fun jsonText(value: com.willcraig.requesthandoff.core.json.Jv): String = when (value) {
    is com.willcraig.requesthandoff.core.json.Jv.Str -> value.value
    else -> variableText(value)
}

private fun variableText(value: com.willcraig.requesthandoff.core.json.Jv): String = when (value) {
    is com.willcraig.requesthandoff.core.json.Jv.Str -> value.value
    else -> com.willcraig.requesthandoff.core.json.render(value).trimEnd()
}

private fun parseUrlEncoded(body: String): List<Pair<String, String>> {
    val flat = body.replace("\n", "").replace("\r", "")
    return splitOutside(flat, '&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val (key, value) = splitOnceOutside(part, '=')
        val name = key.trim()
        if (name.isEmpty()) return@mapNotNull null
        name to (value?.trim() ?: "")
    }
}

private fun parseMultipart(
    file: String,
    request: String,
    body: String,
    contentType: String,
    notes: MutableList<Note>,
): Body {
    val boundary = Regex("""boundary="?([^";]+)"?""").find(contentType)?.groupValues?.get(1)?.trim()
    if (boundary.isNullOrEmpty()) {
        notes += Note(Severity.WARNING, file, request, "Multipart body has no boundary and was exported as text.")
        return Body.Raw(body.trim(), "text")
    }
    val marker = "--$boundary"
    val chunks = body.split(marker).drop(1)
    val parts = mutableListOf<Part>()
    for (chunk in chunks) {
        val trimmedStart = chunk.trimStart('\n', '\r')
        if (trimmedStart.startsWith("--") || trimmedStart.isBlank()) continue
        val withoutTrailer = trimmedStart.removeSuffix("\n").removeSuffix("\r")
        val (headerText, content) = splitPart(withoutTrailer)
        val partHeaders = headerText.lines().mapNotNull { line ->
            val match = HEADER_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            Header(match.groupValues[1].trim(), match.groupValues[2].trim())
        }
        val disposition = partHeaders.firstOrNull { it.name.equals("Content-Disposition", ignoreCase = true) }?.value.orEmpty()
        val name = parameter(disposition, "name") ?: continue
        val filename = parameter(disposition, "filename")
        val partType = partHeaders.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
        val fileRef = FILE_BODY.matchEntire(content.trim())
        if (filename != null || fileRef != null) {
            val src = fileRef?.groupValues?.get(1) ?: filename
            if (fileRef == null && content.isNotBlank()) {
                notes += Note(
                    Severity.WARNING,
                    file,
                    request,
                    "Inline bytes for multipart file '$name' were not embedded. The file name is used as src.",
                )
            }
            parts += Part(name = name, src = src, contentType = partType)
        } else {
            parts += Part(name = name, text = content, contentType = partType)
        }
    }
    return Body.Multipart(parts)
}

private fun splitPart(text: String): Pair<String, String> {
    val marker = "\n\n"
    val index = text.indexOf(marker)
    if (index < 0) return text to ""
    return text.substring(0, index) to text.substring(index + marker.length)
}

private fun parameter(header: String, name: String): String? {
    val pattern = Regex("""(?:^|;)\s*""" + Regex.escape(name) + """\s*=\s*(?:"([^"]*)"|([^;\s]+))""")
    val match = pattern.find(header) ?: return null
    return match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { null }
}

private fun scriptNotes(
    file: String,
    request: String,
    line: Int,
    pre: List<ScriptSource>,
    response: List<ScriptSource>,
    notes: MutableList<Note>,
) {
    if (pre.isNotEmpty()) {
        notes += Note(
            Severity.WARNING,
            file,
            request,
            "Pre-request script was not translated and is included as comments.",
            line,
        )
    }
    if (response.isNotEmpty()) {
        notes += Note(
            Severity.WARNING,
            file,
            request,
            "Response handler script was not translated and is included as comments.",
            line,
        )
    }
}

private fun dynamicVariableNotes(
    file: String,
    request: String,
    line: Int,
    url: String,
    headers: List<Header>,
    body: Body,
    notes: MutableList<Note>,
) {
    val chunks = mutableListOf(url)
    headers.forEach { chunks += it.value }
    when (body) {
        is Body.Raw -> chunks += body.text
        is Body.GraphQl -> {
            chunks += body.query
            body.variables?.let { chunks += it }
        }
        is Body.UrlEncoded -> body.fields.forEach { chunks += it.second }
        is Body.Multipart -> body.parts.forEach { part -> part.text?.let { chunks += it } }
        is Body.FileRef -> chunks += body.src
        Body.None -> Unit
    }
    val found = Regex("\\{\\{\\$[^}]+}}").findAll(chunks.joinToString("\n")).map { it.value }.toSet()
    found.sorted().forEach { variable ->
        notes += Note(
            Severity.WARNING,
            file,
            request,
            "Dynamic variable '$variable' was left unchanged.",
            line,
        )
    }
}
