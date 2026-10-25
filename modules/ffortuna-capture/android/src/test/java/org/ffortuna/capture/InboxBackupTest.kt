package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InboxBackupTest {
    private fun row(source:String="source")=BackupObservation(UUID.randomUUID().toString(),source,1,"a".repeat(64),
        "2026-10-25T00:00:00Z","2026-10-25T00:00:01Z","pending",null,null,"simulation","org.ffortuna.capturepoc")
    private fun backup(vararg rows:BackupObservation)=InboxBackup("2026-10-25T00:00:02Z",rows.toList())
    @Test fun legitimateEqualObservationsRemainIndependentButIdentityCollisionsAreRejected() {
        val one=row();val two=row("source-two");backup(one,two).validate()
        assertThrows(BackupFailure::class.java) { backup(one,one).validate() }
        assertThrows(BackupFailure::class.java) { backup(one,one.copy(id=UUID.randomUUID().toString())).validate() }
    }
    @Test fun sourceParserStateAndTimestampAreValidatedBeforePersistence() {
        val valid=row()
        for(invalid in listOf(valid.copy(source="bank"),valid.copy(sourcePackage="other.app"),valid.copy(revision=0),
            valid.copy(capturedAt="yesterday"),valid.copy(state="processed"),valid.copy(parserVersion="future/2")))
            assertThrows(BackupFailure::class.java) { backup(invalid).validate() }
    }
    @Test fun recoverySignatureIgnoresExportTimeButDetectsChangedObservations() {
        val row=row();val one=backup(row)
        assertEquals(one.contentSignature(),one.copy(createdAt="2026-10-26T00:00:00Z").contentSignature())
        assertNotEquals(one.contentSignature(),backup(row.copy(sourceKey="updated-source")).contentSignature())
    }
}
