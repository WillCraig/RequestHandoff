package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Formatting that import is allowed to change:
 * header order, trailing whitespace, multipart boundary strings, `.rest` rewritten as `.http`,
 * and a POST GraphQL request written with the `GRAPHQL` method (the parser stores it as POST).
 * Script events, Postman auth objects, and dynamic-variable names are not part of the v0.1 mapping.
 */
class FidelityTest {
    @Test
    fun exportThenImportPreservesKitchenRequestsFoldersAndPublicEnv() {
        val root = File("src/test/resources/samples/kitchen")
        val files = httpFiles(root)
        val exported = exportHttpFiles(
            ExportInput(
                collectionName = "kitchen",
                singleFile = false,
                files = files,
                publicEnvironmentJson = File(root, "http-client.env.json").readText(),
                privateEnvironmentOmitted = true,
            ),
            ExportOptions.fixed(),
        )
        val imported = importPostmanCollection(
            ImportInput(
                collectionJson = exported.collectionJson,
                collectionFileName = exported.collectionFileName,
                environmentJson = exported.environments.single().json,
                environmentFileName = exported.environments.single().fileName,
            ),
        )
        assertEquals(exported.requestsExported, imported.requestsImported)
        assertEquals(semantics(files), semantics(imported.files.map { HttpFileInput(it.relativePath, it.content) }))
        assertEquals(setOf("host", "token"), variables(imported.files.map { HttpFileInput(it.relativePath, it.content) }).keys)
        assertEquals(
            environmentValues(exported.environments.single().json),
            environmentValues(reexportEnvironment(imported.publicEnvironmentJson)),
        )
        assertTrue(imported.files.joinToString("\n") { it.content }.contains("request.variables.set"))
        assertFalse(imported.reportMarkdown.contains("test-private-token"))
        roundTripCollection(imported, "kitchen")
    }

    @Test
    fun singleFileExportImportsBackToTheSameRequests() {
        val input = listOf(
            HttpFileInput(
                "health.http",
                """
                @host = https://example.com
                ### Health
                GET {{host}}/health
                Authorization: Bearer {{token}}
                """.trimIndent(),
            ),
        )
        val exported = exportHttpFiles(
            ExportInput(collectionName = "health", singleFile = true, files = input),
            ExportOptions.fixed(),
        )
        val imported = importPostmanCollection(
            ImportInput(exported.collectionJson, exported.collectionFileName),
        )
        assertEquals(1, imported.files.size)
        assertEquals(semantics(input), semantics(imported.files.map { HttpFileInput(it.relativePath, it.content) }))
        assertEquals("https://example.com", variables(imported.files.map { HttpFileInput(it.relativePath, it.content) }).getValue("host"))
    }

    @Test
    fun importThenExportPreservesGoldenCollectionRequestsAndEnv() {
        val collection = File("src/test/resources/golden/api.postman_collection.json").readText()
        val environment = File("src/test/resources/golden/dev.postman_environment.json").readText()
        CollectionSchema.assertValid(collection)
        val imported = importPostmanCollection(
            ImportInput(collection, "api.postman_collection.json", environment, "dev.postman_environment.json"),
        )
        assertTrue(imported.files.joinToString("\n") { it.content }.contains("request.variables.set"))
        val again = exportImported(imported, "api")
        CollectionSchema.assertValid(again.collectionJson)
        assertEquals(postmanRequests(collection), postmanRequests(again.collectionJson))
        assertEquals(environmentValues(environment), environmentValues(again.environments.single().json))
        assertEquals("https://example.com", collectionVariables(again.collectionJson).getValue("host"))
    }

