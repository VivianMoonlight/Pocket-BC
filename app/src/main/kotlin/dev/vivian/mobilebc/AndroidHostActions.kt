package dev.vivian.mobilebc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun Context.openAppNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
    }
    startSettingsActivitySafely(intent)
}

internal fun Context.openNotificationChannelSettings(channelId: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelId.isBlank()) {
        openAppNotificationSettings()
        return
    }

    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

    startSettingsActivitySafely(intent)
}

private fun Context.startSettingsActivitySafely(intent: Intent) {
    runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching {
            startActivity(fallback)
        }.onFailure { throwable ->
            Toast.makeText(
                this,
                getString(
                    R.string.android_settings_open_failed,
                    throwable.message ?: throwable::class.java.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

internal fun Context.sharePacketLogs(text: String) {
    val exportText = text.ifBlank { getString(R.string.packet_logs_empty) }
    val subject = getString(R.string.packet_log_subject)

    val exportResult = runCatching {
        val packetLogDirectory = File(cacheDir, "packet-logs")
        if (!packetLogDirectory.exists() && !packetLogDirectory.mkdirs()) {
            error("Unable to create packet log export directory: ${packetLogDirectory.absolutePath}")
        }

        val timestamp = PacketLogExportTimeFormatter.format(LocalDateTime.now())
        val packetLogFile = File(packetLogDirectory, "BC-packet-log-$timestamp.txt")
        packetLogFile.writeText(exportText, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            packetLogFile,
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(
                Intent.EXTRA_TEXT,
                getString(
                    R.string.packet_log_attached,
                    packetLogFile.name,
                    packetLogFile.length().toReadableByteCount(),
                ),
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addNewTaskFlagIfNeeded(this@sharePacketLogs)
        }

        val targets = packageManager.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.packet_log_no_share_app), Toast.LENGTH_SHORT).show()
            return
        }

        targets.forEach { target ->
            grantUriPermission(
                target.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val chooser = Intent.createChooser(sendIntent, getString(R.string.packet_log_share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addNewTaskFlagIfNeeded(this@sharePacketLogs)
        }

        Toast.makeText(
            this,
            getString(R.string.packet_log_ready, packetLogFile.length().toReadableByteCount()),
            Toast.LENGTH_SHORT,
        ).show()
        startActivity(chooser)
    }

    exportResult.onFailure { throwable ->
        val message = when (throwable) {
            is ActivityNotFoundException -> getString(R.string.packet_log_no_share_app)
            else -> getString(
                R.string.packet_log_export_failed,
                throwable.message ?: throwable::class.java.simpleName,
            )
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private fun Long.toReadableByteCount(): String {
    if (this < 1024L) return "$this B"
    val kib = this / 1024.0
    if (kib < 1024.0) return "%.1f KiB".format(kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return "%.1f MiB".format(mib)
    val gib = mib / 1024.0
    return "%.1f GiB".format(gib)
}

private fun Intent.addNewTaskFlagIfNeeded(context: Context): Intent =
    apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

private val PacketLogExportTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
