package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.JsonParseException
import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.arr
import com.willcraig.requesthandoff.core.json.obj
import com.willcraig.requesthandoff.core.json.parseJson
import com.willcraig.requesthandoff.core.json.render

fun exportHttpFiles(input: ExportInput, options: ExportOptions = ExportOptions.random()): ExportOutput {
    val notes = mutableListOf<Note>()
    val parsed = input.files.sortedBy { it.relativePath }.map { file ->
        if (file.content == null) {
            notes += Note(
                Severity.WARNING,
                file.relativePath,
                null,
                file.error ?: "Could not read the file.",
            )
            file.relativePath to ParsedFile(emptyList(), emptyList(), emptyList())
        } else {
            file.relativePath to parseHttpFile(file.relativePath, file.content)
        }
    }
    parsed.forEach { notes += it.second.notes }

    val variables = mergeVariables(parsed, notes)
    val requests = parsed.flatMap { (path, model) -> model.requests.map { path to it } }
    val items = if (input.singleFile) {
        arr(requests.map { requestItem(it.second) })
    } else {
        renderTree(requests)
    }
    val environments = buildEnvironments(input, options, notes)
    val collection = obj {
        put(
            "info",
            obj {
                put("name", input.collectionName.ifBlank { "requests" })
                put("_postman_id", options.nextId())
                put("description", "Exported by RequestHandoff $VERSION from JetBrains HTTP Client files.")
                put("schema", COLLECTION_SCHEMA)
            },
        )
        put("item", items)
        if (variables.isNotEmpty()) {
            put(
                "variable",
                arr(variables.map { (key, value) ->
                    obj {
                        put("key", key)
                        put("value", value)
                        put("type", "string")
                    }
                }),
            )
        }
    }
    val collectionFileName = "${fileSlug(input.collectionName.ifBlank { "requests" })}.postman_collection.json"
    val collectionJson = render(collection)
    val ordered = notes.sortedWith(compareBy({ it.file }, { it.line }, { it.severity }, { it.message }))
    val exported = requests.size
    val skipped = ordered.count { it.severity == Severity.SKIPPED }
    val warnings = ordered.count { it.severity == Severity.WARNING }
    val report = renderReport(
        exported = exported,
        skipped = skipped,
        warnings = warnings,
        notes = ordered,
        collectionFileName = collectionFileName,
        environments = environments,
    )
    return ExportOutput(
        collectionFileName = collectionFileName,
        collectionJson = collectionJson,
        environments = environments,
        reportFileName = REPORT_FILE_NAME,
        reportMarkdown = report,
        requestsExported = exported,
        requestsSkipped = skipped,
        warningCount = warnings,
    )
}

private fun mergeVariables(
    parsed: List<Pair<String, ParsedFile>>,
    notes: MutableList<Note>,
): List<Pair<String, String>> {
    val values = linkedMapOf<String, String>()
    val origin = linkedMapOf<String, String>()
    for ((path, model) in parsed) {
        for ((key, value) in model.variables) {
            val previous = origin[key]
            if (previous != null && previous != path && values[key] != value) {
                notes += Note(
                    Severity.WARNING,
                    path,
                    null,
                    "Collection variable '$key' is also defined in $previous. Using the value from $path.",
                )
            }
            values[key] = value
            origin[key] = path
        }
    }
    return values.entries.map { it.key to it.value }
}

private class Folder {
    val children = linkedMapOf<String, Folder>()
    val requests = mutableListOf<HttpRequest>()
}

private fun renderTree(requests: List<Pair<String, HttpRequest>>): Jv.Arr {
    val root = Folder()
    for ((path, request) in requests) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        var node = root
        for (directory in parts.dropLast(1)) {
            node = node.children.getOrPut(directory) { Folder() }
        }
        val fileNode = node.children.getOrPut(fileStem(parts.last())) { Folder() }
        fileNode.requests += request
    }
    return renderFolder(root)
}

private fun renderFolder(folder: Folder): Jv.Arr {
    val items = mutableListOf<Jv>()
    for (name in folder.children.keys.sorted()) {
        val child = folder.children.getValue(name)
        items += obj {
            put("name", name)
            put("item", renderFolder(child))
        }
    }
    folder.requests.forEach { items += requestItem(it) }
    return arr(items)
}

