package ru.workinprogress.tracy.server.mcp

import kotlinx.serialization.Serializable

/**
 * The second phase of the MCP contract: free-form values are released only after the agent reports
 * what it checked in phase one.
 *
 * The order is the point. Asking an agent that has already read an injection whether it was an
 * injection is asking a compromised component to audit itself, so phase one hands over structure
 * only — times, identifiers, levels, developer templates, field *keys* — and phase two hands over
 * values.
 *
 * The report has to be refutable, otherwise it is theatre: the server knows the real entries and
 * rejects a report that does not match them.
 *
 * **The trap katcher paid for, avoided here.** Its first gate required *every* claimed stack frame
 * to resolve in the repository, and so it blocked an agent that honestly listed library frames
 * while passing one that reported less. The rule rewarded a worse report. This gate therefore asks
 * for *sufficient* evidence, not complete: one entry that matches the previous result is enough.
 */
@Serializable
public data class ContentRequest(
    public val entryIds: List<Long>,
    /** Entries the agent says it looked at in phase one. */
    public val checked: List<Long> = emptyList(),
)

public sealed interface GateVerdict {
    public data object Allowed : GateVerdict

    public data class Refused(
        val reason: String,
    ) : GateVerdict
}

public class EntryContentGate(
    /** Entry ids this connection has actually been shown in phase one. */
    private val offered: () -> Set<Long>,
) {
    public fun evaluate(request: ContentRequest): GateVerdict {
        if (request.entryIds.isEmpty()) return GateVerdict.Refused("no entries requested")
        if (request.checked.isEmpty()) {
            return GateVerdict.Refused(
                "report which entries you examined: content is released after the structure was read, " +
                    "not before",
            )
        }

        val seen = offered()
        val unknown = request.checked.filter { it !in seen }
        if (unknown.isNotEmpty()) {
            // A report about entries this connection never saw is either a stale copy or an
            // invention; either way it establishes nothing.
            return GateVerdict.Refused("reported entries were not part of any previous result")
        }

        val overlap = request.checked.any { it in request.entryIds }
        if (!overlap) {
            return GateVerdict.Refused("the report does not cover any of the entries requested")
        }

        val outsideOffer = request.entryIds.filter { it !in seen }
        if (outsideOffer.isNotEmpty()) {
            return GateVerdict.Refused("requested entries were not part of any previous result")
        }

        return GateVerdict.Allowed
    }
}
