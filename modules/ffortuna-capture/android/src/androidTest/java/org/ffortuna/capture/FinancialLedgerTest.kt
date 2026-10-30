package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Shared scenarios can also run in app; compiling tests is not evidence of on-device execution. */
@RunWith(AndroidJUnit4::class)
class FinancialLedgerTest {
    private fun run(id: String) {
        FinancialSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext)
            .cases().first { it.id == id }.run()
    }

    @Test fun openingBalancesAndLedger() = run("finance-opening-ledger")
    @Test fun invalidWritesAreAtomic() = run("finance-validation-atomic")
    @Test fun manualCommandRetryIsIdempotent() = run("finance-manual-idempotency")
    @Test fun equalPurchasesRemainSeparate() = run("finance-equal-purchases")
    @Test fun sourceEventPostingIsIdempotent() = run("finance-source-idempotency")
    @Test fun diagnosticClearPreservesFinance() = run("finance-diagnostic-clear-isolated")
    @Test fun portableBackupStagesAndPreservesPrevious() = run("finance-portable-backup")
    @Test fun localRecoveryUsesFinancialPayloadAndRotates() = run("finance-local-recovery")
    @Test fun lostKeyCanRestoreToFreshBox() = run("finance-key-loss-restore")
    @Test fun interruptedStagingIsRecovered() = run("finance-staging-recovery")
    @Test fun schemaOneCatalogMigratesWithoutKeyRewrite() = run("finance-schema-migration")
    @Test fun balanceCheckAndArchivedLinksAreStable() = run("finance-balance-catalog")
}
