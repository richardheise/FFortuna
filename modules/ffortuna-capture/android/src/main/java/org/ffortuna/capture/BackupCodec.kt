package org.ffortuna.capture

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BackupProblem { PASSWORD_LENGTH, INVALID_FILE, AUTH_FAILED, TOO_LARGE, INVALID_DATA, PREVIOUS_EXISTS, NO_PREVIOUS, STORAGE, IO, BUSY }
class BackupFailure(val problem: BackupProblem) : RuntimeException(problem.name)

/** V1: magic(8), iterations(4, BE), salt(16), nonce(12), ciphertext length(4), ciphertext+tag.
 * The entire 44-byte header is GCM AAD. Only this version's fixed KDF cost is accepted.
 * No normalization/trim of passphrases. JCA PBKDF2-HMAC-SHA256 uses their UTF-8 encoding.
 */
object BackupCodec {
    const val ITERATIONS = 600_000
    const val MAX_PLAINTEXT = 8 * 1024 * 1024
    const val HEADER_SIZE = 44
    const val MAX_FILE = HEADER_SIZE + MAX_PLAINTEXT + 16
    private val MAGIC = "FFTBKP01".toByteArray(Charsets.US_ASCII)
    fun validatePassword(password: CharArray) {
        if (password.size !in 12..128 || password.any { it == '\u0000' }) throw BackupFailure(BackupProblem.PASSWORD_LENGTH)
        // Reject unpaired UTF-16 surrogates, which different UTF-8 encoders can replace differently.
        var i=0
        while (i < password.size) {
            val c=password[i++]
            if (Character.isHighSurrogate(c)) {
                if (i >= password.size || !Character.isLowSurrogate(password[i++])) throw BackupFailure(BackupProblem.PASSWORD_LENGTH)
            } else if (Character.isLowSurrogate(c)) throw BackupFailure(BackupProblem.PASSWORD_LENGTH)
        }
    }
    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        val spec=PBEKeySpec(password,salt,ITERATIONS,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (plain.size > MAX_PLAINTEXT) throw BackupFailure(BackupProblem.TOO_LARGE)
        val random=SecureRandom()
        val salt=ByteArray(16).also(random::nextBytes)
        val nonce=ByteArray(12).also(random::nextBytes)
        val header=ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).putInt(plain.size+16).array()
        val secret=key(password,salt)
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(secret,"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(header)
            header+cipher.doFinal(plain)
        } finally { secret.fill(0) }
    }
    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (file.size !in (HEADER_SIZE+16)..MAX_FILE) throw BackupFailure(BackupProblem.INVALID_FILE)
        val header=ByteBuffer.wrap(file,0,HEADER_SIZE)
        val magic=ByteArray(8).also(header::get)
        if (!magic.contentEquals(MAGIC) || header.int != ITERATIONS) throw BackupFailure(BackupProblem.INVALID_FILE)
        val salt=ByteArray(16).also(header::get)
        val nonce=ByteArray(12).also(header::get)
        if (header.int != file.size-HEADER_SIZE) throw BackupFailure(BackupProblem.INVALID_FILE)
        val secret=key(password,salt)
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(secret,"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(file,0,HEADER_SIZE)
            cipher.doFinal(file,HEADER_SIZE,file.size-HEADER_SIZE)
        } catch (_: AEADBadTagException) { throw BackupFailure(BackupProblem.AUTH_FAILED) }
        finally { secret.fill(0) }
    }
    fun readBounded(input: InputStream): ByteArray {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while (true) {
            val read=input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size()+read > MAX_FILE) throw BackupFailure(BackupProblem.TOO_LARGE)
            output.write(buffer,0,read)
        }
        return output.toByteArray()
    }
}
