package com.willcraig.requesthandoff.core

/**
 * Rewrites JetBrains HTTP Client pre-request and response-handler JavaScript
 * into Postman `pm.*` script lines.
 *
 * Documented calls are rewritten. A call with no safe Postman equivalent is
 * left as a line comment, and its API name is returned so the export report
 * can list it. Standard JavaScript (`console.log`, `URLSearchParams`, `btoa`, `CryptoJS`)
 * is copied through. A local or parameter named `client`, `response`, or `request`
 * is still rewritten; this pass does not track JavaScript scope.
 */
internal data class ScriptTranslation(
    val lines: List<String>,
    val unsupportedApis: List<String>,
)

internal fun translateHttpClientScript(source: String): ScriptTranslation {
    val text = normalizeNewlines(source).trim('\n')
    if (text.isEmpty()) return ScriptTranslation(emptyList(), emptyList())
    val cursor = JsCursor(text)
    val spans = cursor.collectUnsupported()
    val commented = commentSpans(text, spans)
    val rewritten = JsCursor(commented).rewrite()
    val apis = mutableListOf<String>()
    spans.forEach { span ->
        if (span.name !in apis) apis += span.name
    }
    return ScriptTranslation(rewritten.split('\n'), apis)
}

fun resolveHttpScriptPath(httpRelativePath: String, scriptReference: String): String {
    val http = httpRelativePath.replace('\\', '/')
    val directory = http.substringBeforeLast('/', "")
    val parts = ArrayDeque<String>()
    if (directory.isNotEmpty()) directory.split('/').forEach { parts.addLast(it) }
    scriptReference.trim().replace('\\', '/').split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast() else parts.addLast("..")
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}

fun referencedHttpScripts(content: String): List<String> =
    parseHttpFile("request.http", content).requests
        .flatMap { it.preRequestScripts + it.responseScripts }
        .mapNotNull { it.file }
        .distinct()

/** Relative script paths can be inlined. Absolute, home, and drive paths cannot. */
fun httpScriptMayBeInlined(scriptReference: String): Boolean {
    val trimmed = scriptReference.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("~") || trimmed.startsWith("/") || trimmed.startsWith("\\")) {
        return false
    }
    return !trimmed.matches(Regex("^[A-Za-z]:.*"))
}

private data class Span(val start: Int, val end: Int, val name: String)

private enum class ApiKind {
    TEST,
    ASSERT,
    LOG,
    GLOBAL_SET,
    GLOBAL_GET,
    GLOBAL_CLEAR,
    GLOBAL_CLEAR_ALL,
    GLOBAL_IS_EMPTY,
    ENV_GET,
    FILE_GET,
    REQUEST_VAR_SET,
    REQUEST_VAR_GET,
    RESPONSE_STATUS,
    RESPONSE_BODY,
    HEADERS_VALUE_OF,
    HEADERS_VALUES_OF,
    CONTENT_TYPE_MIME,
    CONTENT_TYPE_CHARSET,
    REQ_HEADERS_FIND,
    REQ_BODY_CALL,
    REQ_BODY_RAW,
    REQ_BODY_SUBSTITUTED,
    REQ_URL_CALL,
    REQ_URL_RAW,
    REQ_URL_SUBSTITUTED,
    REQ_METHOD,
    REQ_ITERATION,
}

private data class Chain(val parts: List<String>, val ends: List<Int>)

private data class Classified(
    val consumed: Int,
    val kind: ApiKind?,
    val name: String,
    val requiresCall: Boolean,
)

private data class Matched(
    val kind: ApiKind?,
    val name: String,
    val prefixEnd: Int,
    val callOpen: Int,
    val callEnd: Int,
)

private val EXPR_KEYWORDS = setOf(
    "return", "throw", "case", "else", "do", "typeof", "void", "delete",
    "yield", "await", "new",
)

private val BETWEEN_KEYWORDS = setOf("in", "instanceof", "of")

private val SHELL_CALLS = setOf("exec", "execFile", "execSync", "execFileSync", "spawn", "spawnSync")

private val BODY_DOM_OR_STREAM = setOf(
    "onEachLine",
    "onEachMessage",
    "getElementsByTagName",
    "getElementsByName",
    "getElementById",
    "getElementsByClassName",
    "createElement",
    "cloneNode",
    "compareDocumentPosition",
    "contains",
    "getRootNode",
    "hasChildNodes",
    "insertBefore",
    "isDefaultNamespace",
    "isEqualNode",
    "isSameNode",
    "lookupNamespaceURI",
    "lookupPrefix",
    "normalize",
    "removeChild",
    "replaceChild",
)

