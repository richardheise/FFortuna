package org.ffortuna.capture

import android.content.Context
import java.io.File
import java.util.UUID

/** Permanent catalog and one financial book; restore stages a new encrypted box before activation. */
class FinanceBoxes(private val context:Context,preferencesName:String="tique-finance-boxes",
    private val defaultName:String=ClassificationCatalog.DEFAULT_NAME,
    private val nameFactory:()->String={"tique-finance-${UUID.randomUUID()}"}):AutoCloseable {
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private var opened:ClassificationCatalog?=null
    init { recoverStaging() }
    private fun name(value:String):String=value.also { check(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    fun activeName()=name(prefs.getString("active",defaultName)!!)
    private fun previousName()=prefs.getString("previous",null)?.let(::name)
    fun hasPrevious()=previousName()!=null
    fun current():ClassificationCatalog=opened ?: ClassificationCatalog(context,activeName(),!prefs.getBoolean("materialized",false)).also {
        try { check(prefs.edit().putBoolean("materialized",true).commit());opened=it } catch(error:Throwable) { it.close();throw error }
    }
    private fun deleteBox(box:String) {
        name(box)
        val file=context.getDatabasePath("$box.db")
        if(file.exists() || listOf("-wal","-shm","-journal").any { File(file.path+it).exists() }) check(context.deleteDatabase("$box.db"))
        KeyVault(context,box).erase()
    }
    private fun recoverStaging() {
        val pending=prefs.getString("pending",null) ?: return
        check(pending!=activeName() && pending!=previousName())
        deleteBox(pending);check(prefs.edit().remove("pending").commit())
    }
    fun restore(plain:ByteArray):Int {
        val data=FinancialBackup.decode(plain)
        recoverStaging()
        if(hasPrevious()) throw BackupFailure(BackupProblem.PREVIOUS_EXISTS)
        // Preserve the existing permanent catalog even when no financial profile was created yet.
        if(!prefs.getBoolean("materialized",false) && !context.getDatabasePath("${activeName()}.db").exists() && !KeyVault(context,activeName()).hasStoredMaterial()) current().exportFinance().fill(0)
        val old=activeName();val staged=name(nameFactory());check(staged!=old)
        check(!context.getDatabasePath("$staged.db").exists() && !KeyVault(context,staged).hasStoredMaterial())
        check(prefs.edit().putString("pending",staged).commit())
        var activated=false
        try {
            ClassificationCatalog(context,staged).use { it.importFinance(plain);it.exportFinance().fill(0) }
            close()
            check(prefs.edit().putString("active",staged).putString("previous",old).putBoolean("materialized",true).remove("pending").commit())
            activated=true
            return data.getValue("finance_transactions").count { it["kind"]!="opening" }
        } finally { if(!activated) recoverStaging() }
    }
    fun switchPrevious() {
        val target=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        ClassificationCatalog(context,target,false).use { it.exportFinance().fill(0) }
        val old=activeName();close()
        check(prefs.edit().putString("active",target).putString("previous",old).putBoolean("materialized",true).commit())
    }
    fun restartBook():Int {
        val plain=current().exportFinance()
        val empty=try {
            val root=org.json.JSONObject(plain.toString(Charsets.UTF_8));val data=root.getJSONObject("data")
            for(table in FinancialLedger.tables.filter { it!="classifications" }) data.put(table,org.json.JSONArray())
            root.toString().toByteArray(Charsets.UTF_8)
        } finally { plain.fill(0) }
        return try { restore(empty) } finally { empty.fill(0) }
    }
    fun discardPrevious() {
        val target=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        check(target!=activeName());deleteBox(target);check(prefs.edit().remove("previous").commit())
    }
    override fun close() { val old=opened;opened=null;old?.close() }
}
