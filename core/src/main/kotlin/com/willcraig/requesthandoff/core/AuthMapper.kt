package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.JsonParseException
import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.arr
import com.willcraig.requesthandoff.core.json.obj
import com.willcraig.requesthandoff.core.json.parseJson
import java.util.Base64

/**
 * JetBrains documents Basic and Digest as `Authorization: Scheme user password`,
 * Bearer tokens, and `{{$auth.token("id")}}` / `{{$auth.idToken("id")}}`.
 * It has no API-key scheme. Only these header and query names are treated as API keys.
 */
private val API_KEY_HEADERS = setOf("x-api-key", "api-key", "apikey")
private val API_KEY_QUERY = setOf("api_key", "apikey", "api-key", "x-api-key")

private val AUTH_CALL = Regex(
    """\{\{\s*[$]auth\.(token|idToken)\s*\(\s*(["'])([^"']+)\2\s*\)\s*\}\}""",
)
private val SIMPLE_VARIABLE = Regex("""\{\{\s*([A-Za-z_][\w.\-]*)\s*\}\}""")
private val SCHEME = Regex("""[A-Za-z][A-Za-z0-9+._-]*""")

internal class AuthContext private constructor(
    private val configs: Map<String, List<EnvAuth>>,
    private val publicValues: Map<String, Map<String, String>>,
    private val privateOmitted: Boolean,
) {
    private val placeholders = linkedSetOf<String>()
    private val variableSites = linkedMapOf<String, VariableSite>()
    private val conflictWarned = mutableSetOf<String>()

    val placeholderNames: List<String> get() = placeholders.toList()

    fun map(file: String, request: HttpRequest): AuthMapResult {
        val notes = mutableListOf<Note>()
        var headers = request.headers
        var url = request.url
        val authRef = findAuthReference(headers, url)
        if (authRef != null) {
            if (authRef.headerIndexes.isNotEmpty() && authRef.queryKeys.isNotEmpty()) {
                notes += note(
                    file,
                    request,
                    "OAuth2 query reference was removed because the Authorization header already references an authentication configuration.",
                )
            }
            val built = oauthOrMock(file, request, authRef, notes)
            headers = headers.filterIndexed { index, _ -> index !in authRef.headerIndexes }
            authRef.queryKeys.forEach { key -> url = removeQueryKey(url, key) }
            record(built.variables, file, request)
            return AuthMapResult(
                request.copy(headers = headers, url = url),
                built.auth,
                notes,
                authRef.calls,
            )
        }

        val authorization = headers.indexOfFirst { it.name.equals("Authorization", ignoreCase = true) }
        if (authorization >= 0) {
            when (val parsed = parseAuthorization(headers[authorization].value)) {
                is ParsedAuthorization.Basic -> {
                    record(listOf(parsed.username, parsed.password), file, request)
                    return AuthMapResult(
                        request.copy(headers = headers.filterIndexed { index, _ -> index != authorization }),
                        authObject("basic", listOf("username" to Jv.Str(parsed.username), "password" to Jv.Str(parsed.password))),
                        notes,
                    )
                }
                is ParsedAuthorization.Bearer -> {
                    record(listOf(parsed.token), file, request)
                    return AuthMapResult(
                        request.copy(headers = headers.filterIndexed { index, _ -> index != authorization }),
                        authObject("bearer", listOf("token" to Jv.Str(parsed.token))),
                        notes,
                    )
                }
                is ParsedAuthorization.Digest -> {
                    record(listOf(parsed.username, parsed.password), file, request)
                    return AuthMapResult(
                        request.copy(headers = headers.filterIndexed { index, _ -> index != authorization }),
                        authObject("digest", listOf("username" to Jv.Str(parsed.username), "password" to Jv.Str(parsed.password))),
                        notes,
                    )
                }
                is ParsedAuthorization.Keep -> notes += note(file, request, parsed.message)
            }
        }

        val apiKey = firstApiKey(headers, url)
        if (apiKey != null) {
            record(listOf(apiKey.value), file, request)
            val keptHeaders = if (apiKey.header) {
                headers.filterNot { it.name.equals(apiKey.name, ignoreCase = true) }
            } else {
                headers
            }
            val keptUrl = if (apiKey.header) url else removeQueryKey(url, apiKey.name)
            if (apiKey.repeated) {
                notes += note(
                    file,
                    request,
                    "Repeated API key '${apiKey.name}' was exported once.",
                )
            }
            return AuthMapResult(
                request.copy(headers = keptHeaders, url = keptUrl),
                authObject(
                    "apikey",
                    listOf(
                        "key" to Jv.Str(apiKey.name),
                        "value" to Jv.Str(apiKey.value),
                        "in" to Jv.Str(if (apiKey.header) "header" else "query"),
                    ),
                ),
                notes,
            )
        }
        return AuthMapResult(request, null, notes)
    }

    fun finish(collectionVariables: Set<String>): List<Note> {
        if (!privateOmitted) return emptyList()
        val notes = mutableListOf<Note>()
        for ((name, site) in variableSites) {
            if (name in collectionVariables) continue
            val defined = publicValues.values.mapNotNull { it[name] }
            when {
                defined.any { it.isNotBlank() } -> Unit
                defined.isEmpty() -> {
                    placeholders += name
                    notes += Note(
                        Severity.WARNING,
                        site.file,
                        site.request,
                        "Auth variable '$name' is not in the public environment. Private values are off, so a placeholder was exported and the secret was not written.",
                        site.line,
                    )
                }
                else -> notes += Note(
                    Severity.WARNING,
                    site.file,
                    site.request,
                    "Auth variable '$name' is empty in the public environment. Private values are off, so the secret was not written.",
                    site.line,
                )
            }
        }
        return notes
    }

    private fun oauthOrMock(
        file: String,
        request: HttpRequest,
        ref: AuthReference,
        notes: MutableList<Note>,
    ): BuiltAuth {
        val matches = configs[ref.id].orEmpty()
        if (matches.isEmpty()) {
            val privateNote = if (privateOmitted) {
                " Private values are off, so a private authentication configuration was not read."
            } else {
                ""
            }
            notes += note(
                file,
                request,
                "Authentication configuration '${ref.id}' was not found in the public environment. " +
                    "OAuth2 auth was exported without an access token or refresh token.$privateNote",
            )
            return BuiltAuth(oauthShell(ref), emptyList())
        }
        val chosen = matches.first()
        if (matches.size > 1 && matches.drop(1).any { it.differsFrom(chosen) } && conflictWarned.add(ref.id)) {
            notes += note(
                file,
                request,
                "Authentication configuration '${ref.id}' differs across environments. request.auth uses '${chosen.environment}'.",
            )
        }
        val type = chosen.string("Type")
        if (type.equals("Mock", ignoreCase = true)) {
            return mockBearer(file, request, ref, chosen, notes)
        }
        if (!type.isNullOrBlank() && !type.equals("OAuth2", ignoreCase = true)) {
            notes += note(
                file,
                request,
                "Authentication type for '${ref.id}' is not OAuth2 or Mock. Supported fields were exported as oauth2.",
            )
        }
        return oauth(file, request, ref, chosen, notes)
    }

    private fun mockBearer(
        file: String,
        request: HttpRequest,
        ref: AuthReference,
        config: EnvAuth,
        notes: MutableList<Note>,
    ): BuiltAuth {
        val field = if (ref.kind == "idToken") "ID Token" else "Token"
        val token = config.string(field).orEmpty()
        if (token.isEmpty()) {
            notes += note(
                file,
                request,
                "Mock authentication '${ref.id}' has no $field in the public environment. Bearer auth was exported with an empty token.",
            )
        } else {
            notes += note(
                file,
                request,
                "Mock authentication '${ref.id}' was exported as bearer auth. No token was acquired from an authorization server.",
            )
        }
        return BuiltAuth(authObject("bearer", listOf("token" to Jv.Str(token))), listOf(token))
    }

    private fun oauth(
        file: String,
        request: HttpRequest,
        ref: AuthReference,
        config: EnvAuth,
        notes: MutableList<Note>,
    ): BuiltAuth {
        val params = mutableListOf<Pair<String, Jv>>()
        val variables = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            params += key to Jv.Str(value)
            variables += value
        }

        val pkce = config.pkce()
        when (val grant = config.string("Grant Type")?.trim().orEmpty()) {
            "" -> notes += note(file, request, "OAuth2 grant type for '${ref.id}' was empty and was omitted.")
            else -> when (val mapped = postmanGrant(grant, pkce.enabled)) {
                null -> notes += note(
                    file,
                    request,
                    "OAuth2 grant type for '${ref.id}' has no Postman oauth2 equivalent and was omitted.",
                )
                else -> add("grant_type", mapped)
            }
        }
        add("authUrl", config.string("Auth URL"))
        add("accessTokenUrl", config.string("Token URL"))
        add("clientId", config.string("Client ID"))
        add("clientSecret", config.string("Client Secret"))
        add("scope", config.string("Scope"))
        add("redirect_uri", config.string("Redirect URL"))
        add("username", config.string("Username"))
        add("password", config.string("Password"))
        add("state", config.string("State"))
        when (config.string("Client Credentials")?.trim()?.lowercase()) {
            null, "" -> Unit
            "basic" -> add("client_authentication", "header")
            "in body" -> add("client_authentication", "body")
            "none" -> notes += note(
                file,
                request,
                "OAuth2 client credentials mode 'none' for '${ref.id}' was omitted. Postman sends client credentials in the header or the body.",
            )
            else -> notes += note(
                file,
                request,
                "OAuth2 client credentials mode for '${ref.id}' was omitted. Postman sends client credentials in the header or the body.",
            )
        }
        add("addTokenTo", if (ref.inQuery) "queryParams" else "header")
        if (!ref.inQuery) {
            add("tokenType", "Bearer")
            params += "headerPrefix" to Jv.Str(if (ref.bearerScheme) "Bearer" else "")
        }
        if (pkce.enabled) add("challengeAlgorithm", pkce.algorithm)
        if (pkce.verifierOmitted) {
            notes += note(file, request, "PKCE code verifier for '${ref.id}' was omitted.")
        } else if (pkce.verifierVariable != null) {
            add("code_verifier", pkce.verifierVariable)
        }
        config.customParameters()?.let { custom ->
            if (custom.auth.items.isNotEmpty()) params += "authRequestParams" to custom.auth
            if (custom.token.items.isNotEmpty()) params += "tokenRequestParams" to custom.token
            if (custom.defaultedScope) {
                notes += note(
                    file,
                    request,
                    "Custom request parameters for '${ref.id}' without a Use scope were copied to Postman auth and token request parameters.",
                )
            }
        }
        if (config.field("Custom Request Headers") != null) {
            notes += note(file, request, "Custom request headers for '${ref.id}' were omitted.")
        }
        val deviceUrl = config.string("Device Auth URL")
        if (!deviceUrl.isNullOrBlank()) {
            notes += note(file, request, "Device Auth URL for '${ref.id}' was omitted. Postman oauth2 has no device-authorization grant.")
        }
        if (ref.kind == "idToken" || config.bool("Use ID Token") == true) {
            notes += note(
                file,
                request,
                "OAuth2 ID token for '${ref.id}' was omitted. Postman oauth2 sends the access token, not the ID token.",
            )
        }
        if (ref.inQuery && !ref.queryKeys.all { it.equals("access_token", ignoreCase = true) }) {
            notes += note(
                file,
                request,
                "OAuth2 query parameter '${ref.queryKeys.first()}' was removed. Postman sends the access token as 'access_token'.",
            )
        }
        notes += note(
            file,
            request,
            "OAuth2 configuration '${ref.id}' was exported without an access token or refresh token. RequestHandoff does not acquire or refresh tokens.",
        )
        return BuiltAuth(authObject("oauth2", params), variables)
    }

    private fun record(values: List<String>, file: String, request: HttpRequest) {
        values.forEach { value ->
            SIMPLE_VARIABLE.findAll(value).forEach { match ->
                variableSites.putIfAbsent(
                    match.groupValues[1],
                    VariableSite(file, request.name, request.line),
                )
            }
        }
    }

    private data class VariableSite(val file: String, val request: String, val line: Int)
    private data class BuiltAuth(val auth: Jv.Obj, val variables: List<String>)

    companion object {
        fun from(publicJson: String?, privateJson: String?, privateOmitted: Boolean): AuthContext {
            val public = environmentObjects(publicJson)
            val private = environmentObjects(privateJson)
            val names = LinkedHashSet<String>()
            names += public.keys
            names += private.keys
            val configs = linkedMapOf<String, MutableList<EnvAuth>>()
            val publicValues = linkedMapOf<String, Map<String, String>>()
            for (name in names) {
                val merged = mergeAuth(name, public[name], private[name])
                merged.forEach { (id, auth) -> configs.getOrPut(id) { mutableListOf() } += auth }
                val values = linkedMapOf<String, String>()
                public[name]?.let { values += flattenEnvironment(it) }
                publicValues[name] = values
            }
            return AuthContext(configs, publicValues, privateOmitted)
        }

        private fun environmentObjects(json: String?): Map<String, Jv.Obj> {
            if (json.isNullOrBlank()) return emptyMap()
            val root = try {
                parseJson(json)
            } catch (_: JsonParseException) {
                return emptyMap()
            }
            if (root !is Jv.Obj) return emptyMap()
            return root.fields.mapNotNull { (name, value) ->
                if (value is Jv.Obj) name to value else null
            }.toMap(linkedMapOf())
        }

        private fun mergeAuth(environment: String, public: Jv.Obj?, private: Jv.Obj?): Map<String, EnvAuth> {
            val ids = LinkedHashSet<String>()
            val publicAuth = authObject(public)
            val privateAuth = authObject(private)
            publicAuth?.fields?.keys?.let { ids += it }
            privateAuth?.fields?.keys?.let { ids += it }
            return ids.associateWith { id ->
                val fields = linkedMapOf<String, Jv>()
                (privateAuth?.fields?.get(id) as? Jv.Obj)?.fields?.let { fields += it }
                (publicAuth?.fields?.get(id) as? Jv.Obj)?.fields?.let { fields += it }
                EnvAuth(environment, fields)
            }
        }

        private fun authObject(environment: Jv.Obj?): Jv.Obj? {
            val security = environment.child("Security") as? Jv.Obj ?: return null
            return security.child("Auth") as? Jv.Obj
        }
    }
}