private class JsCursor(private val text: String) {
    private var i = 0
    private var exprStart = true
    private val n = text.length

    fun collectUnsupported(): List<Span> {
        val spans = mutableListOf<Span>()
        walk(n, null, spans)
        return spans
    }

    fun rewrite(): String {
        val out = StringBuilder()
        walk(n, out, null)
        return out.toString()
    }

    private fun walk(until: Int, out: StringBuilder?, spans: MutableList<Span>?) {
        while (i < until) {
            val before = i
            if (copyTrivia(out)) continue
            if (i >= until) break
            if (atFreeIdent()) {
                val matched = matchAt(i)
                if (matched != null && matched.kind == null) {
                    val end = endOfChain(matched.prefixEnd).coerceAtMost(until)
                    spans?.add(Span(i, end, matched.name))
                    if (out != null) out.append(text, i, end)
                    i = end
                    exprStart = false
                    continue
                }
                if (matched != null && matched.kind != null && out != null) {
                    out.append(emit(matched))
                    val end = (if (matched.callEnd >= 0) matched.callEnd else matched.prefixEnd).coerceAtMost(until)
                    i = end
                    exprStart = false
                    continue
                }
                if (matched != null && out == null) {
                    i = matched.prefixEnd
                    exprStart = false
                    continue
                }
            }
            if (!copyToken(out, spans, until)) {
                if (out != null && i < n) out.append(text[i])
                if (i < n) i++
            }
            if (i == before) i++
        }
    }

    private fun matchAt(start: Int): Matched? {
        val chain = readChain(start) ?: return null
        val classified = classify(chain.parts) ?: return null
        val prefixEnd = chain.ends[classified.consumed - 1]
        val call = findCall(prefixEnd)
        if (classified.requiresCall && call == null) {
            if (classified.kind == null) return null
            return Matched(null, classified.name, prefixEnd, -1, -1)
        }
        if (classified.kind == null) {
            return Matched(null, classified.name, prefixEnd, call?.first ?: -1, call?.second ?: -1)
        }
        if (!classified.requiresCall && call != null && classified.kind in PROPERTY_KINDS) {
            return Matched(null, classified.name, prefixEnd, call.first, call.second)
        }
        return Matched(classified.kind, classified.name, prefixEnd, call?.first ?: -1, call?.second ?: -1)
    }

    private fun emit(matched: Matched): String {
        val kind = matched.kind ?: return ""
        val rawArgs = if (matched.callOpen >= 0 && matched.callEnd > matched.callOpen) {
            text.substring(matched.callOpen + 1, matched.callEnd - 1)
        } else {
            null
        }
        val args = rawArgs?.let { rewriteArgs(it) }
        return when (kind) {
            ApiKind.TEST -> if (args == null) "pm.test" else "pm.test($args)"
            ApiKind.LOG -> if (args == null) "console.log" else "console.log($args)"
            ApiKind.ASSERT -> assertCall(rawArgs.orEmpty())
            ApiKind.GLOBAL_SET -> "pm.globals.set($args)"
            ApiKind.GLOBAL_GET -> "pm.globals.get($args)"
            ApiKind.GLOBAL_CLEAR -> "pm.globals.unset($args)"
            ApiKind.GLOBAL_CLEAR_ALL -> "pm.globals.clear()"
            ApiKind.GLOBAL_IS_EMPTY -> "(Object.keys(pm.globals.toObject()).length === 0)"
            ApiKind.ENV_GET -> "pm.environment.get($args)"
            ApiKind.FILE_GET -> "pm.collectionVariables.get($args)"
            ApiKind.REQUEST_VAR_SET -> "pm.variables.set($args)"
            ApiKind.REQUEST_VAR_GET -> "pm.variables.get($args)"
            ApiKind.RESPONSE_STATUS -> "pm.response.code"
            ApiKind.RESPONSE_BODY -> if (followedByMember(matched.prefixEnd)) "pm.response.json()" else "pm.response.text()"
            ApiKind.HEADERS_VALUE_OF -> "pm.response.headers.get($args)"
            ApiKind.HEADERS_VALUES_OF -> valuesOf(rawArgs.orEmpty())
            ApiKind.CONTENT_TYPE_MIME ->
                "(pm.response.headers.get(\"Content-Type\") || \"\").split(\";\")[0].trim()"
            ApiKind.CONTENT_TYPE_CHARSET ->
                "((pm.response.headers.get(\"Content-Type\") || \"\").match(/charset\\s*=\\s*\"?([^\";]+)\"?/i) || [null, null])[1]"
            ApiKind.REQ_HEADERS_FIND -> "pm.request.headers.get($args)"
            ApiKind.REQ_BODY_RAW ->
                "(pm.request.body ? pm.request.body.toString() : \"\")"
            ApiKind.REQ_BODY_CALL, ApiKind.REQ_BODY_SUBSTITUTED ->
                "pm.variables.replaceIn(pm.request.body ? pm.request.body.toString() : \"\")"
            ApiKind.REQ_URL_RAW -> "pm.request.url.toString()"
            ApiKind.REQ_URL_CALL, ApiKind.REQ_URL_SUBSTITUTED ->
                "pm.variables.replaceIn(pm.request.url.toString())"
            ApiKind.REQ_METHOD -> "pm.request.method"
            ApiKind.REQ_ITERATION -> "pm.info.iteration"
        }
    }

