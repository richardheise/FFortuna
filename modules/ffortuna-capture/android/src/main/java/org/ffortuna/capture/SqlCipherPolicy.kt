package org.ffortuna.capture

import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/** No native keying/schema query runs before the core log level is disabled. */
object SqlCipherPolicy {
    fun initialize() { Logger.setTarget(NoopTarget());System.loadLibrary("sqlcipher") }
    val failClosed=DatabaseErrorHandler { _,error -> throw error }
    val hook=object:SQLiteDatabaseHook {
        override fun preKey(connection:SQLiteConnection) {
            check(connection.executeForString("PRAGMA cipher_log_level=NONE",null,null)=="NONE")
            check(connection.executeForString("PRAGMA cipher_log_level",null,null)=="NONE")
        }
        override fun postKey(connection:SQLiteConnection) {
            connection.execute("PRAGMA temp_store=MEMORY",null,null)
            check(connection.executeForLong("PRAGMA temp_store",null,null)==2L)
        }
    }
}
