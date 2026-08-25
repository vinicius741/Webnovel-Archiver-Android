package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/*
 * OpenRouter structured-output schemas and provider-routing blocks for the chapter-rewrite
 * pipeline, split out of [AiChapterRewritePlanning] to keep both files inside detekt budgets.
 * Shapes are spike-proven; changing them changes model behavior across every rewrite.
 */
object AiChapterRewriteSchemas {
    /** OpenRouter structured-output schema for the rewrite reply. */
    fun rewriteResponseFormat(): JsonObject =
        JsonObject().apply {
            addProperty("type", "json_schema")
            add(
                "json_schema",
                JsonObject().apply {
                    addProperty("name", "chapter_rewrite")
                    addProperty("strict", true)
                    add(
                        "schema",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        "blocks",
                                        JsonObject().apply {
                                            addProperty("type", "array")
                                            add(
                                                "items",
                                                JsonObject().apply {
                                                    addProperty("type", "object")
                                                    add(
                                                        "properties",
                                                        JsonObject().apply {
                                                            add("id", JsonObject().apply { addProperty("type", "string") })
                                                            add("html", JsonObject().apply { addProperty("type", "string") })
                                                        },
                                                    )
                                                    add(
                                                        "required",
                                                        JsonArray().apply {
                                                            add(JsonPrimitive("id"))
                                                            add(JsonPrimitive("html"))
                                                        },
                                                    )
                                                    addProperty("additionalProperties", false)
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        "self_audit",
                                        JsonObject().apply {
                                            addProperty("type", "object")
                                            add(
                                                "properties",
                                                JsonObject().apply {
                                                    add("protected_blocks_unchanged", JsonObject().apply { addProperty("type", "boolean") })
                                                    add(
                                                        "possible_drift",
                                                        JsonObject().apply {
                                                            addProperty("type", "array")
                                                            add("items", JsonObject().apply { addProperty("type", "string") })
                                                        },
                                                    )
                                                },
                                            )
                                            add(
                                                "required",
                                                JsonArray().apply {
                                                    add(JsonPrimitive("protected_blocks_unchanged"))
                                                    add(JsonPrimitive("possible_drift"))
                                                },
                                            )
                                            addProperty("additionalProperties", false)
                                        },
                                    )
                                },
                            )
                            add(
                                "required",
                                JsonArray().apply {
                                    add(JsonPrimitive("blocks"))
                                    add(JsonPrimitive("self_audit"))
                                },
                            )
                            addProperty("additionalProperties", false)
                        },
                    )
                },
            )
        }

    /** OpenRouter structured-output schema for the verifier reply. */
    fun verifierResponseFormat(): JsonObject =
        JsonObject().apply {
            addProperty("type", "json_schema")
            add(
                "json_schema",
                JsonObject().apply {
                    addProperty("name", "chapter_rewrite_verification")
                    addProperty("strict", true)
                    add(
                        "schema",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        "findings",
                                        JsonObject().apply {
                                            addProperty("type", "array")
                                            add(
                                                "items",
                                                JsonObject().apply {
                                                    addProperty("type", "object")
                                                    add(
                                                        "properties",
                                                        JsonObject().apply {
                                                            add(
                                                                "severity",
                                                                JsonObject().apply {
                                                                    addProperty("type", "string")
                                                                    add(
                                                                        "enum",
                                                                        JsonArray().apply {
                                                                            add(JsonPrimitive("blocker"))
                                                                            add(JsonPrimitive("warning"))
                                                                        },
                                                                    )
                                                                },
                                                            )
                                                            add("type", JsonObject().apply { addProperty("type", "string") })
                                                            add(
                                                                "block_ids",
                                                                JsonObject().apply {
                                                                    addProperty("type", "array")
                                                                    add("items", JsonObject().apply { addProperty("type", "string") })
                                                                },
                                                            )
                                                            add("evidence", JsonObject().apply { addProperty("type", "string") })
                                                        },
                                                    )
                                                    add(
                                                        "required",
                                                        JsonArray().apply {
                                                            add(JsonPrimitive("severity"))
                                                            add(JsonPrimitive("type"))
                                                            add(JsonPrimitive("block_ids"))
                                                            add(JsonPrimitive("evidence"))
                                                        },
                                                    )
                                                    addProperty("additionalProperties", false)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            add("required", JsonArray().apply { add(JsonPrimitive("findings")) })
                            addProperty("additionalProperties", false)
                        },
                    )
                },
            )
        }

    /** Strict privacy-first provider routing; the engine steps down only on routing 404s. */
    fun strictProviderRouting(): JsonObject =
        JsonObject().apply {
            addProperty("zdr", true)
            addProperty("data_collection", "deny")
            addProperty("require_parameters", true)
        }

    fun relaxedProviderRouting(): JsonObject =
        JsonObject().apply {
            addProperty("zdr", true)
            addProperty("data_collection", "deny")
        }
}