    private fun rewriteArgs(raw: String): String = JsCursor(raw).rewrite()

    private fun assertCall(rawArgs: String): String {
        val pieces = splitArgs(rawArgs).map { JsCursor(it).rewrite() }
        val rendered = if (pieces.isEmpty()) "undefined" else pieces.joinToString(", ")
        return "pm.expect($rendered).to.eql(true)"
    }

    private fun valuesOf(rawArgs: String): String {
        val name = splitArgs(rawArgs).firstOrNull()?.let { JsCursor(it).rewrite() } ?: "undefined"
        return "pm.response.headers.filter(function (header) { return header.key.toLowerCase() === String($name).toLowerCase(); }).map(function (header) { return header.value; })"
    }

    private fun followedByMember(index: Int): Boolean {
        val j = skipTriviaFrom(index)
        if (j >= n) return false
        if (text[j] == '[' || text[j] == '.') return true
        return j + 1 < n && text[j] == '?' && text[j + 1] == '.'
    }

    private fun readChain(start: Int): Chain? {
        if (!isIdentStart(text[start])) return null
        val parts = mutableListOf<String>()
        val ends = mutableListOf<Int>()
        var cursor = start
        while (cursor < n && isIdentStart(text[cursor])) {
            val end = identEnd(cursor)
            parts += text.substring(cursor, end)
            ends += end
            val after = skipTriviaFrom(end)
            val dot = dotEnd(after)
            if (dot == null) break
            val next = skipTriviaFrom(dot)
            if (next >= n || !isIdentStart(text[next])) break
            cursor = next
        }
        if (parts.isEmpty()) return null
        return Chain(parts, ends)
    }

    private fun findCall(afterPrefix: Int): Pair<Int, Int>? {
        val open = skipTriviaFrom(afterPrefix)
        if (open >= n || text[open] != '(') return null
        val end = endOfDelimited(open, '(', ')')
        return if (end > open) open to end else null
    }

    private fun endOfChain(afterPrefix: Int): Int {
        var cursor = afterPrefix
        while (true) {
            val j = skipTriviaFrom(cursor)
            val dot = dotEnd(j)
            if (dot != null) {
                val next = skipTriviaFrom(dot)
                if (next < n && isIdentStart(text[next])) {
                    cursor = identEnd(next)
                    continue
                }
                break
            }
            if (j < n && text[j] == '(') {
                cursor = endOfDelimited(j, '(', ')')
                continue
            }
            if (j < n && text[j] == '[') {
                cursor = endOfDelimited(j, '[', ']')
                continue
            }
            break
        }
        return cursor
    }

    private fun atFreeIdent(): Boolean {
        if (i >= n || !isIdentStart(text[i])) return false
        if (i == 0) return true
        val previous = text[i - 1]
        return !isIdentPart(previous) && previous != '.'
    }

    private fun copyTrivia(out: StringBuilder?): Boolean {
        if (i >= n) return false
        if (text[i].isWhitespace()) {
            val start = i
            while (i < n && text[i].isWhitespace()) i++
            out?.append(text, start, i)
            return true
        }
        if (i + 1 < n && text[i] == '/' && (text[i + 1] == '/' || text[i + 1] == '*')) {
            val start = i
            i = endOfComment(i)
            out?.append(text, start, i)
            return true
        }
        return false
    }

