package com.willcraig.requesthandoff.core

internal data class DynamicRewrite(
    val text: String,
    val warnings: List<String>,
)

private val TOKEN = Regex("""\{\{\s*(\$[^}]*?)\s*}}""")
private val EMPTY_CALL = Regex("""^(\$[A-Za-z_][A-Za-z0-9_.]*)\s*\(\s*\)$""")
private val INTEGER_CALL = Regex("""^\${'$'}random\.integer\s*(?:\(\s*([^)]*)\s*\))?$""")
private val HOST_DOT = Regex("""^\$(?:env|processEnv|dotenv)\.([A-Za-z_][A-Za-z0-9_]*)$""")
private val HOST_WORD = Regex(
    """^\$(?:processEnv|dotenv)\s+(?:\[%([A-Za-z_][A-Za-z0-9_]*)%\]|%([A-Za-z_][A-Za-z0-9_]*)%|([A-Za-z_][A-Za-z0-9_]*))$""",
)

private val EXACT = mapOf(
    "\$uuid" to "\$guid",
    "\$random.uuid" to "\$guid",
    "\$random.internet.uuid" to "\$guid",
    "\$timestamp" to "\$timestamp",
    "\$isoTimestamp" to "\$isoTimestamp",
    "\$randomInt" to "\$randomInt",
    "\$random.integer" to "\$randomInt",
    "\$random.email" to "\$randomEmail",
)

private val FAKER = mapOf(
    "random.internet.emailAddress" to "\$randomEmail",
    "random.internet.safeEmailAddress" to "\$randomExampleEmail",
    "random.internet.domainName" to "\$randomDomainName",
    "random.internet.domainWord" to "\$randomDomainWord",
    "random.internet.domainSuffix" to "\$randomDomainSuffix",
    "random.internet.image" to "\$randomImageUrl",
    "random.internet.macAddress" to "\$randomMACAddress",
    "random.internet.ipV4Address" to "\$randomIP",
    "random.internet.ipV6Address" to "\$randomIPV6",
    "random.internet.userAgentAny" to "\$randomUserAgent",
    "random.name.firstName" to "\$randomFirstName",
    "random.name.lastName" to "\$randomLastName",
    "random.name.fullName" to "\$randomFullName",
    "random.name.prefix" to "\$randomNamePrefix",
    "random.name.suffix" to "\$randomNameSuffix",
    "random.name.username" to "\$randomUserName",
    "random.address.city" to "\$randomCity",
    "random.address.streetName" to "\$randomStreetName",
    "random.address.streetAddress" to "\$randomStreetAddress",
    "random.address.country" to "\$randomCountry",
    "random.address.countryCode" to "\$randomCountryCode",
    "random.address.latitude" to "\$randomLatitude",
    "random.address.longitude" to "\$randomLongitude",
    "random.phoneNumber.phoneNumber" to "\$randomPhoneNumber",
    "random.company.name" to "\$randomCompanyName",
    "random.company.suffix" to "\$randomCompanySuffix",
    "random.company.catchPhrase" to "\$randomCatchPhrase",
    "random.company.bs" to "\$randomBs",
    "random.color.name" to "\$randomColor",
    "random.color.hex" to "\$randomHexColor",
    "random.bool.bool" to "\$randomBoolean",
    "random.commerce.productName" to "\$randomProductName",
    "random.commerce.department" to "\$randomDepartment",
    "random.commerce.material" to "\$randomProductMaterial",
    "random.lorem.word" to "\$randomLoremWord",
    "random.lorem.sentence" to "\$randomLoremSentence",
    "random.lorem.paragraph" to "\$randomLoremParagraph",
)

internal fun rewriteDynamicVariables(text: String): DynamicRewrite {
    if (!text.contains("{{")) return DynamicRewrite(text, emptyList())
    val warnings = linkedSetOf<String>()
    val rewritten = TOKEN.replace(text) { match ->
        val original = match.value
        val decision = decide(match.groupValues[1].trim(), original)
        decision.warning?.let { warnings += it }
        decision.replacement ?: original
    }
    return DynamicRewrite(rewritten, warnings.toList())
}

private data class Decision(val replacement: String?, val warning: String?)

private fun decide(rawInner: String, original: String): Decision {
    val inner = rawInner.trim()
    if (isHostForm(inner)) return hostDecision(inner, original)
    val normalized = EMPTY_CALL.matchEntire(inner)?.groupValues?.get(1) ?: inner
    EXACT[normalized]?.let { return Decision("{{$it}}", null) }
    integerDecision(normalized, original)?.let { return it }
    FAKER[normalized.removePrefix("\$")]?.let { return Decision("{{$it}}", null) }
    return Decision(null, "Dynamic variable '$original' has no Postman equivalent and was left unchanged.")
}

private fun integerDecision(inner: String, original: String): Decision? {
    val match = INTEGER_CALL.matchEntire(inner) ?: return null
    val args = match.groupValues[1]
    if (args.isBlank()) return Decision("{{\$randomInt}}", null)
    val parts = args.split(',')
    if (parts.size == 2 && parts.all { it.trim().isNotEmpty() }) {
        return Decision(
            "{{\$randomInt}}",
            "Dynamic variable '$original' was mapped to '{{\$randomInt}}'. " +
                "Postman \$randomInt is an integer from 0 to 1000 and does not use the HTTP Client range (from inclusive, to exclusive).",
        )
    }
    return Decision(null, "Dynamic variable '$original' has no Postman equivalent and was left unchanged.")
}

private fun isHostForm(inner: String): Boolean {
    if (inner.startsWith("\$env.")) return true
    return isBareOrQualified(inner, "\$processEnv") || isBareOrQualified(inner, "\$dotenv")
}

private fun isBareOrQualified(inner: String, name: String): Boolean {
    if (!inner.startsWith(name)) return false
    if (inner.length == name.length) return true
    val next = inner[name.length]
    return next == '.' || next.isWhitespace()
}

private fun hostDecision(inner: String, original: String): Decision {
    val name = hostVariableName(inner)
    if (name == null) {
        return Decision(null, "Host environment variable '$original' was not read and was left unchanged.")
    }
    return Decision(
        "{{$name}}",
        "Host environment variable '$original' was not read. It was rewritten to '{{$name}}'. The value on this machine was not copied.",
    )
}

private fun hostVariableName(inner: String): String? {
    HOST_DOT.matchEntire(inner)?.let { return it.groupValues[1] }
    val word = HOST_WORD.matchEntire(inner) ?: return null
    return word.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
}
