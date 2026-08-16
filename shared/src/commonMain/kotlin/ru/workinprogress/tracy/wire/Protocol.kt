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

    /**
     * The agent's clock at the moment it put this batch on the wire.
     *
     * Without it the server can only compute `received - oldestRecord`, which is the clock
     * difference plus the flush wait plus the network plus any retry — and on a 60-second backoff
     * the retry is the whole number. Two services on the stand reported 60 966 ms of "clock skew"
     * that way, which was the backoff ceiling and not a clock at all (M-110).
     *
     * With it there are two separate numbers: `received - sent` is the clock difference, and
     * `sent - oldestRecord` is how long the record waited, measured start to finish on one clock.
     */
    public const val SENT: String = "X-Tracy-Sent"

    /**
     * Identifies one run of the agent — generated at startup, constant until the process ends.
     *
     * The idempotency key used to be `(instance, seq)`, which is right for a retry from the same
     * process and wrong for anything else: the instance name is chosen by the consumer, and
     * nothing required it to be unique per launch. On our own platform three services out of four
     * took the service's public domain from `HOSTNAME`, so every generation of the pod introduced
     * itself as the same instance. A fresh pod restarts `seq` at zero, every batch looked like a
     * redelivery of one already stored, and the server answered `202` — so the agent dropped
     * records that were never written. Silent on both sides for hours after every deploy (M-111).
     *
     * With a run in the key the consumer no longer has to guess a requirement it cannot see.
     */
    public const val RUN: String = "X-Tracy-Run"
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