    private fun copyToken(out: StringBuilder?, spans: MutableList<Span>?, until: Int): Boolean {
        if (i >= n) return false
        val start = i
        if (text[i] == '\'' || text[i] == '"') {
            i = endOfString(i)
            out?.append(text, start, i)
            exprStart = false
            return true
        }
        if (text[i] == '`') {
            copyTemplate(out, spans, until)
            exprStart = false
            return true
        }
        if (text[i] == '/' && exprStart) {
            i = endOfRegex(i)
            out?.append(text, start, i)
            exprStart = false
            return true
        }
        if (isIdentStart(text[i])) {
            val end = identEnd(i)
            val name = text.substring(i, end)
            out?.append(name)
            i = end
            exprStart = name in BETWEEN_KEYWORDS || (exprStart && name in EXPR_KEYWORDS)
            return true
        }
        if (text[i].isDigit() || (text[i] == '.' && i + 1 < n && text[i + 1].isDigit() && exprStart)) {
            i = endOfNumber(i)
            out?.append(text, start, i)
            exprStart = false
            return true
        }
        val two = if (i + 1 < n) text.substring(i, i + 2) else ""
        if (two == "==" || two == "!=" || two == "<=" || two == ">=" || two == "&&" || two == "||" ||
            two == "??" || two == "+=" || two == "-=" || two == "*=" || two == "%=" || two == "&=" ||
            two == "|=" || two == "^=" || two == "=>"
        ) {
            out?.append(two)
            i += 2
            if (two == "==" && i < n && text[i] == '=') {
                out?.append('=')
                i++
            }
            if (two == "!=" && i < n && text[i] == '=') {
                out?.append('=')
                i++
            }
            exprStart = true
            return true
        }
        if ((two == "++" || two == "--")) {
            out?.append(two)
            i += 2
            if (exprStart) {
                exprStart = true
            }
            return true
        }
        val c = text[i]
        out?.append(c)
        i++
        exprStart = when (c) {
            '(', '[', '{', ',', ';', '?', ':', '=', '!', '~', '+', '-', '*', '%', '<', '>', '&', '|', '^' -> true
            else -> false
        }
        return true
    }

    private fun copyTemplate(out: StringBuilder?, spans: MutableList<Span>?, until: Int) {
        out?.append('`')
        i++
        while (i < n && i < until) {
            if (text[i] == '\\') {
                out?.append(text[i])
                i++
                if (i < n) {
                    out?.append(text[i])
                    i++
                }
                continue
            }
            if (text[i] == '`') {
                out?.append('`')
                i++
                return
            }
            if (text[i] == '$' && i + 1 < n && text[i + 1] == '{') {
                out?.append("\${")
                i += 2
                val exprEnd = endOfBrace(i)
                val saved = exprStart
                exprStart = true
                val nestedOut = if (out == null) null else StringBuilder()
                walk(exprEnd, nestedOut, spans)
                out?.append(nestedOut)
                i = exprEnd
                exprStart = saved
                if (i < n && text[i] == '}') {
                    out?.append('}')
                    i++
                }
                continue
            }
            out?.append(text[i])
            i++
        }
    }

    private fun skipTriviaFrom(index: Int): Int {
        var cursor = index
        while (cursor < n) {
            if (text[cursor].isWhitespace()) {
                cursor++
                continue
            }
            if (cursor + 1 < n && text[cursor] == '/' && (text[cursor + 1] == '/' || text[cursor + 1] == '*')) {
                cursor = endOfComment(cursor)
                continue
            }
            break
        }
        return cursor
    }

    private fun dotEnd(index: Int): Int? {
        if (index >= n) return null
        if (text[index] == '.') return index + 1
        if (index + 1 < n && text[index] == '?' && text[index + 1] == '.') return index + 2
        return null
    }

    private fun identEnd(start: Int): Int {
        var cursor = start + 1
        while (cursor < n && isIdentPart(text[cursor])) cursor++
        return cursor
    }

    fun endOfComment(start: Int): Int {
        if (start + 1 < n && text[start + 1] == '/') {
            val nl = text.indexOf('\n', start)
            return if (nl < 0) n else nl
        }
        val close = text.indexOf("*/", start + 2)
        return if (close < 0) n else close + 2
    }

    fun endOfString(start: Int): Int {
        val quote = text[start]
        var cursor = start + 1
        while (cursor < n) {
            if (text[cursor] == '\\') {
                cursor += 2
                continue
            }
            if (text[cursor] == quote) return cursor + 1
            if (text[cursor] == '\n') return cursor
            cursor++
        }
        return n
    }

