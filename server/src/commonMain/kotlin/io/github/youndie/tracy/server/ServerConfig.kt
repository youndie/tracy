package io.github.youndie.tracy.server

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
    /**
     * How long a batch marker outlives the batch it marks.
     *
     * Deliberately not tied to [retentionDays]: a marker answers "have I already stored this
     * batch", and that question dies with the agent's last retry, not with the records. Two days is
     * chosen against the retry that can arrive latest — an agent holding a batch it never saw a
     * `202` for keeps asking every minute for as long as the server is unreachable, so the horizon
     * has to cover an outage, and a couple of days covers one nobody slept through. Below that
     * window a deleted marker turns a lawful retry into duplicated records.
     */
    val markersRetentionDays: Int = 2,
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
    /**
     * How many SQLite connections the pool may open.
     *
     * The knob that decides whether tracy survives its own read load, and it is not the throughput
     * knob it looks like. Every pooled connection is a dedicated `sqlx-sqlite-worker` thread with
     * its own malloc arena — and, worse, one more reader that can be holding a snapshot. SQLite's
     * checkpoint cannot reset the log while any reader is active, so a wide pool keeps the window
     * shut, the log grows, reads get slower, more requests are in flight, and the pool opens even
     * more of itself. M-137 measured the two ends: at ten connections the process is killed under
     * both a 256 MiB and a 128 MiB limit; at two it ran past twice the death point with a database
     * twice the size, using a third of the memory.
     */
    val dbMaxConnections: Int = DEFAULT_DB_MAX_CONNECTIONS,
    /**
     * After how long an idle connection is closed, giving its thread and its caches back. `null`
     * keeps every connection the peak ever opened, which is what the pool does by default.
     */
    val dbIdleTimeoutSeconds: Long? = null,
    /**
     * How often the write-ahead log is forced back to the start. `0` turns the sweep off, which
     * leaves the log to SQLite's own PASSIVE checkpoint — and M-137 measured what that comes to
     * under overlapping readers: 931 MB of log in an hour, and the process killed by its limit.
     */
    val walCheckpointSeconds: Long = DEFAULT_WAL_CHECKPOINT_SECONDS,
    /**
     * The size at which the log is swept without waiting for the clock.
     *
     * A sweep on a timer alone is tuned for the quiet case and arrives too late in the one that
     * matters: at 128 MiB M-137 watched the log go 8 → 20 → 31 MB in twenty seconds, because by
     * then reads were slow and every one of them was holding a checkpoint off. What has to be
     * bounded is the file, so the file is what triggers the sweep.
     */
    val walMaxBytes: Long = DEFAULT_WAL_MAX_BYTES,
) {
    companion object {
        /**
         * Two, not ten (M-137): see [dbMaxConnections]. Raising it trades the log — and with it the
         * resident set — for concurrency that SQLite serialises on one writer anyway.
         */
        const val DEFAULT_DB_MAX_CONNECTIONS: Int = 2

        /** See [walCheckpointSeconds]. */
        const val DEFAULT_WAL_CHECKPOINT_SECONDS: Long = 60

        /** See [walMaxBytes]. */
        const val DEFAULT_WAL_MAX_BYTES: Long = 32L * 1024 * 1024

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
                markersRetentionDays = read("TRACY_RETENTION_MARKERS_DAYS")?.toIntOrNull() ?: 2,
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
                dbMaxConnections =
                    read("TRACY_DB_MAX_CONNECTIONS")?.toIntOrNull()?.takeIf { it > 0 }
                        ?: DEFAULT_DB_MAX_CONNECTIONS,
                dbIdleTimeoutSeconds = read("TRACY_DB_IDLE_TIMEOUT_SECONDS")?.toLongOrNull()?.takeIf { it > 0 },
                walCheckpointSeconds =
                    read("TRACY_WAL_CHECKPOINT_SECONDS")?.toLongOrNull()?.takeIf { it >= 0 }
                        ?: DEFAULT_WAL_CHECKPOINT_SECONDS,
                walMaxBytes =
                    read("TRACY_WAL_MAX_BYTES")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_WAL_MAX_BYTES,
            )
        }
    }
}
