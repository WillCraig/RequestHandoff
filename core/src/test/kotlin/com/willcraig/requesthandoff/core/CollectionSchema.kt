package com.willcraig.requesthandoff.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlin.test.assertTrue

object CollectionSchema {
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4).getSchema(
        CollectionSchema::class.java.getResourceAsStream("/schema/postman-collection-v2.1.0.schema.json")
            ?: error("Vendored Postman Collection v2.1 schema is missing"),
    )

    fun errors(json: String): List<String> =
        schema.validate(mapper.readTree(json)).map { it.message }.sorted()

    fun assertValid(json: String) {
        val failures = errors(json)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