    fun endOfRegex(start: Int): Int {
        var cursor = start + 1
        var inClass = false
        while (cursor < n) {
            val c = text[cursor]
            if (c == '\\') {
                cursor += 2
                continue
            }
            if (c == '[') inClass = true
            else if (c == ']' && inClass) inClass = false
            else if (c == '/' && !inClass) {
                cursor++
                while (cursor < n && text[cursor].isLetter()) cursor++
                return cursor
            } else if (c == '\n') {
                return cursor
            }
            cursor++
        }
        return n
    }

    private fun endOfNumber(start: Int): Int {
        var cursor = start
        if (text[cursor] == '.') cursor++
        while (cursor < n && (text[cursor].isDigit() || text[cursor] == '_' || text[cursor] == '.' ||
                text[cursor] == 'e' || text[cursor] == 'E' || text[cursor] == 'x' || text[cursor] == 'X' ||
                text[cursor] == 'n' || text[cursor].isLetter())
        ) {
            cursor++
        }
        return cursor
    }

    private fun endOfDelimited(openIndex: Int, open: Char, close: Char): Int {
        var cursor = openIndex + 1
        var depth = 1
        while (cursor < n && depth > 0) {
            val c = text[cursor]
            if (c == '/' && cursor + 1 < n && (text[cursor + 1] == '/' || text[cursor + 1] == '*')) {
                cursor = endOfComment(cursor)
                continue
            }
            if (c == '\'' || c == '"') {
                cursor = endOfString(cursor)
                continue
            }
            if (c == '`') {
                cursor = endOfTemplateOpaque(cursor)
                continue
            }
            if (c == '/' && cursor + 1 < n && text[cursor + 1] != '/' && text[cursor + 1] != '*' &&
                previousAllowsRegex(text, cursor)
            ) {
                cursor = endOfRegex(cursor)
                continue
            }
            if (c == open) depth++
            else if (c == close) depth--
            cursor++
        }
        return cursor.coerceAtMost(n)
    }

    fun endOfTemplateOpaque(start: Int): Int {
        var cursor = start + 1
        while (cursor < n) {
            if (text[cursor] == '\\') {
                cursor += 2
                continue
            }
            if (text[cursor] == '`') return cursor + 1
            if (text[cursor] == '$' && cursor + 1 < n && text[cursor + 1] == '{') {
                cursor = endOfBrace(cursor + 2)
                if (cursor < n && text[cursor] == '}') cursor++
                continue
            }
            cursor++
        }
        return n
    }

    private fun endOfBrace(start: Int): Int {
        var cursor = start
        var depth = 1
        while (cursor < n && depth > 0) {
            val c = text[cursor]
            if (c == '/' && cursor + 1 < n && (text[cursor + 1] == '/' || text[cursor + 1] == '*')) {
                cursor = endOfComment(cursor)
                continue
            }
            if (c == '\'' || c == '"') {
                cursor = endOfString(cursor)
                continue
            }
            if (c == '`') {
                cursor = endOfTemplateOpaque(cursor)
                continue
            }
            if (c == '/' && cursor + 1 < n && text[cursor + 1] != '/' && text[cursor + 1] != '*' &&
                previousAllowsRegex(text, cursor)
            ) {
                cursor = endOfRegex(cursor)
                continue
            }
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return cursor
            }
            cursor++
        }
        return cursor.coerceAtMost(n)
    }
}

private val PROPERTY_KINDS = setOf(
    ApiKind.RESPONSE_STATUS,
    ApiKind.RESPONSE_BODY,
    ApiKind.CONTENT_TYPE_MIME,
    ApiKind.CONTENT_TYPE_CHARSET,
    ApiKind.REQ_METHOD,
)

private fun classify(parts: List<String>): Classified? {
    if (parts.isEmpty()) return null
    return when (parts[0]) {
        "client" -> classifyClient(parts)
        "response" -> classifyResponse(parts)
        "request" -> classifyRequest(parts)
        "crypto", "jwt", "DOMParser" ->
            Classified(parts.size, null, parts.joinToString(".").take(120), requiresCall = false)
        "jsonPath" -> Classified(1, null, "jsonPath", requiresCall = true)
        in SHELL_CALLS -> Classified(1, null, parts[0], requiresCall = true)
        else -> null
    }
}

