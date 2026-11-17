package com.willcraig.requesthandoff.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptTranslatorTest {
    @Test
    fun rewritesDocumentedClientResponseAndRequestCalls() {
        assertEquals("pm.test(\"created\", function () {})", translate("client.test(\"created\", function () {})"))
        assertEquals("const t = pm.test;", translate("const t = client.test;"))
        assertEquals("pm.expect(true).to.eql(true)", translate("client.assert(true)"))
        assertEquals("pm.expect(undefined).to.eql(true)", translate("client.assert()"))
        assertEquals(
            "pm.expect(check(a, b), \"a, b\").to.eql(true)",
            translate("client . assert ( check(a, b) , \"a, b\" )"),
        )
        assertEquals("console.log(1)", translate("client.log(1)"))
        assertEquals("const log = console.log;", translate("const log = client.log;"))
        assertEquals("console.log(1)", translate("console.log(1)"))
        assertEquals("pm.globals.set(\"k\", \"v\")", translate("client.global.set(\"k\", \"v\")"))
        assertEquals("pm.globals.get(\"k\")", translate("client.variables.global.get(\"k\")"))
        assertEquals("pm.globals.unset(\"k\")", translate("client.global.clear(\"k\")"))
        assertEquals("pm.globals.clear()", translate("client.global.clearAll()"))
        assertEquals(
            "if (!(Object.keys(pm.globals.toObject()).length === 0)) {}",
            translate("if (!client.global.isEmpty()) {}"),
        )
        assertEquals("pm.environment.get(\"host\")", translate("request.environment.get(\"host\")"))
        assertEquals("pm.environment.get(\"host\")", translate("client.variables.environment.get(\"host\")"))
        assertEquals("pm.collectionVariables.get(\"token\")", translate("client.variables.file.get(\"token\")"))
        assertEquals("pm.variables.set(\"name\", \"Ada\")", translate("request.variables.set(\"name\", \"Ada\")"))
        assertEquals("pm.variables.get(\"name\")", translate("client.variables.request.get(\"name\")"))
        assertEquals("pm.response.code", translate("response.status"))
        assertEquals("pm.response.json().id", translate("response.body.id"))
        assertEquals("pm.response.json().textContent", translate("response.body.textContent"))
        assertEquals("JSON.parse(pm.response.text())", translate("JSON.parse(response.body)"))
        assertEquals("console.log(pm.response.text())", translate("console.log(response.body)"))
        assertEquals("pm.response.headers.get(\"X\")", translate("response.headers.valueOf(\"X\")"))
        assertEquals(valuesOf, translate("response.headers.valuesOf(\"Set-Cookie\")"))
        assertEquals(mimeType, translate("response.contentType.mimeType"))
        assertEquals(charset, translate("response.contentType.charset"))
        assertEquals("$mimeType.length", translate("response.contentType.mimeType.length"))
        assertEquals("pm.request.headers.get(\"Accept\")", translate("request.headers.findByName(\"Accept\")"))
        assertEquals(rawBody, translate("request.body.getRaw()"))
        assertEquals(substitutedBody, translate("request.body()"))
        assertEquals(substitutedBody, translate("request.body.tryGetSubstituted()"))
        assertEquals("pm.request.url.toString()", translate("request.url.getRaw()"))
        assertEquals(
            "pm.variables.replaceIn(pm.request.url.toString())",
            translate("request.url()"),
        )
        assertEquals(
            "pm.variables.replaceIn(pm.request.url.toString())",
            translate("request.url.tryGetSubstituted()"),
        )
        assertEquals("pm.request.method", translate("request.method"))
        assertEquals("pm.info.iteration", translate("request.iteration()"))
        assertEquals("new URLSearchParams()", translate("new URLSearchParams()"))
        assertEquals("btoa(\"a\")", translate("btoa(\"a\")"))
        assertEquals("atob(\"a\")", translate("atob(\"a\")"))
        assertEquals("CryptoJS.SHA256(\"a\")", translate("CryptoJS.SHA256(\"a\")"))
        assertEquals("const id = \"{{${'$'}uuid}}\";", translate("const id = \"{{${'$'}uuid}}\";"))
    }

    @Test
    fun rewritesNestedCallsTemplatesAndKeepsStringsCommentsAndRegexes() {
        assertEquals(
            """
            pm.test("created", function() {
              pm.expect(pm.response.code === 200).to.eql(true);
            });
            """.trimIndent(),
            translate(
                """
                client.test("created", function() {
                  client.assert(response.status === 200);
                });
                """.trimIndent(),
            ),
        )
        assertEquals(
            "console.log(`status \${pm.response.code}`)",
            translate("client.log(`status \${response.status}`)"),
        )
        assertEquals(
            "console.log(`\\${'$'}{response.status}`)",
            translate("client.log(`\\${'$'}{response.status}`)"),
        )
        assertEquals("console.log(\"response.status\")", translate("client.log(\"response.status\")"))
        assertEquals("// response.status\nconsole.log(1)", translate("// response.status\nclient.log(1)"))
        assertEquals("/* response.status */\nconsole.log(1)", translate("/* response.status */\nclient.log(1)"))
        assertEquals("pm.response.code / 2", translate("response.status / 2"))
        assertEquals("return /response.status/g", translate("return /response.status/g"))
        assertEquals("const re = () => /response.status/;", translate("const re = () => /response.status/;"))
        assertEquals("console.log(/a(b)/)", translate("client.log(/a(b)/)"))
        assertEquals(
            "pm.expect(/a,b/.test(pm.response.text()), \"m\").to.eql(true)",
            translate("client.assert(/a,b/.test(response.body), \"m\")"),
        )
        assertEquals("console.log(1)", translate("client\n  .log(1)"))
        assertEquals("console.log(1);\nconsole.log(2);", translate("client.log(1);\r\nclient.log(2);\r"))
    }

    @Test
    fun commentsUnsupportedCallsAndListsEachApiOnce() {
        val mixed = translateHttpClientScript("client.log(1); client.exit();")
        assertEquals(
            listOf("// Unsupported HTTP Client API client.exit: client.log(1); client.exit();"),
            mixed.lines,
        )
        assertEquals(listOf("client.exit"), mixed.unsupportedApis)

        val exit = translateHttpClientScript("client.exit(\n\n);")
        assertEquals(
            listOf(
                "// Unsupported HTTP Client API client.exit: client.exit(",
                "//",
                "// Unsupported HTTP Client API client.exit: );",
            ),
            exit.lines,
        )
        assertEquals(listOf("client.exit"), exit.unsupportedApis)
        assertEquals(
            listOf("client.global.headers.add"),
            translateHttpClientScript("client.global.headers.add(\"A\", \"B\")").unsupportedApis,
        )
        assertEquals(listOf("client.environment"), apis("client.environment.get(\"host\")"))
        assertEquals(listOf("client.variables.environment.set"), apis("client.variables.environment.set(\"h\", \"x\")"))
        assertEquals(listOf("client.variables.file.set"), apis("client.variables.file.set(\"k\", \"v\")"))
        assertEquals(listOf("response.body"), apis("response.body()"))
        assertEquals(listOf("response.status"), apis("response.status()"))
        assertEquals(listOf("response.headers"), apis("response.headers"))
        assertEquals(listOf("response.headers.all"), apis("response.headers.all()"))
        assertEquals(listOf("response.contentType"), apis("response.contentType"))
        assertEquals(listOf("response.cookies"), apis("response.cookies"))
        assertEquals(listOf("response.cookiesByName"), apis("response.cookiesByName(\"a\")"))
        assertEquals(listOf("request.headers.all"), apis("request.headers.all()"))
        assertEquals(listOf("request.method"), apis("request.method()"))
        assertEquals(listOf("request.templateValue"), apis("request.templateValue"))
        assertEquals(listOf("client.assert"), apis("const check = client.assert;"))
        assertTrue(translate("response.body.onEachLine(function (line) {})").startsWith("// Unsupported"))
        assertFalse(translate("response.body.getElementById(\"a\")").contains("pm.response.json"))
        val path = translateHttpClientScript("jsonPath(response.body, \"\$.id\")")
        assertEquals(listOf("jsonPath"), path.unsupportedApis)
        assertFalse(path.lines.single().contains("pm.response"))
        assertEquals(emptyList(), apis("const jsonPath = 1;"))
        assertEquals("const jsonPath = 1;", translate("const jsonPath = 1;"))
        assertEquals(listOf("spawn"), apis("spawn(\"ls\")"))
        assertEquals(listOf("execFileSync"), apis("execFileSync(\"id\")"))
        assertEquals(emptyList(), apis("const exec = 1; const spawn = 1;"))
        assertEquals("const exec = 1;", translate("const exec = 1;"))
        assertEquals(listOf("crypto.subtle.digest"), apis("crypto.subtle.digest(\"SHA-256\", data)"))
        assertEquals(listOf("jwt.sign"), apis("jwt.sign(payload, secret)"))
        assertEquals(listOf("DOMParser"), apis("const parser = new DOMParser();"))
        assertEquals("CryptoJS.MD5(\"a\")", translate("CryptoJS.MD5(\"a\")"))
    }

    @Test
    fun rewritesMembersOnParametersBecauseScopeIsNotTracked() {
        assertEquals(
            "function (response) { return pm.response.code; }",
            translate("function (response) { return response.status; }"),
        )
        assertEquals(
            "function (response) { return response; }",
            translate("function (response) { return response; }"),
        )
    }

    @Test
    fun resolvesScriptPathsAndListsFileReferences() {
        assertEquals("users/check.js", resolveHttpScriptPath("users/create.http", "./check.js"))
        assertEquals("check.js", resolveHttpScriptPath("requests.http", "./check.js"))
        assertEquals("shared/check.js", resolveHttpScriptPath("users/create.http", "../shared/check.js"))
        assertEquals("../check.js", resolveHttpScriptPath("requests.http", "../check.js"))
        assertEquals("users/check.js", resolveHttpScriptPath("users/create.http", ".\\check.js"))
        assertEquals(
            listOf("./check.js", "../prep.js"),
            referencedHttpScripts(
                """
                GET https://example.com
                > ./check.js

                ### Next
                < ../prep.js
                POST https://example.com
                > {% client.log(1) %}
                """.trimIndent(),
            ),
        )
        assertTrue(httpScriptMayBeInlined("./check.js"))
        assertTrue(httpScriptMayBeInlined("../shared/check.js"))
        assertFalse(httpScriptMayBeInlined("/tmp/check.js"))
        assertFalse(httpScriptMayBeInlined("~/check.js"))
        assertFalse(httpScriptMayBeInlined("C:/check.js"))
        assertFalse(httpScriptMayBeInlined("C:\\check.js"))
    }

    private fun translate(source: String): String = translateHttpClientScript(source).lines.joinToString("\n")

    private fun apis(source: String): List<String> = translateHttpClientScript(source).unsupportedApis
}

private const val valuesOf =
    "pm.response.headers.filter(function (header) { return header.key.toLowerCase() === String(\"Set-Cookie\").toLowerCase(); }).map(function (header) { return header.value; })"

private const val mimeType =
    "(pm.response.headers.get(\"Content-Type\") || \"\").split(\";\")[0].trim()"

private const val charset =
    "((pm.response.headers.get(\"Content-Type\") || \"\").match(/charset\\s*=\\s*\"?([^\";]+)\"?/i) || [null, null])[1]"

private const val rawBody = "(pm.request.body ? pm.request.body.toString() : \"\")"

private const val substitutedBody =
    "pm.variables.replaceIn(pm.request.body ? pm.request.body.toString() : \"\")"
