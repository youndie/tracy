package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.JsonPrimitive

public const val REDACTED: String = "***"

public data class RedactedText(
    val text: String,
    val changed: Boolean,
)

public data class RedactedFields(
    val fields: Fields?,
    val names: List<String>,
)

/**
 * Redaction runs in the agent, before anything leaves the process, and **before normalisation**.
 *
 * Two lessons from measuring real logs (research 1.10) shaped this:
 *
 * 1. A secret often arrives with **no field name at all** — the live bot token found in production
 *    was inside a URL in a message written by Ktor's own client logging. Redacting named fields
 *    could not have seen it.
 * 2. The template normaliser preserved it, which would have put the secret into `log_template` —
 *    the table that outlives record bodies, gets FTS-indexed and is handed to agents as trusted
 *    text. Hence the ordering rule, which is an invariant and not a preference.
 *
 * Honest limit: this matches **known shapes**. A bespoke key format that resembles nothing here
 * will pass. It is a last line of defence, not permission to log secrets.
 */
public class Redactor(
    private val fieldNames: Set<String> = DEFAULT_FIELD_NAMES,
    private val fieldNamePatterns: List<Regex> = DEFAULT_FIELD_NAME_PATTERNS,
    private val valuePatterns: List<Regex> = DEFAULT_VALUE_PATTERNS,
    private val redactMessageText: Boolean = true,
) {
    public fun redactMessage(message: String): RedactedText {
        if (!redactMessageText) return RedactedText(message, changed = false)

        var out = message
        for (rule in MESSAGE_RULES) {
            out = rule.first.replace(out, rule.second)
        }
        for (rx in valuePatterns) {
            out = rx.replace(out, REDACTED)
        }
        return RedactedText(out, changed = out != message)
    }

    public fun redactFields(fields: Fields?): RedactedFields {
        if (fields.isNullOrEmpty()) return RedactedFields(fields, emptyList())

        val names = mutableListOf<String>()
        val out = LinkedHashMap<String, JsonPrimitive>(fields.size)
        for ((name, value) in fields) {
            if (isSensitiveName(name)) {
                names += name
                out[name] = JsonPrimitive(REDACTED)
                continue
            }
            val redacted = redactMessage(value.content)
            if (redacted.changed) {
                names += name
                out[name] = JsonPrimitive(redacted.text)
            } else {
                out[name] = value
            }
        }
        return RedactedFields(out, names)
    }

    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower in fieldNames || fieldNamePatterns.any { it.containsMatchIn(lower) }
    }

    public companion object {
        public val DEFAULT_FIELD_NAMES: Set<String> =
            setOf(
                "authorization",
                "cookie",
                "set-cookie",
                "password",
                "passwd",
                "token",
                "secret",
                "apikey",
                "credentials",
                "session",
            )

        public val DEFAULT_FIELD_NAME_PATTERNS: List<Regex> =
            listOf(
                Regex("""api[_-]?key"""),
                Regex("""access[_-]?token"""),
                Regex("""refresh[_-]?token"""),
                Regex("""client[_-]?secret"""),
                Regex("""private[_-]?key"""),
            )

        /** Shapes that identify themselves regardless of where they appear. */
        public val DEFAULT_VALUE_PATTERNS: List<Regex> =
            listOf(
                // JWT
                Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}(\.[A-Za-z0-9_-]+)?"""),
                // Card numbers, 13-19 digits with optional separators
                Regex("""\b(?:\d[ -]?){13,19}\b"""),
            )

        /**
         * Message-text rules, applied in order. Each keeps the surrounding structure readable —
         * a log line whose URL turned into `***` is much less useful than one that still shows
         * the host and the endpoint.
         */
        private val MESSAGE_RULES: List<Pair<Regex, String>> =
            listOf(
                // scheme://user:password@host  ->  scheme://***@host
                Regex("""(://)[^/\s:@]+:[^/\s@]+@""") to "$1$REDACTED@",
                // Bearer <token>
                Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{16,}""") to "$1$REDACTED",
                // /bot123456:AAF...  — an id:secret path segment. Exactly the shape found in
                // production logs, and common far beyond Telegram.
                Regex("""(/[^/\s]*?:)[A-Za-z0-9_-]{20,}""") to "$1$REDACTED",
                // ?token=... &api_key=... — masks the value, keeps the parameter name visible
                Regex("""(?i)([?&](?:api[_-]?key|access[_-]?token|token|secret|password|key|auth)=)[^&\s]+""")
                    to "$1$REDACTED",
                // A long opaque path segment: mixed letters and digits, no dots, 32+ chars
                Regex("""(/)(?=[^/\s]{32,}(?:/|$|\?))(?=[^/\s]*[A-Za-z])(?=[^/\s]*\d)[A-Za-z0-9_-]+""")
                    to "$1$REDACTED",
            )
    }
}