private fun classifyClient(parts: List<String>): Classified? {
    if (parts.size == 1) return null
    if (parts.size > 2 && parts[1] !in setOf("global", "variables", "test", "assert", "log", "exit")) {
        return unsupported(parts, 2)
    }
    return when (parts[1]) {
        "test" -> if (parts.size == 2) call(ApiKind.TEST, "client.test", optional = true) else unsupported(parts, 2)
        "assert" -> if (parts.size == 2) call(ApiKind.ASSERT, "client.assert") else unsupported(parts, 2)
        "log" -> if (parts.size == 2) call(ApiKind.LOG, "client.log", optional = true) else unsupported(parts, 2)
        "exit" -> unsupported(parts, 2)
        "global" -> classifyGlobal(parts, start = 2, prefix = "client.global")
        "variables" -> classifyVariables(parts)
        else -> unsupported(parts, 2)
    }
}

private fun classifyVariables(parts: List<String>): Classified {
    if (parts.size < 3) return unsupported(parts, parts.size)
    return when (parts[2]) {
        "global" -> classifyGlobal(parts, start = 3, prefix = "client.variables.global")
        "environment" -> oneMethod(parts, 3, "client.variables.environment", "get", ApiKind.ENV_GET)
        "file" -> oneMethod(parts, 3, "client.variables.file", "get", ApiKind.FILE_GET)
        "request" -> requestVars(parts, 3, "client.variables.request")
        else -> unsupported(parts, 3)
    }
}

private fun classifyGlobal(parts: List<String>, start: Int, prefix: String): Classified {
    if (parts.size <= start) return Classified(parts.size, null, prefix, requiresCall = false)
    if (parts[start] == "headers") {
        val name = if (parts.size > start + 1) "$prefix.headers.${parts[start + 1]}" else "$prefix.headers"
        return Classified(minOf(parts.size, start + 2), null, name, requiresCall = false)
    }
    if (parts.size != start + 1) return unsupported(parts, start + 1)
    return when (parts[start]) {
        "set" -> call(ApiKind.GLOBAL_SET, "$prefix.set")
        "get" -> call(ApiKind.GLOBAL_GET, "$prefix.get")
        "clear" -> call(ApiKind.GLOBAL_CLEAR, "$prefix.clear")
        "clearAll" -> call(ApiKind.GLOBAL_CLEAR_ALL, "$prefix.clearAll")
        "isEmpty" -> call(ApiKind.GLOBAL_IS_EMPTY, "$prefix.isEmpty")
        else -> unsupported(parts, start + 1)
    }
}

private fun oneMethod(parts: List<String>, methodIndex: Int, prefix: String, method: String, kind: ApiKind): Classified {
    if (parts.size <= methodIndex) return Classified(parts.size, null, prefix, requiresCall = false)
    if (parts.size != methodIndex + 1 || parts[methodIndex] != method) return unsupported(parts, methodIndex + 1)
    return call(kind, "$prefix.$method")
}

private fun requestVars(parts: List<String>, methodIndex: Int, prefix: String): Classified {
    if (parts.size <= methodIndex) return Classified(parts.size, null, prefix, requiresCall = false)
    if (parts.size != methodIndex + 1) return unsupported(parts, methodIndex + 1)
    return when (parts[methodIndex]) {
        "set" -> call(ApiKind.REQUEST_VAR_SET, "$prefix.set")
        "get" -> call(ApiKind.REQUEST_VAR_GET, "$prefix.get")
        else -> unsupported(parts, methodIndex + 1)
    }
}

private fun classifyResponse(parts: List<String>): Classified? {
    if (parts.size == 1) return null
    return when (parts[1]) {
        "status" -> Classified(2, ApiKind.RESPONSE_STATUS, "response.status", false)
        "body" -> classifyBody(parts)
        "headers" -> classifyHeaders(parts)
        "contentType" -> classifyContentType(parts)
        "cookies" -> Classified(minOf(parts.size, 2), null, "response.cookies", false)
        "cookiesByName" -> Classified(minOf(parts.size, 2), null, "response.cookiesByName", false)
        else -> unsupported(parts, 2)
    }
}

private fun classifyBody(parts: List<String>): Classified {
    if (parts.size >= 3 && parts[2] in BODY_DOM_OR_STREAM) {
        return Classified(minOf(parts.size, 3), null, "response.body.${parts[2]}", requiresCall = false)
    }
    return Classified(2, ApiKind.RESPONSE_BODY, "response.body", requiresCall = false)
}

