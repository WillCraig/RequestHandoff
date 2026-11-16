package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.JsonParseException
import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.obj
import com.willcraig.requesthandoff.core.json.parseJson
import com.willcraig.requesthandoff.core.json.render

const val IMPORT_REPORT_FILE_NAME = "requestHandoff-import-report.md"
const val PUBLIC_ENVIRONMENT_FILE_NAME = "http-client.env.json"
const val PRIVATE_ENVIRONMENT_FILE_NAME = "http-client.private.env.json"

data class ImportInput(
    val collectionJson: String,
    val collectionFileName: String = "collection.json",
    val environmentJson: String? = null,
    val environmentFileName: String? = null,
)

data class ImportFile(
    val relativePath: String,
    val content: String,
)

data class ImportOutput(
    val files: List<ImportFile>,
    val publicEnvironmentJson: String?,
    val privateEnvironmentJson: String?,
    val reportFileName: String,
    val reportMarkdown: String,
    val requestsImported: Int,
    val requestsSkipped: Int,
    val warningCount: Int,
)

/**
 * Imports a Postman Collection v2.1 document, and an optional Postman environment,
 * into HTTP Client files. Secret environment values are returned only in
 * [ImportOutput.privateEnvironmentJson].
 */
fun importPostmanCollection(input: ImportInput): ImportOutput {
    val notes = mutableListOf<Note>()
    val root = try {
        parseJson(input.collectionJson)
    } catch (ex: JsonParseException) {
        notes += Note(Severity.WARNING, input.collectionFileName, null, "Collection file is not valid JSON (${ex.message}).")
        return finish(emptyList(), null, null, notes)
    }
    if (root !is Jv.Obj) {
        notes += Note(Severity.WARNING, input.collectionFileName, null, "Collection JSON must be an object.")
        return finish(emptyList(), null, null, notes)
    }
    val info = root.fields["info"] as? Jv.Obj
    val schema = info?.fields?.get("schema")?.scalar()
    if (schema == null) {
        notes += Note(Severity.WARNING, input.collectionFileName, null, "Collection info.schema is missing. Import used v2.1 rules.")
    } else if (!schema.contains("v2.1")) {
        notes += Note(
            Severity.WARNING,
            input.collectionFileName,
            null,
            "Collection schema is not v2.1. Import used v2.1 rules.",
        )
    }
    val collectionName = info?.fields?.get("name")?.scalar()?.trim().orEmpty().ifEmpty {
        defaultCollectionName(input.collectionFileName)
    }
    val group = Group(collectionName)
    descriptionComment(info?.fields?.get("description"))?.let { group.comments += it }
    readVariables(root.fields["variable"], input.collectionFileName, null, group, notes)
    readScripts(root.fields["event"], input.collectionFileName, null, group, notes)
    noteAuth(root.fields["auth"], input.collectionFileName, null, applyHeaders = null, notes)
    val items = root.fields["item"] as? Jv.Arr
    if (items == null) {
        notes += Note(Severity.WARNING, input.collectionFileName, null, "Collection item array is missing.")
    } else {
        items.items.forEach { readItem(it, group, input.collectionFileName, notes) }
    }
    val files = emitFiles(group, notes)
    val environment = readEnvironment(input, notes)
    return finish(files, environment?.publicJson, environment?.privateJson, notes)
}

private data class PendingNote(val severity: Severity, val request: String?, val message: String)

private data class PendingRequest(val request: WrittenRequest, val notes: MutableList<PendingNote>)

private class Group(val name: String) {
    val requests = mutableListOf<PendingRequest>()
    val children = linkedMapOf<String, Group>()
    val variables = mutableListOf<Pair<String, String>>()
    val comments = mutableListOf<String>()
    val notes = mutableListOf<PendingNote>()
}

private data class EnvironmentWrite(val publicJson: String?, val privateJson: String?)

private data class FileSpec(
    val relativePath: String,
    val comments: List<String>,
    val variables: List<Pair<String, String>>,
    val requests: List<PendingRequest>,
    val notes: List<PendingNote>,
)

private fun readItem(value: Jv, parent: Group, collectionFile: String, notes: MutableList<Note>) {
    val item = value as? Jv.Obj
    if (item == null) {
        notes += Note(Severity.WARNING, collectionFile, null, "A collection item was not a JSON object and was skipped.")
        return
    }
    val name = item.fields["name"]?.scalar()?.trim().orEmpty().ifEmpty { "request" }
    val children = item.fields["item"] as? Jv.Arr
    if (children != null) {
        val folder = childGroup(parent, name, notes, collectionFile)
        descriptionComment(item.fields["description"])?.let { folder.comments += it }
        readVariables(item.fields["variable"], collectionFile, name, folder, notes)
        readScripts(item.fields["event"], collectionFile, name, folder, notes)
        noteAuth(item.fields["auth"], collectionFile, name, applyHeaders = null, notes)
        children.items.forEach { readItem(it, folder, collectionFile, notes) }
        if (item.fields["request"] != null) {
            folder.notes += PendingNote(
                Severity.WARNING,
                name,
                "Folder '$name' also had a request. That request was imported into the folder.",
            )
            readRequest(item, name, folder, collectionFile, notes)
        }
        return
    }
    readRequest(item, name, parent, collectionFile, notes)
}

