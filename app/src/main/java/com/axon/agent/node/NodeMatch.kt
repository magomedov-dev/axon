package com.axon.agent.node

/**
 * How a `nodeAction` selector value is compared against a node attribute. Pure
 * (no Android) so it is unit-testable and shared by the finder.
 *
 * - `exact`    — equality (default).
 * - `contains` — substring.
 * - `regex`    — find anywhere (Kotlin `Regex.containsMatchIn`); anchor with
 *                `^`/`$` for a full match.
 */
object NodeMatch {
    const val EXACT = "exact"
    const val CONTAINS = "contains"
    const val REGEX = "regex"
    val MODES = setOf(EXACT, CONTAINS, REGEX)

    fun matches(actual: String?, value: String, mode: String): Boolean {
        if (actual == null) return false
        return when (mode) {
            CONTAINS -> actual.contains(value)
            REGEX -> Regex(value).containsMatchIn(actual)
            else -> actual == value
        }
    }
}
