package com.deepseek.mobilechat.data.local.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Small, explicit accessors so both codecs read the same way. Everything is
// hand-written: this module does not apply the kotlinx-serialization compiler
// plugin, so the `JsonElement` tree is the source of truth.

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requiredString(key: String): String =
    stringOrNull(key) ?: throw IllegalArgumentException("missing string field '$key'")

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.strings(): List<String> =
    mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }

internal fun parseJsonObject(json: String): JsonObject =
    try {
        Json.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalArgumentException("expected a JSON object")
    } catch (e: Exception) {
        if (e is IllegalArgumentException) throw e
        throw IllegalArgumentException("invalid JSON", e)
    }