private fun childGroup(parent: Group, name: String, notes: MutableList<Note>, file: String): Group {
    if (!parent.children.containsKey(name)) return parent.children.getOrPut(name) { Group(name) }
    var suffix = 2
    var unique = "$name-$suffix"
    while (parent.children.containsKey(unique)) {
        suffix++
        unique = "$name-$suffix"
    }
    notes += Note(Severity.WARNING, file, null, "Folder '$name' was renamed to '$unique' because the name was already used.")
    return parent.children.getOrPut(unique) { Group(unique) }
}

private fun readRequest(
    item: Jv.Obj,
    name: String,
    parent: Group,
    collectionFile: String,
    notes: MutableList<Note>,
) {
    val requestNotes = mutableListOf<PendingNote>()
    val responseCount = (item.fields["response"] as? Jv.Arr)?.items?.size ?: 0
    if (responseCount > 0) {
        requestNotes += PendingNote(
            Severity.WARNING,
            name,
            "$responseCount saved response ${if (responseCount == 1) "example was" else "examples were"} not imported.",
        )
    }
    val requestValue = item.fields["request"]
    val request = when (requestValue) {
        is Jv.Str -> null
        is Jv.Obj -> requestValue
        null -> {
            requestNotes += PendingNote(Severity.SKIPPED, name, "Request '$name' had no request and was skipped.")
            parent.notes += requestNotes
            return
        }
        else -> {
            requestNotes += PendingNote(Severity.SKIPPED, name, "Request '$name' was not an object or URL and was skipped.")
            parent.notes += requestNotes
            return
        }
    }
    var method = request?.fields?.get("method")?.scalar()?.trim().orEmpty()
    var url = when (requestValue) {
        is Jv.Str -> requestValue.value
        else -> request?.fields?.get("url")?.let { urlText(it, requestNotes, name) }.orEmpty()
    }
    if (method.isEmpty()) {
        method = "GET"
        if (requestValue !is Jv.Str) {
            requestNotes += PendingNote(Severity.WARNING, name, "Request method was missing and was imported as GET.")
        }
    }
    method = sanitizeMethod(method)
    val encodedUrl = sanitizeUrl(url)
    if (encodedUrl != url.trim()) {
        requestNotes += PendingNote(Severity.WARNING, name, "URL contained whitespace and spaces were encoded as %20.")
    }
    url = encodedUrl
    if (url.isEmpty()) {
        requestNotes += PendingNote(Severity.SKIPPED, name, "Request '$name' had no URL and was skipped.")
        parent.notes += requestNotes
        return
    }
    val skip = skipReason(method, url) ?: profileSkip(request?.fields?.get("protocolProfileBehavior") as? Jv.Obj)
    if (skip != null) {
        requestNotes += PendingNote(Severity.SKIPPED, name, skip)
        parent.notes += requestNotes
        return
    }
    if (request?.fields?.get("proxy") != null) {
        requestNotes += PendingNote(Severity.WARNING, name, "Proxy configuration was not imported.")
    }
    if (request?.fields?.get("certificate") != null) {
        requestNotes += PendingNote(Severity.WARNING, name, "Client certificate was not imported.")
    }
    val headers = readHeaders(request?.fields?.get("header"), requestNotes, name)
    noteAuth(request?.fields?.get("auth"), collectionFile, name, headers, notes)
    var body = readBody(request?.fields?.get("body"), requestNotes, name)
    val dynamicNotes = mutableListOf<String>()
    url = mapDynamic(url, dynamicNotes)
    for (index in headers.indices) {
        headers[index] = Header(headers[index].name, mapDynamic(headers[index].value, dynamicNotes))
    }
    body = mapBody(body, dynamicNotes)
    dynamicNotes.distinct().forEach { token ->
        requestNotes += PendingNote(Severity.WARNING, name, "Dynamic variable '$token' was left unchanged.")
    }
    val adjustment = headersForBody(headers, body, method)
    adjustment.notes.distinct().forEach { requestNotes += PendingNote(Severity.WARNING, name, it) }
    val comments = mutableListOf<String>()
    descriptionComment(item.fields["description"])?.let { comments += it }
    descriptionComment(request?.fields?.get("description"))?.let { comments += it }
    readVariables(item.fields["variable"], collectionFile, name, parent, notes)
    val scripts = readScripts(item.fields["event"], collectionFile, name, parent, notes, comments, requestNotes)
    val written = WrittenRequest(
        name = name,
        comments = comments,
        preScripts = scripts.preRequest,
        method = method,
        url = url,
        headers = adjustment.headers,
        body = body,
        responseScripts = scripts.response,
    )
    parent.requests += PendingRequest(written, requestNotes)
    parent.notes += requestNotes
}

