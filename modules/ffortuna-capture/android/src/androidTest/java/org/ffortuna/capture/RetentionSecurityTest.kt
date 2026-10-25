package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Shared isolated cases; compiling this suite is not evidence of execution on a device. */
@RunWith(AndroidJUnit4::class)
class RetentionSecurityTest {
    private fun run(id:String) {
        RetentionSecurityScenarios(InstrumentationRegistry.getInstrumentation().targetContext)
            .cases().first { it.id==id }.run()
    }
    @Test fun policy_settings() = run("policy-settings")
    @Test fun retention_active() = run("retention-active")
    @Test fun retention_previous() = run("retention-previous")
    @Test fun missing_active_db() = run("missing-active-db")
    @Test fun missing_previous_db() = run("missing-previous-db")
    @Test fun retention_missing_key() = run("retention-missing-key")
    @Test fun corrupt_db() = run("corrupt-db")
    @Test fun wrong_db_key() = run("wrong-db-key")
    @Test fun retention_wal() = run("retention-wal")
    @Test fun local_recovery_rotation() = run("local-recovery-rotation")
    @Test fun local_recovery_key_loss() = run("local-recovery-key-loss")
    @Test fun local_recovery_tamper() = run("local-recovery-tamper")
    @Test fun local_recovery_staging() = run("local-recovery-staging")
    @Test fun local_recovery_disable() = run("local-recovery-disable")
    @Test fun log_sensitive_probe() = run("log-sensitive-probe")
}