internal data class AuthMapResult(
    val request: HttpRequest,
    val auth: Jv.Obj?,
    val notes: List<Note>,
    val consumedAuthCalls: List<String> = emptyList(),
)

private class EnvAuth(val environment: String, private val fields: Map<String, Jv>) {
    fun field(name: String): Jv? = fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun string(name: String): String? = when (val value = field(name)) {
        is Jv.Str -> value.value
        is Jv.Num -> value.literal
        is Jv.Bool -> value.value.toString()
        else -> null
    }

    fun bool(name: String): Boolean? = when (val value = field(name)) {
        is Jv.Bool -> value.value
        is Jv.Str -> when (value.value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    fun differsFrom(other: EnvAuth): Boolean {
        val keys = LinkedHashSet<String>()
        keys += fields.keys
        keys += other.fields.keys
        return keys.any { key -> field(key).renderPlain() != other.field(key).renderPlain() }
    }

    fun pkce(): Pkce {
        val value = field("PKCE") ?: return Pkce(false, "S256", false, null)
        val verifier = when (value) {
            is Jv.Obj -> value.child("Code Verifier")
            else -> null
        }
        val verifierText = when (verifier) {
            is Jv.Str -> verifier.value
            else -> null
        }
        val variable = verifierText != null && SIMPLE_VARIABLE.matchEntire(verifierText.trim()) != null
        val enabled = when (value) {
            is Jv.Bool -> value.value
            is Jv.Str -> value.value.equals("true", ignoreCase = true)
            is Jv.Obj -> true
            else -> false
        }
        val algorithm = when (value) {
            is Jv.Obj -> when (value.child("Code Challenge Method").text().trim().lowercase()) {
                "plain" -> "plain"
                else -> "S256"
            }
            else -> "S256"
        }
        return Pkce(
            enabled = enabled,
            algorithm = algorithm,
            verifierOmitted = verifierText != null && !variable && verifierText.isNotBlank(),
            verifierVariable = if (variable) verifierText?.trim() else null,
        )
    }

    fun customParameters(): CustomParameters? {
        val value = field("Custom Request Parameters") as? Jv.Obj ?: return null
        val auth = mutableListOf<Jv>()
        val token = mutableListOf<Jv>()
        var defaulted = false
        for ((name, child) in value.fields) {
            val scope = (child as? Jv.Obj)?.child("Use")?.text()?.trim()?.lowercase()
            val entries = parameterEntries(name, child)
            when (scope) {
                "in auth request" -> auth += entries
                "in token request" -> token += entries
                "everywhere" -> {
                    auth += entries
                    token += entries
                }
                else -> {
                    defaulted = true
                    auth += entries
                    token += entries
                }
            }
        }
        return CustomParameters(arr(auth), arr(token), defaulted)
    }
}

private data class Pkce(
    val enabled: Boolean,
    val algorithm: String,
    val verifierOmitted: Boolean,
    val verifierVariable: String?,
)

private data class CustomParameters(val auth: Jv.Arr, val token: Jv.Arr, val defaultedScope: Boolean)

private fun parameterEntries(name: String, value: Jv): List<Jv> {
    val raw = if (value is Jv.Obj && value.fields.keys.any { it.equals("Value", ignoreCase = true) }) {
        value.child("Value") ?: Jv.Null
    } else {
        value
    }
    val values = when (raw) {
        is Jv.Arr -> raw.items
        else -> listOf(raw)
    }
    return values.map { item ->
        obj {
            put("key", name)
            put("value", item.text())
            put("enabled", true)
        }
    }
}

private data class AuthReference(
    val kind: String,
    val id: String,
    val bearerScheme: Boolean,
    val inQuery: Boolean,
    val headerIndexes: Set<Int>,
    val queryKeys: List<String>,
    val calls: List<String>,
)

private fun findAuthReference(headers: List<Header>, url: String): AuthReference? {
    val headerIndexes = mutableSetOf<Int>()
    val headerCalls = mutableListOf<String>()
    var kind = ""
    var id = ""
    var bearer = false
    headers.forEachIndexed { index, header ->
        if (!header.name.equals("Authorization", ignoreCase = true)) return@forEachIndexed
        val value = header.value.trim()
        val bearerRest = value.substringAfter(' ', missingDelimiterValue = "")
        val direct = authCall(value)
        val fromBearer = if (value.startsWith("Bearer ", ignoreCase = true)) authCall(bearerRest.trim()) else null
        val match = fromBearer ?: direct ?: return@forEachIndexed
        if (headerIndexes.isEmpty()) {
            kind = match.kind
            id = match.id
            bearer = fromBearer != null
        }
        headerIndexes += index
        headerCalls += match.source
    }
    val queryHits = queryParameters(url).mapNotNull { (key, value) ->
        val call = authCall(value.orEmpty()) ?: return@mapNotNull null
        key to call
    }
    if (headerIndexes.isEmpty() && queryHits.isEmpty()) return null
    val queryKeys = queryHits.map { it.first }
    val queryCalls = queryHits.map { it.second.source }
    if (headerIndexes.isEmpty()) {
        val call = queryHits.first().second
        return AuthReference(
            kind = call.kind,
            id = call.id,
            bearerScheme = false,
            inQuery = true,
            headerIndexes = emptySet(),
            queryKeys = queryKeys,
            calls = queryCalls,
        )
    }
    return AuthReference(
        kind = kind,
        id = id,
        bearerScheme = bearer,
        inQuery = false,
        headerIndexes = headerIndexes,
        queryKeys = queryKeys,
        calls = headerCalls + queryCalls,
    )
}

private data class AuthCall(val kind: String, val id: String, val source: String)

private fun authCall(value: String): AuthCall? {
    val match = AUTH_CALL.matchEntire(value.trim()) ?: return null
    return AuthCall(match.groupValues[1], match.groupValues[3], match.value)
}

private sealed interface ParsedAuthorization {
    data class Basic(val username: String, val password: String) : ParsedAuthorization
    data class Bearer(val token: String) : ParsedAuthorization
    data class Digest(val username: String, val password: String) : ParsedAuthorization
    data class Keep(val message: String) : ParsedAuthorization
}

private fun parseAuthorization(value: String): ParsedAuthorization {
    val trimmed = value.trim()
    val scheme = trimmed.substringBefore(' ')
    val rest = trimmed.substringAfter(' ', "").trim()
    if (!SCHEME.matches(scheme)) {
        return ParsedAuthorization.Keep(
            "Authorization value was left as a header because it is not a recognized scheme.",
        )
    }
    return when (scheme.lowercase()) {
        "basic" -> parseBasic(rest)
        "bearer" -> if (rest.isEmpty()) {
            ParsedAuthorization.Keep("Bearer Authorization value was empty and was left as a header.")
        } else {
            ParsedAuthorization.Bearer(rest)
        }
        "digest" -> parseDigest(rest)
        else -> ParsedAuthorization.Keep(
            "Authorization scheme '$scheme' was left as a header. It is not mapped to a Postman auth object.",
        )
    }
}

private fun parseBasic(rest: String): ParsedAuthorization {
    val parts = rest.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    return when (parts.size) {
        2 -> ParsedAuthorization.Basic(parts[0], parts[1])
        1 -> decodeBasic(parts[0]) ?: splitBasicCredential(parts[0]) ?: ParsedAuthorization.Keep(
            "Basic Authorization value was left as a header because it is not a username and password or a base64 user:password token.",
        )
        else -> ParsedAuthorization.Keep(
            "Basic Authorization value was left as a header because it is not a username and password or a base64 user:password token.",
        )
    }
}

private fun parseDigest(rest: String): ParsedAuthorization {
    if (rest.contains('=')) {
        return ParsedAuthorization.Keep(
            "Digest Authorization challenge was left as a header. Postman digest auth needs a username and password, not a precomputed challenge.",
        )
    }
    val parts = rest.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    return if (parts.size == 2) {
        ParsedAuthorization.Digest(parts[0], parts[1])
    } else {
        ParsedAuthorization.Keep(
            "Digest Authorization value was left as a header because it is not a username and password.",
        )
    }
}

private fun decodeBasic(token: String): ParsedAuthorization.Basic? {
    if (token.any { it.isWhitespace() || it == '{' || it == '}' }) return null
    if (!token.matches(Regex("""[A-Za-z0-9+/]+={0,2}"""))) return null
    val padded = token + "=".repeat((4 - token.length % 4) % 4)
    val bytes = try {
        Base64.getDecoder().decode(padded)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val text = bytes.toString(Charsets.UTF_8)
    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes) || !text.contains(':') || text.contains('\u0000')) {
        return null
    }
    val username = text.substringBefore(':')
    val password = text.substringAfter(':')
    if (username.isEmpty()) return null
    return ParsedAuthorization.Basic(username, password)
}

private fun splitBasicCredential(token: String): ParsedAuthorization.Basic? {
    val colon = token.indexOf(':')
    if (colon <= 0) return null
    return ParsedAuthorization.Basic(token.substring(0, colon), token.substring(colon + 1))
}

private data class ApiKeyMatch(val name: String, val value: String, val header: Boolean, val repeated: Boolean)

private fun firstApiKey(headers: List<Header>, url: String): ApiKeyMatch? {
    val headerHits = headers.filter { it.name.lowercase() in API_KEY_HEADERS }
    if (headerHits.isNotEmpty()) {
        val name = headerHits.first().name
        val same = headerHits.count { it.name.equals(name, ignoreCase = true) }
        return ApiKeyMatch(name, headerHits.first().value, header = true, repeated = same > 1)
    }
    val queryHits = queryParameters(url).filter { (key, _) -> key.lowercase() in API_KEY_QUERY }
    if (queryHits.isEmpty()) return null
    val first = queryHits.first()
    val same = queryHits.count { it.first.equals(first.first, ignoreCase = true) }
    return ApiKeyMatch(first.first, first.second.orEmpty(), header = false, repeated = same > 1)
}

private fun postmanGrant(grant: String, pkce: Boolean): String? = when (grant.lowercase()) {
    "authorization code" -> if (pkce) "authorization_code_with_pkce" else "authorization_code"
    "client credentials" -> "client_credentials"
    "implicit" -> "implicit"
    "password" -> "password_credentials"
    else -> null
}

private fun oauthShell(ref: AuthReference): Jv.Obj {
    val params = mutableListOf("addTokenTo" to Jv.Str(if (ref.inQuery) "queryParams" else "header"))
    if (!ref.inQuery) {
        params += "tokenType" to Jv.Str("Bearer")
        params += "headerPrefix" to Jv.Str(if (ref.bearerScheme) "Bearer" else "")
    }
    return authObject("oauth2", params)
}

private fun authObject(type: String, params: List<Pair<String, Jv>>): Jv.Obj = obj {
    put("type", type)
    put(type, arr(params.map { (key, value) ->
        obj {
            put("key", key)
            put("value", value)
            if (value is Jv.Str) put("type", "string")
        }
    }))
}

private fun note(file: String, request: HttpRequest, message: String): Note =
    Note(Severity.WARNING, file, request.name, message, request.line)

private fun queryParameters(url: String): List<Pair<String, String?>> {
    val (beforeHash, _) = splitOnceOutside(url, '#')
    val query = splitOnceOutside(beforeHash, '?').second ?: return emptyList()
    return splitOutside(query, '&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val (key, value) = splitOnceOutside(part, '=')
        val name = key.trim()
        if (name.isEmpty()) return@mapNotNull null
        name to value?.trim()
    }
}

private fun removeQueryKey(url: String, name: String): String {
    val (beforeHash, hash) = splitOnceOutside(url, '#')
    val (base, query) = splitOnceOutside(beforeHash, '?')
    if (query == null) return url
    val kept = splitOutside(query, '&').filter { part ->
        val key = splitOnceOutside(part, '=').first.trim()
        key.isNotEmpty() && !key.equals(name, ignoreCase = true)
    }
    val rebuilt = if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    return if (hash == null) rebuilt else "$rebuilt#$hash"
}

private fun Jv?.child(name: String): Jv? =
    (this as? Jv.Obj)?.fields?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun Jv?.text(): String = when (this) {
    is Jv.Str -> value
    is Jv.Num -> literal
    is Jv.Bool -> value.toString()
    null, Jv.Null -> ""
    else -> ""
}

private fun Jv?.renderPlain(): String = when (this) {
    null -> ""
    is Jv.Str -> value
    is Jv.Num -> literal
    is Jv.Bool -> value.toString()
    Jv.Null -> ""
    is Jv.Arr -> items.joinToString(",") { it.renderPlain() }
    is Jv.Obj -> fields.entries.joinToString(",") { (key, value) -> "$key=${value.renderPlain()}" }
}