private fun requestItem(request: HttpRequest): Jv.Obj = obj {
    put("name", request.name)
    val events = scriptEvents(request)
    if (events.isNotEmpty()) put("event", arr(events))
    put(
        "request",
        obj {
            put("method", request.method)
            if (request.headers.isNotEmpty()) {
                put("header", arr(request.headers.map { header ->
                    obj {
                        put("key", header.name)
                        put("value", header.value)
                    }
                }))
            }
            val body = bodyJson(request.body)
            if (body != null) put("body", body)
            put("url", postmanUrl(request.url))
        },
    )
}

private fun bodyJson(body: Body): Jv? = when (body) {
    Body.None -> null
    is Body.Raw -> obj {
        put("mode", "raw")
        put("raw", body.text)
        put("options", obj { put("raw", obj { put("language", body.language) }) })
    }
    is Body.FileRef -> obj {
        put("mode", "file")
        put("file", obj { put("src", body.src) })
    }
    is Body.UrlEncoded -> obj {
        put("mode", "urlencoded")
        put("urlencoded", arr(body.fields.map { (key, value) ->
            obj {
                put("key", key)
                put("value", value)
            }
        }))
    }
    is Body.Multipart -> obj {
        put("mode", "formdata")
        put("formdata", arr(body.parts.map { part ->
            if (part.src != null) {
                obj {
                    put("key", part.name)
                    put("src", part.src)
                    put("type", "file")
                    put("contentType", part.contentType)
                }
            } else {
                obj {
                    put("key", part.name)
                    put("value", part.text ?: "")
                    put("type", "text")
                    put("contentType", part.contentType)
                }
            }
        }))
    }
    is Body.GraphQl -> obj {
        put("mode", "graphql")
        put("graphql", obj {
            put("query", body.query)
            put("variables", body.variables ?: "")
        })
    }
}

private fun scriptEvents(request: HttpRequest): List<Jv> {
    val events = mutableListOf<Jv>()
    request.preRequestScripts.forEach { events += scriptEvent("prerequest", it, "pre-request script") }
    request.responseScripts.forEach { events += scriptEvent("test", it, "response handler") }
    return events
}

private fun scriptEvent(listen: String, script: ScriptSource, label: String): Jv = obj {
    put("listen", listen)
    put("script", obj {
        put("type", "text/javascript")
        put("exec", arr(commentLines(script, label).map { Jv.Str(it) }))
    })
}

private fun commentLines(script: ScriptSource, label: String): List<String> {
    val lines = mutableListOf("// RequestHandoff v$VERSION does not translate HTTP Client scripts.")
    if (script.file != null) {
        lines += "// Original $label file (not inlined): ${script.file}"
    }
    if (script.inline != null) {
        lines += "// Original $label:"
        script.inline.lineSequence().forEach { line ->
            lines += if (line.isEmpty()) "//" else "// $line"
        }
    }
    return lines
}

private fun buildEnvironments(
    input: ExportInput,
    options: ExportOptions,
    notes: MutableList<Note>,
): List<EnvironmentOutput> {
    val public = readEnvironments(input.publicEnvironmentJson, "http-client.env.json", notes)
    val private = readEnvironments(input.privateEnvironmentJson, "http-client.private.env.json", notes)
    if (input.privateEnvironmentOmitted) {
        notes += Note(
            Severity.WARNING,
            "http-client.private.env.json",
            null,
            "Private environment file was present and was not read. Private values are excluded unless you explicitly enable them.",
        )
    }
    val names = LinkedHashSet<String>()
    names += public.keys
    names += private.keys
    val used = mutableSetOf<String>()
    return names.map { name ->
        val values = linkedMapOf<String, Pair<String, Boolean>>()
        public[name].orEmpty().forEach { (key, value) -> values[key] = value to false }
        private[name].orEmpty().forEach { (key, value) -> values[key] = value to true }
        val fileName = uniqueEnvironmentFile(name, used)
        EnvironmentOutput(name, fileName, render(environmentDocument(name, values, options)))
    }
}

private fun uniqueEnvironmentFile(name: String, used: MutableSet<String>): String {
    val slug = fileSlug(name)
    var fileName = "$slug.postman_environment.json"
    var suffix = 2
    while (!used.add(fileName)) {
        fileName = "$slug-$suffix.postman_environment.json"
        suffix++
    }
    return fileName
}

