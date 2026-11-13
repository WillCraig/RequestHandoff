package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExporterTest {
    @Test
    fun mapsRequestNamesMethodsQueryHeadersVariablesAndAuthHeader() {
        val output = export(
            """
            @host = https://example.com
            @token = file-token

            ### List users
            # @no-log
            GET {{host}}/users?active=true&q={{search}}
                &page=2
            Authorization: Bearer {{token}}
            Accept: application/json

            ###
            # @name createUser
            POST {{host}}/users HTTP/1.1
            Content-Type: application/json
            Authorization: Basic dXNlcjpwYXNz

            {
              "name": "Ada"
            }
            """.trimIndent(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        val root = parseJson(output.collectionJson) as Jv.Obj
        val items = root.fields.getValue("item") as Jv.Arr
        assertEquals(2, items.items.size)
        val list = items.items[0] as Jv.Obj
        assertEquals("List users", list.fields.getValue("name").str())
        val listRequest = list.fields.getValue("request") as Jv.Obj
        assertEquals("GET", listRequest.fields.getValue("method").str())
        assertFalse(listRequest.fields.containsKey("auth"))
        val headers = (listRequest.fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("Bearer {{token}}", headers.first { it.fields.getValue("key").str() == "Authorization" }.fields.getValue("value").str())
        val url = listRequest.fields.getValue("url") as Jv.Obj
        assertEquals("{{host}}/users?active=true&q={{search}}&page=2", url.fields.getValue("raw").str())
        val query = (url.fields.getValue("query") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals(listOf("active", "q", "page"), query.map { it.fields.getValue("key").str() })
        assertEquals("{{search}}", query[1].fields.getValue("value").str())
        val variables = (root.fields.getValue("variable") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("https://example.com", variables.first { it.fields.getValue("key").str() == "host" }.fields.getValue("value").str())
        val create = items.items[1] as Jv.Obj
        assertEquals("createUser", create.fields.getValue("name").str())
        val createRequest = create.fields.getValue("request") as Jv.Obj
        assertEquals("POST", createRequest.fields.getValue("method").str())
        val body = createRequest.fields.getValue("body") as Jv.Obj
        assertEquals("raw", body.fields.getValue("mode").str())
        assertEquals("json", ((body.fields.getValue("options") as Jv.Obj).fields.getValue("raw") as Jv.Obj).fields.getValue("language").str())
        assertTrue(output.reportMarkdown.contains("Request flag '@no-log' was not converted."))
        assertTrue(output.reportMarkdown.contains("WebSocket and gRPC requests are skipped."))
        assertTrue(output.reportMarkdown.contains("not translated"))
    }

    @Test
    fun prefersSeparatorTitleOverAtName() {
        val output = export(
            """
            ### Get user
            # @name getUser
            GET https://example.com/users/1
            """.trimIndent(),
        )
        val item = firstItem(output)
        assertEquals("Get user", item.fields.getValue("name").str())
    }

    @Test
    fun exportsBodiesScriptsFilesAndSkipsSockets() {
        val output = export(
            """
            run ./auth.http

            ### Upload
            POST https://example.com/upload
            Content-Type: multipart/form-data; boundary=Boundary

            --Boundary
            Content-Disposition: form-data; name="title"

            Hello
            --Boundary
            Content-Disposition: form-data; name="file"; filename="notes.txt"
            Content-Type: text/plain

            < ./notes.txt
            --Boundary--

            ### Raw file
            POST https://example.com/raw
            Content-Type: application/octet-stream

            < ./payload.bin

            ### Form
            POST https://example.com/form
            Content-Type: application/x-www-form-urlencoded

            name=Ada&role={{role}}

            ### Plain
            POST https://example.com/note
            Content-Type: text/plain

            hello {{name}}

            ### Graph
            < {%
            request.variables.set("userId", "1");
            %}
            POST https://example.com/graphql
            X-REQUEST-TYPE: GraphQL
            Authorization: Bearer {{token}}

            query(${'$'}id: ID!) {
              user(id: ${'$'}id) { name }
            }

            {
              "id": "{{userId}}"
            }

            > {%
            client.test("created", function() {
              client.assert(response.status === 200);
            });
            %}

            >> ./response.json

            ### Generated
            GET https://example.com/{{${'$'}uuid}}

            ### Socket
            WEBSOCKET wss://example.com/ws

            ### Rpc
            GRPC localhost:50051/demo.Greeter/SayHello
            """.trimIndent(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        assertEquals(6, output.requestsExported)
        assertEquals(2, output.requestsSkipped)
        val items = items(output)
        val upload = requestOf(items[0])
        val form = upload.fields.getValue("body") as Jv.Obj
        assertEquals("formdata", form.fields.getValue("mode").str())
        val parts = (form.fields.getValue("formdata") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("text", parts[0].fields.getValue("type").str())
        assertEquals("Hello", parts[0].fields.getValue("value").str())
        assertEquals("file", parts[1].fields.getValue("type").str())
        assertEquals("./notes.txt", parts[1].fields.getValue("src").str())

        val raw = requestOf(items[1])
        val fileBody = raw.fields.getValue("body") as Jv.Obj
        assertEquals("file", fileBody.fields.getValue("mode").str())
        assertEquals("./payload.bin", (fileBody.fields.getValue("file") as Jv.Obj).fields.getValue("src").str())

        val encoded = requestOf(items[2]).fields.getValue("body") as Jv.Obj
        assertEquals("urlencoded", encoded.fields.getValue("mode").str())
        val fields = (encoded.fields.getValue("urlencoded") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("{{role}}", fields[1].fields.getValue("value").str())

        val plain = requestOf(items[3]).fields.getValue("body") as Jv.Obj
        assertEquals("text", ((plain.fields.getValue("options") as Jv.Obj).fields.getValue("raw") as Jv.Obj).fields.getValue("language").str())

        val graphItem = items[4] as Jv.Obj
        val graph = requestOf(graphItem)
        assertEquals("POST", graph.fields.getValue("method").str())
        assertFalse(graph.fields.containsKey("auth"))
        val graphBody = graph.fields.getValue("body") as Jv.Obj
        assertEquals("graphql", graphBody.fields.getValue("mode").str())
        val graphql = graphBody.fields.getValue("graphql") as Jv.Obj
        assertTrue(graphql.fields.getValue("query").str().contains("user(id:"))
        assertTrue(graphql.fields.getValue("variables").str().contains("{{userId}}"))
        val events = (graphItem.fields.getValue("event") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals(listOf("prerequest", "test"), events.map { it.fields.getValue("listen").str() })
        val exec = events.flatMap { ((it.fields.getValue("script") as Jv.Obj).fields.getValue("exec") as Jv.Arr).items.map { line -> line.str() } }
        assertTrue(exec.any { it.contains("request.variables.set") && it.trim().startsWith("//") })
        assertTrue(exec.any { it.contains("client.test") && it.trim().startsWith("//") })
        assertTrue(exec.none { it.contains("client.test") && !it.trim().startsWith("//") })

        val url = requestOf(items[5]).fields.getValue("url") as Jv.Obj
        assertTrue(url.fields.getValue("raw").str().contains("{{${'$'}uuid}}"))
        assertTrue(output.reportMarkdown.contains("WebSocket requests are skipped"))
        assertTrue(output.reportMarkdown.contains("gRPC requests are skipped"))
        assertTrue(output.reportMarkdown.contains("Import 'run ./auth.http' was not converted."))
        assertTrue(output.reportMarkdown.contains("Response output redirect"))
        assertTrue(output.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}' was left unchanged."))
        assertTrue(output.reportMarkdown.contains("Pre-request script was not translated"))
        assertTrue(output.reportMarkdown.contains("Response handler script was not translated"))
    }

    @Test
    fun splitsAbsoluteUrl() {
        val output = export("GET https://example.com:8080/a/b?x=1#frag\n")
        val url = requestOf(firstItem(output)).fields.getValue("url") as Jv.Obj
        assertEquals("https", url.fields.getValue("protocol").str())
        assertEquals(listOf("example", "com"), (url.fields.getValue("host") as Jv.Arr).items.map { it.str() })
        assertEquals("8080", url.fields.getValue("port").str())
        assertEquals(listOf("a", "b"), (url.fields.getValue("path") as Jv.Arr).items.map { it.str() })
        assertEquals("frag", url.fields.getValue("hash").str())
    }

    @Test
    fun keepsVariableHostIntact() {
        val output = export("GET {{baseUrl}}/v1/users\n")
        val url = requestOf(firstItem(output)).fields.getValue("url") as Jv.Obj
        assertEquals(listOf("{{baseUrl}}"), (url.fields.getValue("host") as Jv.Arr).items.map { it.str() })
        assertEquals(listOf("v1", "users"), (url.fields.getValue("path") as Jv.Arr).items.map { it.str() })
        assertFalse(url.fields.containsKey("protocol"))
    }

    @Test
    fun directoryStructureBecomesFolders() {
        val output = exportHttpFiles(
            ExportInput(
                collectionName = "api",
                singleFile = false,
                files = listOf(
                    HttpFileInput("health.http", "GET https://example.com/health\n"),
                    HttpFileInput(
                        "users/list.http",
                        """
                        ### List
                        GET https://example.com/users

                        ### Get
                        GET https://example.com/users/1
                        """.trimIndent(),
                    ),
                ),
            ),
            ExportOptions.fixed(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        val folders = items(output).map { it as Jv.Obj }
        assertEquals(listOf("health", "users"), folders.map { it.fields.getValue("name").str() })
        val users = (folders[1].fields.getValue("item") as Jv.Arr).items.single() as Jv.Obj
        assertEquals("list", users.fields.getValue("name").str())
        val requests = (users.fields.getValue("item") as Jv.Arr).items.map { (it as Jv.Obj).fields.getValue("name").str() }
        assertEquals(listOf("List", "Get"), requests)
        assertEquals("api.postman_collection.json", output.collectionFileName)
    }

    @Test
    fun privateEnvironmentIsOptInAndNeverCopiedIntoTheReport() {
        val public = """{"dev":{"host":"https://dev.example","auth":{"user":"ada"}},"prod":{"host":"https://example.com"}}"""
        val private = """{"dev":{"auth":{"token":"super-secret-value"},"onlyPrivate":"hidden"}}"""
        val omitted = exportHttpFiles(
            ExportInput(
                collectionName = "api",
                singleFile = true,
                files = listOf(HttpFileInput("api.http", "GET {{host}}/status\n")),
                publicEnvironmentJson = public,
                privateEnvironmentJson = null,
                privateEnvironmentOmitted = true,
            ),
            ExportOptions.fixed(),
        )
        assertEquals(listOf("dev", "prod"), omitted.environments.map { it.name })
        val dev = parseJson(omitted.environments.first().json) as Jv.Obj
        val values = (dev.fields.getValue("values") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("ada", values.first { it.fields.getValue("key").str() == "auth.user" }.fields.getValue("value").str())
        assertTrue(values.none { it.fields.getValue("key").str() == "auth.token" })
        assertFalse(omitted.collectionJson.contains("super-secret-value"))
        assertFalse(omitted.reportMarkdown.contains("super-secret-value"))
        assertTrue(omitted.environments.none { it.json.contains("super-secret-value") })
        assertTrue(omitted.reportMarkdown.contains("Private environment file was present and was not read."))
        assertEquals(1, omitted.warningCount)

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
        val includedDev = parseJson(included.environments.first { it.name == "dev" }.json) as Jv.Obj
        val includedValues = (includedDev.fields.getValue("values") as Jv.Arr).items.map { it as Jv.Obj }
        val token = includedValues.first { it.fields.getValue("key").str() == "auth.token" }
        assertEquals("super-secret-value", token.fields.getValue("value").str())
        assertEquals("secret", token.fields.getValue("type").str())
        assertEquals("default", includedValues.first { it.fields.getValue("key").str() == "host" }.fields.getValue("type").str())
        assertFalse(included.reportMarkdown.contains("super-secret-value"))
        assertFalse(included.collectionJson.contains("super-secret-value"))
        CollectionSchema.assertValid(included.collectionJson)
    }

    @Test
    fun countsMatchTheReport() {
        val output = export("WEBSOCKET wss://example.com/socket\n")
        assertEquals(0, output.requestsExported)
        assertEquals(1, output.requestsSkipped)
        assertTrue(output.reportMarkdown.contains("Requests exported: 0"))
        assertTrue(output.reportMarkdown.contains("Requests skipped: 1"))
        assertTrue(output.reportMarkdown.contains("Warnings: ${output.warningCount}"))
        assertEquals("requestHandoff-report.md", output.reportFileName)
        CollectionSchema.assertValid(output.collectionJson)
    }

    @Test
    fun parsesCrlfAndResponseExample() {
        val output = export("### Saved\r\nHTTP/1.1 200 OK\r\n\r\n{\"ok\":true}\r\n### Live\r\nGET https://example.com/live\r\n")
        assertEquals(1, output.requestsExported)
        assertEquals("Live", firstItem(output).fields.getValue("name").str())
        assertTrue(output.reportMarkdown.contains("Saved HTTP response example was not converted."))
    }

    private fun export(content: String): ExportOutput = exportHttpFiles(
        ExportInput(collectionName = "requests", singleFile = true, files = listOf(HttpFileInput("requests.http", content))),
        ExportOptions.fixed(),
    )

    private fun items(output: ExportOutput): List<Jv> =
        ((parseJson(output.collectionJson) as Jv.Obj).fields.getValue("item") as Jv.Arr).items

    private fun firstItem(output: ExportOutput): Jv.Obj = items(output).first() as Jv.Obj

    private fun requestOf(item: Jv): Jv.Obj = (item as Jv.Obj).fields.getValue("request") as Jv.Obj

    private fun Jv.str(): String = assertIs<Jv.Str>(this).value
}
