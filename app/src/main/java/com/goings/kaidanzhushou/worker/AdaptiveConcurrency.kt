package com.goings.kaidanzhushou.worker

import com.goings.kaidanzhushou.data.remote.KimiErrorKind
import kotlin.math.pow
import kotlin.random.Random

class AdaptiveConcurrency {
    @Volatile
    var limit: Int = 4
        private set
    private var successes = 0

    @Synchronized
    fun success() {
        successes++
        if (limit == 1 && successes >= 5) { limit = 2; successes = 0 }
        else if (limit == 2 && successes >= 5) { limit = 4; successes = 0 }
    }

    @Synchronized
    fun failure(kind: KimiErrorKind) {
        if (kind == KimiErrorKind.RATE_LIMIT) limit = when (limit) {
            4 -> 2
            else -> 1
        }
        successes = 0
    }
}

object RetryPolicy {
    fun isRetryable(kind: KimiErrorKind) = kind in setOf(KimiErrorKind.NETWORK, KimiErrorKind.RATE_LIMIT, KimiErrorKind.SERVER)
    fun pausesBatch(kind: KimiErrorKind) = kind in setOf(KimiErrorKind.UNAUTHORIZED, KimiErrorKind.QUOTA, KimiErrorKind.PERMANENT)
    fun delayMillis(attempt: Int, serverMillis: Long? = null, jitter: Long = Random.nextLong(250, 1250)): Long {
        val exponential = (1_000.0 * 2.0.pow((attempt - 1).coerceIn(0, 5))).toLong()
        return (serverMillis ?: exponential) + jitter
    }
}
