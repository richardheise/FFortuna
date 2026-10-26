package org.ffortuna.capture

import org.json.JSONArray
import org.json.JSONObject

/** Only explicitly selected textual fields. Never serialize a Notification/Bundle/RemoteViews. */
data class DiagnosticFields(val title:String="",val text:String="",val bigText:String="",val subText:String="",val textLines:List<String> = emptyList()) {
    fun toMap():Map<String,Any> = mapOf("title" to title,"text" to text,"bigText" to bigText,"subText" to subText,"textLines" to textLines)
    fun encode():String=JSONObject().put("title",title).put("text",text).put("bigText",bigText).put("subText",subText).put("textLines",JSONArray(textLines)).toString()
    fun hasText()=listOf(title,text,bigText,subText).any { it.isNotBlank() } || textLines.any { it.isNotBlank() }
    fun validate(reviewed:Boolean=false) {
        val fields=listOf(title,text,bigText,subText)+textLines
        if(textLines.size>32 || fields.any { it.length>DiagnosticPolicy.MAX_FIELD } || fields.sumOf { it.length }>DiagnosticPolicy.MAX_CHARS)
            throw DiagnosticFailure("TEXT_LIMIT")
        if(reviewed && fields.any { !DiagnosticRedactor.validateReviewed(it) }) throw DiagnosticFailure("REVIEW_SENSITIVE")
    }
    fun suggested():DiagnosticFields {
        fun suggest(value:String):String {
            val cleaned=value.replace("\r\n","\n").map { if(Character.isISOControl(it) && it!='\n' && it!='\t') ' ' else it }.joinToString("")
            return DiagnosticRedactor.suggest(cleaned).take(DiagnosticPolicy.MAX_FIELD)
        }
        return copy(title=suggest(title),text=suggest(text),bigText=suggest(bigText),subText=suggest(subText),textLines=textLines.map(::suggest))
    }
    companion object {
        fun decode(json:String):DiagnosticFields {
            if(json.length>DiagnosticPolicy.MAX_CHARS*6+1024) throw DiagnosticFailure("TEXT_LIMIT")
            try {
                val obj=JSONObject(json)
                if(obj.keys().asSequence().toSet()!=setOf("title","text","bigText","subText","textLines")) throw DiagnosticFailure("INVALID_REVIEW")
                fun string(key:String):String=(obj.get(key) as? String) ?: throw DiagnosticFailure("INVALID_REVIEW")
                val array=obj.get("textLines") as? JSONArray ?: throw DiagnosticFailure("INVALID_REVIEW")
                if(array.length()>32) throw DiagnosticFailure("TEXT_LIMIT")
                return DiagnosticFields(string("title"),string("text"),string("bigText"),string("subText"),
                    (0 until array.length()).map { array.get(it) as? String ?: throw DiagnosticFailure("INVALID_REVIEW") }).also { it.validate() }
            } catch(error:DiagnosticFailure) { throw error }
            catch (_:Exception) { throw DiagnosticFailure("INVALID_REVIEW") }
        }
    }
}
