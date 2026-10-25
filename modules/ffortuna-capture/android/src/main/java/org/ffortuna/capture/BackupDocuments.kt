package org.ffortuna.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import expo.modules.kotlin.activityresult.AppContextActivityResultContract

/** Only the public filename/action is saved by the activity contract; never a password or payload. */
class BackupDocumentContract(private val create:Boolean):AppContextActivityResultContract<String,Uri?> {
    private lateinit var application:Context
    override fun createIntent(context:Context,input:String):Intent {
      application=context.applicationContext
      return Intent(if(create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type=if(create) "application/octet-stream" else "*/*"
        putExtra(Intent.EXTRA_LOCAL_ONLY,true)
        if(create) putExtra(Intent.EXTRA_TITLE,input)
      }
    }
    override fun parseResult(input:String,resultCode:Int,intent:Intent?):Uri?=
        if(resultCode==Activity.RESULT_OK && ::application.isInitialized)
            intent?.data?.takeIf { it.scheme=="content" && DocumentsContract.isDocumentUri(application,it) } else null
}
