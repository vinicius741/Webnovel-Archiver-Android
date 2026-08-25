package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/*
 * Catalog parameter parsing for the model lists (chat + images), split out of [OpenRouterClient]
 * to keep that file inside the size budget. See that class for the format history and the
 * release bug that motivated the map-shaped image-catalog handling.
 */

/**
 * The request parameters an image model supports, mapped to each parameter's allowed values
 * when the catalog enumerates them. The image catalog ships each parameter as a map entry
 * (`"aspect_ratio": {"type": "enum", "values": [...]}`) — so the parameter names are the map's
 * keys, NOT a string array (treating it as an array was a release bug: Gson's
 * [JsonObject.getAsJsonArray] casts, so every fetch crashed). The values matter as much as the
 * names: many models accept a parameter with a narrower enum than the endpoint's global one
 * (e.g. recraft models offer `3:4` but not `2:3`), and an out-of-enum value is rejected. A
 * plain string array is also accepted for forward compatibility; spec entries without a
 * `values` array mean the parameter is accepted but unconstrained.
 */
internal fun JsonObject.imageSupportedParameters(): Map<String, List<String>?> {
    val member = get("supported_parameters") ?: return emptyMap()
    return when {
        member.isJsonObject ->
            member.asJsonObject.entrySet().associate { (name, spec) ->
                name to spec.enumeratedValues()
            }
        member.isJsonArray ->
            member
                .asJsonArray
                .mapNotNull { parameter -> parameter.takeIf { it.isJsonPrimitive }?.asString }
                .associateWith { null }
        else -> emptyMap()
    }
}

internal fun JsonElement.enumeratedValues(): List<String>? =
    takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.get("values")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
        ?.takeIf { it.isNotEmpty() }

/**
 * The request parameters a chat model supports. The chat catalog reports a plain string array;
 * a map-shaped entry (the image catalog's format) degrades to its keys for forward compatibility.
 */
internal fun JsonObject.chatSupportedParameters(): List<String> {
    val member = get("supported_parameters") ?: return emptyList()
    return when {
        member.isJsonArray ->
            member.asJsonArray.mapNotNull { parameter ->
                parameter.takeIf { it.isJsonPrimitive }?.asString
            }
        member.isJsonObject -> member.asJsonObject.keySet().toList()
        else -> emptyList()
    }
}
