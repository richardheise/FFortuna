package org.ffortuna.capture

import org.json.JSONObject

/** Validates decrypted reviewed exports without importing them or exposing their contents. */
object DiagnosticBundle {
    fun validate(plain:ByteArray):Int {
        if(plain.size>BackupCodec.MAX_PLAINTEXT) throw DiagnosticFailure("TEXT_LIMIT")
        try {
            val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
            // The export has a fixed, shallow schema; bound nesting before JSONObject parsing.
            var depth=0;var quoted=false;var escaped=false
            for(c in text) {
                if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
                else when(c) { '"' -> quoted=true; '{','[' -> { depth++;if(depth>8) throw DiagnosticFailure("INVALID_EXPORT") }; '}',']' -> { depth--;if(depth<0) throw DiagnosticFailure("INVALID_EXPORT") } }
            }
            if(quoted || depth!=0) throw DiagnosticFailure("INVALID_EXPORT")
            val root=JSONObject(text)
            if(root.keys().asSequence().toSet()!=setOf("format","version","sourcePackage","reviewedByUser","samples") ||
                root.get("format")!="ffortuna-reviewed-notification-samples" || root.get("version")!=1 ||
                root.get("sourcePackage")!=DiagnosticPolicy.PACKAGE || root.get("reviewedByUser")!=true) throw DiagnosticFailure("INVALID_EXPORT")
            val rows=root.getJSONArray("samples")
            if(rows.length() !in 1..DiagnosticPolicy.MAX_REVIEWED) throw DiagnosticFailure("INVALID_EXPORT")
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index)
                if(row.keys().asSequence().toSet()!=setOf("sample","groupSummary","truncated","fields") || row.get("sample")!=index+1 ||
                    row.get("groupSummary") !is Boolean || row.get("truncated") !is Boolean) throw DiagnosticFailure("INVALID_EXPORT")
                DiagnosticFields.decode(row.getJSONObject("fields").toString()).validate(true)
            }
            return rows.length()
        } catch(error:DiagnosticFailure) { throw error }
        catch (_:Exception) { throw DiagnosticFailure("INVALID_EXPORT") }
    }
}
