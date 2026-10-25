package org.ffortuna.capture

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget

/** Device-only: exercises real SQLCipher files, Android Keystore, and pointer recovery. */
@RunWith(AndroidJUnit4::class)
class CaptureBackupTest {
    // Library instrumentation runs as org.ffortuna.capture.test. Keep its isolated storage/UID,
    // but emit source metadata for the production synthetic app, as required by the format.
    private val context:Context get()=object:ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getPackageName()="org.ffortuna.capturepoc"
    }
    private val password="senha sintética de teste".toCharArray()
    private fun payload(description:String="Café fictício",rawOnly:String="raw-only-canary")=JSONObject()
        .put("marker","ffortuna.synthetic.v1").put("amountCents",1990).put("kind","expense")
        .put("effectiveDate","2026-10-25").put("description",description).put("category","Alimentação")
        .put("accountRef","demo-main").put("externalId","fixture").put("rawOnlyCanary",rawOnly).toString()
    private fun scenario(block:(CaptureBoxes,String,String)->Unit) {
        val name="test-${UUID.randomUUID()}";val prefsName="$name-prefs"
        val boxes=CaptureBoxes(context,prefsName,name)
        try { block(boxes,name,prefsName) }
        finally {
            boxes.close()
            val prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
            val names=listOfNotNull(name,prefs.getString("active-box",null),prefs.getString("previous-box",null),prefs.getString("pending-box",null)).toSet()
            for(box in names) { context.deleteDatabase("$box.db");KeyVault(context,box).erase() }
            context.deleteSharedPreferences(prefsName)
        }
    }
    @Test fun portableBackupOmitsRawAndRestoresResultsIdentityAndNonprocessableObservations()=scenario { boxes,_,_ ->
        val source=boxes.current()
        val id=source.observe("one",payload(),1792886400000);source.processPending()
        source.observe("pending",payload(),1792886400001)
        val original=source.exportBackup()
        val plain=original.encode()
        assertFalse(plain.toString(Charsets.UTF_8).contains("raw-only-canary"))
        val encrypted=BackupCodec.encrypt(plain,password);plain.fill(0)
        val decrypted=BackupCodec.decrypt(encrypted,password)
        val backup=InboxBackup.decode(decrypted);decrypted.fill(0)
        assertEquals(2,boxes.restore(backup))
        val restored=boxes.current();val snapshot=restored.snapshot()
        assertEquals(2,snapshot["total"]);assertEquals(0,snapshot["pending"]);assertEquals(1,snapshot["unavailablePending"])
        assertEquals(0,restored.processPending());assertTrue(restored.verifyEncrypted())
        assertEquals(id,restored.observe("one",payload(),1792886400000))
        assertEquals(original,restored.exportBackup().copy(createdAt=original.createdAt))
        @Suppress("UNCHECKED_CAST") val rows=snapshot["observations"] as List<Map<String,Any?>>
        assertTrue(rows.all { it["payload"]==null })
    }
    @Test fun restoreWithoutOriginalKeystoreKeyPreservesOldFilesAndSupportsReopen()=scenario { boxes,name,prefsName ->
        boxes.current().observe("source",payload(),1792886400000);boxes.current().processPending()
        val encrypted=BackupCodec.encrypt(boxes.current().exportBackup().encode(),password)
        boxes.close()
        val wrapped=File(context.noBackupFilesDir,"$name.wrapped");val wrappedBefore=wrapped.readBytes()
        val file=context.getDatabasePath("$name.db");val fileBefore=file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("ffortuna.$name.wrap.v1") }
        assertThrows(CaptureStorageFailure::class.java) { boxes.current() }
        assertThrows(BackupFailure::class.java) { BackupCodec.decrypt(encrypted,"outra senha errada longa".toCharArray()) }
        assertArrayEquals(fileBefore,file.readBytes());assertArrayEquals(wrappedBefore,wrapped.readBytes())
        val plain=BackupCodec.decrypt(encrypted,password)
        val backup=InboxBackup.decode(plain);plain.fill(0)
        boxes.restore(backup)
        assertTrue(boxes.hasPrevious());assertArrayEquals(fileBefore,file.readBytes());assertArrayEquals(wrappedBefore,wrapped.readBytes())
        assertEquals(1,boxes.current().snapshot()["total"])
        val active=boxes.activeName();boxes.close()
        CaptureBoxes(context,prefsName,name).use { reopened ->
            assertEquals(active,reopened.activeName());assertEquals(1,reopened.current().snapshot()["total"])
        }
        assertThrows(CaptureStorageFailure::class.java) { boxes.switchPrevious() }
        assertEquals(active,boxes.activeName())
        assertThrows(BackupFailure::class.java) { boxes.restore(backup) }
        boxes.discardPrevious();assertFalse(file.exists());assertFalse(wrapped.exists())
        boxes.restore(backup);assertEquals(1,boxes.current().snapshot()["total"])
    }
    @Test fun retainedBoxCanBeSwappedWithoutMergingAndConsentIsPaused()=scenario { boxes,_,prefsName ->
        val prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
        boxes.current().observe("first",payload(),1792886400000);boxes.current().processPending()
        val backup=boxes.current().exportBackup()
        boxes.current().observe("second",payload(),1792886400001);boxes.current().processPending()
        assertTrue(prefs.edit().putBoolean("enabled",true).commit())
        boxes.restore(backup);assertFalse(prefs.getBoolean("enabled",true))
        assertEquals(1,boxes.current().snapshot()["total"])
        boxes.switchPrevious();assertEquals(2,boxes.current().snapshot()["total"])
        boxes.switchPrevious();assertEquals(1,boxes.current().snapshot()["total"])
    }
    @Test fun interruptedStagingIsRemovedBeforeOpeningTheUnchangedActiveBox()=scenario { boxes,name,prefsName ->
        boxes.current().observe("active",payload(),1792886400000);boxes.current().processPending()
        val backup=boxes.current().exportBackup();boxes.close()
        val pending="test-staging-${UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("pending-box",pending).commit())
        CaptureStore(context,pending).use { it.importBackup(backup) }
        CaptureBoxes(context,prefsName,name).use { recovered ->
            assertEquals(name,recovered.activeName());assertEquals(1,recovered.current().snapshot()["total"])
            assertFalse(context.getDatabasePath("$pending.db").exists())
            assertFalse(File(context.noBackupFilesDir,"$pending.wrapped").exists())
            assertFalse(prefs.contains("pending-box"))
        }
    }
    @Test fun privateDatabaseSidecarsWrappedKeyAndBackupDoNotContainFixturePlaintext()=scenario { boxes,_,_ ->
        val marker="FFORTUNA-SYNTHETIC-PROBE-${UUID.randomUUID()}"
        boxes.current().observe("probe",payload(marker),1792886400000);boxes.current().processPending()
        val encrypted=BackupCodec.encrypt(boxes.current().exportBackup().encode(),password)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(marker))
        assertTrue(boxes.current().verifyEncrypted())
        val file=context.getDatabasePath("${boxes.activeName()}.db")
        val wrapped=File(context.noBackupFilesDir,"${boxes.activeName()}.wrapped")
        for(path in listOf(file,File(file.path+"-wal"),File(file.path+"-shm"),File(file.path+"-journal"),wrapped,File(wrapped.path+".bak"),File(wrapped.path+".new")))
            if(path.exists()) assertFalse(path.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
    }
    @Test fun futureMalformedAndDeeplyNestedBackupDataIsRejected() {
        val empty=InboxBackup("2026-10-25T00:00:00Z",emptyList()).encode()
        assertEquals(0,InboxBackup.decode(empty).observations.size)
        val future=JSONObject(empty.toString(Charsets.UTF_8)).put("version",2).toString().toByteArray()
        assertThrows(BackupFailure::class.java) { InboxBackup.decode(future) }
        assertThrows(BackupFailure::class.java) { InboxBackup.decode("[".repeat(5000).toByteArray()) }
        assertThrows(BackupFailure::class.java) { InboxBackup.decode(byteArrayOf(0xc3.toByte(),0x28)) }
    }
    @Test fun actualRollbackJournalAndWalDoNotExposeSyntheticProbeValues() {
        val name="test-sidecar-${UUID.randomUUID()}";val vault=KeyVault(context,name)
        val file=context.getDatabasePath("$name.db");file.parentFile!!.mkdirs()
        val marker="FFORTUNA-ROLLBACK-PROBE-${UUID.randomUUID()}";val changed="FFORTUNA-WAL-PROBE-${UUID.randomUUID()}"
        System.loadLibrary("sqlcipher");Logger.setTarget(NoopTarget())
        val key=vault.load(false)
        try {
            SQLiteDatabase.openOrCreateDatabase(file,key,null,null).use { db ->
                db.execSQL("CREATE TABLE probe(value TEXT)");db.execSQL("INSERT INTO probe VALUES (?)",arrayOf(marker))
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE probe SET value=?",arrayOf(changed))
                    val journal=File(file.path+"-journal");assertTrue(journal.exists())
                    assertFalse(journal.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
                    assertFalse(journal.readBytes().toString(Charsets.ISO_8859_1).contains(changed))
                } finally { db.endTransaction() }
                assertTrue(db.enableWriteAheadLogging())
                db.execSQL("INSERT INTO probe VALUES (?)",arrayOf(changed))
                val wal=File(file.path+"-wal");assertTrue(wal.exists())
                for(path in listOf(file,wal,File(file.path+"-shm"))) if(path.exists()) {
                    val text=path.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(text.contains(marker));assertFalse(text.contains(changed))
                }
            }
        } finally { key.fill(0);context.deleteDatabase(file.name);vault.erase() }
    }
}