private fun readHeaders(value: Jv?, notes: MutableList<PendingNote>, request: String): MutableList<Header> {
    val headers = mutableListOf<Header>()
    when (value) {
        null -> return headers
        is Jv.Str -> value.value.split('\n').forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val match = HEADER_LINE.matchEntire(trimmed)
            if (match == null) {
                notes += PendingNote(Severity.WARNING, request, "Ignored unrecognized header line.")
            } else {
                headers += Header(match.groupValues[1].trim(), match.groupValues[2])
            }
        }
        is Jv.Arr -> value.items.forEach { item ->
            val header = item as? Jv.Obj
            if (header == null) {
                notes += PendingNote(Severity.WARNING, request, "A header was not a JSON object and was skipped.")
                return@forEach
            }
            if (header.fields["disabled"].isTrue()) {
                val key = header.fields["key"]?.scalar() ?: header.fields["name"]?.scalar() ?: "header"
                notes += PendingNote(Severity.WARNING, request, "Disabled header '$key' was not imported.")
                return@forEach
            }
            val key = header.fields["key"]?.scalar() ?: header.fields["name"]?.scalar()
            if (key.isNullOrBlank()) {
                notes += PendingNote(Severity.WARNING, request, "A header without a name was skipped.")
                return@forEach
            }
            val raw = header.fields["value"]?.scalar() ?: ""
            val single = raw.replace('\n', ' ').replace('\r', ' ')
            if (single != raw) {
                notes += PendingNote(
                    Severity.WARNING,
                    request,
                    "Header '$key' contained a line break. The line break was replaced with a space.",
                )
            }
            headers += Header(key.trim(), single)
        }
        else -> notes += PendingNote(Severity.WARNING, request, "Headers were not a list or a string and were skipped.")
    }
    return headers
}

private fun readBody(value: Jv?, notes: MutableList<PendingNote>, request: String): Body {
    val body = value as? Jv.Obj ?: return Body.None
    if (body.fields["disabled"].isTrue()) {
        notes += PendingNote(Severity.WARNING, request, "Request body was disabled and was not imported.")
        return Body.None
    }
    val mode = body.fields["mode"]?.scalar()?.lowercase() ?: inferMode(body)
    return when (mode) {
        "raw" -> {
            val text = body.fields["raw"]?.scalar() ?: ""
            val language = ((body.fields["options"] as? Jv.Obj)?.fields?.get("raw") as? Jv.Obj)
                ?.fields?.get("language")?.scalar()
                ?: if (looksLikeJsonText(text)) "json" else "text"
            if (text.trimEnd().isEmpty()) Body.None else Body.Raw(text.trimEnd(), language)
        }
        "urlencoded" -> {
            val fields = readUrlEncoded(body.fields["urlencoded"], notes, request)
            if (fields.any { it.first.contains('&') || it.first.contains('=') || it.second.contains('&') || it.second.contains('\n') }) {
                notes += PendingNote(
                    Severity.WARNING,
                    request,
                    "A urlencoded field contains '&' or a line break, which HTTP Client form bodies cannot escape.",
                )
            }
            Body.UrlEncoded(fields)
        }
        "formdata" -> Body.Multipart(readFormData(body.fields["formdata"], notes, request))
        "file" -> {
            val file = body.fields["file"] as? Jv.Obj
            val src = file?.fields?.get("src")?.scalar()?.trim()
            if (file?.fields?.containsKey("content") == true) {
                notes += PendingNote(Severity.WARNING, request, "Inline file bytes were not imported.")
            }
            if (src.isNullOrEmpty()) {
                notes += PendingNote(Severity.WARNING, request, "File body had no src and was skipped.")
                Body.None
            } else {
                Body.FileRef(src)
            }
        }
        "graphql" -> {
            val graphql = body.fields["graphql"] as? Jv.Obj
            if (graphql == null) {
                notes += PendingNote(Severity.WARNING, request, "GraphQL body had no query and was skipped.")
                Body.None
            } else {
                val query = graphqlText(graphql.fields["query"])
                val variables = graphqlText(graphql.fields["variables"])
                Body.GraphQl(query, variables)
            }
        }
        else -> {
            if (mode != null) {
                notes += PendingNote(Severity.WARNING, request, "Body mode '$mode' was imported as text.")
            }
            val text = body.fields["raw"]?.scalar() ?: ""
            if (text.trimEnd().isEmpty()) Body.None else Body.Raw(text.trimEnd(), "text")
        }
    }
}

private fun inferMode(body: Jv.Obj): String? = when {
    body.fields["urlencoded"] != null -> "urlencoded"
    body.fields["formdata"] != null -> "formdata"
    body.fields["file"] != null -> "file"
    body.fields["graphql"] != null -> "graphql"
    body.fields["raw"] != null -> "raw"
    else -> null
}

private fun readUrlEncoded(value: Jv?, notes: MutableList<PendingNote>, request: String): List<Pair<String, String>> {
    val items = (value as? Jv.Arr)?.items ?: return emptyList()
    return items.mapNotNull { item ->
        val field = item as? Jv.Obj ?: return@mapNotNull null
        val key = field.fields["key"]?.scalar() ?: return@mapNotNull null
        if (field.fields["disabled"].isTrue()) {
            notes += PendingNote(Severity.WARNING, request, "Disabled form field '$key' was not imported.")
            return@mapNotNull null
        }
        key to (field.fields["value"]?.scalar() ?: "")
    }
}

