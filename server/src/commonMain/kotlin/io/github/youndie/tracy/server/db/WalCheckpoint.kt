package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** What one forced checkpoint did, and how big the write-ahead log is afterwards. */
@Serializable
public data class WalState(
    /** Size of the `-wal` file. Zero when there is none — nothing has been written yet. */
    public val bytes: Long,
    /** True when readers held the checkpoint off and the log could not be reset. */
    public val busy: Boolean,
    /** Frames in the log when the checkpoint ran. */
    public val framesInLog: Long,
    /** Frames it managed to move into the database file. */
    public val framesCheckpointed: Long,
)

/**
 * Forces the write-ahead log back to the start, because SQLite's own checkpoint cannot.
 *
 * **What M-137 measured.** Under a fixed ingest rate with a read in every tenth request, the `-wal`
 * file grew to 931 MB in an hour while the database file stopped growing at 187 MB, and the process
 * was OOM-killed at a 256 MiB limit. Nothing was leaking: SQLite checkpoints automatically, but only
 * in PASSIVE mode, which copies frames older than the oldest **active reader** and never resets the
 * file while one is running. With overlapping readers there is always one running, so the log only
 * ever grows — and a growing log makes every read slower, which (at a rate that does not back off)
 * raises the number of requests in flight, the number of threads, and with them the number of
 * per-thread malloc arenas. The limit is reached by the second effect, which is why the first one
 * was not visible in `VmRSS` alone.
 *
 * TRUNCATE rather than RESTART: RESTART also resets the log, but leaves the file at its high-water
 * mark, and the high-water mark is the number this exists to keep down. It blocks writers for the
 * length of the copy and waits for readers through the busy timeout — at a minute apart, on a log
 * kept small by the previous run, that is a pause of milliseconds.
 *
 * A busy result is not an error: it means readers were in the way this time. It is reported rather
 * than retried, because the next run is a minute away and the size it reports is the honest state.
 */
public class WalCheckpoint(
    private val db: ISQLite,
    private val dbPath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    public suspend fun checkpoint(): WalState {
        // `PRAGMA wal_checkpoint` answers with one row: busy, frames in the log, frames moved.
        val row =
            db
                .fetchAll("PRAGMA wal_checkpoint(TRUNCATE);")
                .getOrThrow()
                .rows
                .firstOrNull()
        return WalState(
            bytes = walBytes(),
            busy = (row?.get(0)?.asLong() ?: 0L) != 0L,
            framesInLog = row?.get(1)?.asLong() ?: 0L,
            framesCheckpointed = row?.get(2)?.asLong() ?: 0L,
        )
    }

    /**
     * The log on disk, which nothing else counts: `databaseBytes` in [io.github.youndie.tracy.server.retention.RetentionState]
     * is `page_count * page_size`, and pages that are still in the log are not in either number. A
     * server can therefore fill a disk while reporting itself well under its size cap, which is what
     * a 931 MB log next to a 187 MB database looked like from the outside: nothing.
     */
    public fun walBytes(): Long = fileSystem.metadataOrNull("$dbPath-wal".toPath())?.size ?: 0L
}