    @Test
    fun importThenExportPreservesRepresentativeCollection() {
        val environment = """
            {
              "id": "env-1",
              "name": "local",
              "values": [
                {"key": "host", "value": "https://local.example", "type": "default", "enabled": true},
                {"key": "auth.user", "value": "ada", "type": "default", "enabled": true},
                {"key": "token", "value": "$SECRET", "type": "secret", "enabled": true}
              ]
            }
        """.trimIndent()
        CollectionSchema.assertValid(REPRESENTATIVE)
        val imported = importPostmanCollection(
            ImportInput(REPRESENTATIVE, "representative.postman_collection.json", environment, "local.postman_environment.json"),
        )
        assertEquals(8, imported.requestsImported)
        assertEquals(2, imported.requestsSkipped)
        assertFalse(imported.reportMarkdown.contains(SECRET))
        assertFalse(imported.publicEnvironmentJson!!.contains(SECRET))
        assertTrue(imported.privateEnvironmentJson!!.contains(SECRET))
        imported.files.forEach { assertFalse(it.content.contains(SECRET)) }
        assertTrue(imported.reportMarkdown.contains("WebSocket requests are skipped."))
        assertTrue(imported.reportMarkdown.contains("gRPC requests are skipped."))
        assertTrue(imported.reportMarkdown.contains("Postman auth type 'bearer' was not imported."))
        assertTrue(imported.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}' was left unchanged."))
        assertTrue(imported.files.joinToString("\n") { it.content }.contains("{{${'$'}uuid}}"))
        assertTrue(imported.files.joinToString("\n") { it.content }.contains("pm.environment.set"))

        val again = exportImported(imported, "representative", includePrivate = true)
        CollectionSchema.assertValid(again.collectionJson)
        assertEquals(postmanRequests(REPRESENTATIVE), postmanRequests(again.collectionJson))
        val values = environmentValues(again.environments.single().json)
        assertEquals("https://local.example" to "default", values.getValue("host"))
        assertEquals("ada" to "default", values.getValue("auth.user"))
        assertEquals(SECRET to "secret", values.getValue("token"))
        assertFalse(again.reportMarkdown.contains(SECRET))
        val publicOnly = exportImported(imported, "representative", includePrivate = false)
        assertFalse(publicOnly.environments.single().json.contains(SECRET))
        assertFalse(publicOnly.collectionJson.contains(SECRET))
        assertFalse(publicOnly.reportMarkdown.contains(SECRET))
    }

    private fun roundTripCollection(imported: ImportOutput, name: String) {
        val again = exportImported(imported, name)
        CollectionSchema.assertValid(again.collectionJson)
        val first = importPostmanCollection(
            ImportInput(again.collectionJson, again.collectionFileName),
        )
        assertEquals(
            semantics(imported.files.map { HttpFileInput(it.relativePath, it.content) }),
            semantics(first.files.map { HttpFileInput(it.relativePath, it.content) }),
        )
    }

    private fun exportImported(imported: ImportOutput, name: String, includePrivate: Boolean = false): ExportOutput =
        exportHttpFiles(
            ExportInput(
                collectionName = name,
                singleFile = imported.files.none { it.relativePath.contains('/') } && imported.files.count { it.content.contains("\n### ") || it.content.startsWith("### ") } <= 1 && imported.files.size == 1,
                files = imported.files.map { HttpFileInput(it.relativePath, it.content) },
                publicEnvironmentJson = imported.publicEnvironmentJson,
                privateEnvironmentJson = if (includePrivate) imported.privateEnvironmentJson else null,
            ),
            ExportOptions.fixed(),
        )

    private fun reexportEnvironment(publicJson: String?): String {
        val exported = exportHttpFiles(
            ExportInput(
                collectionName = "env",
                singleFile = true,
                files = listOf(HttpFileInput("ping.http", "GET https://example.com/health\n")),
                publicEnvironmentJson = publicJson,
            ),
            ExportOptions.fixed(),
        )
        return exported.environments.single().json
    }

    private fun httpFiles(root: File): List<HttpFileInput> =
        root.listFiles { file ->
            val extension = file.extension.lowercase()
            extension == "http" || extension == "rest"
        }.orEmpty().sortedBy { it.name }.map { HttpFileInput(it.name, it.readText()) }

    private fun semantics(files: List<HttpFileInput>): List<RequestShape> =
        files.flatMap { file ->
            parseHttpFile(file.relativePath, file.content.orEmpty()).requests.map { request ->
                RequestShape(
                    path = stem(file.relativePath),
                    name = request.name,
                    method = request.method,
                    url = request.url,
                    headers = normalizeHeaders(request.headers),
                    body = bodyShape(request.body),
                )
            }
        }.sortedWith(compareBy({ it.path }, { it.name }, { it.method }, { it.url }))

    private fun variables(files: List<HttpFileInput>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        files.forEach { file ->
            parseHttpFile(file.relativePath, file.content.orEmpty()).variables.forEach { (key, value) ->
                values[key] = value
            }
        }
        return values
    }

    private fun stem(path: String): String = path.removeSuffix(".http").removeSuffix(".rest")

    private fun normalizeHeaders(headers: List<Header>): List<Pair<String, String>> =
        headers.map { header ->
            val value = if (header.name.equals("Content-Type", ignoreCase = true) &&
                header.value.lowercase().contains("multipart/form-data")
            ) {
                "multipart/form-data"
            } else {
                header.value.trimEnd()
            }
            header.name to value
        }.sortedBy { it.first.lowercase() + "\u0000" + it.second }

