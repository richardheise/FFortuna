package org.ffortuna.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureIdentityTest {
    @Test
    fun acceptsOnlyEnabledOwnNonSummaryNotifications() {
        assertTrue(CaptureIdentity.accepts("org.ffortuna.test", "org.ffortuna.test", false, true))
        assertFalse(CaptureIdentity.accepts("org.ffortuna.test", "org.ffortuna.test", true, true))
        assertFalse(CaptureIdentity.accepts("com.other.app", "org.ffortuna.test", false, true))
        assertFalse(CaptureIdentity.accepts("org.ffortuna.test", "org.ffortuna.test", false, false))
    }

    @Test
    fun sourceKeyPreservesNotificationAndGenerationIdentity() {
        val first = CaptureIdentity.sourceKey("notif-1", "generation-a")
        val second = CaptureIdentity.sourceKey("notif-1", "generation-b")

        assertNotEquals(first, second)
        assertEquals(first, CaptureIdentity.sourceKey("notif-1", "generation-a"))
        assertNotEquals(
            CaptureIdentity.sourceKey("ab", "c"),
            CaptureIdentity.sourceKey("b", "ca"),
        )
    }

    @Test
    fun fingerprintIsStableForRedeliveryAndChangesWithPayloadOrPublicationTime() {
        val observed = CaptureIdentity.fingerprint("Compra sintética R$ 12,00", 1_725_000_000_000L)

        assertEquals(observed, CaptureIdentity.fingerprint("Compra sintética R$ 12,00", 1_725_000_000_000L))
        assertNotEquals(observed, CaptureIdentity.fingerprint("Compra sintética R$ 13,00", 1_725_000_000_000L))
        assertNotEquals(observed, CaptureIdentity.fingerprint("Compra sintética R$ 12,00", 1_725_000_000_001L))
    }

    @Test
    fun equalPurchasesRemainDistinctAcrossGenerations() {
        val fingerprint = CaptureIdentity.fingerprint("Compra sintética R$ 12,00", 1_725_000_000_000L)
        val firstSource = CaptureIdentity.sourceKey("notif-1", "purchase-generation-1")
        val secondSource = CaptureIdentity.sourceKey("notif-1", "purchase-generation-2")

        assertEquals(fingerprint, CaptureIdentity.fingerprint("Compra sintética R$ 12,00", 1_725_000_000_000L))
        assertNotEquals(firstSource, secondSource)
    }
}