private fun classifyHeaders(parts: List<String>): Classified {
    if (parts.size >= 3 && parts.size == 3 && parts[2] == "valueOf") {
        return call(ApiKind.HEADERS_VALUE_OF, "response.headers.valueOf")
    }
    if (parts.size >= 3 && parts.size == 3 && parts[2] == "valuesOf") {
        return call(ApiKind.HEADERS_VALUES_OF, "response.headers.valuesOf")
    }
    val name = if (parts.size >= 3) "response.headers.${parts[2]}" else "response.headers"
    return Classified(minOf(parts.size, 3), null, name, requiresCall = false)
}

private fun classifyContentType(parts: List<String>): Classified {
    if (parts.size >= 3 && parts[2] == "mimeType") {
        return Classified(3, ApiKind.CONTENT_TYPE_MIME, "response.contentType.mimeType", false)
    }
    if (parts.size >= 3 && parts[2] == "charset") {
        return Classified(3, ApiKind.CONTENT_TYPE_CHARSET, "response.contentType.charset", false)
    }
    val name = if (parts.size >= 3) "response.contentType.${parts[2]}" else "response.contentType"
    return Classified(minOf(parts.size, 3), null, name, requiresCall = false)
}

private fun classifyRequest(parts: List<String>): Classified? {
    if (parts.size == 1) return null
    return when (parts[1]) {
        "environment" -> oneMethod(parts, 2, "request.environment", "get", ApiKind.ENV_GET)
        "variables" -> requestVars(parts, 2, "request.variables")
        "headers" -> classifyRequestHeaders(parts)
        "body" -> classifyRawAccess(parts, "body", ApiKind.REQ_BODY_CALL, ApiKind.REQ_BODY_RAW, ApiKind.REQ_BODY_SUBSTITUTED)
        "url" -> classifyRawAccess(parts, "url", ApiKind.REQ_URL_CALL, ApiKind.REQ_URL_RAW, ApiKind.REQ_URL_SUBSTITUTED)
        "method" -> Classified(2, ApiKind.REQ_METHOD, "request.method", false)
        "iteration" -> if (parts.size == 2) call(ApiKind.REQ_ITERATION, "request.iteration") else unsupported(parts, 2)
        "templateValue" -> Classified(minOf(parts.size, 2), null, "request.templateValue", false)
        else -> unsupported(parts, 2)
    }
}

private fun classifyRequestHeaders(parts: List<String>): Classified {
    if (parts.size == 3 && parts[2] == "findByName") return call(ApiKind.REQ_HEADERS_FIND, "request.headers.findByName")
    val name = if (parts.size >= 3) "request.headers.${parts[2]}" else "request.headers"
    return Classified(minOf(parts.size, 3), null, name, requiresCall = false)
}

private fun classifyRawAccess(
    parts: List<String>,
    member: String,
    callKind: ApiKind,
    rawKind: ApiKind,
    substitutedKind: ApiKind,
): Classified {
    if (parts.size == 2) return call(callKind, "request.$member")
    if (parts.size == 3 && parts[2] == "getRaw") return call(rawKind, "request.$member.getRaw")
    if (parts.size == 3 && parts[2] == "tryGetSubstituted") return call(substitutedKind, "request.$member.tryGetSubstituted")
    return unsupported(parts, 3)
}

private fun call(kind: ApiKind, name: String, optional: Boolean = false): Classified =
    Classified(name.count { it == '.' } + 1, kind, name, requiresCall = !optional)

private fun unsupported(parts: List<String>, consumed: Int): Classified {
    val count = consumed.coerceIn(1, parts.size)
    return Classified(count, null, parts.take(count).joinToString("."), requiresCall = false)
}

