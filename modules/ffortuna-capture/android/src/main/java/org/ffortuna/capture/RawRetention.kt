package org.ffortuna.capture

import java.time.Duration
import java.time.Instant
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class RawRetentionSummary(val removed:Int, val remaining:Int, val nextExpiry:Instant?)

/** Native execution triggers cleanup; no promises of work during force-stop or before unlock. */
object RawRetention {
    val duration:Duration=Duration.ofDays(30)
    fun expiresAt(capturedAt:String):Instant=Instant.parse(capturedAt).plus(duration)
    fun expired(capturedAt:String,now:Instant)=!expiresAt(capturedAt).isAfter(now)

    fun purge(db:SQLiteDatabase,now:Instant):RawRetentionSummary {
        val expired=mutableListOf<String>();var remaining=0;var next:Instant?=null
        // ISO timestamps have variable fractional precision; compare Instants, not SQL text.
        db.rawQuery("SELECT id,captured_at FROM observations WHERE payload IS NOT NULL",null).use { rows ->
            while(rows.moveToNext()) {
                val expiry=expiresAt(rows.getString(1))
                if(!expiry.isAfter(now)) expired.add(rows.getString(0))
                else { remaining++;if(next==null || expiry.isBefore(next)) next=expiry }
            }
        }
        var removed=0
        if(expired.isNotEmpty()) {
            db.beginTransaction()
            try {
                db.compileStatement("UPDATE observations SET payload=NULL WHERE id=? AND payload IS NOT NULL").use { statement ->
                    for(id in expired) { statement.bindString(1,id);removed+=statement.executeUpdateDelete() }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val wal=db.rawQuery("PRAGMA journal_mode",null).use { check(it.moveToFirst());it.getString(0).equals("wal",true) }
        if(wal) db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use {
            check(it.moveToFirst() && it.getInt(0)==0) { "RETENTION_CHECKPOINT_BLOCKED" }
        }
        return RawRetentionSummary(removed,remaining,next)
    }
}

data class BoxRetentionStatus(val status:String, val removed:Int=0, val remaining:Int=0,
    val nextExpiry:Instant?=null, val failureCode:String?=null) {
    fun toMap():Map<String,Any?> = mapOf("status" to status,"removed" to removed,"remaining" to remaining,
        "nextExpiry" to nextExpiry?.toString(),"failureCode" to failureCode)
}
data class RetentionStatus(val active:BoxRetentionStatus,val previous:BoxRetentionStatus) {
    val blocked:Boolean get()=active.status=="blocked" || previous.status=="blocked"
    fun toMap():Map<String,Any?> = mapOf("blocked" to blocked,"active" to active.toMap(),"previous" to previous.toMap())
}
