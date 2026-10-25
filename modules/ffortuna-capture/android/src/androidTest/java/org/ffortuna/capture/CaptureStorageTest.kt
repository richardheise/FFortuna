package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Device tests: use exactly the same isolated scenario callbacks as the on-device checker. */
@RunWith(AndroidJUnit4::class)
class CaptureStorageTest {
    private fun run(id: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeSecurityScenarios(context).cases().first { it.id == id }.run()
    }

    @Test fun failedLegacySecuritySetupCanReopenWithoutReplacingTheKey() = run("legacy-setup")
    @Test fun encryptedObservationSurvivesReopenAndInterruptedProcessing() = run("storage-reopen")
    @Test fun unavailablePendingPayloadIsErasedWithoutDeletingTheObservation() = run("expired-pending")
    @Test fun missingKeystoreKeyDoesNotReplaceWrappedSecret() = run("missing-key")
    @Test fun tamperedWrappedKeyFailsClosed() = run("tampered-key")
    @Test fun wrappedKeyRecoversAnInterruptedAtomicWriteWithoutGeneratingAnotherKey() = run("atomic-key-recovery")
}
