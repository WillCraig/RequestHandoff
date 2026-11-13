package com.willcraig.requesthandoff.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class GoldenFixtureTest {
    @Test
    fun collectionReportAndEnvironmentMatchGoldenFiles() {
        val output = exportHttpFiles(
            ExportInput(
                collectionName = "api",
                singleFile = false,
                files = listOf(
                    HttpFileInput(
                        "health.http",
                        """
                        @host = https://example.com
                        GET {{host}}/health
                        Authorization: Bearer {{token}}
                        """.trimIndent(),
                    ),
                    HttpFileInput(
                        "users/create.http",
                        """
                        ### Create user
                        < {%
                        request.variables.set("name", "Ada");
                        %}
                        POST {{host}}/users
                        Content-Type: application/json
                        X-REQUEST-TYPE: GraphQL

                        mutation {
                          create(name: "{{name}}") { id }
                        }

                        {
                          "name": "{{name}}"
                        }
                        """.trimIndent(),
                    ),
                ),
                publicEnvironmentJson = """{"dev":{"host":"https://dev.example","auth":{"user":"ada"}}}""",
                privateEnvironmentJson = null,
                privateEnvironmentOmitted = true,
            ),
            ExportOptions.fixed(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        assertGolden("api.postman_collection.json", output.collectionJson)
        assertGolden("requestHandoff-report.md", output.reportMarkdown)
        assertEquals(1, output.environments.size)
        assertGolden(output.environments.single().fileName, output.environments.single().json)
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
