package org.ffortuna.capture

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class DiagnosticFailure(val code:String):RuntimeException(code)
data class DiagnosticMoment(val wall:Instant,val elapsed:Long,val boot:Int)
data class DiagnosticWindow(val startedAt:Instant,val expiresAt:Instant,val elapsedStart:Long,val boot:Int) {
    fun active(now:DiagnosticMoment):Boolean = now.boot>=0 && now.boot==boot && now.elapsed>=elapsedStart &&
        now.elapsed-elapsedStart<DiagnosticPolicy.SESSION.toMillis() && !now.wall.isBefore(startedAt) && now.wall.isBefore(expiresAt)
}
object DiagnosticPolicy {
    const val PACKAGE="com.nu.production"
    const val MAX_ROWS=200
    const val MAX_REVIEWED=20
    const val MAX_FIELD=4096
    const val MAX_CHARS=16*1024
    val SESSION:Duration=Duration.ofHours(24)
    val RETENTION:Duration=Duration.ofDays(7)
    fun window(now:DiagnosticMoment):DiagnosticWindow {
        require(now.elapsed>=0 && now.boot>=0)
        return DiagnosticWindow(now.wall,now.wall.plus(SESSION),now.elapsed,now.boot)
    }
    fun fingerprint(payload:String?,publishedAt:Long,summary:Boolean,group:String?):String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(value in listOf(payload ?: "",publishedAt.toString(),summary.toString(),group ?: "")) {
            val bytes=value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes);bytes.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
