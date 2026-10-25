package org.ffortuna.capture

enum class CaptureStorageStage { LIBRARY, KEY, OPEN, SECURITY, MIGRATION, RETENTION }

/** Diagnostic identifiers only: never expose exception messages, SQL, paths or key material. */
class CaptureStorageFailure(val stage: CaptureStorageStage, cause: Throwable) :
    RuntimeException("Capture storage initialization failed", cause)

fun storageFailureMessage(error: Exception): String {
    val phase = if (error is CaptureStorageFailure) error.stage.name else "PROCESSING"
    val kind = (if (error is CaptureStorageFailure) error.cause else error)?.javaClass?.simpleName ?: "Unknown"
    return "Caixa cifrada indisponível [$phase/$kind]. Dados e chave preservados; captura bloqueada. Nenhum reset foi feito."
}
