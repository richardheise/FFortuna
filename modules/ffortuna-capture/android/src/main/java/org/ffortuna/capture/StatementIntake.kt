package org.ffortuna.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import expo.modules.kotlin.activityresult.AppContextActivityResultContract

/** Retain only a granted URI in memory. Read content after the user's explicit action. */
object StatementIntake {
    @Volatile private var pending:Uri?=null
    fun receive(intent:Intent?):Boolean {
        if(intent==null) return false
        val uri=try {
            when(intent.action) {
                Intent.ACTION_SEND -> if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                    else @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_VIEW -> intent.data
                else -> null
            }
        } catch (_:Exception) { null }
        if(uri?.scheme!="content") { if(intent.action in setOf(Intent.ACTION_SEND,Intent.ACTION_VIEW)) pending=null;return false }
        pending=uri;return true
    }
    fun hasPending()=pending!=null
    fun clear() { pending=null }
    fun importPending(context:Context):Map<String,Any?> {
        val uri=pending ?: throw StatementFailure("NO_SHARED_FILE")
        val result=read(context,uri)
        if(pending==uri) pending=null
        return result
    }
    fun read(context:Context,uri:Uri):Map<String,Any?> {
        if(uri.scheme!="content") throw StatementFailure("INVALID_STATEMENT")
        val bytes=try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                try {
                    while(true) {
                        val size=input.read(buffer);if(size<0) break
                        if(output.size()+size>StatementParser.MAX_BYTES) throw StatementFailure("STATEMENT_TOO_LARGE")
                        output.write(buffer,0,size)
                    }
                } finally { buffer.fill(0) }
                val bytes=output.toByteArray()
                if(bytes.size>StatementParser.MAX_BYTES) { bytes.fill(0);throw StatementFailure("STATEMENT_TOO_LARGE") }
                bytes
            } ?: throw StatementFailure("STATEMENT_IO")
        } catch(error:StatementFailure) { throw error } catch (_:Exception) { throw StatementFailure("STATEMENT_IO") }
        try { return CaptureRuntime.stageStatement(context,bytes) } finally { bytes.fill(0) }
    }
}

class StatementDocumentContract:AppContextActivityResultContract<String,Uri?> {
    override fun createIntent(context:Context,input:String)=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
        putExtra(Intent.EXTRA_LOCAL_ONLY,true)
        putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","text/comma-separated-values","text/plain","application/x-ofx","application/ofx","application/x-qfx","application/octet-stream"))
    }
    override fun parseResult(input:String,resultCode:Int,intent:Intent?):Uri?=
        if(resultCode==android.app.Activity.RESULT_OK) intent?.data?.takeIf { it.scheme=="content" } else null
}
