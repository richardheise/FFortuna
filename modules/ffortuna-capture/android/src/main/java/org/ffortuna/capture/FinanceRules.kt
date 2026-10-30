package org.ffortuna.capture

import java.math.BigInteger
import java.time.LocalDate

class FinanceFailure(val code:String):IllegalStateException(code)

/** BRL integers only; validates aggregates before exposing them to JavaScript. */
object FinanceRules {
    const val MAX=9007199254740991L
    fun cents(value:Any?,signed:Boolean=false):Long {
        val number=when(value) { is Int -> value.toLong();is Long -> value;else -> throw FinanceFailure("INVALID_AMOUNT") }
        if(number !in (if(signed) -MAX else 1)..MAX) throw FinanceFailure("INVALID_AMOUNT")
        return number
    }
    fun sum(values:Iterable<Long>):Long {
        val total=values.fold(BigInteger.ZERO) { a,b -> a+BigInteger.valueOf(b) }
        if(total.abs()>BigInteger.valueOf(MAX)) throw FinanceFailure("AMOUNT_LIMIT")
        return total.toLong()
    }
    fun date(value:String):String {
        try { if(!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || value.startsWith("0000") || LocalDate.parse(value).toString()!=value) throw IllegalArgumentException() }
        catch (_:Exception) { throw FinanceFailure("INVALID_DATE") }
        return value
    }
    fun month(value:String):String {
        if(!value.matches(Regex("[0-9]{4}-[0-9]{2}"))) throw FinanceFailure("INVALID_DATE")
        date("$value-01");return value
    }
    fun text(value:String,max:Int):String {
        if(value.length>max || value.any { Character.isISOControl(it) && it!='\n' && it!='\t' }) throw FinanceFailure("INVALID_FIELDS")
        return value
    }
}
