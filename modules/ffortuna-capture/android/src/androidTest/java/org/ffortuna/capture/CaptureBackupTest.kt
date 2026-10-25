package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only SQLCipher and Keystore checks, also executable by the synthetic app. */
@RunWith(AndroidJUnit4::class)
class CaptureBackupTest {
    private fun run(id: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeSecurityScenarios(context).cases().first { it.id == id }.run()
    }

    @Test fun portableBackupOmitsRawAndRestoresResultsIdentityAndNonprocessableObservations() = run("backup-portable")
    @Test fun restoreWithoutOriginalKeystoreKeyPreservesOldFilesAndSupportsReopen() = run("backup-missing-key")
    @Test fun retainedBoxCanBeSwappedWithoutMergingAndConsentIsPaused() = run("backup-retained")
    @Test fun interruptedStagingIsRemovedBeforeOpeningTheUnchangedActiveBox() = run("backup-staging")
    @Test fun privateDatabaseSidecarsWrappedKeyAndBackupDoNotContainFixturePlaintext() = run("backup-plaintext")
    @Test fun futureMalformedAndDeeplyNestedBackupDataIsRejected() = run("backup-malformed")
    @Test fun actualRollbackJournalAndWalDoNotExposeSyntheticProbeValues() = run("backup-sidecars")
}
