package com.willcraig.requesthandoff.core

import java.time.Instant
import java.util.UUID

const val VERSION = "0.1.0"
const val REPORT_FILE_NAME = "requestHandoff-report.md"
const val COLLECTION_SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

data class HttpFileInput(
    val relativePath: String,
    val content: String?,
    val error: String? = null,
)

data class ExportInput(
    val collectionName: String,
    val files: List<HttpFileInput>,
    val singleFile: Boolean,
    val publicEnvironmentJson: String? = null,
    val privateEnvironmentJson: String? = null,
    val privateEnvironmentOmitted: Boolean = false,
)

data class ExportOptions(
    val ids: Iterator<String>,
    val exportedAt: String,
) {
    fun nextId(): String = ids.next()

    companion object {
        fun random(now: Instant = Instant.now()): ExportOptions = ExportOptions(
            ids = sequence { while (true) yield(UUID.randomUUID().toString()) }.iterator(),
            exportedAt = now.toString(),
        )

        fun fixed(exportedAt: String = "2026-01-15T00:00:00Z"): ExportOptions {
            var n = 1L
            return ExportOptions(
                ids = sequence {
                    while (true) {
                        yield("00000000-0000-4000-8000-%012d".format(n))
                        n++
                    }
                }.iterator(),
                exportedAt = exportedAt,
            )
        }
    }
}

data class EnvironmentOutput(
    val name: String,
    val fileName: String,
    val json: String,
)

data class ExportOutput(
    val collectionFileName: String,
    val collectionJson: String,
    val environments: List<EnvironmentOutput>,
    val reportFileName: String,
    val reportMarkdown: String,
    val requestsExported: Int,
    val requestsSkipped: Int,
    val warningCount: Int,
)

internal data class Header(val name: String, val value: String)

internal data class ScriptSource(val inline: String? = null, val file: String? = null)

internal sealed interface Body {
    data object None : Body
    data class Raw(val text: String, val language: String) : Body
    data class FileRef(val src: String) : Body
    data class UrlEncoded(val fields: List<Pair<String, String>>) : Body
    data class Multipart(val parts: List<Part>) : Body
    data class GraphQl(val query: String, val variables: String?) : Body
}

internal data class Part(
    val name: String,
    val text: String? = null,
    val src: String? = null,
    val contentType: String? = null,
)

internal data class HttpRequest(
    val name: String,
    val method: String,
    val url: String,
    val headers: List<Header>,
    val body: Body,
    val preRequestScripts: List<ScriptSource>,
    val responseScripts: List<ScriptSource>,
    val line: Int,
)

internal enum class Severity { SKIPPED, WARNING }

internal data class Note(
    val severity: Severity,
    val file: String,
    val request: String?,
    val message: String,
    val line: Int = 0,
)

internal data class ParsedFile(
    val variables: List<Pair<String, String>>,
    val requests: List<HttpRequest>,
    val notes: List<Note>,
)
