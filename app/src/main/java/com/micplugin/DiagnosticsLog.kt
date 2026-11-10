package com.micplugin

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects everything needed to diagnose a problem into one text: device and Android details,
 * the app's own state (passed in by the caller), recent crash reports and this app's recent
 * logcat lines (an app can only read its own log lines, so nothing from other apps is included).
 * Contains no audio and no personal data beyond the device model.
 */
object DiagnosticsLog {

    private const val MAX_LOGCAT_LINES = 2000
    private const val MAX_CRASH_CHARS = 6000
    private const val MAX_TOTAL_CHARS = 200_000
    private const val KEEP_FILES = 3

    /** Blocking: call from a background thread. */
    fun build(context: Context, appState: String): String {
        val header = header(context, appState)
        val logcat = readLogcat()
        val title = "\n=== Recent app log (logcat) ===\n"
        val full = header + title + logcat
        if (full.length <= MAX_TOTAL_CHARS) return full
        val room = (MAX_TOTAL_CHARS - header.length - title.length - 60).coerceAtLeast(0)
        return header + "\n=== Recent app log (logcat, only the newest part) ===\n" + logcat.takeLast(room)
    }

    /** Writes the text into the cache folder (shareable through the app's FileProvider). */
    fun writeFile(context: Context, text: String): File {
        val dir = context.cacheDir
        dir.listFiles { f -> f.name.startsWith("micup-log-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES - 1)
            ?.forEach { it.delete() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "micup-log-$ts.txt").also { it.writeText(text) }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MicUp log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Send log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // The clipboard has a size limit, so copy the newest part if the text is very long.
        cm.setPrimaryClip(ClipData.newPlainText("MicUp log", text.takeLast(100_000)))
    }

    private fun header(context: Context, appState: String): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        appendLine("=== MicUp diagnostic log ===")
        appendLine("Created:   $now")
        appendLine("App:       ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device:    ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, hw=${Build.HARDWARE}, board=${Build.BOARD})")
        if (Build.VERSION.SDK_INT >= 31) {
            appendLine("SoC:       ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        appendLine("Android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Kernel:    ${System.getProperty("os.version") ?: "unknown"}")
        appendLine("ABIs:      ${Build.SUPPORTED_ABIS.joinToString()}")
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        appendLine("Mic perm:  ${if (micGranted) "granted" else "NOT granted"}")
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            appendLine("Audio:     native rate=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} " +
                "frames/buffer=${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} " +
                "mode=${am.mode} micMuted=${am.isMicrophoneMute}")
        } catch (e: Throwable) {
            appendLine("Audio:     unavailable (${e.javaClass.simpleName})")
        }
        appendLine("ALSA:      ${readFile("/proc/asound/cards").replace("\n", " | ")}")
        appendLine()
        appendLine("=== App state ===")
        appendLine(appState.trim())

        val crashes = try { CrashReporter.getCrashReports(context).take(3) } catch (e: Throwable) { emptyList() }
        appendLine()
        appendLine("=== Crash reports (${crashes.size}) ===")
        if (crashes.isEmpty()) appendLine("none recorded")
        crashes.forEach { f ->
            appendLine("--- ${f.name} ---")
            appendLine(readFile(f.absolutePath).take(MAX_CRASH_CHARS))
        }
    }

    private fun readFile(path: String): String = try {
        File(path).readText().trim().ifEmpty { "(empty)" }
    } catch (e: Throwable) {
        "unreadable (${e.javaClass.simpleName})"
    }

    private fun readLogcat(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", MAX_LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.destroy()
        out.ifBlank { "(logcat returned nothing)" }
    } catch (e: Throwable) {
        "(could not read logcat: $e)"
    }
}
