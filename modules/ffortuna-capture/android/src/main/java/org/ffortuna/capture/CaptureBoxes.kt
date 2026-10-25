package org.ffortuna.capture

import android.content.Context
import java.util.UUID

/** Used exclusively by the native serial writer. One active box, at most one retained box.
 * A durable pending name recovers interrupted staging; only fully verified boxes become active.
 */
class CaptureBoxes(private val context:Context, preferencesName:String="capture-consent",
    private val defaultName:String="ffortuna-capture-poc") : AutoCloseable {
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private var store:CaptureStore?=null
    init { recoverStaging() }
    fun activeName():String=prefs.getString("active-box",defaultName)!!.also { check(it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    private fun previousName():String?=prefs.getString("previous-box",null)?.also { check(it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    fun hasPrevious()=previousName()!=null
    fun current():CaptureStore=store ?: CaptureStore(context,activeName()).also { store=it }
    private fun deleteBox(name:String) {
        check(name.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val file=context.getDatabasePath("$name.db")
        if(file.exists() || listOf("-wal","-shm","-journal").any { java.io.File(file.path+it).exists() })
            check(context.deleteDatabase(file.name))
        KeyVault(context,name).erase()
    }
    private fun recoverStaging() {
        val pending=prefs.getString("pending-box",null) ?: return
        check(pending!=activeName() && pending!=previousName())
        deleteBox(pending)
        check(prefs.edit().remove("pending-box").commit())
    }
    fun restore(backup:InboxBackup):Int {
        backup.validate()
        recoverStaging()
        if(hasPrevious()) throw BackupFailure(BackupProblem.PREVIOUS_EXISTS)
        val old=activeName();val candidate="ffortuna-restore-${UUID.randomUUID()}"
        check(prefs.edit().putString("pending-box",candidate).commit())
        var activated=false
        try {
            CaptureStore(context,candidate).use { staged -> staged.importBackup(backup);staged.verifyEncrypted() }
            close()
            // Activation and pausing consent are a single durable preference update.
            check(prefs.edit().putString("active-box",candidate).putString("previous-box",old)
                .putBoolean("enabled",false).remove("pending-box").commit())
            activated=true
            return backup.observations.size
        } finally {
            if(!activated) recoverStaging()
        }
    }
    fun switchPrevious() {
        val previous=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        // Validate the target before changing the active pointer. A missing old key fails closed.
        CaptureStore(context,previous).use { it.verifyEncrypted() }
        val old=activeName();close()
        check(prefs.edit().putString("active-box",previous).putString("previous-box",old).putBoolean("enabled",false).commit())
    }
    fun discardPrevious() {
        val previous=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        check(previous!=activeName())
        deleteBox(previous)
        check(prefs.edit().remove("previous-box").commit())
    }
    fun reset() { close();deleteBox(activeName());check(prefs.edit().putBoolean("enabled",false).commit()) }
    override fun close() { val old=store;store=null;old?.close() }
}
