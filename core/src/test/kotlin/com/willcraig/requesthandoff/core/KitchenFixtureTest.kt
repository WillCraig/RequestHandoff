package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class KitchenFixtureTest {
    @Test
    fun sampleFilesMatchGoldensSchemaAndRoundTrip() {
        val root = File("src/test/resources/samples/kitchen")
        val files = root.listFiles { file ->
            val extension = file.extension.lowercase()
            extension == "http" || extension == "rest"
        }.orEmpty().sortedBy { it.name }
        assertEquals(listOf("extra.rest", "requests.http"), files.map { it.name })
        val output = exportHttpFiles(
            ExportInput(
                collectionName = "kitchen",
                singleFile = false,
                files = files.map { HttpFileInput(it.name, it.readText()) },
                publicEnvironmentJson = File(root, "http-client.env.json").readText(),
                privateEnvironmentJson = null,
                privateEnvironmentOmitted = true,
            ),
            ExportOptions.fixed(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        assertGolden("kitchen/kitchen.postman_collection.json", output.collectionJson)
        assertGolden("kitchen/requestHandoff-report.md", output.reportMarkdown)
        assertEquals(listOf("local"), output.environments.map { it.name })
        assertGolden("kitchen/${output.environments.single().fileName}", output.environments.single().json)

        assertTrue(output.requestsExported > 1)
        assertEquals(2, output.requestsSkipped)
        assertEquals(3, output.warningCount)
        assertTrue(output.reportMarkdown.contains("WebSocket requests are skipped"))
        assertTrue(output.reportMarkdown.contains("gRPC requests are skipped"))
        assertTrue(output.reportMarkdown.contains("left as comments"))
        assertTrue(output.reportMarkdown.contains("Response handler script file './check.js' was not found and was not inlined."))
        assertFalse(output.reportMarkdown.contains("was not translated"))
        assertTrue(output.reportMarkdown.contains("Request flag '@no-log' was not converted."))
        assertTrue(output.reportMarkdown.contains("Private environment file was present and was not read."))
        assertTrue(output.collectionJson.contains("{{${'$'}guid}}"))
        assertFalse(output.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}' has no Postman equivalent"))
        assertFalse(output.collectionJson.contains("client.test"))

        val readBack = readBackRequests(output.collectionJson)
        val parsed = files.flatMap { parseHttpFile(it.name, it.readText()).requests }
        assertEquals(parsed.size, readBack.size)
        assertEquals(parsed.map { it.name }.toSet(), readBack.map { it.name }.toSet())
        parsed.forEach { request ->
            val exported = readBack.single { it.name == request.name }
            assertEquals(request.method, exported.method)
            assertEquals(request.url, exported.rawUrl)
            assertEquals(request.headers.map { it.name to it.value }, exported.headers)
            assertEquals(bodyMode(request.body), exported.bodyMode)
        }
        val graph = readBack.single { it.name == "Graph" }
        assertEquals("POST", graph.method)
        assertEquals("graphql", graph.bodyMode)
        val events = scriptLines(output.collectionJson, "Graph")
        assertTrue(events.any { it == "pm.variables.set(\"userId\", \"1\");" })
        assertTrue(events.any { it.contains("script file './check.js' was not found and was not inlined.") })
        assertTrue(events.none { it.contains("request.variables.set") })

        val environment = readBackEnvironment(output.environments.single().json)
        assertEquals("local", environment.name)
        assertEquals("https://local.example", environment.values.getValue("host").value)
        assertEquals("default", environment.values.getValue("host").type)
        assertEquals("ada", environment.values.getValue("auth.user").value)
        assertEquals("admin", environment.values.getValue("role").value)
        val variables = collectionVariables(output.collectionJson)
        assertEquals("https://example.com", variables.getValue("host"))
        assertEquals("file-token", variables.getValue("token"))
    }

    private fun assertGolden(name: String, actual: String) {
        val file = File("src/test/resources/golden/$name")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            fail("Wrote src/test/resources/golden/$name. Review it and re-run.")
        }
        assertEquals(file.readText(), actual)
    }
}

private data class ReadBackRequest(
    val name: String,
    val method: String,
    val rawUrl: String,
    val headers: List<Pair<String, String>>,
    val bodyMode: String?,
)

private data class ReadBackValue(val value: String, val type: String)

private data class ReadBackEnvironment(val name: String, val values: Map<String, ReadBackValue>)

private fun bodyMode(body: Body): String? = when (body) {
    Body.None -> null
    is Body.Raw -> "raw"
    is Body.FileRef -> "file"
    is Body.UrlEncoded -> "urlencoded"
    is Body.Multipart -> "formdata"
    is Body.GraphQl -> "graphql"
}

private fun readBackRequests(collectionJson: String): List<ReadBackRequest> {
    val root = parseJson(collectionJson) as Jv.Obj
    return walkItems(root.fields.getValue("item") as Jv.Arr)
}

private fun walkItems(items: Jv.Arr): List<ReadBackRequest> = items.items.flatMap { item ->
    val obj = item as Jv.Obj
    val request = obj.fields["request"] as? Jv.Obj
    if (request != null) {
        listOf(readRequest(obj.fields.getValue("name").str(), request))
    } else {
        walkItems(obj.fields.getValue("item") as Jv.Arr)
    }
}

private fun readRequest(name: String, request: Jv.Obj): ReadBackRequest {
    val headers = (request.fields["header"] as? Jv.Arr)?.items.orEmpty().map { header ->
        val obj = header as Jv.Obj
        obj.fields.getValue("key").str() to obj.fields.getValue("value").str()
    }
    val url = request.fields.getValue("url") as Jv.Obj
    val body = request.fields["body"] as? Jv.Obj
    return ReadBackRequest(
        name = name,
        method = request.fields.getValue("method").str(),
        rawUrl = url.fields.getValue("raw").str(),
        headers = headers,
        bodyMode = body?.fields?.get("mode")?.str(),
    )
}

private fun scriptLines(collectionJson: String, requestName: String): List<String> {
    val root = parseJson(collectionJson) as Jv.Obj
    val item = findNamed(root.fields.getValue("item") as Jv.Arr, requestName)
    val events = item.fields["event"] as? Jv.Arr ?: return emptyList()
    return events.items.flatMap { event ->
        val script = (event as Jv.Obj).fields.getValue("script") as Jv.Obj
        (script.fields.getValue("exec") as Jv.Arr).items.map { it.str() }
    }
}

private fun findNamed(items: Jv.Arr, name: String): Jv.Obj {
    items.items.forEach { item ->
        val obj = item as Jv.Obj
        if (obj.fields["request"] != null && obj.fields.getValue("name").str() == name) return obj
        val children = obj.fields["item"] as? Jv.Arr ?: return@forEach
        val found = runCatching { findNamed(children, name) }.getOrNull()
        if (found != null) return found
    }
    error("Request '$name' was not in the collection")
}

private fun collectionVariables(collectionJson: String): Map<String, String> {
    val root = parseJson(collectionJson) as Jv.Obj
    val variables = root.fields.getValue("variable") as Jv.Arr
    return variables.items.associate { item ->
        val obj = item as Jv.Obj
        obj.fields.getValue("key").str() to obj.fields.getValue("value").str()
    }
}

private fun readBackEnvironment(json: String): ReadBackEnvironment {
    val root = parseJson(json) as Jv.Obj
    val values = (root.fields.getValue("values") as Jv.Arr).items.associate { item ->
        val obj = item as Jv.Obj
        obj.fields.getValue("key").str() to ReadBackValue(
            value = obj.fields.getValue("value").str(),
            type = obj.fields.getValue("type").str(),
        )
    }
    return ReadBackEnvironment(root.fields.getValue("name").str(), values)
}

private fun Jv.str(): String = assertIs<Jv.Str>(this).value