private fun environmentDocument(
    name: String,
    values: Map<String, Pair<String, Boolean>>,
    options: ExportOptions,
): Jv = obj {
    put("id", options.nextId())
    put("name", name)
    put("values", arr(values.map { (key, value) ->
        obj {
            put("key", key)
            put("value", value.first)
            put("type", if (value.second) "secret" else "default")
            put("enabled", true)
        }
    }))
    put("_postman_variable_scope", "environment")
    put("_postman_exported_at", options.exportedAt)
    put("_postman_exported_using", "RequestHandoff/$VERSION")
}

private fun readEnvironments(
    json: String?,
    file: String,
    notes: MutableList<Note>,
): Map<String, List<Pair<String, String>>> {
    if (json.isNullOrBlank()) return emptyMap()
    val root = try {
        parseJson(json)
    } catch (ex: JsonParseException) {
        notes += Note(Severity.WARNING, file, null, "Environment file is not valid JSON (${ex.message}).")
        return emptyMap()
    }
    if (root !is Jv.Obj) {
        notes += Note(Severity.WARNING, file, null, "Environment file must be a JSON object of named environments.")
        return emptyMap()
    }
    val environments = linkedMapOf<String, List<Pair<String, String>>>()
    for ((name, value) in root.fields) {
        if (value !is Jv.Obj) {
            notes += Note(Severity.WARNING, file, null, "Environment '$name' is not a JSON object and was skipped.")
            continue
        }
        environments[name] = flattenEnvironment(value)
    }
    return environments
}

private fun flattenEnvironment(value: Jv.Obj): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    fun walk(prefix: String, node: Jv) {
        when (node) {
            is Jv.Obj -> {
                if (node.fields.isEmpty() && prefix.isNotEmpty()) {
                    out += prefix to "{}"
                }
                node.fields.forEach { (key, child) ->
                    val next = if (prefix.isEmpty()) key else "$prefix.$key"
                    walk(next, child)
                }
            }
            is Jv.Arr -> out += prefix to render(node).trimEnd()
            is Jv.Str -> out += prefix to node.value
            is Jv.Num -> out += prefix to node.literal
            is Jv.Bool -> out += prefix to node.value.toString()
            Jv.Null -> out += prefix to ""
        }
    }
    walk("", value)
    return out
}

private fun renderReport(
    exported: Int,
    skipped: Int,
    warnings: Int,
    notes: List<Note>,
    collectionFileName: String,
    environments: List<EnvironmentOutput>,
): String = buildString {
    append("# RequestHandoff export report\n\n")
    append("Requests exported: $exported\n")
    append("Requests skipped: $skipped\n")
    append("Warnings: $warnings\n\n")
    append("## Limitations\n\n")
    append("These limits apply to every v0.1 export:\n\n")
    append("- Response-handler and pre-request scripts are not translated. Inline scripts are copied into the collection as commented-out script text. Script files are named in those comments and are not inlined.\n")
    append("- WebSocket and gRPC requests are skipped.\n")
    append("- Authorization headers are copied as headers. Postman auth objects are not created.\n")
    append("- Dynamic variables such as `{{${'$'}uuid}}` are left unchanged.\n\n")
    append("## Skipped requests\n\n")
    append(section(notes.filter { it.severity == Severity.SKIPPED }))
    append("## Not converted\n\n")
    append(section(notes.filter { it.severity == Severity.WARNING }))
    append("## Environments\n\n")
    if (environments.isEmpty()) {
        append("No environment files were written.\n\n")
    } else {
        append("Named environments written: ")
        append(environments.joinToString(", ") { it.name })
        append(".\n\n")
    }
    append("## Output files\n\n")
    append("- `$collectionFileName`\n")
    environments.forEach { append("- `${it.fileName}`\n") }
    append("- `$REPORT_FILE_NAME`\n")
}

private fun section(notes: List<Note>): String {
    if (notes.isEmpty()) return "None.\n\n"
    return notes.joinToString("\n") { note ->
        val where = if (note.request.isNullOrBlank()) note.file else "${note.file} — ${note.request}"
        "- $where — ${note.message}"
    } + "\n\n"
}
