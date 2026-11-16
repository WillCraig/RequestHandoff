package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import com.willcraig.requesthandoff.core.json.render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.willcraig.requesthandoff.core.json.JsonParseException

class JsonTest {
    @Test
    fun roundTripsObjectsArraysAndEscapes() {
        val parsed = parseJson("""{"b":"a\"b\\c","n":1,"ok":true,"z":null,"arr":["x",{"k":"v"}]}""")
        val again = parseJson(render(parsed))
        assertEquals(parsed, again)
        val objectValue = parsed as Jv.Obj
        assertEquals(Jv.Str("a\"b\\c"), objectValue.fields.getValue("b"))
        assertEquals(Jv.Num("1"), objectValue.fields.getValue("n"))
    }

    @Test
    fun rejectsTrailingComma() {
        assertFailsWith<JsonParseException> { parseJson("""{"a":1,}""") }
    }
}
