package com.payabli.example.app.demo.payment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The raw API response, pretty-printed with keys sorted.
 *
 * Sorted because two responses are read side by side more often than one is read alone, and a field
 * that moves between them is a field a reader has to hunt for. Arrays keep their order: their order
 * is data.
 */
object ResponseJson {
    private val printer =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    const val UNRENDERABLE: String = "The response could not be displayed."

    fun render(response: JsonObject?): String {
        if (response == null) return UNRENDERABLE
        return runCatching { printer.encodeToString(JsonElement.serializer(), sorted(response)) }
            .getOrDefault(UNRENDERABLE)
    }

    private fun sorted(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
            is JsonArray -> JsonArray(element.map { sorted(it) })
            else -> element
        }
}
