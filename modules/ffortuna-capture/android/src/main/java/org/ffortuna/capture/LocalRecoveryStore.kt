package org.ffortuna.capture

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class LocalRecoveryEntry(val id: String, val createdAt: String, val count: Int)

/** Encrypted, device-local recovery snapshots. These copies are not portable to another installation. */
class LocalRecoveryStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    keyName: String = "ffortuna-local-recovery",
    fileNamespace: String = "local-recovery"
) {
    private data class IndexedEntry(val public: LocalRecoveryEntry, val contentSignature: String)
    private data class State(val entries: List<IndexedEntry>, val key: ByteArray)

    private val namespace = fileNamespace.also {
        if (!it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
    }
    private val vaultName = keyName.also {
        if (!it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) || it == "index" || it.startsWith("snapshot-"))
            throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
    }
    private val directory = try { File(context.noBackupFilesDir, namespace) }
    catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO) }
    private val indexFile = File(directory, "index.bin")
    private val settings = try { context.getSharedPreferences("$namespace-settings", Context.MODE_PRIVATE) }
    catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.PREFERENCES_IO) }
    private val vaultContext = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = directory
    }
    private val vault = KeyVault(vaultContext, vaultName)

    fun needsSnapshot(): Boolean = guarded {
        if (!isEnabled()) return@guarded false
        val entries = withState { it.entries }
        shouldSnapshot(entries)
    }

    fun maybeSave(backup: InboxBackup) = guarded {
        if (!isEnabled()) throw LocalRecoveryFailure(LocalRecoveryCode.DISABLED)
        backup.validate()
        if (backup.observations.isEmpty()) return@guarded
        val signature = backup.contentSignature()
        withState { state ->
            if (state.entries.firstOrNull()?.contentSignature == signature) return@withState
            if (state.entries.isNotEmpty() && !shouldSnapshot(state.entries)) return@withState

            val id = UUID.randomUUID().toString()
            val createdAt = Instant.now(clock).toString()
            val plain = backup.copy(createdAt = createdAt).encode()
            var encrypted: ByteArray? = null
            try {
                encrypted = LocalRecoveryCodec.encryptSnapshot(state.key, id, plain)
                val target = snapshotFile(id)
                writeAtomic(target, encrypted)
                verifySnapshot(state.key, id, target, signature, backup.observations.size)

                val next = (listOf(IndexedEntry(LocalRecoveryEntry(id, createdAt, backup.observations.size), signature)) + state.entries)
                    .take(MAX_ENTRIES)
                writeIndex(state.key, next)
                cleanupOrphans(next.map { it.public.id }.toSet())
            } finally {
                plain.fill(0); encrypted?.fill(0)
            }
        }
    }

    fun entries(): List<LocalRecoveryEntry> = guarded {
        if (!isEnabled() && !directory.exists()) return@guarded emptyList()
        withState { state -> state.entries.map { it.public } }
    }

    fun load(id: String): InboxBackup = guarded {
        requireId(id)
        if (!directory.exists()) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
        withState { state ->
            val entry = state.entries.firstOrNull { it.public.id == id }
                ?: throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
            readSnapshot(state.key, entry)
        }
    }

    fun setEnabled(enabled: Boolean) = guarded {
        try {
            if (!settings.edit().putBoolean(KEY_ENABLED, enabled).commit())
                throw LocalRecoveryFailure(LocalRecoveryCode.PREFERENCES_IO)
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.PREFERENCES_IO) }
    }

    /** Explicit user action: disables first, then removes only this namespace and its dedicated key. */
    fun clearAndDisable() = guarded {
        setEnabled(false)
        inspectDirectory()
        try {
            directory.listFiles().orEmpty().forEach { file ->
                if (!file.delete()) throw LocalRecoveryFailure(LocalRecoveryCode.CLEANUP_FAILED)
            }
            vault.erase()
            if (!directory.delete() && directory.exists()) throw LocalRecoveryFailure(LocalRecoveryCode.CLEANUP_FAILED)
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.CLEANUP_FAILED) }
    }

    fun status(): Map<String, Any?> {
        val enabled = try { isEnabled() } catch (_: Exception) { return statusMap("blocked", false, 0, null, LocalRecoveryCode.PREFERENCES_IO.name) }
        if (!enabled && !directory.exists()) return statusMap("disabled", false, 0, null, null)
        return try {
            val entries = withState { it.entries.map { entry -> entry.public } }
            val latest = entries.firstOrNull()?.createdAt
            statusMap(if (enabled) "ready" else "disabled", enabled, entries.size, latest, null)
        } catch (failure: LocalRecoveryFailure) {
            statusMap("blocked", enabled, 0, null, failure.code)
        } catch (_: Exception) {
            statusMap("blocked", enabled, 0, null, LocalRecoveryCode.STORAGE_IO.name)
        }
    }

    private fun statusMap(status: String, enabled: Boolean, count: Int, latest: String?, failure: String?): Map<String, Any?> =
        mapOf("status" to status, "enabled" to enabled, "count" to count, "latestAt" to latest, "failureCode" to failure)

    private fun isEnabled(): Boolean = try { settings.getBoolean(KEY_ENABLED, true) }
    catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.PREFERENCES_IO) }

    private fun shouldSnapshot(entries: List<IndexedEntry>): Boolean {
        val last = entries.firstOrNull() ?: return true
        val latest = try { Instant.parse(last.public.createdAt) }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID) }
        val now = Instant.now(clock)
        return !now.isBefore(latest) && Duration.between(latest, now) >= SNAPSHOT_INTERVAL
    }

    private inline fun <T> withState(block: (State) -> T): T {
        val state = openState()
        try { return block(state) } finally { state.key.fill(0) }
    }

    private fun openState(): State {
        try {
            if (!directory.exists() && !directory.mkdirs()) throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO)
            inspectDirectory()
            val snapshotCopies = directory.listFiles().orEmpty().any { isSnapshotArtifact(it.name) }
            val anyFiles = directory.listFiles().orEmpty().isNotEmpty()
            val hasKey = try { vault.hasStoredMaterial() } catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.KEY_UNAVAILABLE) }
            if ((anyFiles || snapshotCopies) && !hasKey) throw LocalRecoveryFailure(LocalRecoveryCode.KEY_UNAVAILABLE)
            val key = try { vault.load(databaseExists = anyFiles || hasKey) }
            catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.KEY_UNAVAILABLE) }

            try {
                val indexExists = indexFile.exists() || File(indexFile.path + ".bak").exists() || File(indexFile.path + ".new").exists()
                val entries = if (indexExists) readIndex(key) else {
                    if (snapshotCopies) throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
                    emptyList()
                }
                if (!indexExists) writeIndex(key, emptyList())
                // Metadata alone cannot authenticate same-size ciphertext corruption.
                authenticateEntries(key, entries)
                cleanupOrphans(entries.map { it.public.id }.toSet())
                return State(entries, key)
            } catch (failure: Throwable) {
                key.fill(0)
                throw failure
            }
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO) }
    }

    private fun readIndex(key: ByteArray): List<IndexedEntry> {
        var encrypted: ByteArray? = null
        var plain: ByteArray? = null
        try {
            encrypted = readBounded(AtomicFile(indexFile).openRead(), INDEX_CIPHERTEXT_LIMIT)
            plain = LocalRecoveryCodec.decryptIndex(key, encrypted)
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
            val root = JSONObject(text)
            if (root.keys().asSequence().toSet() != setOf("version", "entries") || root.get("version") != 1)
                throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
            val rows = root.getJSONArray("entries")
            if (rows.length() > MAX_ENTRIES) throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
            val ids = HashSet<String>()
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                if (row.keys().asSequence().toSet() != setOf("id", "createdAt", "count", "contentSignature"))
                    throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
                val id = row.getString("id").also(::requireId)
                val createdAt = row.getString("createdAt")
                Instant.parse(createdAt)
                val count = row.get("count")
                if(count !is Int) throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
                val signature = row.getString("contentSignature")
                if (!ids.add(id) || count !in 1..10_000 || !signature.matches(Regex("[a-f0-9]{64}")))
                    throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID)
                IndexedEntry(LocalRecoveryEntry(id, createdAt, count), signature)
            }
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.INDEX_INVALID) }
        finally { encrypted?.fill(0); plain?.fill(0) }
    }

    private fun writeIndex(key: ByteArray, entries: List<IndexedEntry>) {
        val rows = JSONArray()
        entries.forEach { item ->
            rows.put(JSONObject().put("id", item.public.id).put("createdAt", item.public.createdAt)
                .put("count", item.public.count).put("contentSignature", item.contentSignature))
        }
        val plain = JSONObject().put("version", 1).put("entries", rows).toString().toByteArray(Charsets.UTF_8)
        var encrypted: ByteArray? = null
        try {
            encrypted = LocalRecoveryCodec.encryptIndex(key, plain)
            writeAtomic(indexFile, encrypted)
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO) }
        finally { plain.fill(0); encrypted?.fill(0) }
    }

    private fun authenticateEntries(key: ByteArray, entries: List<IndexedEntry>) {
        entries.forEach { entry -> readSnapshot(key, entry) }
    }

    private fun verifySnapshot(key: ByteArray, id: String, file: File, signature: String, count: Int) {
        val item = IndexedEntry(LocalRecoveryEntry(id, "2000-01-01T00:00:00Z", count), signature)
        readSnapshot(key, item, file)
    }

    private fun readSnapshot(key: ByteArray, entry: IndexedEntry, file: File = snapshotFile(entry.public.id)): InboxBackup {
        var encrypted: ByteArray? = null
        var plain: ByteArray? = null
        try {
            encrypted = readBounded(AtomicFile(file).openRead(), LocalRecoveryCodec.MAX_CIPHERTEXT)
            plain = LocalRecoveryCodec.decryptSnapshot(key, entry.public.id, encrypted)
            val backup = InboxBackup.decode(plain)
            if (backup.observations.size != entry.public.count || backup.contentSignature() != entry.contentSignature)
                throw LocalRecoveryFailure(LocalRecoveryCode.SNAPSHOT_INVALID)
            return backup
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.SNAPSHOT_INVALID) }
        finally { encrypted?.fill(0); plain?.fill(0) }
    }

    private fun cleanupOrphans(referenced: Set<String>) {
        try {
            for (file in directory.listFiles().orEmpty()) {
                val id = snapshotIdForArtifact(file.name) ?: continue
                if (id !in referenced && !file.delete()) throw LocalRecoveryFailure(LocalRecoveryCode.CLEANUP_FAILED)
                if (id in referenced && file.name != snapshotFile(id).name) {
                    // AtomicFile sidecars for committed entries are retained for safe AtomicFile recovery.
                }
            }
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.CLEANUP_FAILED) }
    }

    private fun inspectDirectory() {
        try {
            if (java.nio.file.Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != directory.parentFile!!.canonicalFile)
                throw LocalRecoveryFailure(LocalRecoveryCode.UNKNOWN_FILE)
        } catch (failure: LocalRecoveryFailure) { throw failure }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.UNKNOWN_FILE) }
        for (file in directory.listFiles().orEmpty()) {
            try {
                if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory.canonicalFile)
                    throw LocalRecoveryFailure(LocalRecoveryCode.UNKNOWN_FILE)
            } catch (failure: LocalRecoveryFailure) { throw failure }
            catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.UNKNOWN_FILE) }
            if (file.name == "index.bin" || file.name == "index.bin.bak" || file.name == "index.bin.new") continue
            val wrappedName = "$vaultName.wrapped"
            if (file.name == wrappedName || file.name == "$wrappedName.bak" || file.name == "$wrappedName.new") continue
            if (snapshotIdForArtifact(file.name) != null) continue
            throw LocalRecoveryFailure(LocalRecoveryCode.UNKNOWN_FILE)
        }
    }

    private fun snapshotIdForArtifact(name: String): String? {
        val match = Regex("snapshot-([0-9a-f-]{36})\\.bin(?:\\.(?:bak|new))?").matchEntire(name) ?: return null
        val id = match.groupValues[1]
        return try { requireId(id); id } catch (_: Exception) { null }
    }

    private fun isSnapshotArtifact(name: String) = snapshotIdForArtifact(name) != null

    private fun snapshotFile(id: String): File = File(directory, "snapshot-${requireId(id)}.bin")

    private fun requireId(id: String):String {
        try { if (UUID.fromString(id).toString() != id) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT) }
        catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT) }
        return id
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
            stream = null
        } catch (_: Exception) {
            if (stream != null) try { atomic.failWrite(stream) } catch (_: Exception) { }
            throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO)
        }
    }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        input.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (out.size() + read > max) throw LocalRecoveryFailure(LocalRecoveryCode.TOO_LARGE)
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            } finally { buffer.fill(0) }
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (failure: LocalRecoveryFailure) { throw failure }
    catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO) }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val MAX_ENTRIES = 3
        private const val INDEX_CIPHERTEXT_LIMIT = 64 * 1024
        private val SNAPSHOT_INTERVAL: Duration = Duration.ofHours(24)
    }
}