private fun splitArgs(args: String): List<String> {
    if (args.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    val buf = StringBuilder()
    val cursor = JsCursor(args)
    var paren = 0
    var brace = 0
    var bracket = 0
    var index = 0
    while (index < args.length) {
        val c = args[index]
        if (c == '/' && index + 1 < args.length && (args[index + 1] == '/' || args[index + 1] == '*')) {
            val end = cursor.endOfCommentPublic(index, args)
            buf.append(args, index, end)
            index = end
            continue
        }
        if (c == '\'' || c == '"') {
            val end = cursor.endOfStringPublic(index, args)
            buf.append(args, index, end)
            index = end
            continue
        }
        if (c == '`') {
            val end = cursor.endOfTemplatePublic(index, args)
            buf.append(args, index, end)
            index = end
            continue
        }
        if (c == '/' && index + 1 < args.length && args[index + 1] != '/' && args[index + 1] != '*' &&
            previousAllowsRegex(args, index)
        ) {
            val end = cursor.endOfRegexPublic(index, args)
            buf.append(args, index, end)
            index = end
            continue
        }
        when (c) {
            '(' -> paren++
            ')' -> paren--
            '{' -> brace++
            '}' -> brace--
            '[' -> bracket++
            ']' -> bracket--
            ',' -> if (paren == 0 && brace == 0 && bracket == 0) {
                parts += buf.toString()
                buf.clear()
                index++
                continue
            }
        }
        buf.append(c)
        index++
    }
    parts += buf.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun JsCursor.endOfCommentPublic(start: Int, source: String): Int = JsCursor(source).endOfComment(start)
private fun JsCursor.endOfStringPublic(start: Int, source: String): Int = JsCursor(source).endOfString(start)
private fun JsCursor.endOfTemplatePublic(start: Int, source: String): Int = JsCursor(source).endOfTemplateOpaque(start)
private fun JsCursor.endOfRegexPublic(start: Int, source: String): Int = JsCursor(source).endOfRegex(start)

private fun previousAllowsRegex(source: String, slash: Int): Boolean {
    val previous = skipBackwardTrivia(source, slash - 1)
    if (previous < 0) return true
    val c = source[previous]
    if (c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`') return false
    if (isIdentPart(c) || c.isDigit()) {
        var start = previous
        while (start > 0 && isIdentPart(source[start - 1])) start--
        val word = source.substring(start, previous + 1)
        return word in EXPR_KEYWORDS || word in BETWEEN_KEYWORDS
    }
    return c == '(' || c == '[' || c == '{' || c == ',' || c == ';' || c == '?' || c == ':' ||
        c == '=' || c == '!' || c == '~' || c == '+' || c == '-' || c == '*' || c == '%' ||
        c == '<' || c == '>' || c == '&' || c == '|' || c == '^'
}

private fun skipBackwardTrivia(source: String, start: Int): Int {
    var cursor = start
    while (cursor >= 0) {
        val c = source[cursor]
        if (c.isWhitespace()) {
            cursor--
            continue
        }
        if (c == '/' && cursor > 0 && source[cursor - 1] == '*') {
            val open = source.lastIndexOf("/*", (cursor - 2).coerceAtLeast(0))
            cursor = if (open < 0) -1 else open - 1
            continue
        }
        val lineStart = source.lastIndexOf('\n', cursor).let { if (it < 0) 0 else it + 1 }
        val comment = lineCommentStart(source, lineStart, cursor)
        if (comment >= 0 && comment < cursor) {
            cursor = comment - 1
            continue
        }
        return cursor
    }
    return -1
}

private fun lineCommentStart(source: String, lineStart: Int, index: Int): Int {
    var i = lineStart
    while (i < index) {
        val c = source[i]
        if (c == '\'' || c == '"') {
            val quote = c
            i++
            while (i < index && source[i] != quote) {
                if (source[i] == '\\') i += 2 else i++
            }
            if (i < index) i++
            continue
        }
        if (c == '/' && i + 1 < source.length && source[i + 1] == '/') return i
        i++
    }
    return -1
}

private fun commentSpans(source: String, spans: List<Span>): String {
    if (spans.isEmpty()) return source
    val starts = mutableListOf(0)
    source.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
    val names = Array(starts.size) { mutableListOf<String>() }
    spans.forEach { span ->
        if (span.end <= span.start) return@forEach
        val from = lineIndex(starts, span.start)
        val to = lineIndex(starts, span.end - 1)
        for (line in from..to) {
            if (span.name !in names[line]) names[line] += span.name
        }
    }
    val lines = source.split('\n')
    return lines.mapIndexed { index, line ->
        val apis = names[index]
        if (apis.isEmpty()) line else commentLine(line, apis)
    }.joinToString("\n")
}

private fun lineIndex(starts: List<Int>, index: Int): Int {
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (starts[mid] <= index) low = mid else high = mid - 1
    }
    return low
}

private fun commentLine(line: String, apis: List<String>): String {
    if (line.isBlank()) return "//"
    return "// Unsupported HTTP Client API ${apis.joinToString(", ")}: $line"
}

private fun isIdentStart(c: Char): Boolean = c == '$' || c == '_' || c.isLetter()

private fun isIdentPart(c: Char): Boolean = c == '$' || c == '_' || c.isLetterOrDigit()
