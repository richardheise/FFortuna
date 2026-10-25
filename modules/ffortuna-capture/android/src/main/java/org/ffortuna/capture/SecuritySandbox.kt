package org.ffortuna.capture

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.util.UUID

/** Isolated storage and key namespace for executable synthetic security checks. */
class SecuritySandbox private constructor(private val base:Context,private val prefix:String) : AutoCloseable {
    constructor(base:Context):this(base,"security-${UUID.randomUUID()}")
    val defaultName: String = prefix
    val preferencesName: String = "boxes"
    private val root = File(base.noBackupFilesDir, "security-checks/$prefix")
    private val names = linkedSetOf(defaultName)
    private var counter = 0L
    private val boxes = mutableListOf<CaptureBoxes>()
    private val preferences = linkedSetOf<String>()
    val context: Context = object : ContextWrapper(base) {
        override fun getPackageName() = "org.ffortuna.capturepoc"
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File {
            requireSafeName(name)
            return File(File(root, "databases").apply { mkdirs() }, name)
        }
        override fun databaseList(): Array<String> = File(root, "databases").list() ?: emptyArray()
        override fun deleteDatabase(name: String): Boolean {
            val file = getDatabasePath(name)
            var ok = true
            for (candidate in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))) {
                if (candidate.exists() && !candidate.delete()) ok = false
            }
            return ok
        }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefName(name), mode)
        override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences(prefName(name))
    }

    private fun requireSafeName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        require(name.startsWith(prefix))
    }
    private fun prefName(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        val isolated = "$prefix-$name"
        preferences.add(isolated)
        return isolated
    }

    fun newName(): String = "$prefix-${++counter}".also { names.add(it); requireSafeName(it) }

    fun newBoxes(clock: Clock = Clock.systemUTC()): CaptureBoxes {
        return CaptureBoxes(context, preferencesName, defaultName, clock = clock, nameFactory = ::newName)
            .also(boxes::add)
    }

    override fun close() {
        var failed=false
        boxes.asReversed().forEach { try { it.close() } catch (_: Exception) { failed=true } }
        boxes.clear()
        try {
            val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            val referenced = listOf("active-box", "previous-box", "pending-box").mapNotNull { prefs.getString(it, null) }
            (names + referenced).filter { it.startsWith(prefix) && it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }
                .forEach { name ->
                    try { context.deleteDatabase("$name.db") } catch (_: Exception) { }
                    try { KeyVault(context, name).erase() } catch (_: Exception) { }
                }
        } catch (_: Exception) { failed=true }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val keyPrefix = "ffortuna.$prefix"
            val aliases = keyStore.aliases().toList().filter {
                it.endsWith(".wrap.v1") && (it == "$keyPrefix.wrap.v1" || it.startsWith("$keyPrefix-"))
            }
            aliases.forEach { keyStore.deleteEntry(it) }
        } catch (_: Exception) { failed=true }
        File(base.dataDir,"shared_prefs").listFiles().orEmpty().forEach { file ->
            val name=file.name.removeSuffix(".bak").removeSuffix(".xml")
            if(file.name.endsWith(".xml") || file.name.endsWith(".xml.bak")) {
                if(name.startsWith("$prefix-")) preferences.add(name)
            }
        }
        preferences.forEach { name -> try {
            base.deleteSharedPreferences(name)
            val file=File(base.dataDir,"shared_prefs/$name.xml")
            if(file.exists() || File(file.path+".bak").exists()) failed=true
        } catch (_: Exception) { failed=true } }
        check(root.canonicalPath.startsWith(File(base.noBackupFilesDir,"security-checks").canonicalPath+File.separator)) { "SANDBOX_PATH" }
        if(java.nio.file.Files.isSymbolicLink(root.toPath()) || (root.exists() && root.walkTopDown().any { java.nio.file.Files.isSymbolicLink(it.toPath()) }))
            throw SecurityCheckFailure("SANDBOX_UNOWNED_LINK")
        if(root.exists() && !root.deleteRecursively()) failed=true
        try {
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if(keyStore.aliases().toList().any { it=="ffortuna.$prefix.wrap.v1" || it.startsWith("ffortuna.$prefix-") }) failed=true
        } catch (_:Exception) { failed=true }
        if(failed) throw SecurityCheckFailure("SANDBOX_CLEANUP")
    }
    companion object {
        private val namespace=Regex("security-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        /** Only called before a batch; UI checks and native capture share the same serial executor. */
        fun recoverAbandoned(base:Context) {
            val prefixes=linkedSetOf<String>()
            File(base.noBackupFilesDir,"security-checks").listFiles().orEmpty().forEach {
                if(namespace.matches(it.name)) prefixes.add(it.name)
                else throw SecurityCheckFailure("SANDBOX_UNKNOWN_DIRECTORY")
            }
            val aliases=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
            val aliasPattern=Regex("ffortuna\\.(security-[0-9a-f-]{36})(?:-[0-9]+)?\\.wrap\\.v1")
            for(alias in aliases) aliasPattern.matchEntire(alias)?.groupValues?.get(1)?.let { if(namespace.matches(it)) prefixes.add(it) }
            for(prefix in prefixes.take(128)) SecuritySandbox(base,prefix).close()
            if(prefixes.size>128) throw SecurityCheckFailure("SANDBOX_MORE_CLEANUP_REQUIRED")
        }
    }
}
