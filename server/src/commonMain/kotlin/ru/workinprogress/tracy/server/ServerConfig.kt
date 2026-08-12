package ru.workinprogress.tracy.server

/**
 * There is no `System.getenv` on Kotlin/Native, so reading the environment is
 * `expect`/`actual`. See docs/services/tracy-server.md for the full list of variables.
 */
expect fun readEnv(name: String): String?

class ServerConfig(
    val httpPort: Int,
    val dbPath: String,
    val ingestKey: String,
    val maxBatchBytes: Int = 1024 * 1024,
    /** Budget of entity references per minute per (service, key) — the breaker of research D15. */
    val entityRefsPerMinute: Int = 2000,
    val suppressedTtlDays: Long = 14,
    /** One retention, not one per level: two ages in one table cannot both be a DROP (research D6). */
    val retentionDays: Int = 30,
    val countsRetentionDays: Int = 90,
    val maxDbBytes: Long = 4L * 1024 * 1024 * 1024,
    /** MCP is not installed at all when this is null: closed by default, not open. */
    val mcpToken: String? = null,
    val mcpAllowedHosts: List<String> = emptyList(),
    /** Name tracy-server observes itself under. `null` disables self-observation. */
    val selfService: String? = null,
    /**
     * Which instance this is. In a cluster `HOSTNAME` is the pod name, which is what makes a
     * record traceable back to a restart; a constant here would merge every replica into one.
     */
    val instanceId: String = "local",
) {
    companion object {
        /**
         * [read] is injectable so that the validation below is testable on every target.
         * Reading the real environment stays the default.
         */
        fun fromEnv(read: (String) -> String? = ::readEnv): ServerConfig {
            val ingestKey = read("TRACY_INGEST_KEY").orEmpty()

            // Failing here is deliberate. A log collector that quietly started without a key
            // is indistinguishable from a healthy one until the first incident.
            require(ingestKey.isNotBlank()) { "TRACY_INGEST_KEY is required" }

            return ServerConfig(
                httpPort = read("TRACY_HTTP_PORT")?.toIntOrNull() ?: 8080,
                dbPath = read("TRACY_DB_PATH") ?: "/data/tracy.db",
                ingestKey = ingestKey,
                maxBatchBytes = read("TRACY_MAX_BATCH_BYTES")?.toIntOrNull() ?: (1024 * 1024),
                entityRefsPerMinute = read("TRACY_ENTITY_REFS_PER_MINUTE")?.toIntOrNull() ?: 2000,
                suppressedTtlDays = read("TRACY_SUPPRESSED_TTL_DAYS")?.toLongOrNull() ?: 14,
                retentionDays = read("TRACY_RETENTION_DAYS")?.toIntOrNull() ?: 30,
                countsRetentionDays = read("TRACY_RETENTION_COUNTS_DAYS")?.toIntOrNull() ?: 90,
                maxDbBytes = read("TRACY_DB_MAX_BYTES")?.toLongOrNull() ?: (4L * 1024 * 1024 * 1024),
                mcpToken = read("TRACY_MCP_TOKEN")?.takeIf { it.isNotBlank() },
                mcpAllowedHosts =
                    read("TRACY_MCP_ALLOWED_HOSTS")
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                selfService = read("TRACY_SELF_SERVICE")?.takeIf { it.isNotBlank() },
                instanceId = read("HOSTNAME")?.takeIf { it.isNotBlank() } ?: "local",
            )
        }
    }
}
