package org.ffortuna.capture

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Invented grammar fixtures, never claimed to be Nubank templates or anonymized real samples. */
class CandidateSecurityScenarios(private val context:Context,private val recordProbe:(String)->Unit={}) {
    private val signer="a".repeat(64)
    private val now=DiagnosticMoment(Instant.parse("2026-10-30T12:00:00Z"),1000,4)
    private fun fields(text:String="Fixture inventada: R$ 99,90")=DiagnosticFields(title="Fixture de parser",text=text)
    private fun expect(ok:Boolean,code:String) { if(!ok) throw SecurityCheckFailure(code) }
    private fun fails(code:String,block:()->Unit) { try { block() } catch (_:Exception) { return };throw SecurityCheckFailure(code) }
    private fun case(id:String,title:String,block:(SecuritySandbox)->Unit)=SecurityScenario(id,title) {
        SecuritySandbox(context).use { try { block(it) } catch(e:SecurityCheckFailure) { throw e }
            catch(e:DiagnosticFailure) { throw SecurityCheckFailure(e.code) }
            catch (_:Exception) { throw SecurityCheckFailure("CANDIDATE_SCENARIO") } }
    }
    private fun observe(db:DiagnosticStore,key:String="fixture",data:DiagnosticFields=fields(),summary:Boolean=false,truncated:Boolean=false):String {
        db.observe(DiagnosticPolicy.PACKAGE,signer,key,now.wall.toEpochMilli(),"fixture-group",summary,now,now) { data to truncated }
        return db.samples(now).first()["id"] as String
    }
    private fun edit(amount:Long=12345,kind:String="transfer",date:String?="2026-10-29")=JSONObject().put("amountCents",amount)
        .put("kind",kind).put("effectiveDate",date ?: JSONObject.NULL).put("description","Correção inventada")
        .put("category","Teste manual").put("status","reviewed").toString()
    @Suppress("UNCHECKED_CAST") private fun map(value:Any?)=value as Map<String,Any?>
    private fun amount(candidate:Map<String,Any?>)=map(map(candidate["parsed"])["draft"])["amountCents"] as? Number
    private fun sql(s:SecuritySandbox,name:String,block:(SQLiteDatabase)->Unit) {
        val key=KeyVault(s.context,name).load(true)
        try { SQLiteDatabase.openDatabase(s.context.getDatabasePath("$name.db").path,key,null,SQLiteDatabase.OPEN_READWRITE,SqlCipherPolicy.failClosed,SqlCipherPolicy.hook).use(block) }
        finally { key.fill(0) }
    }
    fun cases():List<SecurityScenario> = listOf(
        case("candidate-original","Parser usa valor original, sem consumir a máscara de exportação") { s ->
            val marker="FFORTUNA-CANDIDATE-PROBE-${java.util.UUID.randomUUID()}";recordProbe(marker)
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now);val id=observe(db,data=fields("$marker: R$ 99,90"))
                db.review(id,fields("Fixture exportada: R$ 12,34").encode(),true,now)
                val candidate=db.candidate(id,now)
                expect(amount(candidate)?.toLong()==9990L,"CANDIDATE_MASKED_AMOUNT")
                expect(map(candidate["original"])["text"].toString().contains("99,90"),"CANDIDATE_ORIGINAL_CHANGED")
                expect(db.exportReviewed(now).toString(Charsets.UTF_8).contains("R$ 12,34"),"CANDIDATE_EXPORT_CHANGED")
                val parsed=map(candidate["parsed"])
                expect(parsed["automaticPostingAllowed"]==false && candidate["transactionId"]==null,"CANDIDATE_AUTOMATION")
                val confidence=map(parsed["confidence"])
                expect(confidence["nature"]=="missing" && confidence["account"]=="missing" && confidence["date"]=="missing","CANDIDATE_GUESSED_FIELDS")
            }
        },
        case("candidate-reprocessing","Retomada e reprocessamento sem duplicar candidatos") { s ->
            val name=s.newName();var first=""
            DiagnosticStore(s.context,name,true).use { db -> db.start(signer,now);first=observe(db) }
            DiagnosticStore(s.context,name).use { db ->
                expect(db.processCandidates(now)==1,"CANDIDATE_RECOVERY")
                repeat(3) { expect(db.candidate(first,now,true)["id"]==first,"CANDIDATE_CHANGED_ID") }
                expect(db.processCandidates(now)==0,"CANDIDATE_REPEATED_PENDING")
                db.observe(DiagnosticPolicy.PACKAGE,signer,"fixture",now.wall.toEpochMilli(),"fixture-group",false,now,now) { fields() to false }
                expect(db.samples(now).size==1,"CANDIDATE_REDELIVERY_DUPLICATE")
                val second=observe(db,"other-key");db.processCandidates(now)
                expect(first!=second && db.samples(now).size==2 && db.candidate(second,now)["previousSampleId"]==null,"CANDIDATE_EQUAL_PURCHASES")
            }
            sql(s,name) { db -> db.rawQuery("SELECT COUNT(*) FROM candidate_parses",null).use { it.moveToFirst();expect(it.getInt(0)==2,"CANDIDATE_PARSE_DUPLICATE") } }
        },
        case("candidate-corrections","Correções persistem após reprocessar e reabrir") { s ->
            val name=s.newName();var id=""
            DiagnosticStore(s.context,name,true).use { db ->
                db.start(signer,now);id=observe(db)
                db.saveCandidate(id,edit(),now);val candidate=db.candidate(id,now,true)
                expect((map(candidate["review"])["amountCents"] as Number).toLong()==12345L && amount(candidate)?.toLong()==9990L,"CANDIDATE_CORRECTION_LOST")
                expect(map(candidate["review"])["kind"]=="transfer","CANDIDATE_TRANSFER_CHANGED")
            }
            DiagnosticStore(s.context,name).use { db ->
                val candidate=db.candidate(id,now,true)
                expect(map(candidate["review"])["description"]=="Correção inventada" && map(candidate["review"])["effectiveDate"]=="2026-10-29","CANDIDATE_CORRECTION_REOPEN")
            }
        },
        case("candidate-ambiguity","Ambiguidades, resumo e truncamento não viram valor seguro") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now)
                val id=observe(db,data=fields("Fixture: R$ 10,00 e R$ 20,00"))
                expect(amount(db.candidate(id,now))==null,"CANDIDATE_AMBIGUOUS")
                val clipped=observe(db,"clipped",truncated=true)
                expect(amount(db.candidate(clipped,now))==null,"CANDIDATE_TRUNCATED")
                val summary=observe(db,"summary",summary=true)
                db.processCandidates(now)
                fails("CANDIDATE_GROUP_ACCEPTED") { db.candidate(summary,now) }
                val noAmount=observe(db,"no-amount",fields("Fixture sem moeda"))
                expect(amount(db.candidate(noAmount,now))==null,"CANDIDATE_NO_AMOUNT")
            }
        },
        case("candidate-validation","Revisão rejeita valores inseguros e natureza indevida") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now);val id=observe(db);db.saveCandidate(id,edit(),now)
                val invalids=listOf(edit(0),edit(-1),edit(BrlNotificationParser.MAX_CENTS+1),edit(kind="pix"),edit(date="2026-02-30"),
                    JSONObject(edit()).put("amountCents",12.34).toString(),JSONObject(edit()).put("unexpected",true).toString())
                for(json in invalids) fails("CANDIDATE_INVALID_ACCEPTED") { db.saveCandidate(id,json,now) }
                expect((map(db.candidate(id,now)["review"])["amountCents"] as Number).toLong()==12345L,"CANDIDATE_INVALID_CHANGED_REVIEW")
            }
        },
        case("candidate-updates","Atualização preserva revisão anterior e explicita vínculo") { s ->
            DiagnosticStore(s.context,s.newName(),true).use { db ->
                db.start(signer,now);val first=observe(db);db.saveCandidate(first,edit(),now)
                val second=observe(db,data=fields("Fixture atualizada: R$ 88,70"));val updated=db.candidate(second,now)
                expect(second!=first && updated["previousSampleId"]==first && updated["relatedReviewExists"]==true,"CANDIDATE_UPDATE_RELATION")
                expect(updated["review"]==null && db.candidate(first,now)["hasLaterRevision"]==true,"CANDIDATE_UPDATE_OVERWROTE")
                expect((map(db.candidate(first,now)["review"])["amountCents"] as Number).toLong()==12345L,"CANDIDATE_OLD_REVIEW_LOST")
            }
        },
        case("candidate-retention","Retenção temporária limpa parsing e revisão juntos") { s ->
            val name=s.newName()
            DiagnosticStore(s.context,name,true).use { db ->
                db.start(signer,now);val id=observe(db);db.saveCandidate(id,edit(),now)
                val expired=now.copy(wall=now.wall.plus(DiagnosticPolicy.RETENTION),elapsed=now.elapsed+DiagnosticPolicy.RETENTION.toMillis())
                expect(db.samples(expired).isEmpty(),"CANDIDATE_RETENTION_SAMPLES")
                fails("CANDIDATE_RETAINED_AFTER_EXPIRY") { db.candidate(id,expired) }
            }
            sql(s,name) { db ->
                for(table in listOf("candidate_parses","candidate_edits")) db.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst();expect(it.getInt(0)==0,"CANDIDATE_RETENTION_CASCADE") }
            }
        },
        case("candidate-migration","Migração 1 para 3 preserva dados e pausa consentimento antigo") { s ->
            val name=s.newName();var id=""
            DiagnosticStore(s.context,name,true).use { db ->
                db.start(signer,now);id=observe(db);db.review(id,fields("Fixture exportada R$ 12,34").encode(),true,now)
            }
            val wrapped=File(s.context.noBackupFilesDir,"$name.wrapped");val before=wrapped.readBytes()
            sql(s,name) { db -> db.execSQL("DROP TABLE candidate_edits");db.execSQL("DROP TABLE candidate_parses");db.execSQL("DROP TABLE statement_batches")
                db.execSQL("ALTER TABLE session DROP COLUMN mode");db.execSQL("ALTER TABLE samples DROP COLUMN mode");db.execSQL("ALTER TABLE samples DROP COLUMN origin_json");db.version=1 }
            DiagnosticStore(s.context,name).use { db ->
                expect(db.state(now)["active"]==false && db.samples(now).single()["id"]==id,"CANDIDATE_MIGRATION_SOURCE")
                expect(db.state(now)["reviewedCount"]==1 && amount(db.candidate(id,now))?.toLong()==9990L,"CANDIDATE_MIGRATION_REVIEW")
                expect(before.contentEquals(wrapped.readBytes()),"CANDIDATE_MIGRATION_KEY")
            }
            sql(s,name) { expect(it.version==3,"CANDIDATE_MIGRATION_VERSION") }
        }
    )
}
