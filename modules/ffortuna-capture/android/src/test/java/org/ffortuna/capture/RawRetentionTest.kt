package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RawRetentionTest {
    @Test fun retentionExpiresAtThirtyDaysAndHonorsSubsecondBoundary() {
        val captured="2026-10-01T00:00:00Z"
        val deadline=Instant.parse("2026-10-31T00:00:00Z")
        assertFalse(RawRetention.expired(captured,deadline.minusNanos(1)))
        assertTrue(RawRetention.expired(captured,deadline))
        assertTrue(RawRetention.expired(captured,deadline.plusNanos(1)))
    }
    @Test fun fractionalIsoPrecisionUsesTimeComparisonInsteadOfLexicographicOrder() {
        assertTrue(RawRetention.expired("2026-10-01T00:00:00Z",Instant.parse("2026-10-31T00:00:00.001Z")))
        assertFalse(RawRetention.expired("2026-10-01T00:00:00.001Z",Instant.parse("2026-10-31T00:00:00Z")))
    }
    @Test fun malformedCaptureTimeDoesNotSilentlyEscapeRetention() {
        assertThrows(Exception::class.java) { RawRetention.expiresAt("not-a-time") }
    }
}
