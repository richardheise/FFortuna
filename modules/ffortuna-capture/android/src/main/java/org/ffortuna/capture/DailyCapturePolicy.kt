package org.ffortuna.capture

import java.time.Duration

/** Daily opt-in is separate from diagnostic consent and survives process/reboot. */
object DailyCapturePolicy {
    const val MAX_ROWS=10_000
    val RAW_RETENTION:Duration=Duration.ofDays(30)
}