private fun readFormData(value: Jv?, notes: MutableList<PendingNote>, request: String): List<Part> {
    val items = (value as? Jv.Arr)?.items ?: return emptyList()
    return items.mapNotNull { item ->
        val field = item as? Jv.Obj ?: return@mapNotNull null
        val key = field.fields["key"]?.scalar() ?: return@mapNotNull null
        if (field.fields["disabled"].isTrue()) {
            notes += PendingNote(Severity.WARNING, request, "Disabled form field '$key' was not imported.")
            return@mapNotNull null
        }
        val type = field.fields["type"]?.scalar()?.lowercase()
        val contentType = field.fields["contentType"]?.scalar()
        val src = field.fields["src"]
        val srcText = when (src) {
            is Jv.Str -> src.value.trim().ifEmpty { null }
            is Jv.Arr -> {
                val first = src.items.firstOrNull()?.scalar()?.trim()?.ifEmpty { null }
                if (src.items.size > 1) {
                    notes += PendingNote(
                        Severity.WARNING,
                        request,
                        "Multipart file '$key' listed multiple sources. The first source was used.",
                    )
                }
                first
            }
            else -> null
        }
        if (type == "file" || (type == null && srcText != null)) {
            if (srcText == null) {
                notes += PendingNote(Severity.WARNING, request, "Multipart file '$key' had no src and was skipped.")
                return@mapNotNull null
            }
            Part(name = key, src = srcText, contentType = contentType)
        } else {
            Part(name = key, text = field.fields["value"]?.scalar() ?: "", contentType = contentType)
        }
    }
}

private fun urlText(value: Jv, notes: MutableList<PendingNote>, request: String): String = when (value) {
    is Jv.Str -> value.value.trim()
    is Jv.Obj -> {
        val raw = value.fields["raw"]?.scalar()?.trim()
        if (!raw.isNullOrEmpty()) raw else buildUrl(value, notes, request)
    }
    else -> ""
}

private fun buildUrl(url: Jv.Obj, notes: MutableList<PendingNote>, request: String): String {
    val protocol = url.fields["protocol"]?.scalar()?.trim()?.trimEnd(':')?.ifEmpty { null }
    val host = hostText(url.fields["host"])
    val port = url.fields["port"]?.scalar()?.trim()?.ifEmpty { null }
    val path = pathText(url.fields["path"])
    val query = queryText(url.fields["query"], notes, request)
    val hash = url.fields["hash"]?.scalar()
    val base = when {
        host.isEmpty() -> path.ifEmpty { "" }
        protocol != null -> buildString {
            append(protocol)
            append("://")
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
            if (path.isNotEmpty()) {
                if (!path.startsWith("/")) append('/')
                append(path)
            }
        }
        else -> buildString {
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
            if (path.isNotEmpty()) {
                if (!path.startsWith("/")) append('/')
                append(path)
            }
        }
    }
    val withQuery = if (query.isEmpty()) base else if (base.contains('?')) "$base&$query" else "$base?$query"
    return if (hash.isNullOrEmpty()) withQuery else "$withQuery#$hash"
}

private fun hostText(value: Jv?): String = when (value) {
    is Jv.Str -> value.value.trim()
    is Jv.Arr -> value.items.mapNotNull { it.scalar() }.filter { it.isNotEmpty() }.joinToString(".")
    else -> ""
}

private fun pathText(value: Jv?): String = when (value) {
    is Jv.Str -> value.value.trim().trimStart('/')
    is Jv.Arr -> value.items.map { segment ->
        when (segment) {
            is Jv.Str -> segment.value
            is Jv.Obj -> segment.fields["value"]?.scalar() ?: ""
            else -> segment.scalar() ?: ""
        }
    }.filter { it.isNotEmpty() }.joinToString("/")
    else -> ""
}

private fun queryText(value: Jv?, notes: MutableList<PendingNote>, request: String): String {
    val items = (value as? Jv.Arr)?.items ?: return ""
    return items.mapNotNull { item ->
        val field = item as? Jv.Obj ?: return@mapNotNull null
        val key = field.fields["key"]?.scalar() ?: return@mapNotNull null
        if (key.isEmpty()) return@mapNotNull null
        if (field.fields["disabled"].isTrue()) {
            notes += PendingNote(Severity.WARNING, request, "Disabled query parameter '$key' was not imported.")
            return@mapNotNull null
        }
        val queryValue = field.fields["value"]
        if (queryValue == null || queryValue is Jv.Null) key else "$key=${queryValue.scalar() ?: ""}"
    }.joinToString("&")
}

private fun readVariables(
    value: Jv?,
    file: String,
    owner: String?,
    group: Group,
    notes: MutableList<Note>,
) {
    val items = (value as? Jv.Arr)?.items ?: return
    var kept = 0
    items.forEach { item ->
        val variable = item as? Jv.Obj ?: return@forEach
        val key = variable.fields["key"]?.scalar() ?: variable.fields["id"]?.scalar() ?: return@forEach
        if (variable.fields["disabled"].isTrue()) {
            notes += Note(Severity.WARNING, file, owner, "Disabled variable '$key' was not imported.")
            return@forEach
        }
        val type = variable.fields["type"]?.scalar().orEmpty()
        if (type.equals("secret", ignoreCase = true)) {
            notes += Note(Severity.WARNING, file, owner, "Secret collection variable '$key' was omitted.")
            return@forEach
        }
        if (!VARIABLE_NAME.matches(key)) {
            notes += Note(
                Severity.WARNING,
                file,
                owner,
                "Collection variable '$key' was omitted because it is not a valid HTTP Client variable name.",
            )
            return@forEach
        }
        var text = variable.fields["value"]?.scalar() ?: ""
        if (text.contains('\n') || text.contains('\r')) {
            text = text.replace('\r', ' ').replace('\n', ' ')
            notes += Note(
                Severity.WARNING,
                file,
                owner,
                "Variable '$key' contained a line break. The line break was replaced with a space.",
            )
        }
        val dynamic = mutableListOf<String>()
        text = mapDynamic(text.trim(), dynamic)
        dynamic.distinct().forEach { token ->
            notes += Note(Severity.WARNING, file, owner, "Dynamic variable '$token' was left unchanged.")
        }
        group.variables += key to text
        kept++
    }
    if (kept > 0 && owner != null) {
        notes += Note(Severity.WARNING, file, owner, "Variables on '$owner' were written as HTTP Client file variables.")
    }
}

