package org.ffortuna.capture

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class LocalRecoveryCode {
    DISABLED, INVALID_INPUT, TOO_LARGE, AUTHENTICATION, INVALID_FORMAT, KEY_UNAVAILABLE,
    INDEX_INVALID, SNAPSHOT_INVALID, STORAGE_IO, UNKNOWN_FILE, CLEANUP_FAILED, PREFERENCES_IO
}

class LocalRecoveryFailure internal constructor(val code: String) : RuntimeException(code) {
    internal constructor(code: LocalRecoveryCode) : this(code.name)
}

/** Versioned AES-GCM envelope for device-local recovery. Roles are authenticated as AAD. */
object LocalRecoveryCodec {
    const val MAX_PLAINTEXT = 8 * 1024 * 1024
    const val HEADER_SIZE = 21 // magic(8), version(1), nonce(12)
    const val TAG_SIZE = 16
    const val MAX_CIPHERTEXT = HEADER_SIZE + MAX_PLAINTEXT + TAG_SIZE
    private val magic = "FFLRC001".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encryptIndex(key: ByteArray, plaintext: ByteArray): ByteArray = encrypt(key, plaintext, "index:v1")
    fun decryptIndex(key: ByteArray, ciphertext: ByteArray): ByteArray = decrypt(key, ciphertext, "index:v1")

    fun encryptSnapshot(key: ByteArray, id: String, plaintext: ByteArray): ByteArray =
        encrypt(key, plaintext, snapshotRole(id))
    fun decryptSnapshot(key: ByteArray, id: String, ciphertext: ByteArray): ByteArray =
        decrypt(key, ciphertext, snapshotRole(id))

    private fun snapshotRole(id: String): String {
        try {
            if (UUID.fromString(id).toString() != id) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
        } catch (_: Exception) { throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT) }
        return "snapshot:$id"
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray, role: String): ByteArray {
        validateKey(key)
        if (plaintext.size > MAX_PLAINTEXT) throw LocalRecoveryFailure(LocalRecoveryCode.TOO_LARGE)
        val nonce = ByteArray(12).also(random::nextBytes)
        return encryptWithNonce(key, plaintext, role, nonce)
    }

    /** Package-visible deterministic primitive for codec vectors; production calls use fresh random nonces. */
    internal fun encryptWithNonce(key: ByteArray, plaintext: ByteArray, role: String, nonce: ByteArray): ByteArray {
        validateKey(key)
        if (plaintext.size > MAX_PLAINTEXT) throw LocalRecoveryFailure(LocalRecoveryCode.TOO_LARGE)
        if (nonce.size != 12) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).put(1).put(nonce).array()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.updateAAD(role.toByteArray(Charsets.UTF_8))
            return header + cipher.doFinal(plaintext)
        } catch (_: Exception) {
            throw LocalRecoveryFailure(LocalRecoveryCode.STORAGE_IO)
        }
    }

    private fun decrypt(key: ByteArray, ciphertext: ByteArray, role: String): ByteArray {
        validateKey(key)
        if (ciphertext.size < HEADER_SIZE + TAG_SIZE || ciphertext.size > MAX_CIPHERTEXT)
            throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_FORMAT)
        val header = ciphertext.copyOfRange(0, HEADER_SIZE)
        val parsed = ByteBuffer.wrap(header)
        val foundMagic = ByteArray(magic.size).also(parsed::get)
        if (!foundMagic.contentEquals(magic) || parsed.get().toInt() != 1)
            throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_FORMAT)
        val nonce = ByteArray(12).also(parsed::get)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.updateAAD(role.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext, HEADER_SIZE, ciphertext.size - HEADER_SIZE)
        } catch (_: AEADBadTagException) {
            throw LocalRecoveryFailure(LocalRecoveryCode.AUTHENTICATION)
        } catch (_: Exception) {
            throw LocalRecoveryFailure(LocalRecoveryCode.AUTHENTICATION)
        } finally {
            header.fill(0); nonce.fill(0); foundMagic.fill(0)
        }
    }

    private fun validateKey(key: ByteArray) {
        if (key.size != 32) throw LocalRecoveryFailure(LocalRecoveryCode.INVALID_INPUT)
    }
}