    private fun bodyShape(body: Body): String = when (body) {
        Body.None -> "none"
        is Body.Raw -> "raw\n${body.text.trimEnd()}"
        is Body.FileRef -> "file\n${body.src}"
        is Body.UrlEncoded -> "urlencoded\n" + body.fields.joinToString("\n") { "${it.first}=${it.second}" }
        is Body.Multipart -> "formdata\n" + body.parts.joinToString("\n") { part ->
            listOf(part.name, part.text?.trimEnd() ?: "", part.src ?: "", part.contentType ?: "").joinToString("\t")
        }
        is Body.GraphQl -> "graphql\n${body.query.trimEnd()}\n${body.variables?.trimEnd().orEmpty()}"
    }

    private fun postmanRequests(collectionJson: String): List<RequestShape> {
        val root = parseJson(collectionJson) as Jv.Obj
        return walk(root.fields.getValue("item") as Jv.Arr, "").sortedWith(
            compareBy({ it.path }, { it.name }, { it.method }, { it.url }),
        )
    }

    private fun walk(items: Jv.Arr, folder: String): List<RequestShape> = items.items.flatMap { item ->
        val obj = item as Jv.Obj
        val name = obj.fields["name"]?.str().orEmpty()
        val request = obj.fields["request"] as? Jv.Obj
        if (request != null && obj.fields["item"] == null) {
            val shape = requestShape(folder, name, request)
            if (shape == null) emptyList() else listOf(shape)
        } else {
            val children = obj.fields["item"] as? Jv.Arr ?: return@flatMap emptyList()
            walk(children, if (folder.isEmpty()) name else "$folder/$name")
        }
    }

    private fun requestShape(folder: String, name: String, request: Jv.Obj): RequestShape? {
        val url = when (val raw = request.fields["url"]) {
            is Jv.Str -> raw.value
            is Jv.Obj -> raw.fields["raw"]?.str() ?: raw.toString()
            else -> ""
        }
        val method = request.fields["method"]?.str() ?: "GET"
        if (skipProtocol(method, url)) return null
        val headers = (request.fields["header"] as? Jv.Arr)?.items.orEmpty().mapNotNull { header ->
            val obj = header as? Jv.Obj ?: return@mapNotNull null
            if ((obj.fields["disabled"] as? Jv.Bool)?.value == true) return@mapNotNull null
            Header(obj.fields["key"]?.str() ?: return@mapNotNull null, obj.fields["value"]?.str() ?: "")
        }
        return RequestShape(folder, name, method.uppercase(), url, normalizeHeaders(headers), postmanBody(request))
    }

    private fun postmanBody(request: Jv.Obj): String {
        val body = request.fields["body"] as? Jv.Obj ?: return "none"
        if ((body.fields["disabled"] as? Jv.Bool)?.value == true) return "none"
        return when (body.fields["mode"]?.str()) {
            "raw" -> "raw\n${body.fields["raw"]?.str().orEmpty().trimEnd()}"
            "file" -> "file\n${((body.fields["file"] as? Jv.Obj)?.fields?.get("src")?.str()).orEmpty()}"
            "urlencoded" -> "urlencoded\n" + fields(body.fields["urlencoded"]).joinToString("\n") { "${it.first}=${it.second}" }
            "formdata" -> "formdata\n" + formParts(body.fields["formdata"])
            "graphql" -> {
                val graphql = body.fields["graphql"] as Jv.Obj
                "graphql\n${graphql.fields["query"]?.str().orEmpty().trimEnd()}\n${graphql.fields["variables"]?.str().orEmpty().trimEnd()}"
            }
            null -> "none"
            else -> "other"
        }
    }

    private fun fields(value: Jv?): List<Pair<String, String>> =
        (value as? Jv.Arr)?.items.orEmpty().mapNotNull { item ->
            val obj = item as? Jv.Obj ?: return@mapNotNull null
            if ((obj.fields["disabled"] as? Jv.Bool)?.value == true) return@mapNotNull null
            (obj.fields["key"]?.str() ?: return@mapNotNull null) to (obj.fields["value"]?.str() ?: "")
        }

    private fun formParts(value: Jv?): String =
        (value as? Jv.Arr)?.items.orEmpty().mapNotNull { item ->
            val obj = item as? Jv.Obj ?: return@mapNotNull null
            if ((obj.fields["disabled"] as? Jv.Bool)?.value == true) return@mapNotNull null
            val key = obj.fields["key"]?.str() ?: return@mapNotNull null
            val src = (obj.fields["src"] as? Jv.Str)?.value
            val text = obj.fields["value"]?.str()?.trimEnd() ?: ""
            val type = obj.fields["contentType"]?.str() ?: ""
            listOf(key, if (src != null) "" else text, src ?: "", type).joinToString("\t")
        }.orEmpty().joinToString("\n")

