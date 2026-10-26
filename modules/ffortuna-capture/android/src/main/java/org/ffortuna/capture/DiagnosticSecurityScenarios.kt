package org.ffortuna.capture

import android.app.Notification
import android.content.Context
import android.os.Bundle
import java.io.File
import java.security.KeyStore
import java.time.Instant

/** Entirely invented payloads; these fixtures are not Nubank samples. */
class DiagnosticSecurityScenarios(private val context:Context,private val recordProbe:(String)->Unit={}) {
    private val signer="a".repeat(64)
    private val now=DiagnosticMoment(Instant.parse("2026-10-26T12:00:00Z"),1000,4)
    private fun fields()=DiagnosticFields(title="Fixture inventada",text="Texto sintético R$ 99,90",bigText="não é uma notificação bancária real")
    private fun expect(value:Boolean,code:String) { if(!value) throw SecurityCheckFailure(code) }
    private fun fails(code:String,block:()->Unit) { try { block() } catch (_:Exception) { return };throw SecurityCheckFailure(code) }
    private fun case(id:String,title:String,block:(SecuritySandbox)->Unit)=SecurityScenario(id,title) {
        SecuritySandbox(context).use { try { block(it) } catch(error:SecurityCheckFailure) { throw error }
            catch(error:DiagnosticFailure) { throw SecurityCheckFailure(error.code) }
            catch (_:Exception) { throw SecurityCheckFailure("DIAGNOSTIC_SCENARIO") } }
    }
    private fun observe(db:DiagnosticStore,key:String="key",data:DiagnosticFields=fields(),moment:DiagnosticMoment=now,summary:Boolean=false)=
        db.observe(DiagnosticPolicy.PACKAGE,signer,key,moment.wall.toEpochMilli(),"fixture-group",summary,moment,moment) { data to false }
    fun cases():List<SecurityScenario> = listOf(
        case("diagnostic-consent","Origem, consentimento e assinatura antes de ler conteúdo") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                var reads=0
                fun attempt(pkg:String=DiagnosticPolicy.PACKAGE,cert:String=signer,published:Long=now.wall.toEpochMilli())=
                    db.observe(pkg,cert,"fixture",published,null,false,now,now) { reads++;fields() to false }
                expect(!attempt() && reads==0,"DIAG_WITHOUT_CONSENT")
                db.start(signer,now)
                expect(!attempt("org.example.other") && reads==0,"DIAG_WRONG_ORIGIN")
                expect(!attempt(published=now.wall.minusSeconds(1).toEpochMilli()) && reads==0,"DIAG_OLD_PUBLICATION")
                fails("DIAG_SIGNER_ACCEPTED") { attempt(cert="b".repeat(64)) }
                expect(reads==0 && db.state(now)["active"]==false,"DIAG_SIGNER_CONTENT")
                db.start(signer,now);expect(attempt() && reads==1,"DIAG_ALLOWED")
                db.stop();expect(!attempt() && reads==1,"DIAG_STOPPED")
            }
        },
        case("diagnostic-identities","Redelivery, atualização e duas notificações iguais") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now)
                expect(observe(db),"DIAG_FIRST");expect(!observe(db),"DIAG_DUPLICATE")
                expect(observe(db,data=fields().copy(text="Fixture atualizada")),"DIAG_UPDATE")
                expect(observe(db,"second-key"),"DIAG_EQUAL_SECOND")
                val rows=db.samples(now)
                expect(rows.size==3 && rows.count { it["revision"]==2 }==1,"DIAG_IDENTITIES")
                db.verify()
            }
        },
        case("diagnostic-groups","Resumo de grupo guarda só metadados") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now)
                expect(db.observe(DiagnosticPolicy.PACKAGE,signer,"summary",now.wall.toEpochMilli(),"g",true,now,now) {
                    throw SecurityCheckFailure("DIAG_SUMMARY_CONTENT_READ")
                },"DIAG_SUMMARY_MISSING")
                observe(db,"child")
                val row=db.samples(now).first { it["isSummary"]==true }
                expect(row["hasText"]==false && db.samples(now).size==2,"DIAG_SUMMARY_DATA")
            }
        },
        case("diagnostic-window","Expiração e reinício exigem novo consentimento") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now)
                val expired=now.copy(wall=now.wall.plus(DiagnosticPolicy.SESSION),elapsed=now.elapsed+DiagnosticPolicy.SESSION.toMillis())
                expect(!observe(db,moment=expired) && db.state(expired)["active"]==false,"DIAG_EXPIRY")
                db.start(signer,now);expect(db.state(now.copy(boot=5))["active"]==false,"DIAG_REBOOT")
            }
        },
        case("diagnostic-retention","Retenção apaga originais e revisões aos sete dias") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now);observe(db)
                val id=db.samples(now).first()["id"] as String
                db.review(id,DiagnosticFields(title="Fixture revisada").encode(),true,now)
                val expired=now.copy(wall=now.wall.plus(DiagnosticPolicy.RETENTION),elapsed=now.elapsed+DiagnosticPolicy.RETENTION.toMillis())
                expect(db.samples(expired).isEmpty() && db.state(expired)["reviewedCount"]==0,"DIAG_RETENTION")
                fails("DIAG_EXPIRED_EXPORTED") { db.exportReviewed(expired) }
                db.verify()
            }
        },
        case("diagnostic-export","Exportação exige revisão e exclui originais e identidades") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now);observe(db,"private-fixture-key",fields().copy(title="NOME-PRIVADO-INVENTADO"))
                val id=db.samples(now).first()["id"] as String
                fails("DIAG_UNREVIEWED_EXPORT") { db.exportReviewed(now) }
                val reviewed=DiagnosticFields(title="Exemplo revisado",text="Valor sintético R$ 12,34")
                fails("DIAG_REVIEW_CONSENT") { db.review(id,reviewed.encode(),false,now) }
                fails("DIAG_REVIEW_CPF") { db.review(id,reviewed.copy(text="000.111.222-33").encode(),true,now) }
                db.review(id,reviewed.encode(),true,now)
                val bytes=db.exportReviewed(now);val text=bytes.toString(Charsets.UTF_8)
                for(secret in listOf(id,"private-fixture-key","NOME-PRIVADO-INVENTADO",now.wall.toString(),signer,"R$ 99,90"))
                    expect(!text.contains(secret),"DIAG_EXPORT_PRIVATE_DATA")
                expect(DiagnosticBundle.validate(bytes)==1,"DIAG_EXPORT_VALIDATION")
                fails("DIAG_FOREIGN_EXPORT") { DiagnosticBundle.validate(InboxBackup(now.wall.toString(),emptyList()).encode()) }
                expect(text.contains("ffortuna-reviewed-notification-samples") && text.contains("Exemplo revisado"),"DIAG_EXPORT_FORMAT")
                val password="senha sintética de diagnóstico".toCharArray()
                try {
                    val encrypted=BackupCodec.encrypt(bytes,password)
                    expect(!encrypted.toString(Charsets.ISO_8859_1).contains("Exemplo revisado"),"DIAG_EXPORT_PLAINTEXT")
                    val plain=BackupCodec.decrypt(encrypted,password)
                    try { expect(bytes.contentEquals(plain),"DIAG_EXPORT_ROUNDTRIP") } finally { plain.fill(0) }
                } finally { password.fill('\u0000');bytes.fill(0) }
                db.excludeReview(id);expect(db.state(now)["reviewedCount"]==0,"DIAG_EXCLUDE_REVIEW")
            }
        },
        case("diagnostic-quota","Limite de coleta sem sobrescrever evidências") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now)
                repeat(DiagnosticPolicy.MAX_ROWS) { expect(observe(db,"fixture-$it"),"DIAG_CAPACITY_EARLY") }
                expect(!observe(db,"overflow") && db.state(now)["count"]==200 && db.state(now)["active"]==false,"DIAG_CAPACITY_OVERFLOW")
            }
        },
        case("diagnostic-key","Reabertura e perda da chave preservam arquivos") { s ->
            val name=s.newName()
            val marker="FFORTUNA-DIAGNOSTIC-PROBE-${java.util.UUID.randomUUID()}";recordProbe(marker)
            DiagnosticStore(s.context,name,true).use { it.start(signer,now);observe(it,data=fields().copy(title=marker)) }
            val probeKey=KeyVault(s.context,name).load(true)
            try { recordProbe(probeKey.joinToString("") { "%02x".format(it) });recordProbe(android.util.Base64.encodeToString(probeKey,android.util.Base64.NO_WRAP)) }
            finally { probeKey.fill(0) }
            DiagnosticStore(s.context,name).use { expect(it.samples(now).size==1,"DIAG_REOPEN");it.verify() }
            val file=s.context.getDatabasePath("$name.db");val before=file.readBytes()
            expect(!before.toString(Charsets.ISO_8859_1).contains(marker),"DIAG_PLAINTEXT_FILE")
            val wrapped=File(s.context.noBackupFilesDir,"$name.wrapped");val key=wrapped.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("ffortuna.$name.wrap.v1") }
            fails("DIAG_LOST_KEY_ACCEPTED") { DiagnosticStore(s.context,name).close() }
            expect(before.contentEquals(file.readBytes()) && key.contentEquals(wrapped.readBytes()),"DIAG_LOST_KEY_REPLACED")
        },
        case("diagnostic-extraction","Somente campos permitidos e texto limitado") { _ ->
            val notification=Notification().apply { extras=Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE,"a".repeat(5000));putCharSequence(Notification.EXTRA_TEXT,"b".repeat(5000))
                putCharSequenceArray(Notification.EXTRA_TEXT_LINES,Array(40) { "c".repeat(1000) })
                putString("private-other-field","NEVER_SERIALIZE_THIS")
            } }
            val (data,truncated)=DiagnosticCapture.extract(notification)
            data.validate();expect(truncated && data.title.length==4096 && data.textLines.size<=32,"DIAG_EXTRACTION_LIMIT")
            expect(!data.encode().contains("NEVER_SERIALIZE_THIS"),"DIAG_EXTRACTION_FIELDS")
            expect(!DiagnosticCapture.extract(Notification().apply { extras=Bundle() }).first.hasText(),"DIAG_EMPTY_TEXT")
        },
        case("diagnostic-isolation","Apagar diagnóstico preserva caixa e backups sintéticos") { s ->
            val boxes=s.newBoxes();boxes.current().observe("fixture","""{"marker":"ffortuna.synthetic.v1","amountCents":100,"kind":"expense","effectiveDate":"2026-10-26","description":"Fixture","category":"A classificar","accountRef":"demo-main","externalId":"fixture"}""",now.wall.toEpochMilli())
            val name=s.newName();val capture=DiagnosticCapture(s.context,name)
            val source=mapOf("installed" to true,"enabled" to true,"signer" to signer)
            expect(capture.status(now,source)["status"]=="absent","DIAG_DEFAULT_OFF")
            capture.start(signer,source,now);capture.stop();capture.clear()
            expect(capture.status(now,source)["status"]=="absent" && !s.context.getDatabasePath("$name.db").exists(),"DIAG_CLEAR_RECREATED")
            expect(boxes.current().exportBackup().observations.size==1,"DIAG_CLEAR_SYNTHETIC")
        }
    )
}
