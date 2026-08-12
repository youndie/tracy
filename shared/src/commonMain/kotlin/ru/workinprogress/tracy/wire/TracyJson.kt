package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration both sides use.
 *
 * - [Json.ignoreUnknownKeys]: a newer agent must not break an older server.
 * - [Json.explicitNulls] off: absent means absent on the wire; nulls would inflate every line.
 * - [Json.encodeDefaults] off: same reason. The discriminator survives this because it is marked
 *   `@EncodeDefault` explicitly, which is the fix the MCP SDK is missing (research 1.8).
 */
public val TracyJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