    private fun skipProtocol(method: String, url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return method.equals("WEBSOCKET", true) || method.equals("GRPC", true) ||
            scheme == "ws" || scheme == "wss" || scheme == "grpc" || scheme == "grpcs"
    }

    private fun environmentValues(json: String): Map<String, Pair<String, String>> {
        val root = parseJson(json) as Jv.Obj
        val values = root.fields.getValue("values") as Jv.Arr
        return values.items.associate { item ->
            val obj = item as Jv.Obj
            obj.fields.getValue("key").str() to (obj.fields.getValue("value").str() to obj.fields.getValue("type").str())
        }
    }

    private fun collectionVariables(json: String): Map<String, String> {
        val root = parseJson(json) as Jv.Obj
        val variables = root.fields["variable"] as? Jv.Arr ?: return emptyMap()
        return variables.items.associate { item ->
            val obj = item as Jv.Obj
            obj.fields.getValue("key").str() to obj.fields.getValue("value").str()
        }
    }

    private fun Jv.str(): String = (this as Jv.Str).value

    private data class RequestShape(
        val path: String,
        val name: String,
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    )

    private companion object {
        const val SECRET = "rh-env-secret-do-not-leak"
        val REPRESENTATIVE = """
            {
              "info": {
                "name": "representative",
                "schema": "$COLLECTION_SCHEMA"
              },
              "variable": [
                {"key": "host", "value": "https://example.com", "type": "string"}
              ],
              "item": [
                {
                  "name": "users",
                  "item": [
                    {
                      "name": "List",
                      "request": {
                        "method": "GET",
                        "header": [
                          {"key": "Accept", "value": "application/json"},
                          {"key": "X-Debug", "value": "nope", "disabled": true}
                        ],
                        "url": {"raw": "{{host}}/users?active=true", "host": ["{{host}}"], "path": ["users"]}
                      }
                    },
                    {
                      "name": "Create",
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "application/json"}],
                        "body": {"mode": "raw", "raw": "{\"name\":\"Ada\"}", "options": {"raw": {"language": "json"}}},
                        "url": {"raw": "{{host}}/users", "host": ["{{host}}"], "path": ["users"]}
                      }
                    }
                  ]
                },
                {
                  "name": "forms",
                  "item": [
                    {
                      "name": "Form",
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "application/x-www-form-urlencoded"}],
                        "body": {"mode": "urlencoded", "urlencoded": [{"key": "name", "value": "Ada"}, {"key": "role", "value": "{{role}}"}]},
                        "url": "https://example.com/form"
                      }
                    },
                    {
                      "name": "Upload",
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "multipart/form-data; boundary=Boundary"}],
                        "body": {
                          "mode": "formdata",
                          "formdata": [
                            {"key": "title", "value": "Hello", "type": "text"},
                            {"key": "file", "src": "./notes.txt", "type": "file", "contentType": "text/plain"}
                          ]
                        },
                        "url": "https://example.com/upload"
                      }
                    },
                    {
                      "name": "Raw file",
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "application/octet-stream"}],
                        "body": {"mode": "file", "file": {"src": "./payload.bin"}},
                        "url": "https://example.com/raw"
                      }
                    }
                  ]
                },
                {
                  "name": "api",
                  "item": [
                    {
                      "name": "Graph",
                      "event": [
                        {
                          "listen": "prerequest",
                          "script": {"type": "text/javascript", "exec": ["// pm.environment.set(\"userId\", \"1\");"]}
                        }
                      ],
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Authorization", "value": "Bearer {{token}}"}],
                        "body": {"mode": "graphql", "graphql": {"query": "query { ping }", "variables": "{\"id\":\"{{userId}}\"}"}},
                        "url": "{{host}}/graphql"
                      }
                    },
                    {
                      "name": "Generated",
                      "request": {"method": "GET", "url": "{{host}}/id/{{${'$'}uuid}}"}
                    },
                    {
                      "name": "Authed",
                      "request": {
                        "method": "GET",
                        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "not-an-env-secret", "type": "string"}]},
                        "url": "https://example.com/secure"
                      }
                    },
                    {
                      "name": "Socket",
                      "request": {"method": "GET", "url": "wss://example.com/live"}
                    },
                    {
                      "name": "Rpc",
                      "request": {"method": "GRPC", "url": "grpc://localhost/demo.Greeter/SayHello"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
