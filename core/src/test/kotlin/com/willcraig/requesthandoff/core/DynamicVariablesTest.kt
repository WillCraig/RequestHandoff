package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DynamicVariablesTest {
    @Test
    fun mapsUuidTimestampIsoTimestampRandomIntAndEmail() {
        val rewritten = rewriteDynamicVariables(
            "{{${'$'}uuid}} {{${'$'}random.uuid}} {{${'$'}random.internet.uuid}} " +
                "{{${'$'}timestamp}} {{${'$'}isoTimestamp}} {{${'$'}randomInt}} {{${'$'}random.integer}} " +
                "{{${'$'}random.integer()}} {{${'$'}random.email}} {{${'$'}random.internet.emailAddress}} " +
                "{{${'$'}random.name.firstName}} {{${'$'}random.bool.bool}}",
        )
        assertEquals(
            "{{${'$'}guid}} {{${'$'}guid}} {{${'$'}guid}} " +
                "{{${'$'}timestamp}} {{${'$'}isoTimestamp}} {{${'$'}randomInt}} {{${'$'}randomInt}} " +
                "{{${'$'}randomInt}} {{${'$'}randomEmail}} {{${'$'}randomEmail}} " +
                "{{${'$'}randomFirstName}} {{${'$'}randomBoolean}}",
            rewritten.text,
        )
        assertTrue(rewritten.warnings.isEmpty())
    }

    @Test
    fun warnsWhenRandomIntegerRangeIsDroppedAndLeavesOtherCallsUnchanged() {
        val rewritten = rewriteDynamicVariables(
            "{{${'$'}random.integer(10, 500)}} {{${'$'}random.integer(10)}} " +
                "{{${'$'}random.alphabetic(8)}} {{${'$'}random.alphanumeric(4)}} " +
                "{{${'$'}random.hexadecimal(6)}} {{${'$'}random.float(1.5, 2)}} " +
                "{{${'$'}exampleServer}} {{${'$'}random.address.fullAddress}} {{user}}",
        )
        assertTrue(rewritten.text.contains("{{${'$'}randomInt}}"))
        assertTrue(rewritten.text.contains("{{${'$'}random.alphabetic(8)}}"))
        assertTrue(rewritten.text.contains("{{${'$'}random.alphanumeric(4)}}"))
        assertTrue(rewritten.text.contains("{{${'$'}random.hexadecimal(6)}}"))
        assertTrue(rewritten.text.contains("{{${'$'}random.float(1.5, 2)}}"))
        assertTrue(rewritten.text.contains("{{${'$'}exampleServer}}"))
        assertTrue(rewritten.text.contains("{{${'$'}random.address.fullAddress}}"))
        assertTrue(rewritten.text.contains("{{user}}"))
        assertEquals(1, rewritten.text.split("{{${'$'}randomInt}}").size - 1)
        val messages = rewritten.warnings.joinToString("\n")
        assertTrue(messages.contains("{{${'$'}random.integer(10, 500)}}"))
        assertTrue(messages.contains("0 to 1000"))
        assertTrue(messages.contains("from inclusive, to exclusive"))
        assertTrue(messages.contains("{{${'$'}random.alphabetic(8)}}"))
        assertTrue(messages.contains("{{${'$'}exampleServer}}"))
        assertTrue(messages.contains("left unchanged"))
    }

    @Test
    fun rewritesDynamicVariablesInUrlHeaderBodyAndFileVariableWithoutTouchingScripts() {
        val output = export(
            """
            @generated = {{${'$'}uuid}}

            ### Created
            < {%
            const id = ${'$'}random.uuid
            %}
            POST https://example.com/{{${'$'}timestamp}}/items/{{${'$'}random.uuid}}?n={{${'$'}randomInt}}
            X-Request-Id: {{${'$'}uuid}}
            Content-Type: application/json

            {
              "when": "{{${'$'}isoTimestamp}}",
              "roll": {{${'$'}random.integer}},
              "mail": "{{${'$'}random.email}}",
              "span": {{${'$'}random.integer(3, 9)}},
              "letters": "{{${'$'}random.alphabetic(8)}}"
            }
            """.trimIndent(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        val root = parseJson(output.collectionJson) as Jv.Obj
        val variables = (root.fields.getValue("variable") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("{{${'$'}guid}}", variables.single().fields.getValue("value").str())
        val request = requestOf(items(output).single())
        val url = request.fields.getValue("url") as Jv.Obj
        assertEquals(
            "https://example.com/{{${'$'}timestamp}}/items/{{${'$'}guid}}?n={{${'$'}randomInt}}",
            url.fields.getValue("raw").str(),
        )
        val query = (url.fields.getValue("query") as Jv.Arr).items.single() as Jv.Obj
        assertEquals("{{${'$'}randomInt}}", query.fields.getValue("value").str())
        val headers = (request.fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("{{${'$'}guid}}", headers.single { it.fields.getValue("key").str() == "X-Request-Id" }.fields.getValue("value").str())
        val body = (request.fields.getValue("body") as Jv.Obj).fields.getValue("raw").str()
        assertTrue(body.contains("\"when\": \"{{${'$'}isoTimestamp}}\""))
        assertTrue(body.contains("\"roll\": {{${'$'}randomInt}}"))
        assertTrue(body.contains("\"mail\": \"{{${'$'}randomEmail}}\""))
        assertTrue(body.contains("\"span\": {{${'$'}randomInt}}"))
        assertTrue(body.contains("\"letters\": \"{{${'$'}random.alphabetic(8)}}\""))
        val exec = scriptLines(output)
        assertTrue(exec.any { it.contains("${'$'}random.uuid") && !it.trim().startsWith("//") })
        assertTrue(exec.none { it.contains("${'$'}guid") })
        assertTrue(output.reportMarkdown.contains("{{${'$'}random.alphabetic(8)}}"))
        assertTrue(output.reportMarkdown.contains("{{${'$'}random.integer(3, 9)}}"))
        assertTrue(output.reportMarkdown.contains("0 to 1000"))
        assertTrue(output.reportMarkdown.contains("left unchanged"))
        assertFalse(output.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}'"))
    }

    @Test
    fun doesNotCopyHostEnvironmentValuesIntoTheCollectionOrReport() {
        val home = System.getenv("HOME")
        assertTrue(!home.isNullOrBlank())
        val output = export(
            """
            GET https://example.com/{{${'$'}env.HOME}}/{{${'$'}processEnv HOME}}/{{${'$'}processEnv %HOME%}}/{{${'$'}dotenv HOME}}
            X-Token: {{${'$'}processEnv [%HOME%]}}
            X-Dot: {{${'$'}dotenv.HOME}}
            """.trimIndent(),
        )
        val raw = (requestOf(items(output).single()).fields.getValue("url") as Jv.Obj).fields.getValue("raw").str()
        assertEquals("https://example.com/{{HOME}}/{{HOME}}/{{HOME}}/{{HOME}}", raw)
        val headers = (requestOf(items(output).single()).fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("{{HOME}}", headers.first { it.fields.getValue("key").str() == "X-Token" }.fields.getValue("value").str())
        assertEquals("{{HOME}}", headers.first { it.fields.getValue("key").str() == "X-Dot" }.fields.getValue("value").str())
        assertFalse(output.collectionJson.contains(home!!))
        assertFalse(output.reportMarkdown.contains(home))
        assertTrue(output.reportMarkdown.contains("was not read"))
        assertTrue(output.reportMarkdown.contains("was not copied"))
        assertFalse(output.collectionJson.contains("\$env."))
        assertFalse(output.collectionJson.contains("\$processEnv"))
        assertFalse(output.collectionJson.contains("\$dotenv"))
    }

    @Test
    fun privateEnvironmentValuesStayOutUnlessOptedInAndNeverAppearInTheReport() {
        val secret = "super-secret-value"
        val public = """{"dev":{"host":"https://dev.example","id":"{{${'$'}uuid}}"}}"""
        val private = """{"dev":{"token":"$secret","note":"$secret {{${'$'}exampleServer}}"}}"""
        val omitted = exportHttpFiles(
            ExportInput(
                collectionName = "api",
                singleFile = true,
                files = listOf(HttpFileInput("api.http", "GET {{host}}/{{${'$'}uuid}}\n")),
                publicEnvironmentJson = public,
                privateEnvironmentJson = null,
                privateEnvironmentOmitted = true,
            ),
            ExportOptions.fixed(),
        )
        assertFalse(omitted.collectionJson.contains(secret))
        assertFalse(omitted.reportMarkdown.contains(secret))
        assertTrue(omitted.environments.none { it.json.contains(secret) })
        assertTrue(omitted.collectionJson.contains("{{${'$'}guid}}"))
        val omittedDev = environmentValues(omitted.environments.single().json)
        assertEquals("{{${'$'}guid}}", omittedDev.getValue("id").first)
        assertEquals("default", omittedDev.getValue("id").second)
        assertFalse(omittedDev.containsKey("token"))
        assertTrue(omitted.reportMarkdown.contains("Private environment file was present and was not read."))

        val included = exportHttpFiles(
            ExportInput(
                collectionName = "api",
                singleFile = true,
                files = listOf(HttpFileInput("api.http", "GET {{host}}/status\n")),
                publicEnvironmentJson = public,
                privateEnvironmentJson = private,
                privateEnvironmentOmitted = false,
            ),
            ExportOptions.fixed(),
        )
        val includedDev = environmentValues(included.environments.single().json)
        assertEquals(secret, includedDev.getValue("token").first)
        assertEquals("secret", includedDev.getValue("token").second)
        assertEquals("$secret {{${'$'}exampleServer}}", includedDev.getValue("note").first)
        assertEquals("secret", includedDev.getValue("note").second)
        assertEquals("{{${'$'}guid}}", includedDev.getValue("id").first)
        assertEquals("default", includedDev.getValue("id").second)
        assertFalse(included.reportMarkdown.contains(secret))
        assertFalse(included.collectionJson.contains(secret))
        assertTrue(included.reportMarkdown.contains("{{${'$'}exampleServer}}"))
        assertTrue(included.reportMarkdown.contains("left unchanged"))
        CollectionSchema.assertValid(included.collectionJson)
    }

    private fun export(content: String): ExportOutput = exportHttpFiles(
        ExportInput(collectionName = "requests", singleFile = true, files = listOf(HttpFileInput("requests.http", content))),
        ExportOptions.fixed(),
    )

    private fun items(output: ExportOutput): List<Jv> =
        ((parseJson(output.collectionJson) as Jv.Obj).fields.getValue("item") as Jv.Arr).items

    private fun requestOf(item: Jv): Jv.Obj = (item as Jv.Obj).fields.getValue("request") as Jv.Obj

    private fun scriptLines(output: ExportOutput): List<String> {
        val item = items(output).single() as Jv.Obj
        val events = item.fields.getValue("event") as Jv.Arr
        return events.items.flatMap { event ->
            val script = (event as Jv.Obj).fields.getValue("script") as Jv.Obj
            (script.fields.getValue("exec") as Jv.Arr).items.map { it.str() }
        }
    }

    private fun environmentValues(json: String): Map<String, Pair<String, String>> {
        val root = parseJson(json) as Jv.Obj
        return (root.fields.getValue("values") as Jv.Arr).items.associate { item ->
            val obj = item as Jv.Obj
            obj.fields.getValue("key").str() to (obj.fields.getValue("value").str() to obj.fields.getValue("type").str())
        }
    }

    private fun Jv.str(): String = assertIs<Jv.Str>(this).value
}
