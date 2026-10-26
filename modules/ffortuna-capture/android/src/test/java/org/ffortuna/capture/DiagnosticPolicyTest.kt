package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiagnosticPolicyTest {
    private val start=DiagnosticMoment(Instant.parse("2026-10-26T12:00:00Z"),1000,4)
    @Test fun windowStopsAtExactTwentyFourHours() {
        val w=DiagnosticPolicy.window(start)
        assertTrue(w.active(start))
        assertTrue(w.active(start.copy(wall=w.expiresAt.minusNanos(1),elapsed=start.elapsed+DiagnosticPolicy.SESSION.toMillis()-1)))
        assertFalse(w.active(start.copy(wall=w.expiresAt)))
    }
    @Test fun monotonicDeadlineCannotBeExtendedByWallClockChanges() {
        val w=DiagnosticPolicy.window(start)
        assertFalse(w.active(start.copy(wall=start.wall.minusNanos(1))))
        assertFalse(w.active(start.copy(elapsed=start.elapsed+DiagnosticPolicy.SESSION.toMillis())))
        assertFalse(w.active(start.copy(elapsed=start.elapsed-1)))
    }
    @Test fun rebootOrUnavailableBootCountRequiresNewConsent() {
        val w=DiagnosticPolicy.window(start)
        assertFalse(w.active(start.copy(boot=5)))
        assertFalse(w.active(start.copy(boot=-1)))
    }
    @Test fun observationIdentityDetectsUpdatesAndGroupingChanges() {
        val first=DiagnosticPolicy.fingerprint("fixture",123,false,"group")
        assertEquals(first,DiagnosticPolicy.fingerprint("fixture",123,false,"group"))
        assertNotEquals(first,DiagnosticPolicy.fingerprint("fixture-updated",123,false,"group"))
        assertNotEquals(first,DiagnosticPolicy.fingerprint("fixture",124,false,"group"))
        assertNotEquals(first,DiagnosticPolicy.fingerprint("fixture",123,true,"group"))
    }
    @Test fun currentSecurityBatchIsRequiredToStartRealCollection() {
        assertFalse(NativeSecurityRunner.approved(null))
        val report=mapOf<String,Any?>("scope" to NativeSecurityRunner.SCOPE,"passed" to 40,"failed" to 0,"notVerified" to 0)
        assertTrue(NativeSecurityRunner.approved(report))
        assertFalse(NativeSecurityRunner.approved(report+mapOf("passed" to 30)))
        assertFalse(NativeSecurityRunner.approved(report+mapOf("notVerified" to 1)))
        assertFalse(NativeSecurityRunner.approved(report+mapOf("failed" to 1)))
    }
    @Test fun executorErrorsExposeOnlySafeCodes() {
        val error=java.util.concurrent.ExecutionException(DiagnosticFailure("REVIEW_SENSITIVE"))
        assertEquals("REVIEW_SENSITIVE",DiagnosticCapture.safeCode(error))
        assertEquals("DIAGNOSTIC_STORAGE",DiagnosticCapture.safeCode(IllegalStateException("private fixture text")))
    }
    @Test fun retentionAndLimitsAreBoundedIndependentlyOfTheSession() {
        assertEquals(7,DiagnosticPolicy.RETENTION.toDays())
        assertEquals(24,DiagnosticPolicy.SESSION.toHours())
        assertEquals(200,DiagnosticPolicy.MAX_ROWS)
        assertEquals(20,DiagnosticPolicy.MAX_REVIEWED)
    }
}
