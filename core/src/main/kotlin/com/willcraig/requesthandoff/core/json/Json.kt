package com.willcraig.requesthandoff.core.json

class JsonParseException(message: String) : IllegalArgumentException(message)

sealed interface Jv {
    data class Obj(val fields: Map<String, Jv>) : Jv
    data class Arr(val items: List<Jv>) : Jv
    data class Str(val value: String) : Jv
    data class Num(val literal: String) : Jv
    data class Bool(val value: Boolean) : Jv
    data object Null : Jv
}

class ObjBuilder {
    private val fields = linkedMapOf<String, Jv>()

    fun put(key: String, value: Jv?) {
        if (value != null) fields[key] = value
    }

    fun put(key: String, value: String?) {
        if (value != null) fields[key] = Jv.Str(value)
    }

    fun put(key: String, value: Boolean) {
        fields[key] = Jv.Bool(value)
    }

    fun build(): Jv.Obj = Jv.Obj(fields.toMap(linkedMapOf()))
}

fun obj(build: ObjBuilder.() -> Unit): Jv.Obj = ObjBuilder().apply(build).build()

fun arr(items: List<Jv>): Jv.Arr = Jv.Arr(items)

fun render(value: Jv): String = buildString {
    write(value, 0)
    append('\n')
}

private fun StringBuilder.write(value: Jv, level: Int) {
    when (value) {
        is Jv.Obj -> {
            if (value.fields.isEmpty()) {
                append("{}")
                return
            }
            append("{\n")
            val entries = value.fields.entries.toList()
            entries.forEachIndexed { index, (key, child) ->
                indent(level + 1)
                append(quote(key))
                append(": ")
                write(child, level + 1)
                if (index != entries.lastIndex) append(',')
                append('\n')
            }
            indent(level)
            append('}')
        }
        is Jv.Arr -> {
            if (value.items.isEmpty()) {
                append("[]")
                return
            }
            append("[\n")
            value.items.forEachIndexed { index, child ->
                indent(level + 1)
                write(child, level + 1)
                if (index != value.items.lastIndex) append(',')
                append('\n')
            }
            indent(level)
            append(']')
        }
        is Jv.Str -> append(quote(value.value))
        is Jv.Num -> append(value.literal)
        is Jv.Bool -> append(if (value.value) "true" else "false")
        Jv.Null -> append("null")
    }
}

private fun StringBuilder.indent(level: Int) {
    repeat(level) { append("  ") }
}

fun quote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

fun parseJson(text: String): Jv {
    val parser = JsonParser(text.removePrefix("\uFEFF"))
    val value = parser.parseValue()
    parser.skipWs()
    if (!parser.eof()) {
        throw parser.error("Unexpected trailing content")
    }
    return value
}

private class JsonParser(private val text: String) {
    private var index = 0
    private var line = 1
    private var column = 1

    fun eof(): Boolean = index >= text.length

    fun parseValue(): Jv {
        skipWs()
        if (eof()) throw error("Unexpected end of JSON")
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Jv.Str(parseString())
            't' -> parseLiteral("true", Jv.Bool(true))
            'f' -> parseLiteral("false", Jv.Bool(false))
            'n' -> parseLiteral("null", Jv.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw error("Unexpected character '$ch'")
        }
    }

    fun skipWs() {
        while (!eof()) {
            when (peek()) {
                ' ', '\t', '\r', '\n' -> advance()
                else -> return
            }
        }
    }

    fun error(message: String): JsonParseException =
        JsonParseException("$message at line $line, column $column")

    private fun parseObject(): Jv.Obj {
        expect('{')
        val fields = linkedMapOf<String, Jv>()
        skipWs()
        if (consume('}')) return Jv.Obj(fields)
        while (true) {
            skipWs()
            if (eof() || peek() != '"') throw error("Expected an object key")
            val key = parseString()
            skipWs()
            expect(':')
            val value = parseValue()
            fields[key] = value
            skipWs()
            when {
                consume(',') -> continue
                consume('}') -> return Jv.Obj(fields)
                else -> throw error("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): Jv.Arr {
        expect('[')
        val items = mutableListOf<Jv>()
        skipWs()
        if (consume(']')) return Jv.Arr(items)
        while (true) {
            items += parseValue()
            skipWs()
            when {
                consume(',') -> continue
                consume(']') -> return Jv.Arr(items)
                else -> throw error("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!eof()) {
            val ch = advance()
            when (ch) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                else -> {
                    if (ch < ' ') throw error("Unescaped control character in string")
                    out.append(ch)
                }
            }
        }
        throw error("Unterminated string")
    }

    private fun parseEscape(): Char {
        if (eof()) throw error("Unterminated escape")
        return when (val ch = advance()) {
            '"', '\\', '/' -> ch
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = buildString {
                    repeat(4) {
                        if (eof()) throw error("Short unicode escape")
                        append(advance())
                    }
                }
                val code = hex.toIntOrNull(16) ?: throw error("Invalid unicode escape")
                code.toChar()
            }
            else -> throw error("Invalid escape '\\$ch'")
        }
    }

    private fun parseNumber(): Jv.Num {
        val start = index
        if (consume('-') && eof()) throw error("Invalid number")
        if (eof()) throw error("Invalid number")
        if (peek() == '0') {
            advance()
        } else if (peek() in '1'..'9') {
            while (!eof() && peek() in '0'..'9') advance()
        } else {
            throw error("Invalid number")
        }
        if (!eof() && peek() == '.') {
            advance()
            if (eof() || peek() !in '0'..'9') throw error("Invalid number")
            while (!eof() && peek() in '0'..'9') advance()
        }
        if (!eof() && (peek() == 'e' || peek() == 'E')) {
            advance()
            if (!eof() && (peek() == '+' || peek() == '-')) advance()
            if (eof() || peek() !in '0'..'9') throw error("Invalid number")
            while (!eof() && peek() in '0'..'9') advance()
        }
        return Jv.Num(text.substring(start, index))
    }

    private fun parseLiteral(literal: String, value: Jv): Jv {
        for (ch in literal) {
            if (eof() || advance() != ch) throw error("Expected '$literal'")
        }
        return value
    }

    private fun expect(ch: Char) {
        if (eof() || advance() != ch) throw error("Expected '$ch'")
    }

    private fun consume(ch: Char): Boolean {
        if (eof() || peek() != ch) return false
        advance()
        return true
    }

    private fun peek(): Char = text[index]

    private fun advance(): Char {
        val ch = text[index++]
        if (ch == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return ch
    }
}
