package io.github.youndie.tracy.wire

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
    /**
     * Every message in every service that installs tracy goes through this, so the shape of the
     * loop matters as much as the rules in it.
     *
     * Each rule is gated by a **cheap substring test**. Measuring the hot path (M-28) showed why:
     * running the regexes unconditionally cost ~9 µs for an ordinary message and ~49 µs for one
     * containing a URL, on Kotlin/Native where regex is expensive. Almost no log line contains a
     * URL, a `Bearer` or a query string, so a `contains` that fails in nanoseconds skips the whole
     * cost for the common case. The rules themselves are unchanged — what changed is that they
     * only run when they could possibly match.
     */
    public fun redactMessage(message: String): RedactedText {
        if (!redactMessageText) return RedactedText(message, changed = false)

        var out = message
        for (rule in MESSAGE_RULES) {
            if (!rule.applies(out)) continue
            out = rule.pattern.replace(out, rule.replacement)
        }
        for (rx in valuePatterns) {
            out = rx.replace(out, REDACTED)
        }
        return RedactedText(out, changed = out != message)
    }

    private class MessageRule(
        val pattern: Regex,
        val replacement: String,
        private val trigger: (String) -> Boolean,
    ) {
        fun applies(text: String): Boolean = trigger(text)
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

    /**
     * Same reasoning as [redactMessage]: this runs for every field of every record, and the five
     * name patterns cost more than everything else on the path put together (M-28). All of them
     * contain one of a handful of words, so a substring gate skips the regexes for names like
     * `orderId` — which is what almost every field is called.
     */
    private fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in fieldNames) return true
        if (NAME_HINTS.none { it in lower }) return false
        return fieldNamePatterns.any { it.containsMatchIn(lower) }
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

        internal val BEARER: Regex = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{16,}""")

        /** `bearer`, one whitespace, sixteen token characters: nothing shorter can hold a match. */
        private const val BEARER_MIN_LENGTH = 23

        /**
         * The gate for [BEARER]: long enough to hold a match, and the word in ASCII letters of any case.
         *
         * It replaces `contains("earer", ignoreCase = true)`, which runs for every message and every
         * field value and turned out to be the most expensive thing on the whole redaction path.
         * Measured on Kotlin/Native, one gate at a time: ~506 ns on `order created` and ~1 080 ns on
         * `401: GET /api/user/me` — two thirds of all seven gates together — because case-insensitive
         * comparison of `Char` goes through Unicode case tables for every character tried. The other
         * six gates cost 20–130 ns each.
         *
         * **Necessary, and therefore safe.** A match needs at least [BEARER_MIN_LENGTH] characters,
         * and `(?i)` folds ASCII only, so the word it matched is `bearer` in ASCII letters of some case.
         * Neither half can close on a text the pattern matches; the test beside this checks that as an
         * implication over generated text, including letters outside ASCII.
         */
        internal fun bearerCandidate(text: String): Boolean =
            text.length >= BEARER_MIN_LENGTH && containsAsciiIgnoreCase(text, "bearer")

        /** Case folding for `A`–`Z` and nothing else — which is all `(?i)` does in [BEARER]. */
        private fun containsAsciiIgnoreCase(
            text: String,
            lowerNeedle: String,
        ): Boolean {
            val last = text.length - lowerNeedle.length
            var i = 0
            while (i <= last) {
                var j = 0
                while (j < lowerNeedle.length && asciiLower(text[i + j]) == lowerNeedle[j]) j++
                if (j == lowerNeedle.length) return true
                i++
            }
            return false
        }

        private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

        internal val ID_SECRET: Regex = Regex("""(/[^/\s]*?:)[A-Za-z0-9_-]{20,}""")

        /**
         * The gate for [ID_SECRET]: a `:` somewhere after the first `/`.
         *
         * **Necessary, and therefore safe.** The pattern needs a `/` followed later by a `:`. Wherever
         * that `/` is, the first `/` of the text is no later, so a `:` follows the first one too.
         * A gate that is ever *narrower* than its pattern is not an optimisation but a leak, which
         * is why the test beside this checks the implication over generated text rather than a few
         * examples.
         *
         * The gate it replaces was `'/' in it && ':' in it`, and on real logs that is almost always
         * true. Measured over seven days of template counts on the stand (every record, before
         * sampling): it opened for **80.8%** of them, because the dominant line is a request log —
         * `401: GET /api/user/me` — whose colon belongs to the status and comes before the path, where
         * this pattern can never begin. The gate existed; it just did not keep anything out.
         */
        internal fun colonAfterFirstSlash(text: String): Boolean {
            val slash = text.indexOf('/')
            return slash >= 0 && text.indexOf(':', slash + 1) >= 0
        }

        /** Every pattern in [DEFAULT_FIELD_NAME_PATTERNS] contains one of these. */
        private val NAME_HINTS = listOf("key", "token", "secret")

        public val DEFAULT_FIELD_NAME_PATTERNS: List<Regex> =
            listOf(
                Regex("""api[_-]?key"""),
                Regex("""access[_-]?token"""),
                Regex("""refresh[_-]?token"""),
                Regex("""client[_-]?secret"""),
                Regex("""private[_-]?key"""),
            )

        /**
         * Extra unguarded patterns a host can add. Empty by default: the shapes tracy ships with
         * live in [MESSAGE_RULES], where each one is gated by a cheap test. Anything added here
         * runs on **every** message of **every** service, so add sparingly.
         */
        public val DEFAULT_VALUE_PATTERNS: List<Regex> = emptyList()

        /**
         * Message-text rules, applied in order. Each keeps the surrounding structure readable —
         * a log line whose URL turned into `***` is much less useful than one that still shows
         * the host and the endpoint.
         */
        private val MESSAGE_RULES: List<MessageRule> =
            listOf(
                // scheme://user:password@host  ->  scheme://***@host
                MessageRule(
                    Regex("""(://)[^/\s:@]+:[^/\s@]+@"""),
                    "$1$REDACTED@",
                ) { "://" in it && '@' in it },
                // Bearer <token>
                MessageRule(
                    BEARER,
                    "$1$REDACTED",
                ) { bearerCandidate(it) },
                // /bot123456:AAF...  — an id:secret path segment. Exactly the shape found in
                // production logs, and common far beyond Telegram.
                MessageRule(
                    ID_SECRET,
                    "$1$REDACTED",
                ) { colonAfterFirstSlash(it) },
                // ?token=... &api_key=... — masks the value, keeps the parameter name visible
                MessageRule(
                    Regex("""(?i)([?&](?:api[_-]?key|access[_-]?token|token|secret|password|key|auth)=)[^&\s]+"""),
                    "$1$REDACTED",
                ) { ('?' in it || '&' in it) && '=' in it },
                // A long opaque path segment: mixed letters and digits, no dots, 32+ chars
                MessageRule(
                    Regex("""(/)(?=[^/\s]{32,}(?:/|$|\?))(?=[^/\s]*[A-Za-z])(?=[^/\s]*\d)[A-Za-z0-9_-]+"""),
                    "$1$REDACTED",
                ) { '/' in it && it.length >= 33 },
                // JWT, wherever it appears
                MessageRule(
                    Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}(\.[A-Za-z0-9_-]+)?"""),
                    REDACTED,
                ) { "eyJ" in it },
                // Card numbers, 13-19 digits with optional separators
                MessageRule(
                    Regex("""\b(?:\d[ -]?){13,19}\b"""),
                    REDACTED,
                ) { it.count { c -> c.isDigit() } >= 13 },
            )
    }
}
