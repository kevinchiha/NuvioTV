package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The `"silent"` block of `version.json`: from [afterMs] (epoch ms) the update may install
 * without the prompt, and the accounts in [earlyUsers] get it before that. Entries are kept as
 * written; matching trims them.
 */
data class SilentBlock(val afterMs: Long, val earlyUsers: List<String>) {
    companion object {
        /** Null for anything but a valid block (AC-1). Never throws. */
        fun parse(element: JsonElement?): SilentBlock? = try {
            parseOrNull(element)
        } catch (e: Exception) {
            null
        }

        private fun parseOrNull(element: JsonElement?): SilentBlock? {
            val obj = element as? JsonObject ?: return null

            // The manifest Json is lenient, so a quoted "123" would still read as a number
            // through longOrNull. Only a bare JSON number counts.
            val after = obj["after"] as? JsonPrimitive ?: return null
            if (after is JsonNull || after.isString) return null
            val afterMs = after.longOrNull ?: return null
            if (afterMs <= 0) return null

            val earlyUsers = when (val users = obj["earlyUsers"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> users.map { entry ->
                    val primitive = entry as? JsonPrimitive ?: return null
                    if (primitive is JsonNull || !primitive.isString) return null
                    primitive.content
                }
                else -> return null
            }
            return SilentBlock(afterMs, earlyUsers)
        }
    }
}

/** The block this update carries, or null. */
fun AppUpdate.silentBlock(): SilentBlock? {
    val afterMs = silentAfterMs ?: return null
    if (afterMs <= 0) return null
    return SilentBlock(afterMs, silentEarlyUsers)
}
