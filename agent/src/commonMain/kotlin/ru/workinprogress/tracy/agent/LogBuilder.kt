package ru.workinprogress.tracy.agent

import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.wire.Fields

/**
 * Receiver of the `{ field(...) }` block.
 *
 * Values collected here are **data** and never trusted: they mostly come from outside the process.
 * The message next to them is a constant the developer wrote, and that difference is the whole
 * basis of research D8 — it can only be established here, at the point of writing, because
 * `"user 42 logged in"` no longer shows where `42` came from.
 */
public class LogBuilder {
    private var collected: MutableMap<String, JsonPrimitive>? = null
    private var indexedKeys: MutableList<String>? = null

    public fun field(
        name: String,
        value: String?,
        indexed: Boolean = false,
    ) {
        put(name, JsonPrimitive(value), indexed)
    }

    public fun field(
        name: String,
        value: Number?,
        indexed: Boolean = false,
    ) {
        put(name, JsonPrimitive(value), indexed)
    }

    public fun field(
        name: String,
        value: Boolean?,
        indexed: Boolean = false,
    ) {
        put(name, JsonPrimitive(value), indexed)
    }

    private fun put(
        name: String,
        value: JsonPrimitive,
        indexed: Boolean,
    ) {
        val map = collected ?: LinkedHashMap<String, JsonPrimitive>(4).also { collected = it }
        map[name] = value
        if (indexed) {
            val keys = indexedKeys ?: mutableListOf<String>().also { indexedKeys = it }
            keys += name
        }
    }

    internal fun fields(): Fields? = collected

    internal fun indexed(): List<String>? = indexedKeys
}
