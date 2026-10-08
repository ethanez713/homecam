package com.homecam.core.crypto

/**
 * The last [RESERVE] messages of a push provider's budget are spent only on urgent alerts
 * (session and health alerts too, which are never held back). Whether the budget is known is
 * the provider's to say: a server that reports no limit gives no reason to hold anything.
 */
object AlertReserve {
    const val RESERVE = 10

    /** Messages left, or null when the server reports no limit. */
    fun remaining(limitMessages: Int, messagesRemaining: Int): Int? =
        if (limitMessages <= 0) null else messagesRemaining

    /** [cost] is how many messages this alert spends: one per recipient on the endpoint. */
    fun allowsOrdinary(remaining: Int?, cost: Int): Boolean =
        remaining == null || remaining - cost >= RESERVE
}
