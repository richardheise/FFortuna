package org.ffortuna.capture

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject

internal class MutableSecurityClock(var time:Instant):Clock() {
    override fun getZone():ZoneId=ZoneOffset.UTC
    override fun withZone(zone:ZoneId):Clock=this
    override fun instant():Instant=time
    fun advance(days:Long) { time=time.plus(Duration.ofDays(days)) }
}

/** Uses production maintenance and recovery code, with time changes restricted to isolated fixtures. */
class RetentionSecurityScenarios(private val context:Context,private val recordProbe:(String)->Unit={}) {
    private fun expect(value:Boolean,code:String) { if(!value) throw SecurityCheckFailure(code) }
    private inline fun <reified T:Exception> fails(code:String,block:()->Unit) {
        try { block() } catch(error:Exception) { if(error is T) return;throw SecurityCheckFailure(code) }
        throw SecurityCheckFailure(code)
    }
    private fun payload(description:String="Exemplo de segurança")=JSONObject().put("marker","ffortuna.synthetic.v1")
        .put("amountCents",1990).put("kind","expense").put("effectiveDate","2026-10-25")
        .put("description",description).put("category","A classificar").put("accountRef","demo-main").put("externalId","fixture").toString()
    private fun clock()=MutableSecurityClock(Instant.parse("2026-10-01T00:00:00Z"))
    private fun row(boxes:CaptureBoxes,key:String,processed:Boolean=true) {
        boxes.current().observe(key,payload(),1792886400000)
        if(processed) boxes.current().processPending()
    }
    private fun rawCount(s:SecuritySandbox,name:String):Int {
        val file=s.context.getDatabasePath("$name.db");expect(file.exists(),"RAW_INSPECTION_MISSING")
        val key=KeyVault(s.context,name).load(true)
        return try { SQLiteDatabase.openDatabase(file.path,key,null,SQLiteDatabase.OPEN_READONLY,SqlCipherPolicy.failClosed,SqlCipherPolicy.hook).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM observations WHERE payload IS NOT NULL",null).use { it.moveToFirst();it.getInt(0) }
        } } finally { key.fill(0) }
    }
    private fun case(id:String,title:String,block:(SecuritySandbox)->Unit)=SecurityScenario(id,title) {
        SecuritySandbox(context).use { sandbox ->
            try { block(sandbox) } catch(error:SecurityCheckFailure) { throw error }
            catch(error:CaptureStorageFailure) { throw SecurityCheckFailure("STORAGE_${error.stage.name}") }
            catch (_:Exception) { throw SecurityCheckFailure("SCENARIO_UNEXPECTED") }
        }
    }
    fun cases():List<SecurityScenario> = listOf(
        case("policy-settings","Temporários em memória e logs SQLCipher desativados") { s ->
            s.newBoxes().current().let { db ->
                val settings=db.securityConfiguration()
                expect(settings["tempStore"]==2 && settings["secureDelete"]==1 && settings["foreignKeys"]==1 && settings["synchronous"]==2 && settings["coreLogLevel"]=="NONE","CIPHER_POLICY")
                expect(db.verifyEncrypted(),"CIPHER_INTEGRITY")
            }
        },
        case("retention-active","Retenção na caixa ativa sem fechar o banco") { s ->
            val time=clock();val boxes=s.newBoxes(time)
            row(boxes,"processed");row(boxes,"pending",false)
            val before=boxes.current().exportBackup().observations
            expect(rawCount(s,s.defaultName)==2,"RETENTION_RAW_INITIAL")
            time.advance(30)
            expect(boxes.current().processPending()==0,"EXPIRED_PENDING_PARSED")
            val snapshot=boxes.current().snapshot()
            expect(snapshot["total"]==2 && snapshot["pending"]==0 && snapshot["unavailablePending"]==1,"RETENTION_ACTIVE_STATE")
            expect(rawCount(s,s.defaultName)==0,"RETENTION_ACTIVE_RAW")
            expect(boxes.current().exportBackup().observations==before,"RETENTION_METADATA_CHANGED")
        },
        case("retention-previous","Limpeza da caixa anterior sem ativá-la") { s ->
            val time=clock();val boxes=s.newBoxes(time)
            row(boxes,"old-processed");row(boxes,"old-pending",false)
            val data=boxes.current().exportBackup();boxes.restore(data)
            row(boxes,"active-pending",false)
            val active=boxes.activeName()
            val first=boxes.maintainRetention()
            expect(!first.blocked && first.previous.remaining==2,"RETENTION_PREVIOUS_INITIAL")
            time.advance(30)
            val cleaned=boxes.maintainRetention(false)
            expect(!cleaned.blocked && cleaned.previous.remaining==0 && boxes.activeName()==active,"RETENTION_PREVIOUS_CHECK")
            expect(rawCount(s,s.defaultName)==0 && rawCount(s,active)==0,"RETENTION_PREVIOUS_RAW")
            expect(boxes.current().snapshot()["total"]==3,"RETENTION_PREVIOUS_RECORDS")
        },
        case("missing-active-db","Banco ausente não é recriado silenciosamente") { s ->
            val boxes=s.newBoxes();row(boxes,"source");boxes.close()
            val key=File(s.context.noBackupFilesDir,"${s.defaultName}.wrapped");val before=key.readBytes()
            val file=s.context.getDatabasePath("${s.defaultName}.db");expect(file.delete(),"MISSING_DB_DELETE")
            fails<CaptureStorageFailure>("MISSING_DB_ACCEPTED") { boxes.current() }
            expect(!file.exists() && key.readBytes().contentEquals(before),"MISSING_DB_CHANGED_KEY")
            expect(boxes.maintainRetention().blocked,"MISSING_DB_NOT_BLOCKED")
        },
        case("missing-previous-db","Caixa anterior ausente bloqueia sem apagar a atual") { s ->
            val boxes=s.newBoxes();row(boxes,"source");val data=boxes.current().exportBackup();boxes.restore(data)
            val active=boxes.activeName();val previous=s.context.getDatabasePath("${s.defaultName}.db")
            val wrapped=File(s.context.noBackupFilesDir,"${s.defaultName}.wrapped");val before=wrapped.readBytes()
            expect(previous.delete(),"PREVIOUS_DB_DELETE")
            fails<CaptureStorageFailure>("PREVIOUS_DB_ACCEPTED") { boxes.switchPrevious() }
            expect(!previous.exists() && wrapped.readBytes().contentEquals(before) && boxes.activeName()==active,"PREVIOUS_DB_RECREATED")
            expect(boxes.maintainRetention().previous.status=="blocked" && boxes.current().snapshot()["total"]==1,"PREVIOUS_DB_NOT_BLOCKED")
            boxes.discardPrevious();expect(!boxes.maintainRetention().blocked,"PREVIOUS_DISCARD_BLOCKED")
        },
        case("retention-missing-key","Chave antiga indisponível preserva arquivos e sinaliza bloqueio") { s ->
            val time=clock();val boxes=s.newBoxes(time);row(boxes,"source");boxes.restore(boxes.current().exportBackup())
            val original=s.context.getDatabasePath("${s.defaultName}.db").readBytes()
            val wrapped=File(s.context.noBackupFilesDir,"${s.defaultName}.wrapped");val keyBefore=wrapped.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("ffortuna.${s.defaultName}.wrap.v1") }
            time.advance(31)
            val result=boxes.maintainRetention()
            expect(result.blocked && result.previous.status=="blocked","RETENTION_MISSING_KEY_READY")
            expect(original.contentEquals(s.context.getDatabasePath("${s.defaultName}.db").readBytes()) && keyBefore.contentEquals(wrapped.readBytes()),"RETENTION_MISSING_KEY_CHANGED")
            expect(boxes.current().snapshot()["total"]==1,"RETENTION_ACTIVE_LOST")
            boxes.discardPrevious();expect(!boxes.maintainRetention().blocked,"RETENTION_MISSING_KEY_RESOLUTION")
        },
        case("corrupt-db","Banco corrompido falha sem substituição ou reset") { s ->
            val boxes=s.newBoxes();row(boxes,"source");boxes.close()
            val file=s.context.getDatabasePath("${s.defaultName}.db");val bytes=file.readBytes()
            for(i in 0 until minOf(128,bytes.size)) bytes[i]=(bytes[i].toInt() xor 0x5a).toByte()
            file.writeBytes(bytes)
            val wrapped=File(s.context.noBackupFilesDir,"${s.defaultName}.wrapped");val before=wrapped.readBytes()
            fails<CaptureStorageFailure>("CORRUPT_DB_ACCEPTED") { boxes.current() }
            expect(bytes.contentEquals(file.readBytes()) && before.contentEquals(wrapped.readBytes()),"CORRUPT_DB_REPLACED")
        },
        case("wrong-db-key","Chave incorreta não altera banco nem chave encapsulada") { s ->
            val boxes=s.newBoxes();row(boxes,"source");boxes.close()
            val file=s.context.getDatabasePath("${s.defaultName}.db");val before=file.readBytes()
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val wrapKey=keyStore.getKey("ffortuna.${s.defaultName}.wrap.v1",null) as SecretKey
            val wrong=ByteArray(32){0x5a.toByte()}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,wrapKey);cipher.updateAAD(s.defaultName.toByteArray())
            val encrypted=cipher.doFinal(wrong);wrong.fill(0)
            val encoded=ByteBuffer.allocate(2+cipher.iv.size+encrypted.size).put(1.toByte()).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
            val wrapped=File(s.context.noBackupFilesDir,"${s.defaultName}.wrapped");val atomic=AtomicFile(wrapped)
            val output=atomic.startWrite();output.write(encoded);atomic.finishWrite(output)
            fails<CaptureStorageFailure>("WRONG_DB_KEY_ACCEPTED") { boxes.current() }
            expect(before.contentEquals(file.readBytes()) && encoded.contentEquals(wrapped.readBytes()),"WRONG_DB_KEY_REPLACED")
        },
        case("retention-wal","Retenção remove bruto e trunca frames antigos do WAL") { s ->
            val name=s.newName();val file=s.context.getDatabasePath("$name.db");val vault=KeyVault(s.context,name)
            SqlCipherPolicy.initialize();val key=vault.load(false)
            try { SQLiteDatabase.openOrCreateDatabase(file,key,null,SqlCipherPolicy.failClosed,SqlCipherPolicy.hook).use { db ->
                db.rawQuery("PRAGMA secure_delete=ON",null).use { expect(it.moveToFirst() && it.getInt(0)==1,"WAL_SECURE_DELETE") }
                expect(db.enableWriteAheadLogging(),"WAL_ENABLE")
                db.execSQL("CREATE TABLE observations(id TEXT PRIMARY KEY,captured_at TEXT,payload TEXT,state TEXT,parsed_json TEXT)")
                val now=Instant.parse("2026-10-31T00:00:00Z")
                db.execSQL("INSERT INTO observations VALUES (?,?,?,'processed','preserved')",arrayOf("old",now.minus(Duration.ofDays(31)).toString(),"expired-fixture"))
                db.execSQL("INSERT INTO observations VALUES (?,?,?,'pending',NULL)",arrayOf("fresh",now.minus(Duration.ofDays(29)).toString(),"fresh-fixture"))
                val result=RawRetention.purge(db,now)
                expect(result.removed==1 && result.remaining==1,"WAL_RETENTION_COUNTS")
                db.rawQuery("SELECT payload,parsed_json FROM observations WHERE id='old'",null).use {
                    expect(it.moveToFirst() && it.isNull(0) && it.getString(1)=="preserved","WAL_RETENTION_DATA")
                }
                val wal=File(file.path+"-wal");expect(!wal.exists() || wal.length()==0L,"WAL_NOT_TRUNCATED")
            } } finally { key.fill(0) }
        },
        case("local-recovery-rotation","Cópias locais automáticas: intervalo, limite e restauração") { s ->
            val time=clock();val boxes=s.newBoxes(time);val recovery=LocalRecoveryStore(s.context,time,s.newName())
            row(boxes,"one");recovery.maybeSave(boxes.current().exportBackup());expect(recovery.entries().size==1,"LOCAL_FIRST")
            row(boxes,"two");recovery.maybeSave(boxes.current().exportBackup());expect(recovery.entries().size==1,"LOCAL_INTERVAL")
            time.advance(1);recovery.maybeSave(boxes.current().exportBackup());expect(recovery.entries().first().count==2,"LOCAL_NEXT_DAY")
            time.advance(1);recovery.maybeSave(boxes.current().exportBackup())
            expect(recovery.entries().size==2,"LOCAL_UNCHANGED_COPY")
            time.time=time.time.minus(Duration.ofDays(4));row(boxes,"rollback")
            recovery.maybeSave(boxes.current().exportBackup());expect(recovery.entries().size==2,"LOCAL_CLOCK_ROLLBACK")
            time.advance(4)
            for(n in 3..4) { time.advance(1);row(boxes,"row-$n");recovery.maybeSave(boxes.current().exportBackup()) }
            val entries=recovery.entries();expect(entries.map { it.count }==listOf(5,4,2),"LOCAL_RING")
            val restored=recovery.load(entries.first().id)
            expect(restored.observations==boxes.current().exportBackup().observations,"LOCAL_DATA")
            boxes.restore(restored);expect(boxes.current().snapshot()["total"]==5 && rawCount(s,boxes.activeName())==0,"LOCAL_RESTORE")
        },
        case("local-recovery-key-loss","Chave da recuperação ausente preserva cópias e caixa ativa") { s ->
            val boxes=s.newBoxes();row(boxes,"one");val keyName=s.newName();val recovery=LocalRecoveryStore(s.context,keyName=keyName)
            recovery.maybeSave(boxes.current().exportBackup())
            val directory=File(s.context.noBackupFilesDir,"local-recovery");val before=directory.listFiles()!!.associate { it.name to it.readBytes() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("ffortuna.$keyName.wrap.v1") }
            expect(recovery.status()["status"]=="blocked","LOCAL_MISSING_KEY_READY")
            fails<LocalRecoveryFailure>("LOCAL_MISSING_KEY_OVERWRITE") { recovery.maybeSave(boxes.current().exportBackup()) }
            expect(before.all { (name,bytes) -> bytes.contentEquals(File(directory,name).readBytes()) },"LOCAL_MISSING_KEY_FILES")
            expect(boxes.current().snapshot()["total"]==1,"LOCAL_MISSING_KEY_ACTIVE")
        },
        case("local-recovery-tamper","Índice e cópia adulterados não sobrescrevem dados") { s ->
            val boxes=s.newBoxes();row(boxes,"one");val recovery=LocalRecoveryStore(s.context,keyName=s.newName())
            recovery.maybeSave(boxes.current().exportBackup());val entry=recovery.entries().first()
            val directory=File(s.context.noBackupFilesDir,"local-recovery");val index=File(directory,"index.bin");val original=index.readBytes()
            val altered=original.clone();altered[altered.lastIndex]=(altered.last().toInt() xor 1).toByte();index.writeBytes(altered)
            expect(recovery.status()["status"]=="blocked","LOCAL_BAD_INDEX_READY")
            fails<LocalRecoveryFailure>("LOCAL_BAD_INDEX_OVERWRITE") { recovery.maybeSave(boxes.current().exportBackup()) }
            expect(index.readBytes().contentEquals(altered),"LOCAL_BAD_INDEX_REPLACED")
            index.writeBytes(original)
            val snapshot=File(directory,"snapshot-${entry.id}.bin");val stamp=snapshot.lastModified()
            val bytes=snapshot.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();snapshot.writeBytes(bytes)
            expect(snapshot.setLastModified(stamp),"LOCAL_TAMPER_STAMP")
            expect(recovery.status()["status"]=="blocked","LOCAL_BAD_SNAPSHOT_READY")
            fails<LocalRecoveryFailure>("LOCAL_BAD_SNAPSHOT_ACCEPTED") { recovery.load(entry.id) }
            expect(boxes.current().snapshot()["total"]==1,"LOCAL_TAMPER_ACTIVE")
        },
        case("local-recovery-staging","Cópia incompleta e índice interrompido são recuperados") { s ->
            val boxes=s.newBoxes();row(boxes,"one");val name=s.newName();val recovery=LocalRecoveryStore(s.context,keyName=name)
            recovery.maybeSave(boxes.current().exportBackup())
            val directory=File(s.context.noBackupFilesDir,"local-recovery")
            val wrappedContext=object:ContextWrapper(s.context) { override fun getNoBackupFilesDir()=directory }
            val key=KeyVault(wrappedContext,name).load(true)
            try {
                val id=UUID.randomUUID().toString();val orphan=File(directory,"snapshot-$id.bin.new")
                orphan.writeBytes(LocalRecoveryCodec.encryptSnapshot(key,id,"uncommitted fixture".toByteArray()))
                val index=File(directory,"index.bin");expect(index.renameTo(File(index.path+".bak")),"LOCAL_INDEX_RENAME")
                LocalRecoveryStore(s.context,keyName=name).let {
                    expect(it.entries().size==1 && !orphan.exists() && index.exists(),"LOCAL_STAGING_RECOVERY")
                }
            } finally { key.fill(0) }
        },
        case("local-recovery-disable","Desativar e apagar cópias locais preserva a caixa ativa") { s ->
            val boxes=s.newBoxes();row(boxes,"one");val keyName=s.newName();val recovery=LocalRecoveryStore(s.context,keyName=keyName)
            recovery.maybeSave(boxes.current().exportBackup());recovery.setEnabled(false)
            expect(!recovery.needsSnapshot() && recovery.entries().size==1,"LOCAL_DISABLE")
            recovery.clearAndDisable()
            expect(recovery.status()["status"]=="disabled" && recovery.status()["count"]==0,"LOCAL_CLEAR")
            expect(recovery.entries().isEmpty() && !File(s.context.noBackupFilesDir,"local-recovery").exists(),"LOCAL_CLEAR_RECREATED_FILES")
            expect(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("ffortuna.$keyName.wrap.v1"),"LOCAL_CLEAR_RECREATED_KEY")
            expect(boxes.current().snapshot()["total"]==1,"LOCAL_CLEAR_ACTIVE")
            recovery.setEnabled(true);recovery.maybeSave(boxes.current().exportBackup());expect(recovery.entries().size==1,"LOCAL_REENABLE")
        },
        case("log-sensitive-probe","Sonda de conteúdo sintético para a janela de logs") { s ->
            val marker="FFORTUNA-PRIVATE-PROBE-${UUID.randomUUID()}";recordProbe(marker)
            val boxes=s.newBoxes();boxes.current().observe("log-probe",payload(marker),1792886400000);boxes.current().processPending()
            val key=KeyVault(s.context,s.defaultName).load(true)
            try {
                recordProbe(key.joinToString("") { "%02x".format(it) })
                recordProbe(android.util.Base64.encodeToString(key,android.util.Base64.NO_WRAP))
            } finally { key.fill(0) }
            expect(boxes.current().verifyEncrypted(),"LOG_PROBE_CIPHER")
        }
    )
}
