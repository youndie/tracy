package ru.workinprogress.tracy.server.mcp

/**
 * Static screen over untrusted text, modelled on katcher's `CrashTrust`.
 *
 * The threat is Agentjacking (research 1.9): an agent does not distinguish data from instructions,
 * and text that reached a log from outside the process can therefore act on it. Authors of the
 * attack say plainly that this is a limitation of the models rather than a configuration mistake,
 * so it is not something a patch removes.
 *
 * Two rules shape the design, both learned from katcher:
 *
 * - **findings never quote the text they withheld.** They are read by the very agent being
 *   protected;
 * - **the screen applies to values and to interpolated messages, never to a developer's
 *   template.** Logs are prose in a way crash titles are not, and screening the developer's own
 *   constants is how a screen starts withholding half of what is useful (research risk 4).
 */
public data class ScreenResult(
    public val safe: Boolean,
    /** Rule names only. Never the payload. */
    public val rules: List<String>,
) {
    public companion object {
        public val SAFE: ScreenResult = ScreenResult(safe = true, rules = emptyList())
    }
}

public object LogTrust {
    /**
     * Invisible characters, written as escapes on purpose: katcher lost one of these to a
     * formatter that silently ate the literal, and the diff showed nothing.
     *
     * The C0 controls at the end were added after looking at a real stream (M-66). A framework's
     * request logger colours its output with ANSI escapes, so `\u001B[31m401 Unauthorized\u001B[m`
     * is what actually arrives — and an escape sequence hides text from a human reader for
     * exactly the reason zero-width characters do. Tab, newline and carriage return are excluded
     * because they are ordinary in a log line.
     */
    private val INVISIBLE =
        Regex(
            "[\u200B\u200C\u200D\u2060\uFEFF\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u00AD" +
                "\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]",
        )

    private val RULES: List<Pair<String, Regex>> =
        listOf(
            "language addressed to the reader" to
                Regex(
                    "(?i)\\b(ignore (all )?(previous|prior)|disregard (the )?above|you are (now )?an?|" +
                        "as an ai|system prompt|new instructions?|do not tell|instead[, ]+(please )?(run|execute)|" +
                        "важно:? выполни|игнорируй предыдущ)",
                ),
            "shell invocation" to
                Regex(
                    "(?i)(\\bcurl\\s+-|\\bwget\\s+https?://|rm\\s+-rf\\s|\\bbash\\s+-c|\\bsh\\s+-c|" +
                        "\\|\\s*(ba)?sh\\b|\\bsudo\\s|\\$\\([^)]+\\)|`[^`]{4,}`)",
                ),
            "mention of a secret store" to
                Regex(
                    "(?i)\\b(AWS_(ACCESS|SECRET)_KEY|GITHUB_TOKEN|NPM_TOKEN|id_rsa|\\.ssh/|" +
                        "\\.aws/credentials|printenv\\b|\\benv\\s*\\|)",
                ),
            "invisible characters" to INVISIBLE,
        )

    public fun screen(text: String?): ScreenResult {
        if (text.isNullOrEmpty()) return ScreenResult.SAFE

        val hits = RULES.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.first }
        return if (hits.isEmpty()) ScreenResult.SAFE else ScreenResult(safe = false, rules = hits)
    }

    /**
     * Screens a field map. Keys are identifiers written by the developer and are left alone;
     * values came from outside.
     */
    public fun screenValues(fields: Map<String, String>): Map<String, ScreenResult> =
        fields.mapValues { (_, value) -> screen(value) }
}
