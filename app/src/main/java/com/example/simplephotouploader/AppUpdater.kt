package com.example.simplephotouploader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AppUpdater {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()

    fun available(context: Context): JSONObject? = runCatching {
        val prefs = RemoteSupport.prefs(context)
        // Do not keep offering a release that the administrator may have withdrawn.
        val age = System.currentTimeMillis() - prefs.getLong("last_sync", 0)
        if (age !in 0..86400000) return null
        JSONObject(prefs.getString("update", null) ?: return null).takeIf {
            it.getString("package") == context.packageName && it.getInt("version_code") > BuildConfig.VERSION_CODE &&
                it.getInt("min_sdk") <= Build.VERSION.SDK_INT && SupportPolicy.allowedDownload(it.getString("url")) &&
                Regex("[0-9a-f]{64}").matches(it.getString("sha256"))
        }
    }.getOrNull()

    @Synchronized
    fun download(context: Context, info: JSONObject): File {
        if (!SupportPolicy.allowedDownload(info.getString("url"))) throw IOException("Invalid update URL")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        if (target.exists() && runCatching { verify(context, target, info) }.isSuccess) return target
        val temporary = File(directory, "download.part")
        try {
            client.newCall(Request.Builder().url(info.getString("url")).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed")
                val body = response.body ?: throw IOException("Empty update")
                if (body.contentLength() > MAX_BYTES) throw IOException("Update too large")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_BYTES) throw IOException("Update too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            verify(context, temporary, info)
            if (!temporary.renameTo(target)) throw IOException("Cannot save update")
            return target
        } finally { temporary.delete() }
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, expected: JSONObject) {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) throw IOException("Invalid APK size")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected.getString("sha256")) throw IOException("APK checksum mismatch")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName || archive.packageName != expected.getString("package") ||
            version != expected.getLong("version_code") || version <= BuildConfig.VERSION_CODE ||
            (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) {
            throw IOException("APK does not match this application")
        }
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        if (signers(installed).isEmpty() || signers(archive) != signers(installed)) throw IOException("APK signing key mismatch")
    }

    fun installer(context: Context, apk: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", apk),
            "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
