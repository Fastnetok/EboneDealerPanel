package com.ebone.dealerpanel

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Checks GitHub Releases for a newer Ebone Dealer Panel version —
 * IDENTICAL logic to EboneAdminPanel's VersionChecker, just pointed at
 * this app's own GitHub repo. Downloads the APK inside the app with a
 * progress bar, then goes straight to the system install prompt.
 *
 * NOTE: If your GitHub repo/org name is different from
 * "Fastnetok/EboneDealerPanel", update GITHUB_API below to match.
 */
object VersionChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/Fastnetok/EboneDealerPanel/releases/latest"

    private val client = OkHttpClient()

    fun checkForUpdate(context: Context) {
        android.util.Log.d("TEST_UPDATE", "checkForUpdate Called")

        val currentVersionName = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: return
        } catch (e: Exception) {
            return
        }

        checkGitHubRelease(context, currentVersionName)
    }

    private fun checkGitHubRelease(context: Context, currentVersionName: String) {
        android.util.Log.d("GitHubUpdate", "checkGitHubRelease Started")

        thread {
            try {
                val request = Request.Builder().url(GITHUB_API).build()
                val response = client.newCall(request).execute()

                android.util.Log.d("GitHubUpdate", "Response Code = ${response.code}")
                if (!response.isSuccessful) return@thread

                val body = response.body?.string() ?: return@thread
                val json = JSONObject(body)
                val tagName = json.getString("tag_name")
                val releaseNotes = json.optString("body", "")
                val downloadUrl = json.getJSONArray("assets")
                    .getJSONObject(0)
                    .getString("browser_download_url")

                val latestVersionName = tagName.replace("v", "", ignoreCase = true)

                android.util.Log.d("GitHubUpdate", "Installed = $currentVersionName, Latest = $latestVersionName")

                if (isNewerVersion(latestVersionName, currentVersionName)) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        showUpdateDialog(context, tagName, releaseNotes, downloadUrl)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GitHubUpdate", "Error", e)
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun showUpdateDialog(
        context: android.app.Activity,
        versionName: String,
        notes: String,
        apkUrl: String
    ) {
        val message = if (notes.isNotEmpty())
            "New version $versionName is available.\n\n$notes"
        else
            "New version $versionName is available."

        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstall(context, apkUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Downloads the APK inside the app (with a progress dialog), then opens the install prompt. */
    private fun downloadAndInstall(context: android.app.Activity, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(context)
                .setTitle("Allow Installs")
                .setMessage("Please allow this app to install updates, then tap Update Now again.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.packageName))
                    context.startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val statusText = TextView(context).apply { text = "Starting download…" }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            gravity = Gravity.CENTER
            addView(statusText)
            addView(progressBar)
        }

        val progressDialog = AlertDialog.Builder(context)
            .setTitle("Downloading Update")
            .setView(container)
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            try {
                val request = Request.Builder().url(apkUrl).build()
                val response = client.newCall(request).execute()
                val body = response.body ?: throw Exception("Empty response")
                val totalBytes = body.contentLength()

                val apkFile = File(context.getExternalFilesDir(null), "update.apk")
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                context.runOnUiThread {
                                    progressBar.progress = percent
                                    statusText.text = "Downloading… $percent%"
                                }
                            }
                        }
                    }
                }

                context.runOnUiThread {
                    progressDialog.dismiss()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("GitHubUpdate", "Download failed", e)
                context.runOnUiThread {
                    progressDialog.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("Update Failed")
                        .setMessage("Could not download the update. Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installApk(context: android.app.Activity, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(installIntent)
    }
}
