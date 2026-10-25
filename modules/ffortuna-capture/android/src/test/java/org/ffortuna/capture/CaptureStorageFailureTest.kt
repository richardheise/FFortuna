package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureStorageFailureTest {
    @Test fun diagnosticsKeepTheStageWithoutLeakingExceptionContent() {
        val sensitive = "secret-key /private/database.db SELECT private payload"
        val message = storageFailureMessage(CaptureStorageFailure(CaptureStorageStage.SECURITY, IllegalStateException(sensitive)))
        assertTrue(message.contains("[SECURITY/IllegalStateException]"))
        assertFalse(message.contains(sensitive))
        assertFalse(message.contains("secret-key"))
        val pendingError = storageFailureMessage(IllegalArgumentException(sensitive))
        assertTrue(pendingError.contains("[PROCESSING/IllegalArgumentException]"))
        assertFalse(pendingError.contains(sensitive))
    }
}