private fun readScripts(
    value: Jv?,
    file: String,
    owner: String?,
    group: Group,
    notes: MutableList<Note>,
    comments: MutableList<String>? = null,
    requestNotes: MutableList<PendingNote>? = null,
): ScriptMapping {
    val views = scriptViews(value, includeDisabled = true) ?: return ScriptMapping()
    val enabled = views.filter { !it.listen.startsWith("disabled:") }
    val disabled = views.filter { it.listen.startsWith("disabled:") }
    disabled.forEach { script ->
        val listen = script.listen.removePrefix("disabled:")
        val message = "Disabled script event '$listen' was not imported."
        if (requestNotes != null) requestNotes += PendingNote(Severity.WARNING, owner, message)
        else notes += Note(Severity.WARNING, file, owner, message)
    }
    if (enabled.isEmpty()) return ScriptMapping()
    val mapped = ImportMappings.scripts.map(enabled)
    val target = comments ?: group.comments
    target += mapped.comments
    if (comments == null) {
        mapped.preRequest.forEach { target += scriptComment(it) }
        mapped.response.forEach { target += scriptComment(it) }
    }
    mapped.notes.forEach { message ->
        if (requestNotes != null) requestNotes += PendingNote(Severity.WARNING, owner, message)
        else notes += Note(Severity.WARNING, file, owner, message)
    }
    return mapped
}

private fun scriptComment(script: ScriptSource): String = buildString {
    if (script.file != null) append("Script file (not inlined): ").append(script.file)
    if (script.inline != null) {
        if (isNotEmpty()) append('\n')
        append(script.inline)
    }
}

private fun scriptViews(value: Jv?, includeDisabled: Boolean): List<PostmanScriptView>? {
    val items = (value as? Jv.Arr)?.items ?: return null
    if (items.isEmpty()) return null
    return items.mapNotNull { item ->
        val event = item as? Jv.Obj ?: return@mapNotNull null
        val listen = event.fields["listen"]?.scalar()?.ifEmpty { null } ?: "script"
        val script = event.fields["script"] as? Jv.Obj
        val exec = when (val raw = script?.fields?.get("exec")) {
            is Jv.Arr -> raw.items.map { it.scalar() ?: "" }
            is Jv.Str -> raw.value.split('\n')
            else -> emptyList()
        }
        val source = scriptSource(script?.fields?.get("src"))
        if (event.fields["disabled"].isTrue()) {
            if (!includeDisabled) return@mapNotNull null
            PostmanScriptView(listen = "disabled:$listen", exec = emptyList(), source = null)
        } else {
            PostmanScriptView(listen = listen, exec = exec, source = source)
        }
    }
}

private fun scriptSource(value: Jv?): String? = when (value) {
    is Jv.Str -> value.value.trim().ifEmpty { null }
    is Jv.Obj -> value.fields["raw"]?.scalar()?.trim()?.ifEmpty { null }
    else -> null
}

private fun noteAuth(
    value: Jv?,
    file: String,
    request: String?,
    applyHeaders: MutableList<Header>?,
    notes: MutableList<Note>,
) {
    val auth = value as? Jv.Obj ?: return
    val type = auth.fields["type"]?.scalar()?.trim().orEmpty().ifEmpty { "unknown" }
    val attributes = mutableListOf<AuthAttribute>()
    auth.fields.forEach { (key, child) ->
        if (key == "type") return@forEach
        when (child) {
            is Jv.Arr -> child.items.forEach { item ->
                val attribute = item as? Jv.Obj ?: return@forEach
                val name = attribute.fields["key"]?.scalar() ?: return@forEach
                attributes += AuthAttribute(name, attribute.fields["value"]?.scalar() ?: "")
            }
            is Jv.Str -> attributes += AuthAttribute(key, child.value)
            else -> Unit
        }
    }
    val mapped = ImportMappings.auth.map(PostmanAuthView(type, attributes))
    mapped.notes.forEach { notes += Note(Severity.WARNING, file, request, it) }
    if (applyHeaders != null) {
        mapped.headers.forEach { header ->
            if (applyHeaders.none { it.name.equals(header.name, ignoreCase = true) }) applyHeaders += header
        }
    }
}

