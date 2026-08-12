package ru.workinprogress.tracy.wire

import kotlinx.serialization.Serializable

/**
 * Mirrors `io.github.oshai.kotlinlogging.Level` on purpose: tracy does not invent its own
 * severity scale, it carries the one the ecosystem already uses.
 */
@Serializable
public enum class Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    public fun atLeast(threshold: Level): Boolean = ordinal >= threshold.ordinal
}
