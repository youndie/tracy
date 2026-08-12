package ru.workinprogress.tracy.server.template

/**
 * Turns an interpolated message into a template by masking its variable parts.
 *
 * Only interpolated messages go through this. A structured record already *is* its template — the
 * developer wrote a constant and put the values in fields — and that is the whole basis of
 * research D8.
 *
 * Two things this is **not**:
 *
 * - it is not a trust boundary. Masking groups messages; it does not make them safe. Redaction
 *   runs earlier, in the agent, and the ordering is an invariant (research 1.10);
 * - it is not exact. A template that merges two different events is a grouping mistake, not a
 *   correctness one, and the alternative — no grouping at all for interpolated logs — is worse.
 */
public object Normalizer {
    private val rules: List<Pair<Regex, String>> =
        listOf(
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""") to "<uuid>",
            Regex("""\b[\w.+-]+@[\w-]+\.[\w.]{2,}\b""") to "<email>",
            Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""") to "<ip>",
            Regex("""\b\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}\S*""") to "<ts>",
            Regex("""\b[0-9a-fA-F]{8,}\b""") to "<hex>",
            Regex(""""[^"]*"""") to "<str>",
            Regex("""'[^']*'""") to "<str>",
            Regex("""\b\d+(?:\.\d+)?(ms|s|ns|us|kB|KB|MB|GB)\b""") to "<num>$1",
            Regex("""(?<![\w<])\d+(?:\.\d+)?(?![\w>])""") to "<num>",
            Regex("""(/[\w.%-]+){2,}""") to "<path>",
        )

    private const val MAX_LENGTH = 300

    public fun normalize(message: String): String {
        var out = message
        for ((pattern, replacement) in rules) {
            out = pattern.replace(out, replacement)
        }
        return out.replace(Regex("""\s+"""), " ").trim().take(MAX_LENGTH)
    }
}
