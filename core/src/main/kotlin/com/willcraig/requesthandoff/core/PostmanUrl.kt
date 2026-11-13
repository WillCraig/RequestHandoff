package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.arr
import com.willcraig.requesthandoff.core.json.obj

internal fun postmanUrl(raw: String): Jv.Obj {
    val (beforeHash, hash) = splitOnceOutside(raw, '#')
    val (base, query) = splitOnceOutside(beforeHash, '?')
    val (protocol, rest) = splitProtocol(base)
    val (hostPort, pathText) = splitHost(rest)
    val (host, port) = splitPort(hostPort)
    val path = pathText.split('/').filter { it.isNotEmpty() }
    val queryItems = if (query == null) emptyList() else parseQuery(query)
    return obj {
        put("raw", raw)
        put("protocol", protocol)
        if (host.isNotEmpty()) put("host", arr(host.map { Jv.Str(it) }))
        if (path.isNotEmpty()) put("path", arr(path.map { Jv.Str(it) }))
        put("port", port)
        if (queryItems.isNotEmpty()) {
            put(
                "query",
                arr(queryItems.map { (key, value) ->
                    obj {
                        put("key", key)
                        if (value != null) put("value", value) else put("value", Jv.Null)
                    }
                }),
            )
        }
        put("hash", hash)
    }
}

private fun splitProtocol(base: String): Pair<String?, String> {
    val marker = "://"
    val index = indexOutside(base, marker)
    if (index < 0) return null to base
    val protocol = base.substring(0, index)
    if (protocol.isEmpty() || protocol.any { it.isWhitespace() }) return null to base
    return protocol.lowercase() to base.substring(index + marker.length)
}

private fun splitHost(rest: String): Pair<String, String> {
    if (rest.isEmpty() || rest.startsWith("/")) return "" to rest
    val slash = if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end >= 0) rest.indexOf('/', end + 1) else indexOutside(rest, "/")
    } else {
        indexOutside(rest, "/")
    }
    if (slash < 0) return rest to ""
    return rest.substring(0, slash) to rest.substring(slash)
}

private fun splitPort(hostPort: String): Pair<List<String>, String?> {
    if (hostPort.isEmpty()) return emptyList<String>() to null
    val colon = hostPort.lastIndexOf(':')
    if (colon > 0 && !hostPort.substring(0, colon).contains("}}")) {
        val port = hostPort.substring(colon + 1)
        if (port.isNotEmpty() && port.all { it.isDigit() } && indexOutside(hostPort.substring(0, colon), ":") < 0) {
            return splitHostName(hostPort.substring(0, colon)) to port
        }
    }
    // A variable such as {{host}} can contain a colon. Only split a numeric port
    // that sits outside {{ }}.
    val outside = splitOnceOutsideFromEnd(hostPort, ':')
    if (outside != null) {
        val (host, port) = outside
        if (port.isNotEmpty() && port.all { it.isDigit() }) {
            return splitHostName(host) to port
        }
    }
    return splitHostName(hostPort) to null
}

private fun splitHostName(host: String): List<String> {
    val bare = if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.lastIndex) else host
    if (bare.isEmpty()) return emptyList()
    if ("{{" in bare) return splitOutside(bare, '.').filter { it.isNotEmpty() }
    if (':' in bare) return listOf(bare)
    return bare.split('.').filter { it.isNotEmpty() }
}

private fun parseQuery(query: String): List<Pair<String, String?>> =
    splitOutside(query, '&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val (key, value) = splitOnceOutside(part, '=')
        if (key.isEmpty()) return@mapNotNull null
        key to value
    }

private fun indexOutside(input: String, token: String): Int {
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
        if (depth == 0 && input.startsWith(token, i)) return i
        i++
    }
    return -1
}

private fun splitOnceOutsideFromEnd(input: String, delimiter: Char): Pair<String, String>? {
    var depth = 0
    var i = input.length - 1
    while (i >= 0) {
        if (i > 0 && input[i] == '}' && input[i - 1] == '}') {
            depth++
            i -= 2
            continue
        }
        if (i > 0 && input[i] == '{' && input[i - 1] == '{') {
            depth = (depth - 1).coerceAtLeast(0)
            i -= 2
            continue
        }
        if (depth == 0 && input[i] == delimiter) {
            return input.substring(0, i) to input.substring(i + 1)
        }
        i--
    }
    return null
}
