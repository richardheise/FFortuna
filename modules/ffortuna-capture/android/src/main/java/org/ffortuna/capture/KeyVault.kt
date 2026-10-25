package org.ffortuna.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the wrapped random database key is persisted; no biometric prompt per capture. */
class KeyVault(private val context: Context, private val name: String) {
    private val alias = "ffortuna.$name.wrap.v1"
    private val wrapped = File(context.noBackupFilesDir, "$name.wrapped")
    private val atomic = AtomicFile(wrapped)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun hasStoredMaterial():Boolean = listOf(wrapped,File(wrapped.path+".bak"),File(wrapped.path+".new")).any { it.exists() } || keyStore().containsAlias(alias)

    fun load(databaseExists: Boolean): ByteArray {
        val store = keyStore()
        if (wrapped.exists() || File(wrapped.path+".bak").exists()) {
            check(store.containsAlias(alias)) { "Chave perdida. A caixa foi preservada; apague somente após confirmar a perda dos dados sintéticos." }
            val key = store.getKey(alias, null) as SecretKey
            val bytes = atomic.openRead().use { input ->
                val buffer=ByteArray(513);var count=0
                while(count<buffer.size) { val read=input.read(buffer,count,buffer.size-count);if(read<0) break;count+=read }
                buffer.copyOf(count)
            }
            check(bytes.size in 30..512 && bytes[0] == 1.toByte()) { "Chave encapsulada inválida; dados preservados." }
            val ivSize = bytes[1].toInt()
            check(ivSize == 12 && bytes.size > 2 + ivSize) { "Chave encapsulada inválida; dados preservados." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes.copyOfRange(2 + ivSize, bytes.size))
        }
        check(!databaseExists && !store.containsAlias(alias)) { "Chave indisponível. Banco preservado; não será substituído." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false).build())
        val key = generator.generateKey()
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(secret)
        val encoded = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(1.toByte()).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        val output = atomic.startWrite()
        try { output.write(encoded); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); secret.fill(0); throw error }
        return secret
    }

    fun erase() {
        atomic.delete()
        check(listOf(wrapped,File(wrapped.path+".bak"),File(wrapped.path+".new")).none { it.exists() }) { "Não foi possível apagar a chave encapsulada." }
        keyStore().deleteEntry(alias)
    }
}
