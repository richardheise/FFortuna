package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassificationCatalogTest {
    private fun run(id: String) {
        ClassificationSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext)
            .cases().first { it.id == id }.run()
    }

    @Test fun seededCategories() = run("classification-seeds")
    @Test fun itemLifecycleAndStableId() = run("classification-lifecycle")
    @Test fun optionsPersistAcrossReopen() = run("classification-persistence")
    @Test fun normalizedDuplicatesAreBlockedPerKind() = run("classification-duplicates")
    @Test fun namesAndTotalCountAreBounded() = run("classification-validation")
    @Test fun missingDatabaseOrKeyPreservesMaterial() = run("classification-missing-material")
    @Test fun catalogSurvivesDiagnosticClear() = run("classification-isolation")
    @Test fun storedLabelsAreEncrypted() = run("classification-encryption")
}
