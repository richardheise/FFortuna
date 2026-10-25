package org.ffortuna.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Deliberately no raw payload or local database/Keystore key in this portable model. */
data class BackupObservation(val id:String, val sourceKey:String, val revision:Int, val fingerprint:String,
    val publishedAt:String, val capturedAt:String, val state:String, val parserVersion:String?,
    val parsedJson:String?, val source:String, val sourcePackage:String)
data class InboxBackup(val createdAt:String, val observations:List<BackupObservation>) {
    /** Stable data identity for automatic local recovery; excludes backup creation time and raw data. */
    fun contentSignature():String {
        validate()
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun field(value:String?) {
            val bytes=value?.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if(bytes!=null) { digest.update(bytes);bytes.fill(0) }
        }
        field("ffortuna.synthetic-inbox/1");field(observations.size.toString())
        for(row in observations) for(value in listOf(row.id,row.sourceKey,row.revision.toString(),row.fingerprint,
            row.publishedAt,row.capturedAt,row.state,row.parserVersion,row.parsedJson,row.source,row.sourcePackage)) field(value)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun validate() {
        fun invalid(): Nothing = throw BackupFailure(BackupProblem.INVALID_DATA)
        fun timestamp(value:String) { if(value.length>40) invalid();try { Instant.parse(value) } catch (_:Exception) { invalid() } }
        timestamp(createdAt)
        if(observations.size>10_000) throw BackupFailure(BackupProblem.TOO_LARGE)
        val ids=HashSet<String>();val revisions=HashSet<Pair<String,Int>>();val fingerprints=HashSet<Pair<String,String>>()
        var characters=0L
        for(row in observations) {
            characters+=row.sourceKey.length.toLong()+(row.parsedJson?.length ?: 0)+256
            if(characters>BackupCodec.MAX_PLAINTEXT) throw BackupFailure(BackupProblem.TOO_LARGE)
            try { if(UUID.fromString(row.id).toString()!=row.id) invalid() } catch (_:Exception) { invalid() }
            if(!ids.add(row.id) || !revisions.add(row.sourceKey to row.revision) || !fingerprints.add(row.sourceKey to row.fingerprint)) invalid()
            if(row.sourceKey.length !in 1..1024 || row.revision<1 || !row.fingerprint.matches(Regex("[a-f0-9]{64}"))) invalid()
            timestamp(row.publishedAt);timestamp(row.capturedAt)
            if(row.source!="simulation" || row.sourcePackage!="org.ffortuna.capturepoc") invalid()
            if(row.state !in listOf("pending","processed")) invalid()
            if(row.state=="pending" && (row.parserVersion!=null || row.parsedJson!=null)) invalid()
            if(row.state=="processed" && (row.parserVersion!="native-synthetic/1" || row.parsedJson==null || row.parsedJson.length>16_384)) invalid()
        }
    }
    fun encode():ByteArray {
        validate()
        val rows=JSONArray()
        for(row in observations) rows.put(JSONObject().put("id",row.id).put("sourceKey",row.sourceKey).put("revision",row.revision)
            .put("fingerprint",row.fingerprint).put("publishedAt",row.publishedAt).put("capturedAt",row.capturedAt)
            .put("state",row.state).put("parserVersion",row.parserVersion ?: JSONObject.NULL).put("parsedJson",row.parsedJson ?: JSONObject.NULL)
            .put("source",row.source).put("sourcePackage",row.sourcePackage))
        val bytes=JSONObject().put("format","ffortuna.synthetic-inbox").put("version",1).put("syntheticOnly",true)
            .put("rawPayloadsIncluded",false).put("createdAt",createdAt).put("observations",rows).toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>BackupCodec.MAX_PLAINTEXT) { bytes.fill(0);throw BackupFailure(BackupProblem.TOO_LARGE) }
        return bytes
    }
    companion object {
        private fun boundedJson(text:String) {
            var depth=0;var quoted=false;var escaped=false
            for(c in text) {
                if(quoted) {
                    if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false
                } else when(c) {
                    '"' -> quoted=true
                    '{','[' -> { depth++;if(depth>32) throw BackupFailure(BackupProblem.INVALID_DATA) }
                    '}',']' -> { depth--;if(depth<0) throw BackupFailure(BackupProblem.INVALID_DATA) }
                }
            }
            if(depth!=0 || quoted) throw BackupFailure(BackupProblem.INVALID_DATA)
        }
        fun decode(bytes:ByteArray):InboxBackup {
            if(bytes.size>BackupCodec.MAX_PLAINTEXT) throw BackupFailure(BackupProblem.TOO_LARGE)
            try {
                val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                boundedJson(text)
                val root=JSONObject(text)
                require(root.keys().asSequence().toSet()==setOf("format","version","syntheticOnly","rawPayloadsIncluded","createdAt","observations"))
                require(root.getString("format")=="ffortuna.synthetic-inbox" && root.get("version")==1)
                require(root.get("syntheticOnly")==true && root.get("rawPayloadsIncluded")==false)
                val rows=root.getJSONArray("observations");if(rows.length()>10_000) throw BackupFailure(BackupProblem.TOO_LARGE)
                val observations=(0 until rows.length()).map { i ->
                    val row=rows.getJSONObject(i)
                    require(row.keys().asSequence().toSet()==setOf("id","sourceKey","revision","fingerprint","publishedAt","capturedAt","state","parserVersion","parsedJson","source","sourcePackage"))
                    fun text(key:String)=row.get(key).also { require(it is String) } as String
                    fun nullable(key:String)=if(row.isNull(key)) null else text(key)
                    val revision=row.get("revision");require(revision is Int)
                    val parsed=nullable("parsedJson")
                    if(parsed!=null) {
                        require(parsed.length<=16_384);boundedJson(parsed)
                        require(JSONObject(parsed).getString("parserVersion")=="native-synthetic/1")
                    }
                    BackupObservation(text("id"),text("sourceKey"),revision,text("fingerprint"),text("publishedAt"),text("capturedAt"),
                        text("state"),nullable("parserVersion"),parsed,text("source"),text("sourcePackage"))
                }
                return InboxBackup(root.getString("createdAt"),observations).also { it.validate() }
            } catch(error:BackupFailure) { throw error }
            catch (_:Exception) { throw BackupFailure(BackupProblem.INVALID_DATA) }
        }
    }
}
