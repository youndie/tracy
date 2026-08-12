package ru.workinprogress.tracy.wire

/**
 * Wire constants shared by the agent and the server. The full contract lives in
 * docs/api/protocol-ingest.md; only the names that both sides must agree on are here.
 */
public const val PROTOCOL_VERSION: Int = 1

public const val INGEST_PATH: String = "/ingest"

public object IngestHeaders {
    public const val KEY: String = "X-Tracy-Key"
    public const val SERVICE: String = "X-Tracy-Service"
    public const val INSTANCE: String = "X-Tracy-Instance"
    public const val RELEASE: String = "X-Tracy-Release"
    public const val SEQ: String = "X-Tracy-Seq"
    public const val DROPPED: String = "X-Tracy-Dropped"
    public const val PRODUCED: String = "X-Tracy-Produced"
}

/**
 * Discriminator of an NDJSON line. Absent means a log record — the most frequent case does not
 * pay for the field.
 */
public object LineKind {
    public const val SPAN: String = "s"
    public const val COUNTER: String = "c"
    public const val ENTITY_REF: String = "r"
}
