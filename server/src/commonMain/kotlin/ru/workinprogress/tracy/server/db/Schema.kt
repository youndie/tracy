package ru.workinprogress.tracy.server.db

/**
 * Static tables: everything that is not sliced by day.
 *
 * The shape follows research D5 and D6, and two of its choices are load bearing rather than
 * cosmetic:
 *
 * - **the message is not stored per record.** D8 makes messages low-cardinality — a template
 *   repeats thousands of times — so a record carries `template_id` and only interpolated messages
 *   keep their raw text. This also shrinks the FTS index by three orders of magnitude, because it
 *   is built over templates instead of rows;
 * - **the exception class is interned** with a plain index. "Where does
 *   `NoTransformationFoundException` appear" is an exact match, not a substring search, and the
 *   stack trace is not indexed at all.
 */
internal val migrationV1: List<String> =
    listOf(
        """CREATE TABLE service (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);""",
        """CREATE TABLE instance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL REFERENCES service(id),
    name TEXT NOT NULL,
    last_seen INTEGER NOT NULL,
    clock_skew_ms INTEGER NOT NULL DEFAULT 0,
    UNIQUE (service_id, name)
);""",
        """CREATE TABLE exception_class (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE
);""",
        """CREATE TABLE entity_key (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE
);""",
        // Global rather than per service: an order created by one service and processed by another
        // has to be one key, and that cross-service view is the whole point (research D12).
        """CREATE TABLE log_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL UNIQUE
);""",
        // contentless_delete lets retention remove rows without keeping a copy of the text.
        // trigram makes substring search use the index instead of degrading to a scan.
        """CREATE VIRTUAL TABLE template_fts USING fts5(
    text,
    content='',
    contentless_delete=1,
    tokenize='trigram'
);""",
        // The only numbers tracy can be honest about: counted by the agent before sampling.
        // `release` is NOT NULL with an empty default because SQLite treats NULLs as distinct in
        // a unique index, which would silently break the upsert.
        """CREATE TABLE template_count (
    service_id INTEGER NOT NULL REFERENCES service(id),
    template_id INTEGER NOT NULL REFERENCES log_template(id),
    level TEXT NOT NULL,
    release TEXT NOT NULL DEFAULT '',
    minute INTEGER NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (service_id, template_id, level, release, minute)
);""",
        """CREATE INDEX template_count_minute ON template_count (minute);""",
        // Idempotency by (instance, seq): a batch redelivered after a timeout must not double
        // anything, and counters are summed on write so they would double loudest.
        """CREATE TABLE ingest_batch (
    instance_id INTEGER NOT NULL REFERENCES instance(id),
    seq INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    PRIMARY KEY (instance_id, seq)
);""",
        """CREATE INDEX ingest_batch_received ON ingest_batch (received_at);""",
        // The breaker of research D15. Durable on purpose: it must survive a restart of the
        // server and of every agent, otherwise a pod restart quietly re-arms a suppressed key.
        """CREATE TABLE entity_key_suppressed (
    key_id INTEGER NOT NULL REFERENCES entity_key(id),
    service_id INTEGER NOT NULL REFERENCES service(id),
    since INTEGER NOT NULL,
    observed_per_minute INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (key_id, service_id)
);""",
    )

/**
 * What the service *produced*, before sampling and before the buffer dropped anything.
 *
 * Stored separately from everything else because it answers a different question: not "how much
 * did we decide to keep" but "who is noisy" (research D13). Measuring the stored rows instead
 * would report tracy's own sampling policy back at the operator.
 */
internal val migrationV2: List<String> =
    listOf(
        """CREATE TABLE service_produced (
    service_id INTEGER NOT NULL REFERENCES service(id),
    minute INTEGER NOT NULL,
    bytes INTEGER NOT NULL,
    dropped INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (service_id, minute)
);""",
        """CREATE INDEX service_produced_minute ON service_produced (minute);""",
    )

internal val allMigrations: List<List<String>> = listOf(migrationV1, migrationV2)

/**
 * Daily partitions. Only at this granularity do both retention and the size cap reduce to
 * `DROP TABLE`; a monthly slice could serve neither, which is what the first version of the docs
 * got wrong (research D6).
 */
internal fun partitionDdl(day: String): List<String> =
    listOf(
        """CREATE TABLE IF NOT EXISTS log_entry_$day (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL,
    instance_id INTEGER NOT NULL,
    ts INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    level TEXT NOT NULL,
    logger TEXT NOT NULL,
    template_id INTEGER NOT NULL,
    raw_message TEXT,
    untrusted INTEGER NOT NULL DEFAULT 0,
    exception_class_id INTEGER,
    exception_message TEXT,
    stack_trace TEXT,
    trace_id BLOB,
    span_id BLOB,
    fields TEXT,
    redacted TEXT,
    release TEXT
);""",
        """CREATE INDEX IF NOT EXISTS log_entry_${day}_trace ON log_entry_$day (trace_id);""",
        """CREATE INDEX IF NOT EXISTS log_entry_${day}_template ON log_entry_$day (template_id, ts);""",
        """CREATE INDEX IF NOT EXISTS log_entry_${day}_service_level
    ON log_entry_$day (service_id, level, ts);""",
        """CREATE INDEX IF NOT EXISTS log_entry_${day}_exception
    ON log_entry_$day (exception_class_id, ts);""",
        // No index on ts alone: the partition *is* the time filter, and rows land in time order,
        // so one would be paid for and never used (research D5).
        """CREATE TABLE IF NOT EXISTS span_$day (
    trace_id BLOB NOT NULL,
    span_id BLOB NOT NULL,
    parent_span_id BLOB,
    service_id INTEGER NOT NULL,
    instance_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    ts INTEGER NOT NULL,
    duration_ms INTEGER,
    status INTEGER,
    error INTEGER,
    fields TEXT
);""",
        """CREATE INDEX IF NOT EXISTS span_${day}_trace ON span_$day (trace_id);""",
        """CREATE INDEX IF NOT EXISTS span_${day}_parent ON span_$day (trace_id, parent_span_id);""",
        """CREATE INDEX IF NOT EXISTS span_${day}_slow ON span_$day (service_id, name, duration_ms);""",
        """CREATE TABLE IF NOT EXISTS entity_ref_$day (
    key_id INTEGER NOT NULL,
    value TEXT NOT NULL,
    ts INTEGER NOT NULL,
    service_id INTEGER NOT NULL,
    instance_id INTEGER NOT NULL,
    trace_id BLOB,
    entry_id INTEGER
);""",
        // Covering index: this answers the structural half of get_entity without touching the
        // table at all, which happens to be exactly what phase one of the MCP contract needs.
        // The second, window-shaped index is deliberately not created here — M-35 measures
        // whether it earns its cost before it exists.
        """CREATE INDEX IF NOT EXISTS entity_ref_${day}_point
    ON entity_ref_$day (key_id, value, ts, service_id, trace_id, entry_id);""",
    )
