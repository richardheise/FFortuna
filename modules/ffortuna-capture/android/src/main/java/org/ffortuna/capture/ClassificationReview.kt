package org.ffortuna.capture

import org.json.JSONArray
import org.json.JSONObject

/** Stable catalog IDs; labels in old reviews remain available as legacy text. No cross-database write. */
object ClassificationReview {
    fun validate(root:JSONObject) {
        val id=if(root.isNull("categoryId")) null else root.get("categoryId") as? String ?: throw DiagnosticFailure("INVALID_CLASSIFICATION")
        if(id!=null && !id.matches(Regex("[a-f0-9-]{36}"))) throw DiagnosticFailure("INVALID_CLASSIFICATION")
        val tags=root.opt("tagIds")
        if(tags!=null && tags!==JSONObject.NULL && tags !is JSONArray) throw DiagnosticFailure("INVALID_CLASSIFICATION")
        if(tags is JSONArray) {
            val ids=(0 until tags.length()).map { tags.get(it) as? String ?: throw DiagnosticFailure("INVALID_CLASSIFICATION") }
            if(ids.size>20 || ids.distinct().size!=ids.size || ids.any { !it.matches(Regex("[a-f0-9-]{36}")) }) throw DiagnosticFailure("INVALID_CLASSIFICATION")
        } else if(root.has("tagIds")) throw DiagnosticFailure("INVALID_CLASSIFICATION")
    }
    fun prepare(json:String,items:List<Map<String,Any?>>,previous:Map<String,Any?>?):String {
        if(json.length>8192) throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val root=try { JSONObject(json) } catch (_:Exception) { throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW") }
        validate(root)
        val category=root.optString("categoryId").takeIf { !root.isNull("categoryId") && it.isNotBlank() }
        val tags=root.optJSONArray("tagIds") ?: JSONArray()
        val previousTags=(previous?.get("tagIds") as? List<*>) ?: emptyList<Any>()
        fun selected(id:String,kind:String,wasSelected:Boolean):Map<String,Any?> {
            val item=items.firstOrNull { it["id"]==id && it["kind"]==kind } ?: throw DiagnosticFailure("CLASSIFICATION_MISSING")
            if(item["archived"]==true && !wasSelected) throw DiagnosticFailure("CLASSIFICATION_ARCHIVED")
            return item
        }
        if(category!=null) root.put("category",selected(category,"category",previous?.get("categoryId")==category)["name"])
        for(i in 0 until tags.length()) selected(tags.getString(i),"tag",previousTags.contains(tags.getString(i)))
        return root.toString()
    }
    @Suppress("UNCHECKED_CAST")
    fun detail(candidate:Map<String,Any?>,items:List<Map<String,Any?>>):Map<String,Any?> {
        val review=candidate["review"] as? Map<String,Any?> ?: return candidate
        val item=items.firstOrNull { it["id"]==review["categoryId"] && it["kind"]=="category" }
        return candidate+mapOf("review" to (review+mapOf("category" to (item?.get("name") ?: review["category"]))))
    }
}