private fun readEnvironment(input: ImportInput, notes: MutableList<Note>): EnvironmentWrite? {
    val json = input.environmentJson
    if (json.isNullOrBlank()) return null
    val file = input.environmentFileName ?: "environment.json"
    val root = try {
        parseJson(json)
    } catch (ex: JsonParseException) {
        notes += Note(Severity.WARNING, file, null, "Environment file is not valid JSON (${ex.message}).")
        return null
    }
    val environment = root as? Jv.Obj
    if (environment == null || environment.fields["values"] !is Jv.Arr || environment.fields["name"] == null) {
        notes += Note(
            Severity.WARNING,
            file,
            null,
            "Environment file is not a Postman environment (expected name and values).",
        )
        return null
    }
    val name = environment.fields.getValue("name").scalar()?.trim().orEmpty().ifEmpty {
        notes += Note(Severity.WARNING, file, null, "Environment name was empty and was imported as 'environment'.")
        "environment"
    }
    val public = linkedMapOf<String, String>()
    val private = linkedMapOf<String, String>()
    val values = environment.fields.getValue("values") as Jv.Arr
    values.items.forEach { item ->
        val entry = item as? Jv.Obj
        if (entry == null) {
            notes += Note(Severity.WARNING, file, null, "An environment value was not a JSON object and was skipped.")
            return@forEach
        }
        val key = entry.fields["key"]?.scalar()?.trim().orEmpty()
        if (key.isEmpty()) {
            notes += Note(Severity.WARNING, file, null, "An environment value without a name was skipped.")
            return@forEach
        }
        if (entry.fields["enabled"].isFalse()) {
            notes += Note(Severity.WARNING, file, null, "Disabled environment key '$key' was skipped.")
            return@forEach
        }
        if (public.containsKey(key) || private.containsKey(key)) {
            notes += Note(Severity.WARNING, file, null, "Environment key '$key' was defined more than once. Using the last value.")
        }
        public.remove(key)
        private.remove(key)
        val dynamic = mutableListOf<String>()
        val text = mapDynamic(entry.fields["value"]?.scalar() ?: "", dynamic)
        dynamic.distinct().forEach { token ->
            notes += Note(Severity.WARNING, file, null, "Dynamic variable '$token' was left unchanged.")
        }
        val secret = entry.fields["type"]?.scalar()?.equals("secret", ignoreCase = true) == true
        if (secret) private[key] = text else public[key] = text
    }
    val publicJson = nestEnvironment(name, public, file, notes)
    val privateJson = nestEnvironment(name, private, file, notes)
    if (private.isNotEmpty()) {
        private.keys.sorted().forEach { key ->
            notes += Note(Severity.WARNING, file, null, "Secret key '$key' was written to $PRIVATE_ENVIRONMENT_FILE_NAME.")
        }
    }
    return EnvironmentWrite(publicJson, privateJson)
}

private fun nestEnvironment(
    name: String,
    entries: Map<String, String>,
    file: String,
    notes: MutableList<Note>,
): String? {
    if (entries.isEmpty()) return null
    val keys = entries.keys
    val literal = linkedSetOf<String>()
    for (key in keys) {
        val parts = key.split('.')
        for (index in 1 until parts.size) {
            val prefix = parts.take(index).joinToString(".")
            if (prefix in keys) {
                literal += key
                literal += prefix
            }
        }
    }
    literal.forEach { key ->
        notes += Note(
            Severity.WARNING,
            file,
            null,
            "Environment key '$key' was left dotted because it conflicts with another key.",
        )
    }
    val root = EnvNode()
    entries.forEach { (key, value) ->
        val parts = if (key in literal || !key.contains('.')) listOf(key) else key.split('.')
        var node = root
        parts.forEachIndexed { index, part ->
            node = node.children.getOrPut(part) { EnvNode() }
            if (index == parts.lastIndex) node.value = value
        }
    }
    return render(obj { put(name, root.toJson()) })
}

private class EnvNode {
    val children = linkedMapOf<String, EnvNode>()
    var value: String? = null

    fun toJson(): Jv {
        if (children.isEmpty()) return Jv.Str(value ?: "")
        return obj {
            children.forEach { (key, child) ->
                if (child.children.isEmpty()) put(key, child.value ?: "") else put(key, child.toJson())
            }
        }
    }
}

private fun emitFiles(root: Group, notes: MutableList<Note>): List<ImportFile> {
    val specs = mutableListOf<FileSpec>()
    val used = mutableSetOf<String>()
    if (root.children.isEmpty()) {
        val path = allocate(pathSegment(root.name.ifBlank { "requests" }) + ".http", used)
        specs += FileSpec(path, root.comments, root.variables, root.requests, root.notes)
    } else {
        if (root.requests.isNotEmpty() || root.notes.any { it.severity == Severity.SKIPPED }) {
            val path = allocate("requests.http", used)
            specs += FileSpec(path, emptyList(), emptyList(), root.requests, root.notes)
        }
        for (child in root.children.values) place(child, "", specs, used, notes)
        val loose = root.notes.filter { note -> specs.none { spec -> spec.notes.contains(note) } }
        if (root.variables.isNotEmpty() || root.comments.isNotEmpty() || (root.requests.isEmpty() && loose.isNotEmpty())) {
            if (specs.size == 1 && root.requests.isEmpty()) {
                val only = specs.single()
                specs[0] = only.copy(
                    comments = root.comments + only.comments,
                    variables = root.variables + only.variables,
                    notes = loose + only.notes,
                )
            } else {
                val path = allocate("variables.http", used)
                specs += FileSpec(path, root.comments, root.variables, emptyList(), loose)
            }
        }
    }
    return specs.map { spec ->
        val variables = dedupeVariables(spec.variables, spec.relativePath, notes)
        spec.notes.forEach { notes += Note(it.severity, spec.relativePath, it.request, it.message) }
        ImportFile(
            relativePath = spec.relativePath,
            content = renderHttpFile(spec.comments, variables, spec.requests.map { it.request }),
        )
    }.filter { file ->
        file.content.isNotBlank()
    }
}

