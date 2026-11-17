package com.willcraig.requesthandoff.core

/**
 * v0.1 import mappings.
 *
 * [Importer] calls these objects and does not encode script, auth, or dynamic-variable
 * translation itself. Replace the implementations here when those mappings land:
 * scripts (#13), auth objects (#14), and dynamic variables (#15).
 */
internal object ImportMappings {
    val scripts: ScriptMapper = CommentScriptMapper
    val auth: AuthMapper = SkipAuthMapper
    val dynamicVariables: DynamicVariableMapper = IdentityDynamicVariableMapper
}

internal data class PostmanScriptView(
    val listen: String,
    val exec: List<String>,
    val source: String? = null,
)

internal data class ScriptMapping(
    val preRequest: List<ScriptSource> = emptyList(),
    val response: List<ScriptSource> = emptyList(),
    val comments: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

internal fun interface ScriptMapper {
    fun map(scripts: List<PostmanScriptView>): ScriptMapping
}

internal data class AuthAttribute(val key: String, val value: String)

internal data class PostmanAuthView(
    val type: String,
    val attributes: List<AuthAttribute>,
)

internal data class AuthMapping(
    val headers: List<Header> = emptyList(),
    val notes: List<String> = emptyList(),
)

internal fun interface AuthMapper {
    fun map(auth: PostmanAuthView): AuthMapping
}

internal fun interface DynamicVariableMapper {
    /** Maps one full token such as `{{$uuid}}`. Return the token unchanged to leave it as written. */
    fun map(token: String): String
}

private object CommentScriptMapper : ScriptMapper {
    override fun map(scripts: List<PostmanScriptView>): ScriptMapping {
        val comments = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (script in scripts) {
            val label = when (script.listen.lowercase()) {
                "prerequest" -> "Pre-request script"
                "test" -> "Response handler script"
                else -> "Script event '${script.listen}'"
            }
            comments += "$label (not translated):"
            if (script.source != null) {
                comments += "Script file (not inlined): ${script.source}"
            }
            script.exec.forEach { line -> comments += line }
            notes += "$label was copied as comments and was not translated."
        }
        return ScriptMapping(comments = comments, notes = notes)
    }
}

private object SkipAuthMapper : AuthMapper {
    override fun map(auth: PostmanAuthView): AuthMapping {
        if (auth.type.equals("noauth", ignoreCase = true) || auth.type.isBlank()) return AuthMapping()
        return AuthMapping(notes = listOf("Postman auth type '${auth.type}' was not imported."))
    }
}

private object IdentityDynamicVariableMapper : DynamicVariableMapper {
    override fun map(token: String): String = token
}
