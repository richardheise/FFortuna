package org.ffortuna.capture

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only fixed literal failure codes may be exposed to JavaScript. */
open class SecurityCheckFailure(val safeCode:String):IllegalStateException(safeCode)

object NativeSecurityRunner {
    const val SCOPE="isolated-native-checks/v5"
    const val REQUIRED_CHECKS=72
    fun approved(report:Map<String,Any?>?):Boolean=report?.let {
        it["scope"]==SCOPE && it["passed"]==REQUIRED_CHECKS && it["failed"]==0 && it["notVerified"]==0
    } ?: false
    fun run(context:Context):Map<String,Any?> {
        val results=mutableListOf<Map<String,Any?>>()
        val probes=mutableListOf<String>()
        val control="FFORTUNA_SECURITY_CONTROL_${UUID.randomUUID()}"
        val start="$control:START";val end="$control:END"
        // Public random control markers contain no notification content, credentials or keys.
        Log.i("FFortunaSecurityCheck",start)
        fun execute(id:String,title:String,block:()->Unit) {
            try { block();results.add(mapOf("id" to id,"title" to title,"status" to "passed","code" to null)) }
            catch(error:Throwable) {
                val code=if(error is SecurityCheckFailure && error.safeCode.matches(Regex("[A-Z0-9_]{1,80}"))) error.safeCode else "CHECK_FAILED"
                results.add(mapOf("id" to id,"title" to title,"status" to "failed","code" to code))
            }
        }
        execute("abandoned-check-cleanup","Limpeza de verificações interrompidas") { SecuritySandbox.recoverAbandoned(context) }
        val specific=RetentionSecurityScenarios(context) { probes.add(it) }.cases()
        val policy=specific.first { it.id=="policy-settings" }
        val cases=listOf(policy)+NativeSecurityScenarios(context).cases()+specific.filter { it.id!=policy.id }+DiagnosticSecurityScenarios(context) { probes.add(it) }.cases()+CandidateSecurityScenarios(context) { probes.add(it) }.cases()+ClassificationSecurityScenarios(context).cases()+ClassificationReviewSecurityScenarios(context).cases()+FinancialSecurityScenarios(context).cases()
        for(case in cases) execute(case.id,case.title,case.run)
        Log.i("FFortunaSecurityCheck",end)
        results.add(logWindow(start,end,probes))
        probes.clear()
        return mapOf("ranAt" to Instant.now().toString(),"syntheticOnly" to true,
            "model" to Build.MODEL,"sdk" to Build.VERSION.SDK_INT,"scope" to SCOPE,
            "passed" to results.count { it["status"]=="passed" },"failed" to results.count { it["status"]=="failed" },
            "notVerified" to results.count { it["status"]=="not_verified" },"cases" to results)
    }

    /** Best-effort bounded observation of this process's main log buffer, not device-wide logging. */
    private fun logWindow(start:String,end:String,probes:List<String>):Map<String,Any?> {
        fun result(status:String,code:String?)=mapOf("id" to "own-log-window","title" to "Janela de logs do próprio processo","status" to status,"code" to code)
        var process:java.lang.Process?=null
        var readerThread:Thread?=null
        val overflow=AtomicBoolean(false);val failed=AtomicBoolean(false)
        val bytes=ByteArrayOutputStream()
        return try {
            val child=ProcessBuilder("logcat","-d","-b","main","-v","raw","--pid=${Process.myPid()}").redirectErrorStream(true).start()
            process=child
            val reader=Thread {
                try { child.inputStream.use { input ->
                    val buffer=ByteArray(4096)
                    while(true) {
                        val count=input.read(buffer);if(count<0) break
                        if(bytes.size()+count>1024*1024) { overflow.set(true);child.destroy();break }
                        bytes.write(buffer,0,count)
                    }
                    buffer.fill(0)
                } } catch (_:Exception) { failed.set(true) }
            }.apply { isDaemon=true;name="ffortuna-log-check";start() }
            readerThread=reader
            val finished=child.waitFor(8,TimeUnit.SECONDS)
            if(!finished) child.destroyForcibly()
            reader.join(2000)
            if(!finished || reader.isAlive || overflow.get() || failed.get() || child.exitValue()!=0) result("not_verified","LOG_WINDOW_UNAVAILABLE")
            else {
                val raw=bytes.toByteArray()
                val text=try { raw.toString(Charsets.UTF_8) } finally { raw.fill(0) }
                val from=text.indexOf(start);val until=text.lastIndexOf(end)
                if(from<0 || until<=from || probes.isEmpty()) result("not_verified","LOG_CONTROLS_MISSING")
                else {
                    val window=text.substring(from,until)
                    val fixtureLeak=listOf("FFORTUNA-SYNTHETIC-PROBE-","FFORTUNA-ROLLBACK-PROBE-","FFORTUNA-WAL-PROBE-").any { window.contains(it) }
                    if(fixtureLeak || probes.any { window.contains(it) }) result("failed","LOG_SENSITIVE_PROBE_FOUND")
                    else result("passed",null)
                }
            }
        } catch (_:Exception) { result("not_verified","LOG_WINDOW_UNAVAILABLE") }
        finally {
            process?.destroy()
            try { process?.inputStream?.close() } catch (_:Exception) { }
            try { readerThread?.join(2000) } catch (_:InterruptedException) { Thread.currentThread().interrupt() }
            if(readerThread?.isAlive!=true) bytes.reset()
        }
    }
}
