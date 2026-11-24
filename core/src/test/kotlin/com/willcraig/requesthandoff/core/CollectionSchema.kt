package com.willcraig.requesthandoff.core

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import kotlin.test.assertTrue

object CollectionSchema {
    private val schema = CollectionSchema::class.java
        .getResourceAsStream("/schema/postman-collection-v2.1.0.schema.json")
        ?.use { stream ->
            SchemaRegistry.withDialect(Dialects.getDraft4()).getSchema(stream)
        }
        ?: error("Vendored Postman Collection v2.1 schema is missing")

    fun errors(json: String): List<String> =
        schema.validate(json, InputFormat.JSON).map { it.message }.sorted()

    fun assertValid(json: String) {
        val failures = errors(json)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
