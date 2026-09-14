package app.aino.mobile.core.db

import kotlin.math.min

sealed interface DeliveryDecision {
    data object Delivered : DeliveryDecision
    data object Failed : DeliveryDecision
    data class Retry(val nextAttemptAtEpochMs: Long) : DeliveryDecision
}

fun deliveryDecision(statusCode: Int?, attempts: Int, nowEpochMs: Long): DeliveryDecision = when {
    statusCode != null && statusCode in 200..299 -> DeliveryDecision.Delivered
    attempts >= MAX_ATTEMPTS -> DeliveryDecision.Failed
    statusCode != null && statusCode in 400..499 && statusCode !in setOf(408, 429) -> DeliveryDecision.Failed
    else -> DeliveryDecision.Retry(nowEpochMs + outboxBackoffMs(attempts))
}

fun outboxBackoffMs(attempts: Int): Long {
    val exponent = attempts.coerceIn(0, 10)
    return min(15 * 60_000L, 5_000L * (1L shl exponent))
}

const val MAX_ATTEMPTS = 6