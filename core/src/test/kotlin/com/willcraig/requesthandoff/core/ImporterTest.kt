package com.willcraig.requesthandoff.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImporterTest {
    @Test
    fun invalidCollectionProducesAReportAndNoRequests() {
        val output = importPostmanCollection(ImportInput("{"))
        assertEquals(0, output.requestsImported)
        assertTrue(output.files.isEmpty())
        assertTrue(output.reportMarkdown.contains("not valid JSON"))
        assertNull(output.publicEnvironmentJson)
        assertNull(output.privateEnvironmentJson)
    }

    @Test
    fun secretEnvironmentValuesStayOutOfPublicFilesAndTheReport() {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collection(
                    """{"name":"Health","request":{"method":"GET","url":"https://example.com/health"}}""",
                ),
                environmentJson = """
                {
                  "name": "dev",
                  "values": [
                    {"key": "host", "value": "https://dev.example", "type": "default", "enabled": true},
                    {"key": "auth.user", "value": "ada", "type": "default"},
                    {"key": "token", "value": "$ENV_SECRET", "type": "secret", "enabled": true},
                    {"key": "retired", "value": "$DISABLED_SECRET", "type": "secret", "enabled": false}
                  ]
                }
                """.trimIndent(),
                environmentFileName = "dev.postman_environment.json",
            ),
        )
        assertEquals(1, output.requestsImported)
        val public = output.publicEnvironmentJson ?: error("public environment")
        val privateFile = output.privateEnvironmentJson ?: error("private environment")
        assertTrue(public.contains("ada"))
        assertTrue(public.contains("auth"))
        assertNoLeak(output, ENV_SECRET)
        assertNoLeak(output, DISABLED_SECRET)
        assertFalse(privateFile.contains(DISABLED_SECRET))
        assertTrue(privateFile.contains(ENV_SECRET))
        assertTrue(output.reportMarkdown.contains("Secret key 'token'"))
        assertTrue(output.reportMarkdown.contains("Disabled environment key 'retired'"))
        assertFalse(output.reportMarkdown.contains(ENV_SECRET))
    }

    @Test
    fun authScriptsExamplesAndDisabledHeadersDoNotLeakSkippedValues() {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collection(
                    """
                    {
                      "name": "Authed",
                      "request": {
                        "method": "GET",
                        "url": "https://example.com/secure",
                        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "$AUTH_SECRET", "type": "string"}]},
                        "header": [
                          {"key": "Accept", "value": "application/json"},
                          {"key": "X-Debug", "value": "$HEADER_SECRET", "disabled": true}
                        ],
                        "proxy": {"host": "127.0.0.1"},
                        "body": {"mode": "file", "file": {"src": "./payload.bin", "content": "$FILE_SECRET"}}
                      },
                      "response": [{"name": "OK", "code": 200, "body": "$RESPONSE_SECRET"}]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val http = output.files.single().content
        assertTrue(http.contains("GET https://example.com/secure"))
        assertTrue(http.contains("< ./payload.bin"))
        assertTrue(http.contains("Accept: application/json"))
        assertFalse(http.contains("X-Debug"))
        assertNoLeak(output, AUTH_SECRET)
        assertNoLeak(output, HEADER_SECRET)
        assertNoLeak(output, FILE_SECRET)
        assertNoLeak(output, RESPONSE_SECRET)
        assertTrue(output.reportMarkdown.contains("Postman auth type 'bearer' was not imported."))
        assertTrue(output.reportMarkdown.contains("Inline file bytes were not imported."))
        assertTrue(output.reportMarkdown.contains("1 saved response example was not imported."))
        assertTrue(output.reportMarkdown.contains("Disabled header 'X-Debug' was not imported."))
        assertTrue(output.reportMarkdown.contains("Proxy configuration was not imported."))
    }

    @Test
    fun structuredUrlIsRebuiltAndDisabledQueryValuesAreOmitted() {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collection(
                    """
                    {
                      "name": "Search",
                      "request": {
                        "method": "GET",
                        "url": {
                          "protocol": "https",
                          "host": ["example", "com"],
                          "path": ["v1", "items"],
                          "port": "8443",
                          "query": [
                            {"key": "q", "value": "1"},
                            {"key": "token", "value": "$QUERY_SECRET", "disabled": true}
                          ],
                          "hash": "top"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val http = output.files.single().content
        assertTrue(http.contains("GET https://example.com:8443/v1/items?q=1#top"))
        assertNoLeak(output, QUERY_SECRET)
        assertTrue(output.reportMarkdown.contains("Disabled query parameter 'token' was not imported."))
    }

    @Test
    fun webSocketAndGrpcAreSkipped() {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collection(
                    """
                    {"name":"Socket","request":{"method":"GET","url":"wss://example.com/live"}},
                    {"name":"Rpc","request":{"method":"POST","url":"grpc://localhost/demo.Greeter/SayHello"}}
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(0, output.requestsImported)
        assertEquals(2, output.requestsSkipped)
        assertTrue(output.files.isEmpty() || output.files.all { !it.content.contains("wss://") && !it.content.contains("grpc://") })
        assertTrue(output.reportMarkdown.contains("WebSocket requests are skipped."))
        assertTrue(output.reportMarkdown.contains("gRPC requests are skipped."))
    }

    @Test
    fun dynamicVariablesStayUnchangedUntilAMapperReplacesThem() {
        assertEquals("{{${'$'}uuid}}", ImportMappings.dynamicVariables.map("{{${'$'}uuid}}"))
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collection(
                    """{"name":"Id","request":{"method":"GET","url":"https://example.com/{{${'$'}uuid}}"}}""",
                ),
            ),
        )
        assertTrue(output.files.single().content.contains("{{${'$'}uuid}}"))
        assertTrue(output.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}' was left unchanged."))
    }

    @Test
    fun secretCollectionVariableIsOmitted() {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = """
                {
                  "info": {"name": "vars", "schema": "$COLLECTION_SCHEMA"},
                  "item": [{"name": "Ping", "request": {"method": "GET", "url": "https://example.com"}}],
                  "variable": [
                    {"key": "host", "value": "https://example.com"},
                    {"key": "token", "value": "$ENV_SECRET", "type": "secret"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertNoLeak(output, ENV_SECRET)
        assertTrue(output.files.single().content.contains("@host = https://example.com"))
        assertTrue(output.reportMarkdown.contains("Secret collection variable 'token' was omitted."))
    }

    private fun collection(items: String): String = """
        {
          "info": {"name": "sample", "schema": "$COLLECTION_SCHEMA"},
          "item": [$items]
        }
    """.trimIndent()

    private fun assertNoLeak(output: ImportOutput, secret: String) {
        assertFalse(output.reportMarkdown.contains(secret), "report leaked a secret value")
        assertFalse(output.publicEnvironmentJson?.contains(secret) == true, "public environment leaked a secret value")
        output.files.forEach { file ->
            assertFalse(file.content.contains(secret), "${file.relativePath} leaked a secret value")
        }
    }

    private companion object {
        const val ENV_SECRET = "rh-env-secret-do-not-leak"
        const val DISABLED_SECRET = "rh-disabled-secret-do-not-leak"
        const val AUTH_SECRET = "rh-auth-secret-do-not-leak"
        const val HEADER_SECRET = "rh-disabled-header-do-not-leak"
        const val FILE_SECRET = "rh-file-bytes-do-not-leak"
        const val RESPONSE_SECRET = "rh-response-body-do-not-leak"
        const val QUERY_SECRET = "rh-query-secret-do-not-leak"
    }
}
