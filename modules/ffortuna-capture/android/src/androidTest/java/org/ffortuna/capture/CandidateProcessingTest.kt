package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateProcessingTest {
    private fun run(id:String) { CandidateSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext).cases().first { it.id==id }.run() }
    @Test fun original() = run("candidate-original")
    @Test fun reprocessing() = run("candidate-reprocessing")
    @Test fun corrections() = run("candidate-corrections")
    @Test fun ambiguity() = run("candidate-ambiguity")
    @Test fun validation() = run("candidate-validation")
    @Test fun updates() = run("candidate-updates")
    @Test fun retention() = run("candidate-retention")
    @Test fun migration() = run("candidate-migration")
}
