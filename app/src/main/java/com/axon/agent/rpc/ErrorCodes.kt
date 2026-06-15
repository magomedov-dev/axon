package com.axon.agent.rpc

/**
 * Stable, machine-readable error codes returned in `error.code`. The PC client
 * branches on these (e.g. retry on STALE, refine on AMBIGUOUS_MATCH). Codes are
 * deliberately distinct so the client never has to parse human messages.
 */
object ErrorCodes {
    // transport / protocol
    const val PARSE_ERROR = "PARSE_ERROR"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val METHOD_NOT_FOUND = "METHOD_NOT_FOUND"
    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val INTERNAL = "INTERNAL"

    // accessibility / node operations (used from later stages)
    const val ACCESSIBILITY_DISABLED = "ACCESSIBILITY_DISABLED"
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    const val AMBIGUOUS_MATCH = "AMBIGUOUS_MATCH"
    const val ACTION_NOT_SUPPORTED = "ACTION_NOT_SUPPORTED"
    const val NOT_EDITABLE = "NOT_EDITABLE"
    const val STALE = "STALE"
}
