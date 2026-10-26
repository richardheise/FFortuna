package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticCaptureTest {
    private fun run(id:String) { DiagnosticSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext).cases().first { it.id==id }.run() }
    @Test fun consent() = run("diagnostic-consent")
    @Test fun identities() = run("diagnostic-identities")
    @Test fun groups() = run("diagnostic-groups")
    @Test fun window() = run("diagnostic-window")
    @Test fun retention() = run("diagnostic-retention")
    @Test fun export() = run("diagnostic-export")
    @Test fun quota() = run("diagnostic-quota")
    @Test fun key() = run("diagnostic-key")
    @Test fun extraction() = run("diagnostic-extraction")
    @Test fun isolation() = run("diagnostic-isolation")
}
