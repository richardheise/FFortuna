package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassificationReviewTest {
    private fun run(id: String) {
        ClassificationReviewSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext)
            .cases().first { it.id == id }.run()
    }

    @Test fun legacyCategoryLabelsRemainSupported() = run("classification-review-legacy")
    @Test fun stableIdsPersistAcrossReprocessAndRename() = run("classification-review-persist")
    @Test fun selectedArchivedOptionsRemainButNewOnesAreBlocked() = run("classification-review-archived")
    @Test fun invalidOptionsDoNotReplaceReview() = run("classification-review-invalid")
}
