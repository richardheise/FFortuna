package org.ffortuna.capture

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.time.Instant
import android.database.sqlite.SQLiteException
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Device tests: compiled separately; they require Android Keystore + packaged SQLCipher. */
@RunWith(AndroidJUnit4::class)
class CaptureStorageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun payload(amount:Long=1990)=JSONObject().put("marker","ffortuna.synthetic.v1")
        .put("amountCents",amount).put("kind","expense").put("effectiveDate","2026-10-23")
        .put("description","Teste fictício").put("category","A classificar")
        .put("accountRef","demo-main").put("externalId","fixture").toString()

    @Test fun failedLegacySecuritySetupCanReopenWithoutReplacingTheKey() {
        val name="test-${UUID.randomUUID()}"
        val vault=KeyVault(context,name)
        var store:CaptureStore?=null
        try {
            System.loadLibrary("sqlcipher")
            Logger.setTarget(NoopTarget())
            val file=context.getDatabasePath("$name.db")
            file.parentFile!!.mkdirs()
            val key=vault.load(false)
            try {
                SQLiteDatabase.openOrCreateDatabase(file,key,null,null).use { legacy ->
                    // Reproduce the old initialization failure before running any migration.
                    assertThrows(SQLiteException::class.java) { legacy.execSQL("PRAGMA secure_delete=ON") }
                }
            } finally { key.fill(0) }
            val wrapped=File(context.noBackupFilesDir,"$name.wrapped")
            val before=wrapped.readBytes()
            store=CaptureStore(context,name)
            assertEquals(2,store.snapshot()["schemaVersion"])
            store.observe("after-upgrade",payload(),1792756800000)
            assertEquals(1,store.processPending())
            assertTrue(store.verifyEncrypted())
            assertArrayEquals(before,wrapped.readBytes())
        } finally { store?.close();context.deleteDatabase("$name.db");vault.erase() }
    }

    @Test fun encryptedObservationSurvivesReopenAndInterruptedProcessing() {
        val name="test-${UUID.randomUUID()}"
        var store:CaptureStore?=null
        try {
            store=CaptureStore(context,name)
            val id=store.observe("source-one",payload(),1792756800000)
            assertEquals(1,store.snapshot()["pending"])
            store.close()
            store=CaptureStore(context,name)
            assertEquals(1,store.snapshot()["total"])
            assertEquals(1,store.processPending())
            assertEquals(0,store.snapshot()["pending"])
            assertEquals(id,store.observe("source-one",payload(),1792756800000))
            assertEquals(1,store.snapshot()["total"])
            store.observe("source-one",payload(2990),1792756800001)
            store.observe("source-two",payload(),1792756800000)
            store.processPending()
            assertEquals(3,store.snapshot()["total"])
            assertTrue(store.verifyEncrypted())
            store.close(); store=null
        } finally {
            store?.close(); context.deleteDatabase("$name.db"); KeyVault(context,name).erase()
        }
    }

    @Test fun unavailablePendingPayloadIsErasedWithoutDeletingTheObservation() {
        val name="test-${UUID.randomUUID()}"
        var store:CaptureStore?=null
        try {
            store=CaptureStore(context,name)
            store.observe("old-source",payload(),1792756800000,Instant.now().minusSeconds(31L*86400).toString())
            store.close()
            store=CaptureStore(context,name)
            assertEquals(1,store.snapshot()["total"])
            assertEquals(1,store.snapshot()["unavailablePending"])
            assertEquals(0,store.processPending())
            store.close();store=null
        } finally { store?.close();context.deleteDatabase("$name.db");KeyVault(context,name).erase() }
    }

    @Test fun missingKeystoreKeyDoesNotReplaceWrappedSecret() {
        val name="test-${UUID.randomUUID()}"
        val vault=KeyVault(context,name)
        try {
            val first=vault.load(false)
            val second=vault.load(true)
            assertArrayEquals(first,second)
            first.fill(0); second.fill(0)
            val wrapped=File(context.noBackupFilesDir,"$name.wrapped")
            val before=wrapped.readBytes()
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("ffortuna.$name.wrap.v1")
            assertThrows(IllegalStateException::class.java) { vault.load(true) }
            assertArrayEquals(before,wrapped.readBytes())
        } finally { vault.erase() }
    }

    @Test fun tamperedWrappedKeyFailsClosed() {
        val name="test-${UUID.randomUUID()}"
        val vault=KeyVault(context,name)
        try {
            vault.load(false).fill(0)
            val wrapped=File(context.noBackupFilesDir,"$name.wrapped")
            val bytes=wrapped.readBytes()
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            wrapped.writeBytes(bytes)
            assertThrows(Exception::class.java) { vault.load(true) }
            assertArrayEquals(bytes,wrapped.readBytes())
        } finally { vault.erase() }
    }

    @Test fun wrappedKeyRecoversAnInterruptedAtomicWriteWithoutGeneratingAnotherKey() {
        val name="test-${UUID.randomUUID()}";val vault=KeyVault(context,name)
        try {
            val first=vault.load(false)
            val file=File(context.noBackupFilesDir,"$name.wrapped")
            assertTrue(file.renameTo(File(file.path+".bak")))
            val recovered=vault.load(true)
            assertArrayEquals(first,recovered);assertTrue(file.exists());first.fill(0);recovered.fill(0)
        } finally { vault.erase() }
    }
}