private fun place(
    group: Group,
    prefix: String,
    specs: MutableList<FileSpec>,
    used: MutableSet<String>,
    notes: MutableList<Note>,
) {
    val segment = pathSegment(group.name)
    if (group.children.isEmpty()) {
        if (group.requests.isEmpty() && group.variables.isEmpty() && group.comments.isEmpty()) {
            val where = join(prefix, segment).ifEmpty { group.name }
            if (group.notes.isEmpty()) {
                notes += Note(Severity.WARNING, where, null, "Folder '${group.name}' contained no requests and was not written.")
            } else {
                group.notes.forEach { notes += Note(it.severity, where, it.request, it.message) }
            }
            return
        }
        val path = allocate(join(prefix, "$segment.http"), used)
        specs += FileSpec(path, group.comments, group.variables, group.requests, group.notes)
        return
    }
    val directory = join(prefix, segment)
    if (group.requests.isNotEmpty() || group.variables.isNotEmpty() || group.comments.isNotEmpty() || group.notes.isNotEmpty()) {
        val path = allocate(join(directory, "requests.http"), used)
        specs += FileSpec(path, group.comments, group.variables, group.requests, group.notes)
    }
    for (child in group.children.values) place(child, directory, specs, used, notes)
}

private fun dedupeVariables(
    variables: List<Pair<String, String>>,
    file: String,
    notes: MutableList<Note>,
): List<Pair<String, String>> {
    val values = linkedMapOf<String, String>()
    for ((key, value) in variables) {
        val previous = values[key]
        if (previous != null && previous != value) {
            notes += Note(Severity.WARNING, file, null, "Variable '$key' was defined more than once. Using the last value.")
        }
        values[key] = value
    }
    return values.entries.map { it.key to it.value }
}

private fun finish(
    files: List<ImportFile>,
    publicJson: String?,
    privateJson: String?,
    notes: MutableList<Note>,
): ImportOutput {
    val ordered = notes.distinctBy { listOf(it.severity.name, it.file, it.request.orEmpty(), it.message) }
        .sortedWith(compareBy({ it.file }, { it.request ?: "" }, { it.severity }, { it.message }))
    val imported = files.sumOf { file ->
        // Count request separators that the writer emits. Skipped requests are not written.
        Regex("""(?m)^### """).findAll(file.content).count()
    }
    val skipped = ordered.count { it.severity == Severity.SKIPPED }
    val warnings = ordered.count { it.severity == Severity.WARNING }
    val report = renderImportReport(
        imported = imported,
        skipped = skipped,
        warnings = warnings,
        notes = ordered,
        files = files,
        wrotePublic = publicJson != null,
        wrotePrivate = privateJson != null,
    )
    return ImportOutput(
        files = files,
        publicEnvironmentJson = publicJson,
        privateEnvironmentJson = privateJson,
        reportFileName = IMPORT_REPORT_FILE_NAME,
        reportMarkdown = report,
        requestsImported = imported,
        requestsSkipped = skipped,
        warningCount = warnings,
    )
}

private fun renderImportReport(
    imported: Int,
    skipped: Int,
    warnings: Int,
    notes: List<Note>,
    files: List<ImportFile>,
    wrotePublic: Boolean,
    wrotePrivate: Boolean,
): String = buildString {
    append("# RequestHandoff import report\n\n")
    append("Requests imported: $imported\n")
    append("Requests skipped: $skipped\n")
    append("Warnings: $warnings\n\n")
    append("## Limitations\n\n")
    append("These limits apply to this import:\n\n")
    append("- Pre-request and response scripts are copied into the `.http` file as comments. They are not translated to HTTP Client scripts, and a later export does not recreate Postman script events from those comments.\n")
    append("- Postman auth objects are reported and skipped. Authorization headers are kept. Collection and folder auth are not copied onto child requests.\n")
    append("- Dynamic variables such as `{{${'$'}uuid}}` are left unchanged.\n")
    append("- WebSocket and gRPC requests, saved response examples, proxy settings, and client certificates are skipped.\n")
    append("- A multipart boundary is generated. The parts are preserved. `.rest` files are written back as `.http` files.\n")
    append("- Secret environment values are written only to `$PRIVATE_ENVIRONMENT_FILE_NAME`. They are not copied into `$PUBLIC_ENVIRONMENT_FILE_NAME`, this report, or logs.\n\n")
    append("## Skipped requests\n\n")
    append(noteSection(notes.filter { it.severity == Severity.SKIPPED }))
    append("## Not imported\n\n")
    append(noteSection(notes.filter { it.severity == Severity.WARNING }))
    append("## Environments\n\n")
    if (!wrotePublic && !wrotePrivate) {
        append("No environment file was written.\n\n")
    } else {
        if (wrotePublic) append("Public values were written to `$PUBLIC_ENVIRONMENT_FILE_NAME`.\n")
        if (wrotePrivate) {
            append("Secret values were written to `$PRIVATE_ENVIRONMENT_FILE_NAME`. The values are not listed in this report.\n")
        }
        append('\n')
    }
    append("## Output files\n\n")
    files.forEach { append("- `${it.relativePath}`\n") }
    if (wrotePublic) append("- `$PUBLIC_ENVIRONMENT_FILE_NAME`\n")
    if (wrotePrivate) append("- `$PRIVATE_ENVIRONMENT_FILE_NAME`\n")
    append("- `$IMPORT_REPORT_FILE_NAME`\n")
}

