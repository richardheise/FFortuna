package org.ffortuna.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pure identity and filtering helpers for synthetic notification capture. */
object CaptureIdentity {
    fun accepts(
        sourcePackage: String,
        ownPackage: String,
        groupSummary: Boolean,
        enabled: Boolean,
    ): Boolean = enabled && sourcePackage == ownPackage && !groupSummary

    /** Keeps a notification's source identity distinct across capture generations. */
    fun sourceKey(notificationKey: String, generation: String): String =
        "${generation.length}:$generation${notificationKey.length}:$notificationKey"

    /**
     * Fingerprints the exact observed payload and publication time using unambiguous,
     * length-prefixed UTF-8 framing followed by the timestamp as a big-endian Long.
     */
    fun fingerprint(payload: String, publishedAt: Long): String {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val framed = ByteBuffer.allocate(Int.SIZE_BYTES + payloadBytes.size + Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payloadBytes.size)
            .put(payloadBytes)
            .putLong(publishedAt)
            .array()
        val digest = MessageDigest.getInstance("SHA-256").digest(framed)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
