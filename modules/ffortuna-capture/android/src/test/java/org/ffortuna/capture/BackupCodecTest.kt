package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class BackupCodecTest {
    private val password="uma frase longa de teste".toCharArray()
    private fun failure(problem:BackupProblem, block:()->Unit) {
        assertEquals(problem,assertThrows(BackupFailure::class.java,block).problem)
    }
    @Test fun opensIndependentOpenSslVectorWithExactUtf8Passphrase() {
        // Generated independently with Node crypto (OpenSSL), PBKDF2-SHA256/600000, AES-256-GCM.
        val hex="464654424b503031000927c0000102030405060708090a0b0c0d0e0f101112131415161718191a1b0000002395981547cedfeab57d62884636e98f4a1bfd0a2781b54f3b8852ff638d73edbc0f6582"
        val bytes=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("FFortuna sintético",BackupCodec.decrypt(bytes,"senha longa com café".toCharArray()).toString(Charsets.UTF_8))
    }
    @Test fun roundTripRandomizesSaltAndNonceWithoutContainingPlaintext() {
        val plain="FFORTUNA-SYNTHETIC-PORTABLE-PROBE".toByteArray()
        val one=BackupCodec.encrypt(plain,password);val two=BackupCodec.encrypt(plain,password)
        assertFalse(one.contentEquals(two));assertFalse(one.toString(Charsets.ISO_8859_1).contains(plain.toString(Charsets.UTF_8)))
        assertArrayEquals(plain,BackupCodec.decrypt(one,password))
        assertArrayEquals(plain,BackupCodec.decrypt(two,password))
    }
    @Test fun wrongPasswordAndAuthenticatedHeaderOrCiphertextTamperingFail() {
        val bytes=BackupCodec.encrypt("synthetic".toByteArray(),password)
        failure(BackupProblem.AUTH_FAILED) { BackupCodec.decrypt(bytes,"outra frase longa de teste".toCharArray()) }
        for(index in listOf(12,28,bytes.lastIndex)) {
            val altered=bytes.clone();altered[index]=(altered[index].toInt() xor 1).toByte()
            failure(BackupProblem.AUTH_FAILED) { BackupCodec.decrypt(altered,password) }
        }
    }
    @Test fun unsupportedVersionCostTruncationAndLengthAreRejectedBeforeDerivation() {
        val header=ByteBuffer.allocate(60).put("FFTBKP01".toByteArray()).putInt(Int.MAX_VALUE).array()
        failure(BackupProblem.INVALID_FILE) { BackupCodec.decrypt(header,password) }
        header[7]='2'.code.toByte();failure(BackupProblem.INVALID_FILE) { BackupCodec.decrypt(header,password) }
        failure(BackupProblem.INVALID_FILE) { BackupCodec.decrypt(ByteArray(59),password) }
        val wrongLength=ByteBuffer.allocate(60).put("FFTBKP01".toByteArray()).putInt(600_000).array()
        failure(BackupProblem.INVALID_FILE) { BackupCodec.decrypt(wrongLength,password) }
    }
    @Test fun passwordLimitsDoNotSilentlyTrimOrAcceptMalformedUtf16() {
        for(chars in listOf("short".toCharArray(),CharArray(129){'a'},("a".repeat(11)+'\u0000').toCharArray(),("a".repeat(11)+'\uD800').toCharArray()))
            failure(BackupProblem.PASSWORD_LENGTH) { BackupCodec.validatePassword(chars) }
        BackupCodec.validatePassword("  uma senha com espaços  ".toCharArray())
    }
    @Test fun inputAndOutputLimitsAreBounded() {
        failure(BackupProblem.TOO_LARGE) { BackupCodec.readBounded(ByteArrayInputStream(ByteArray(BackupCodec.MAX_FILE+1))) }
        failure(BackupProblem.TOO_LARGE) { BackupCodec.encrypt(ByteArray(BackupCodec.MAX_PLAINTEXT+1),password) }
        assertArrayEquals(byteArrayOf(1,2),BackupCodec.readBounded(ByteArrayInputStream(byteArrayOf(1,2))))
    }
}