private fun noteSection(notes: List<Note>): String {
    if (notes.isEmpty()) return "None.\n\n"
    return notes.joinToString("\n") { note ->
        val where = if (note.request.isNullOrBlank()) note.file else "${note.file} — ${note.request}"
        "- $where — ${note.message}"
    } + "\n\n"
}

private fun descriptionComment(value: Jv?): String? {
    val text = when (value) {
        is Jv.Str -> value.value
        is Jv.Obj -> value.fields["content"]?.scalar()
        else -> null
    }?.trim().orEmpty()
    return text.ifEmpty { null }
}

private fun mapDynamic(text: String, unchanged: MutableList<String>): String =
    DYNAMIC.replace(text) { match ->
        val token = match.value
        val mapped = ImportMappings.dynamicVariables.map(token)
        if (mapped == token) unchanged += token
        mapped
    }

private fun mapBody(body: Body, unchanged: MutableList<String>): Body = when (body) {
    Body.None -> body
    is Body.Raw -> body.copy(text = mapDynamic(body.text, unchanged))
    is Body.FileRef -> body.copy(src = mapDynamic(body.src, unchanged))
    is Body.UrlEncoded -> body.copy(fields = body.fields.map { it.first to mapDynamic(it.second, unchanged) })
    is Body.Multipart -> body.copy(parts = body.parts.map { part ->
        part.copy(
            text = part.text?.let { mapDynamic(it, unchanged) },
            src = part.src?.let { mapDynamic(it, unchanged) },
        )
    })
    is Body.GraphQl -> body.copy(
        query = mapDynamic(body.query, unchanged),
        variables = body.variables?.let { mapDynamic(it, unchanged) },
    )
}

private fun skipReason(method: String, url: String): String? {
    val scheme = url.substringBefore("://", "").lowercase()
    return when {
        method.equals("WEBSOCKET", ignoreCase = true) || scheme == "ws" || scheme == "wss" ->
            "WebSocket requests are skipped."
        method.equals("GRPC", ignoreCase = true) || scheme == "grpc" || scheme == "grpcs" ->
            "gRPC requests are skipped."
        else -> null
    }
}

private fun profileSkip(profile: Jv.Obj?): String? {
    if (profile == null) return null
    val values = profile.fields.values.map { it.scalar().orEmpty().lowercase() }
    return when {
        values.any { it == "websocket" || it == "ws" || it == "wss" } -> "WebSocket requests are skipped."
        values.any { it == "grpc" || it == "grpcs" } -> "gRPC requests are skipped."
        else -> null
    }
}

private fun sanitizeMethod(method: String): String {
    val upper = method.trim().uppercase()
    if (METHOD.matches(upper)) return upper
    val cleaned = upper.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' || it == '.' }
    return cleaned.ifEmpty { "GET" }
}

private fun sanitizeUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.none { it.isWhitespace() }) return trimmed
    return trimmed.replace(Regex("""\s+"""), "%20")
}

private fun graphqlText(value: Jv?): String = when (value) {
    null, Jv.Null -> ""
    is Jv.Str -> value.value
    else -> render(value).trimEnd()
}

private fun looksLikeJsonText(body: String): Boolean {
    val trimmed = body.trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

private fun defaultCollectionName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    return base.removeSuffix(".postman_collection.json").removeSuffix(".json").ifBlank { "requests" }
}

private fun pathSegment(name: String): String {
    val cleaned = name.trim().replace(ILLEGAL_PATH, "-").trim('.', ' ')
    return cleaned.ifEmpty { "request" }
}

private fun join(prefix: String, child: String): String = if (prefix.isEmpty()) child else "$prefix/$child"

private fun allocate(path: String, used: MutableSet<String>): String {
    if (used.add(path)) return path
    val stem = path.removeSuffix(".http")
    var suffix = 2
    while (true) {
        val candidate = "$stem-$suffix.http"
        if (used.add(candidate)) return candidate
        suffix++
    }
}

private fun Jv.scalar(): String? = when (this) {
    is Jv.Str -> value
    is Jv.Num -> literal
    is Jv.Bool -> value.toString()
    Jv.Null -> ""
    else -> null
}

private fun Jv?.isTrue(): Boolean = this is Jv.Bool && value

private fun Jv?.isFalse(): Boolean = this is Jv.Bool && !value

private val HEADER_LINE = Regex("""^([^:\s][^:]*)\s*:\s?(.*)$""")
private val VARIABLE_NAME = Regex("""[A-Za-z_][\w.\-]*""")
private val METHOD = Regex("""[A-Z][A-Z0-9+_.\-]*""")
private val DYNAMIC = Regex("""\{\{\$[^}]+}}""")
private val ILLEGAL_PATH = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
