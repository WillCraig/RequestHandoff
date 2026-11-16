package com.willcraig.requesthandoff.core

import com.willcraig.requesthandoff.core.json.Jv
import com.willcraig.requesthandoff.core.json.parseJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthMapperTest {
    @Test
    fun mapsBasicBearerAndDigestCredentials() {
        val encoded = Base64.getEncoder().encodeToString("user:a:b".toByteArray(Charsets.UTF_8))
        val output = exportOne(
            """
            ### Basic tokens
            GET https://example.com/basic
            Authorization: Basic {{username}} {{password}}

            ### Basic encoded
            GET https://example.com/encoded
            Authorization: Basic $encoded

            ### Basic variables
            GET https://example.com/colon
            Authorization: Basic {{user}}:{{pass}}

            ### Bearer
            GET https://example.com/bearer
            Authorization: Bearer {{token}}
            Accept: application/json

            ### Digest
            GET https://example.com/digest
            Authorization: Digest {{digestUser}} {{digestPassword}}
            """.trimIndent(),
            privateOmitted = true,
            publicJson = """{"dev":{"host":"https://dev.example"}}""",
        )
        CollectionSchema.assertValid(output.collectionJson)
        val items = items(output)
        val basic = attributes(requestOf(items[0]))
        assertEquals("basic", authType(requestOf(items[0])))
        assertEquals("{{username}}", basic.getValue("username").str())
        assertEquals("{{password}}", basic.getValue("password").str())
        assertFalse(requestOf(items[0]).fields.containsKey("header"))

        val encodedAuth = attributes(requestOf(items[1]))
        assertEquals("user", encodedAuth.getValue("username").str())
        assertEquals("a:b", encodedAuth.getValue("password").str())

        val colon = attributes(requestOf(items[2]))
        assertEquals("{{user}}", colon.getValue("username").str())
        assertEquals("{{pass}}", colon.getValue("password").str())

        val bearerRequest = requestOf(items[3])
        assertEquals("bearer", authType(bearerRequest))
        assertEquals("{{token}}", attributes(bearerRequest).getValue("token").str())
        val headers = (bearerRequest.fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals(listOf("Accept"), headers.map { it.fields.getValue("key").str() })

        val digest = attributes(requestOf(items[4]))
        assertEquals("digest", authType(requestOf(items[4])))
        assertEquals("{{digestUser}}", digest.getValue("username").str())
        assertEquals("{{digestPassword}}", digest.getValue("password").str())

        assertTrue(output.reportMarkdown.contains("Auth variable 'username' is not in the public environment."))
        assertTrue(output.reportMarkdown.contains("Auth variable 'token' is not in the public environment."))
        assertFalse(output.reportMarkdown.contains("super-secret-value"))
        val dev = environmentValues(output, "dev")
        listOf("username", "password", "user", "pass", "token", "digestUser", "digestPassword").forEach { name ->
            val value = dev.getValue(name)
            assertEquals("", value.value)
            assertEquals("secret", value.type)
        }
    }

    @Test
    fun leavesUnparsedAuthorizationInPlaceWithoutQuotingIt() {
        val output = exportOne(
            """
            ### Basic
            GET https://example.com/basic
            Authorization: Basic test-basic-secret

            ### Digest challenge
            GET https://example.com/digest
            Authorization: Digest username="ada", realm="api", nonce="abc", response="def"

            ### Negotiate
            GET https://example.com/negotiate
            Authorization: Negotiate test-negotiate-blob
            """.trimIndent(),
        )
        CollectionSchema.assertValid(output.collectionJson)
        val items = items(output)
        assertFalse(requestOf(items[0]).fields.containsKey("auth"))
        assertFalse(requestOf(items[1]).fields.containsKey("auth"))
        assertFalse(requestOf(items[2]).fields.containsKey("auth"))
        assertTrue(output.collectionJson.contains("test-basic-secret"))
        assertTrue(output.collectionJson.contains("test-negotiate-blob"))
        assertTrue(output.reportMarkdown.contains("Basic Authorization value was left as a header"))
        assertTrue(output.reportMarkdown.contains("Digest Authorization challenge was left as a header"))
        assertTrue(output.reportMarkdown.contains("Authorization scheme 'Negotiate' was left as a header"))
        assertFalse(output.reportMarkdown.contains("test-basic-secret"))
        assertFalse(output.reportMarkdown.contains("test-negotiate-blob"))
        assertFalse(output.reportMarkdown.contains("nonce"))
    }

    @Test
    fun mapsApiKeyHeaderAndQuery() {
        val output = exportOne(
            """
            ### Header
            GET https://example.com/header
            X-Api-Key: {{apiKey}}
            Accept: application/json

            ### Repeated
            GET https://example.com/repeated
            X-Api-Key: one
            X-Api-Key: two

            ### Query
            GET https://example.com/query?api_key={{apiKey}}&page=2#section

            ### Other name stays
            GET https://example.com/both
            X-Api-Key: first
            Api-Key: second
            """.trimIndent(),
            privateOmitted = true,
            publicJson = """{"dev":{}}""",
        )
        CollectionSchema.assertValid(output.collectionJson)
        val items = items(output)
        val header = attributes(requestOf(items[0]))
        assertEquals("apikey", authType(requestOf(items[0])))
        assertEquals("X-Api-Key", header.getValue("key").str())
        assertEquals("{{apiKey}}", header.getValue("value").str())
        assertEquals("header", header.getValue("in").str())
        val kept = (requestOf(items[0]).fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals(listOf("Accept"), kept.map { it.fields.getValue("key").str() })

        val repeated = attributes(requestOf(items[1]))
        assertEquals("one", repeated.getValue("value").str())
        assertFalse(requestOf(items[1]).fields.containsKey("header"))
        assertTrue(output.reportMarkdown.contains("Repeated API key 'X-Api-Key' was exported once."))

        val queryRequest = requestOf(items[2])
        val query = attributes(queryRequest)
        assertEquals("api_key", query.getValue("key").str())
        assertEquals("query", query.getValue("in").str())
        val url = queryRequest.fields.getValue("url") as Jv.Obj
        assertEquals("https://example.com/query?page=2#section", url.fields.getValue("raw").str())
        assertEquals("section", url.fields.getValue("hash").str())

        val both = attributes(requestOf(items[3]))
        assertEquals("X-Api-Key", both.getValue("key").str())
        assertEquals("first", both.getValue("value").str())
        val second = (requestOf(items[3]).fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("Api-Key", second.single().fields.getValue("key").str())
        assertEquals("second", second.single().fields.getValue("value").str())

        val dev = environmentValues(output, "dev")
        assertEquals("", dev.getValue("apiKey").value)
        assertEquals("secret", dev.getValue("apiKey").type)
        assertTrue(output.reportMarkdown.contains("Auth variable 'apiKey' is not in the public environment."))
    }

    @Test
    fun apiKeyDoesNotOverrideAuthorizationAndIgnoresGenericTokenQuery() {
        val output = exportOne(
            """
            ### Basic wins
            GET https://example.com/basic
            Authorization: Basic {{username}} {{password}}
            X-Api-Key: kept

            ### Unmapped scheme still allows an API key
            GET https://example.com/negotiate
            Authorization: Negotiate test-negotiate-blob
            X-Api-Key: {{apiKey}}

            ### Generic query
            GET https://example.com/items?token=abc&access_token=xyz
            """.trimIndent(),
        )
        val items = items(output)
        assertEquals("basic", authType(requestOf(items[0])))
        val basicHeaders = (requestOf(items[0]).fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("X-Api-Key", basicHeaders.single().fields.getValue("key").str())
        assertEquals("kept", basicHeaders.single().fields.getValue("value").str())

        val negotiate = requestOf(items[1])
        assertEquals("apikey", authType(negotiate))
        assertEquals("{{apiKey}}", attributes(negotiate).getValue("value").str())
        val negotiateHeaders = (negotiate.fields.getValue("header") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("Authorization", negotiateHeaders.single().fields.getValue("key").str())
        assertEquals("Negotiate test-negotiate-blob", negotiateHeaders.single().fields.getValue("value").str())
        assertFalse(output.reportMarkdown.contains("test-negotiate-blob"))

        assertFalse(requestOf(items[2]).fields.containsKey("auth"))
        assertTrue((requestOf(items[2]).fields.getValue("url") as Jv.Obj).fields.getValue("raw").str().contains("access_token=xyz"))
    }

    @Test
    fun mapsOAuth2ReferenceWithoutAcquiringAToken() {
        val output = exportOne(
            """
            ### API
            GET https://example.com/{{${'$'}uuid}}
            Authorization: Bearer {{${'$'}auth.token("api")}}
            """.trimIndent(),
            privateOmitted = true,
            publicJson = OAUTH_PUBLIC,
        )
        CollectionSchema.assertValid(output.collectionJson)
        val request = requestOf(items(output).single())
        assertEquals("oauth2", authType(request))
        assertFalse(request.fields.containsKey("header"))
        val auth = attributes(request)
        assertEquals("authorization_code_with_pkce", auth.getValue("grant_type").str())
        assertEquals("https://auth.example/authorize", auth.getValue("authUrl").str())
        assertEquals("https://auth.example/token", auth.getValue("accessTokenUrl").str())
        assertEquals("client-id", auth.getValue("clientId").str())
        assertEquals("{{clientSecret}}", auth.getValue("clientSecret").str())
        assertEquals("openid", auth.getValue("scope").str())
        assertEquals("https://app.example/callback", auth.getValue("redirect_uri").str())
        assertEquals("header", auth.getValue("client_authentication").str())
        assertEquals("header", auth.getValue("addTokenTo").str())
        assertEquals("Bearer", auth.getValue("tokenType").str())
        assertEquals("Bearer", auth.getValue("headerPrefix").str())
        assertEquals("S256", auth.getValue("challengeAlgorithm").str())
        assertEquals("{{codeVerifier}}", auth.getValue("code_verifier").str())
        assertFalse(auth.containsKey("accessToken"))
        assertFalse(auth.containsKey("refreshToken"))

        val tokenParams = (auth.getValue("tokenRequestParams") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals("audience", tokenParams.first().fields.getValue("key").str())
        assertEquals("https://api.example", tokenParams.first().fields.getValue("value").str())
        val authParams = (auth.getValue("authRequestParams") as Jv.Arr).items.map { it as Jv.Obj }
        assertEquals(listOf("resource", "resource"), authParams.map { it.fields.getValue("key").str() })

        assertTrue(output.reportMarkdown.contains("OAuth2 configuration 'api' was exported without an access token or refresh token."))
        assertTrue(output.reportMarkdown.contains("Custom request headers for 'api' were omitted."))
        assertTrue(output.reportMarkdown.contains("Custom request parameters for 'api' without a Use scope"))
        assertTrue(output.reportMarkdown.contains("Dynamic variable '{{${'$'}uuid}}' was left unchanged."))
        assertFalse(output.reportMarkdown.contains("Dynamic variable '{{${'$'}auth.token(\"api\")}}' was left unchanged."))
        assertFalse(output.reportMarkdown.contains("client-secret-must-not-print"))
        val dev = environmentValues(output, "dev")
        assertEquals("", dev.getValue("clientSecret").value)
        assertEquals("secret", dev.getValue("clientSecret").type)
        assertEquals("", dev.getValue("codeVerifier").value)
    }

    @Test
    fun keepsDynamicWarningWhenTheSameAuthCallRemainsInTheBody() {
        val output = exportOne(
            """
            POST https://example.com/echo
            Authorization: Bearer {{${'$'}auth.token("api")}}

            {{${'$'}auth.token("api")}}
            """.trimIndent(),
            publicJson = """{"dev":{"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Client Credentials","Token URL":"https://auth.example/token","Client ID":"client-id"}}}}}""",
        )
        assertEquals("oauth2", authType(requestOf(items(output).single())))
        assertTrue(output.reportMarkdown.contains("Dynamic variable '{{${'$'}auth.token(\"api\")}}' was left unchanged."))
        assertTrue(output.collectionJson.contains("${'$'}auth.token(\\\"api\\\")"))
    }

    @Test
    fun mapsOAuth2QueryReferenceAndPrefersTheAuthorizationHeader() {
        val output = exportOne(
            """
            ### Query
            GET https://example.com/query?x-token={{${'$'}auth.token("api")}}&keep=1#frag

            ### Access token name
            GET https://example.com/named?access_token={{${'$'}auth.token("api")}}

            ### Header wins
            GET https://example.com/both?access={{${'$'}auth.token("api")}}
            Authorization: {{${'$'}auth.token("api")}}

            ### ID token
            GET https://example.com/id
            Authorization: Bearer {{${'$'}auth.idToken("api")}}
            """.trimIndent(),
            publicJson = """{"dev":{"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Implicit","Auth URL":"https://auth.example/authorize","Client ID":"client-id","Client Credentials":"none"}}}}}""",
        )
        CollectionSchema.assertValid(output.collectionJson)
        val items = items(output)
        val query = attributes(requestOf(items[0]))
        assertEquals("queryParams", query.getValue("addTokenTo").str())
        assertFalse(query.containsKey("headerPrefix"))
        assertFalse(query.containsKey("accessToken"))
        val queryUrl = requestOf(items[0]).fields.getValue("url") as Jv.Obj
        assertEquals("https://example.com/query?keep=1#frag", queryUrl.fields.getValue("raw").str())
        assertTrue(output.reportMarkdown.contains("OAuth2 query parameter 'x-token' was removed. Postman sends the access token as 'access_token'."))

        val named = attributes(requestOf(items[1]))
        assertEquals("queryParams", named.getValue("addTokenTo").str())
        assertFalse(output.reportMarkdown.contains("OAuth2 query parameter 'access_token' was removed."))

        val header = attributes(requestOf(items[2]))
        assertEquals("header", header.getValue("addTokenTo").str())
        assertEquals("", header.getValue("headerPrefix").str())
        assertEquals("https://example.com/both", (requestOf(items[2]).fields.getValue("url") as Jv.Obj).fields.getValue("raw").str())
        assertFalse(requestOf(items[2]).fields.containsKey("header"))
        assertTrue(output.reportMarkdown.contains("OAuth2 query reference was removed because the Authorization header already references an authentication configuration."))

        val idToken = attributes(requestOf(items[3]))
        assertEquals("implicit", idToken.getValue("grant_type").str())
        assertEquals("Bearer", idToken.getValue("headerPrefix").str())
        assertFalse(authAttributes(requestOf(items[3])).any { it.fields.getValue("key").str() == "client_authentication" })
        assertTrue(output.reportMarkdown.contains("OAuth2 client credentials mode 'none' for 'api' was omitted."))
        assertTrue(output.reportMarkdown.contains("OAuth2 ID token for 'api' was omitted."))
    }

    @Test
    fun omitsDeviceGrantAndLiteralPkceVerifier() {
        val output = exportOne(
            """
            GET https://example.com/device
            Authorization: Bearer {{${'$'}auth.token("device")}}
            """.trimIndent(),
            publicJson = """
            {"dev":{"Security":{"Auth":{"device":{
              "Type":"OAuth2",
              "Grant Type":"Device Authorization",
              "Token URL":"https://auth.example/token",
              "Device Auth URL":"https://auth.example/device",
              "Client ID":"client-id",
              "PKCE":{"Code Challenge Method":"Plain","Code Verifier":"literal-pkce-verifier-value"}
            }}}}}
            """.trimIndent(),
        )
        val auth = attributes(requestOf(items(output).single()))
        assertFalse(auth.containsKey("grant_type"))
        assertFalse(auth.containsKey("code_verifier"))
        assertEquals("plain", auth.getValue("challengeAlgorithm").str())
        assertFalse(output.collectionJson.contains("literal-pkce-verifier-value"))
        assertFalse(output.reportMarkdown.contains("literal-pkce-verifier-value"))
        assertTrue(output.reportMarkdown.contains("OAuth2 grant type for 'device' has no Postman oauth2 equivalent and was omitted."))
        assertTrue(output.reportMarkdown.contains("Device Auth URL for 'device' was omitted."))
        assertTrue(output.reportMarkdown.contains("PKCE code verifier for 'device' was omitted."))
    }

    @Test
    fun usesTheFirstEnvironmentWhenAuthConfigsDiffer() {
        val output = exportOne(
            """
            GET https://example.com/items
            Authorization: Bearer {{${'$'}auth.token("api")}}
            """.trimIndent(),
            publicJson = """
            {
              "dev": {"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Password","Token URL":"https://auth.example/token","Client ID":"dev-client","Username":"{{user}}","Password":"{{password}}","Client Credentials":"in body"}}}},
              "prod": {"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Password","Token URL":"https://auth.example/token","Client ID":"prod-client","Username":"other","Password":"other"}}}}
            }
            """.trimIndent(),
            privateOmitted = true,
        )
        val auth = attributes(requestOf(items(output).single()))
        assertEquals("password_credentials", auth.getValue("grant_type").str())
        assertEquals("dev-client", auth.getValue("clientId").str())
        assertEquals("body", auth.getValue("client_authentication").str())
        assertEquals("{{user}}", auth.getValue("username").str())
        assertTrue(output.reportMarkdown.contains("Authentication configuration 'api' differs across environments. request.auth uses 'dev'."))
        assertEquals("", environmentValues(output, "dev").getValue("user").value)
        assertEquals("secret", environmentValues(output, "dev").getValue("user").type)
        assertEquals("", environmentValues(output, "prod").getValue("user").value)
        assertEquals("secret", environmentValues(output, "prod").getValue("user").type)
    }

    @Test
    fun mapsMockAuthAsBearerWithoutPrintingTheToken() {
        val output = exportOne(
            """
            ### Mock
            GET https://example.com/mock
            Authorization: Bearer {{${'$'}auth.token("mock")}}

            ### Empty
            GET https://example.com/empty
            Authorization: Bearer {{${'$'}auth.token("empty")}}
            """.trimIndent(),
            publicJson = """
            {"dev":{"Security":{"Auth":{
              "mock":{"Type":"Mock","Token":"mock-token-value"},
              "empty":{"Type":"Mock","Token":""}
            }}}}
            """.trimIndent(),
        )
        val items = items(output)
        assertEquals("bearer", authType(requestOf(items[0])))
        assertEquals("mock-token-value", attributes(requestOf(items[0])).getValue("token").str())
        assertEquals("", attributes(requestOf(items[1])).getValue("token").str())
        assertTrue(output.reportMarkdown.contains("Mock authentication 'mock' was exported as bearer auth."))
        assertTrue(output.reportMarkdown.contains("Mock authentication 'empty' has no Token in the public environment."))
        assertFalse(output.reportMarkdown.contains("mock-token-value"))
        assertFalse(output.collectionJson.contains("accessToken"))
    }

    @Test
    fun placeholdersWhenPrivateEnvironmentIsOff() {
        val privateSecret = "test-private-client-secret"
        val output = exportOne(
            """
            GET https://example.com/items
            Authorization: Bearer {{${'$'}auth.token("api")}}
            """.trimIndent(),
            publicJson = """{"dev":{"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Client Credentials","Token URL":"https://auth.example/token","Client ID":"client-id","Client Secret":"{{clientSecret}}"}}}}}""",
            privateJson = null,
            privateOmitted = true,
        )
        CollectionSchema.assertValid(output.collectionJson)
        assertEquals("{{clientSecret}}", attributes(requestOf(items(output).single())).getValue("clientSecret").str())
        assertFalse(authAttributes(requestOf(items(output).single())).any { it.fields.getValue("key").str() == "accessToken" })
        val placeholder = environmentValues(output, "dev").getValue("clientSecret")
        assertEquals("", placeholder.value)
        assertEquals("secret", placeholder.type)
        assertTrue(output.reportMarkdown.contains("Auth variable 'clientSecret' is not in the public environment. Private values are off, so a placeholder was exported and the secret was not written."))
        assertFalse(output.reportMarkdown.contains("private authentication configuration was not read"))
        assertFalse(output.collectionJson.contains(privateSecret))
        assertFalse(output.reportMarkdown.contains(privateSecret))
        assertTrue(output.environments.none { it.json.contains(privateSecret) })

        val missing = exportOne(
            """
            GET https://example.com/missing
            Authorization: Bearer {{${'$'}auth.token("missing")}}
            """.trimIndent(),
            privateOmitted = true,
            publicJson = """{"dev":{"host":"https://dev.example"}}""",
        )
        val shell = attributes(requestOf(items(missing).single()))
        assertEquals("header", shell.getValue("addTokenTo").str())
        assertEquals("Bearer", shell.getValue("headerPrefix").str())
        assertFalse(shell.containsKey("accessToken"))
        assertTrue(missing.reportMarkdown.contains("Authentication configuration 'missing' was not found in the public environment."))
        assertTrue(missing.reportMarkdown.contains("Private values are off, so a private authentication configuration was not read."))
    }

    @Test
    fun privateOptInCopiesAuthConfigButNotIntoTheReport() {
        val secret = "test-private-client-secret"
        val output = exportOne(
            """
            GET https://example.com/items
            Authorization: Bearer {{${'$'}auth.token("api")}}
            """.trimIndent(),
            publicJson = """{"dev":{"Security":{"Auth":{"api":{"Type":"OAuth2","Grant Type":"Client Credentials","Token URL":"https://auth.example/token","Client ID":"public-client"}}}}}""",
            privateJson = """{"dev":{"Security":{"Auth":{"api":{"Client ID":"private-client","Client Secret":"$secret"}}}}}""",
            privateOmitted = false,
        )
        val auth = attributes(requestOf(items(output).single()))
        assertEquals("public-client", auth.getValue("clientId").str())
        assertEquals(secret, auth.getValue("clientSecret").str())
        assertFalse(output.reportMarkdown.contains(secret))
        assertTrue(output.environments.single().json.contains(secret))
        assertFalse(output.reportMarkdown.contains("Auth variable"))
    }

    @Test
    fun emptyPublicVariableWarnsWithoutDuplicatingTheKey() {
        val output = exportOne(
            """
            GET https://example.com/health
            Authorization: Bearer {{token}}
            """.trimIndent(),
            publicJson = """{"dev":{"token":""}}""",
            privateOmitted = true,
        )
        val values = environmentValues(output, "dev")
        assertEquals(1, values.keys.count { it == "token" })
        assertEquals("", values.getValue("token").value)
        assertEquals("default", values.getValue("token").type)
        assertTrue(output.reportMarkdown.contains("Auth variable 'token' is empty in the public environment. Private values are off, so the secret was not written."))
    }

    @Test
    fun collectionVariableAndPublicValueSkipPlaceholders() {
        val withVariable = exportOne(
            """
            @token = file-token
            GET https://example.com/health
            Authorization: Bearer {{token}}
            """.trimIndent(),
            publicJson = """{"dev":{"host":"https://dev.example"}}""",
            privateOmitted = true,
        )
        assertFalse(environmentValues(withVariable, "dev").containsKey("token"))
        assertFalse(withVariable.reportMarkdown.contains("Auth variable 'token'"))
        val variables = (parseJson(withVariable.collectionJson) as Jv.Obj).fields.getValue("variable") as Jv.Arr
        val token = variables.items.map { it as Jv.Obj }.first { it.fields.getValue("key").str() == "token" }
        assertEquals("file-token", token.fields.getValue("value").str())

        val publicValue = exportOne(
            """
            GET https://example.com/health
            Authorization: Bearer {{token}}
            """.trimIndent(),
            publicJson = """{"dev":{"token":"public-token"}}""",
            privateOmitted = true,
        )
        val value = environmentValues(publicValue, "dev").getValue("token")
        assertEquals("public-token", value.value)
        assertEquals("default", value.type)
        assertFalse(publicValue.reportMarkdown.contains("Auth variable 'token'"))

        val noEnvironment = exportOne(
            """
            GET https://example.com/health
            Authorization: Bearer {{token}}
            """.trimIndent(),
            privateOmitted = true,
        )
        assertTrue(noEnvironment.environments.isEmpty())
        val collectionVariables = (parseJson(noEnvironment.collectionJson) as Jv.Obj).fields.getValue("variable") as Jv.Arr
        val placeholder = collectionVariables.items.map { it as Jv.Obj }.single()
        assertEquals("token", placeholder.fields.getValue("key").str())
        assertEquals("", placeholder.fields.getValue("value").str())
        assertTrue(noEnvironment.reportMarkdown.contains("Auth variable 'token' is not in the public environment."))
    }

    @Test
    fun doesNotApplySecurityAuthWithoutAReference() {
        val output = exportOne(
            """
            GET https://example.com/health
            Accept: text/plain
            """.trimIndent(),
            publicJson = OAUTH_PUBLIC,
        )
        assertFalse(requestOf(items(output).single()).fields.containsKey("auth"))
        assertFalse(output.reportMarkdown.contains("OAuth2 configuration"))
        assertTrue(output.environments.single().json.contains("Security.Auth.api.Client ID"))
    }

    @Test
    fun authMappingDoesNotConsumeExportIds() {
        val output = exportOne(
            """
            GET https://example.com/health
            Authorization: Bearer {{token}}
            """.trimIndent(),
            publicJson = """{"dev":{"host":"https://dev.example"}}""",
            privateOmitted = true,
        )
        val info = (parseJson(output.collectionJson) as Jv.Obj).fields.getValue("info") as Jv.Obj
        assertEquals("00000000-0000-4000-8000-000000000002", info.fields.getValue("_postman_id").str())
        val environment = parseJson(output.environments.single().json) as Jv.Obj
        assertEquals("00000000-0000-4000-8000-000000000001", environment.fields.getValue("id").str())
    }

    private fun exportOne(
        content: String,
        publicJson: String? = null,
        privateJson: String? = null,
        privateOmitted: Boolean = false,
    ): ExportOutput = exportHttpFiles(
        ExportInput(
            collectionName = "requests",
            singleFile = true,
            files = listOf(HttpFileInput("requests.http", content)),
            publicEnvironmentJson = publicJson,
            privateEnvironmentJson = privateJson,
            privateEnvironmentOmitted = privateOmitted,
        ),
        ExportOptions.fixed(),
    )

    private fun items(output: ExportOutput): List<Jv> =
        ((parseJson(output.collectionJson) as Jv.Obj).fields.getValue("item") as Jv.Arr).items

    private fun requestOf(item: Jv): Jv.Obj = (item as Jv.Obj).fields.getValue("request") as Jv.Obj

    private fun authType(request: Jv.Obj): String =
        (request.fields.getValue("auth") as Jv.Obj).fields.getValue("type").str()

    private fun authAttributes(request: Jv.Obj): List<Jv.Obj> {
        val auth = request.fields.getValue("auth") as Jv.Obj
        val type = auth.fields.getValue("type").str()
        return (auth.fields.getValue(type) as Jv.Arr).items.map { it as Jv.Obj }
    }

    private fun attributes(request: Jv.Obj): Map<String, Jv> =
        authAttributes(request).associate { it.fields.getValue("key").str() to it.fields.getValue("value") }

    private fun environmentValues(output: ExportOutput, name: String): Map<String, EnvValue> {
        val root = parseJson(output.environments.first { it.name == name }.json) as Jv.Obj
        return (root.fields.getValue("values") as Jv.Arr).items.associate { item ->
            val obj = item as Jv.Obj
            obj.fields.getValue("key").str() to EnvValue(
                obj.fields.getValue("value").str(),
                obj.fields.getValue("type").str(),
            )
        }
    }

    private data class EnvValue(val value: String, val type: String)

    private fun Jv.str(): String = assertIs<Jv.Str>(this).value

    private companion object {
        val OAUTH_PUBLIC = """
        {"dev":{"Security":{"Auth":{"api":{
          "Type":"OAuth2",
          "Grant Type":"Authorization Code",
          "Auth URL":"https://auth.example/authorize",
          "Token URL":"https://auth.example/token",
          "Redirect URL":"https://app.example/callback",
          "Client ID":"client-id",
          "Client Secret":"{{clientSecret}}",
          "Scope":"openid",
          "Client Credentials":"basic",
          "PKCE":{"Code Challenge Method":"SHA-256","Code Verifier":"{{codeVerifier}}"},
          "Custom Request Parameters":{
            "audience":{"Value":"https://api.example","Use":"In Token Request"},
            "resource":["https://api.example/a","https://api.example/b"]
          },
          "Custom Request Headers":{"Accept":"application/json"}
        }}}}}
        """.trimIndent()
    }
}
