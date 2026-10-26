package org.ffortuna.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest
import java.time.Instant

/** Narrow package visibility. This records the installed signer; it does not certify the publisher. */
object DiagnosticSource {
    @Suppress("DEPRECATION")
    fun inspect(context:Context):Map<String,Any?> = try {
        val manager=context.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=manager.getPackageInfo(DiagnosticPolicy.PACKAGE,flags)
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.let { if(it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory?.takeLast(1)?.toTypedArray() } else info.signatures
        check(!signatures.isNullOrEmpty())
        val signer=signatures.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(":")
        val app=manager.getApplicationInfo(DiagnosticPolicy.PACKAGE,0)
        mapOf("installed" to true,"enabled" to app.enabled,"packageName" to info.packageName,
            "label" to manager.getApplicationLabel(app).toString().take(128),"versionName" to info.versionName?.take(128),"signer" to signer)
    } catch (_:Exception) {
        mapOf("installed" to false,"enabled" to false,"packageName" to DiagnosticPolicy.PACKAGE,"label" to "Nubank",
            "versionName" to null,"signer" to null)
    }
    fun moment(context:Context)=DiagnosticMoment(Instant.now(),SystemClock.elapsedRealtime(),
        try { Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT) } catch (_:Exception) { -1 })
}
