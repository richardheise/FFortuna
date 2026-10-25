package org.ffortuna.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LocalRecoveryCodecTest {
    private fun key() = ByteArray(32) { it.toByte() }
    private fun uuid() = UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()
    private fun failure(code: LocalRecoveryCode, block: () -> Unit) {
        assertEquals(code.name, assertThrows(LocalRecoveryFailure::class.java, block).code)
    }

    @Test fun opensIndependentAes256GcmFormatVector() {
        val vectorKey = ByteArray(32)
        val vectorNonce = ByteArray(12)
        val encrypted = LocalRecoveryCodec.encryptWithNonce(vectorKey, "synthetic-vector".toByteArray(), "index:v1", vectorNonce)
        val expected = "46464c524330303101000000000000000000000000bdde2e4925051f076463b3b6d987f26a821c44caed4158886a473cfc7c6163f8"
        assertEquals(expected, encrypted.joinToString("") { "%02x".format(it) })
        assertEquals("FFLRC001", encrypted.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(1, encrypted[8].toInt())
        assertArrayEquals("synthetic-vector".toByteArray(), LocalRecoveryCodec.decryptIndex(vectorKey, encrypted))
    }

    @Test fun freshNonceAndAuthenticatedRolesKeepCiphertextsSeparate() {
        val key = key(); val plain = "synthetic recovery fixture".toByteArray(); val id = uuid()
        val first = LocalRecoveryCodec.encryptSnapshot(key, id, plain)
        val second = LocalRecoveryCodec.encryptSnapshot(key, id, plain)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(plain.toString(Charsets.UTF_8)))
        assertArrayEquals(plain, LocalRecoveryCodec.decryptSnapshot(key, id, first))
        failure(LocalRecoveryCode.AUTHENTICATION) { LocalRecoveryCodec.decryptIndex(key, first) }
        val otherId = "123e4567-e89b-12d3-a456-426614174001"
        failure(LocalRecoveryCode.AUTHENTICATION) { LocalRecoveryCodec.decryptSnapshot(key, otherId, first) }
    }

    @Test fun wrongKeyAndHeaderNonceOrCiphertextTamperingFailAuthentication() {
        val key = key(); val encrypted = LocalRecoveryCodec.encryptIndex(key, "local synthetic index".toByteArray())
        val wrong = ByteArray(32) { (it + 1).toByte() }
        failure(LocalRecoveryCode.AUTHENTICATION) { LocalRecoveryCodec.decryptIndex(wrong, encrypted) }
        for (offset in listOf(0, 8, 9, encrypted.lastIndex)) {
            val altered = encrypted.clone(); altered[offset] = (altered[offset].toInt() xor 1).toByte()
            val code = if (offset == 0 || offset == 8) LocalRecoveryCode.INVALID_FORMAT else LocalRecoveryCode.AUTHENTICATION
            failure(code) { LocalRecoveryCodec.decryptIndex(key, altered) }
        }
    }

    @Test fun malformedLengthsRolesAndKeysAreRejectedWithFixedCodes() {
        val key = key()
        failure(LocalRecoveryCode.INVALID_FORMAT) { LocalRecoveryCodec.decryptIndex(key, ByteArray(LocalRecoveryCodec.HEADER_SIZE + 15)) }
        failure(LocalRecoveryCode.INVALID_FORMAT) { LocalRecoveryCodec.decryptIndex(key, ByteArray(LocalRecoveryCodec.MAX_CIPHERTEXT + 1)) }
        failure(LocalRecoveryCode.INVALID_INPUT) { LocalRecoveryCodec.decryptSnapshot(key, "not-a-uuid", ByteArray(40)) }
        failure(LocalRecoveryCode.INVALID_INPUT) { LocalRecoveryCodec.encryptIndex(ByteArray(31), byteArrayOf(1)) }
    }

    @Test fun plaintextBoundIsEnforcedBeforeEncryption() {
        val tooLarge = ByteArray(LocalRecoveryCodec.MAX_PLAINTEXT + 1)
        failure(LocalRecoveryCode.TOO_LARGE) { LocalRecoveryCodec.encryptIndex(key(), tooLarge) }
        assertTrue(LocalRecoveryCodec.encryptIndex(key(), ByteArray(LocalRecoveryCodec.MAX_PLAINTEXT)).size <= LocalRecoveryCodec.MAX_CIPHERTEXT)
    }
}
