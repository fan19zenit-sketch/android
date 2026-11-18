package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal object DiagnosticShare {
    fun intent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", report)
        return Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
