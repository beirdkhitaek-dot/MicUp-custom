package com.micplugin.service

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

enum class ShizukuState {
    UNAVAILABLE,   // Shizuku not installed / not running
    NEED_GRANT,    // Installed but permission not granted yet
    READY,         // Permission granted, good to go
}

@Singleton
class ShizukuManager @Inject constructor() {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val REQUEST_CODE = 42
    }

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state

    // Called once from App.onCreate
    fun init() {
        try {
            // Always register listeners first so we catch Shizuku starting later
            Shizuku.addBinderReceivedListener {
                Log.i(TAG, "Shizuku binder received")
                refreshState()
            }
            Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku binder died")
                _state.value = ShizukuState.UNAVAILABLE
            }
            Shizuku.addRequestPermissionResultListener { _, result ->
                _state.value = if (result == PackageManager.PERMISSION_GRANTED)
                    ShizukuState.READY else ShizukuState.NEED_GRANT
            }

            // Now check current state (binder may or may not be up yet)
            if (Shizuku.pingBinder()) {
                refreshState()
            } else {
                _state.value = ShizukuState.UNAVAILABLE
                Log.i(TAG, "Shizuku binder not available yet — waiting for callback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku init failed: $e")
            _state.value = ShizukuState.UNAVAILABLE
        }
    }

    private fun refreshState() {
        _state.value = try {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                else -> ShizukuState.NEED_GRANT
            }
        } catch (e: Exception) {
            ShizukuState.UNAVAILABLE
        }
    }

    /** Call from Activity to trigger the Shizuku permission dialog */
    fun requestPermission() {
        try {
            if (_state.value == ShizukuState.NEED_GRANT)
                Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: $e")
        }
    }

    /**
     * Run a shell command via Shizuku (adb-level).
     * Returns stdout, or null on failure.
     */
    fun exec(cmd: String): String? {
        if (_state.value != ShizukuState.READY) return null
        return execInternal(cmd)
    }

    /** Exec with auto su-fallback — tries Shizuku first, then root su paths */
    fun execWithFallback(cmd: String): String? {
        if (_state.value == ShizukuState.READY) {
            execInternal(cmd)?.let { return it }
        }
        // Fallback: try known su binary paths
        val suPaths = listOf(
            "/data/adb/magisk/busybox",
            "/data/adb/ksu/bin/su",
            "/system/xbin/su",
            "/system/bin/su",
            "su",
        )
        for (su in suPaths) {
            try {
                val args = if (su.contains("busybox")) arrayOf(su, "sh", "-c", cmd)
                           else arrayOf(su, "-c", cmd)
                val p = Runtime.getRuntime().exec(args)
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor()
                Log.d(TAG, "execWithFallback[$su][$cmd] => ${out.take(80)}")
                if (out.isNotBlank()) return out
            } catch (_: Exception) {}
        }
        Log.e(TAG, "execWithFallback: all su paths failed for: $cmd")
        return null
    }

    /**
     * Runs a command in the Shizuku (adb "shell") process, not in this app. Shizuku hides
     * newProcess() since v13, so it is called by reflection; if that fails we fall back to a
     * normal in-app process and say so in the log, because results then are NOT shell results.
     */
    private fun execInternal(cmd: String): String? {
        val viaShizuku = try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            try { p.waitFor() } catch (_: Throwable) { /* output is already complete */ }
            Log.d(TAG, "shizuku-exec[$cmd] => ${out.take(120)}")
            out
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku newProcess failed, running as the app instead: $e")
            null
        }
        if (viaShizuku != null) return viaShizuku
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Log.d(TAG, "app-exec[$cmd] => ${out.take(120)}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $e")
            null
        }
    }

    /** Starts a long-running command in the Shizuku shell and returns it (null if Shizuku is not ready). */
    fun spawn(cmd: String): Process? {
        if (_state.value != ShizukuState.READY) return null
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        } catch (e: Throwable) {
            Log.e(TAG, "spawn failed: $e"); null
        }
    }

    /**
     * Facts for the diagnostic log, gathered as the Shizuku shell user (blocking; call from a
     * background thread). Tells us whether commands really run as shell, whether the kernel can
     * do ALSA loopback, and whether the shell may use Android's audio routing policy.
     */
    fun probe(): String {
        if (_state.value != ShizukuState.READY) return "Shizuku not ready (${_state.value}) - no probe"
        val checks = listOf(
            "who am I"            to "id",
            "SELinux context"     to "cat /proc/self/attr/current",
            "Android/kernel"      to "getprop ro.build.version.release; cat /proc/version",
            "ALSA cards"          to "cat /proc/asound/cards",
            "ALSA loopback config" to "(zcat /proc/config.gz | grep -i -E 'SND_ALOOP|SND_DUMMY|SND_USB_AUDIO') 2>&1 | head -5",
            "aloop module files"  to "ls /vendor/lib/modules /vendor_dlkm/lib/modules /system/lib/modules 2>&1 | grep -i -E 'aloop|snd' | head -10",
            "loaded modules"      to "grep -i -E 'snd|aloop' /proc/modules | head -10",
            "can load modules?"   to "grep CapEff /proc/self/status",
            "audio routing perm"  to "dumpsys package com.android.shell | grep -i -E 'MODIFY_AUDIO_ROUTING|CAPTURE_AUDIO_OUTPUT|CAPTURE_AUDIO_HOTWORD' | head -5",
            "audio policy mixes"  to "dumpsys media.audio_policy | grep -i -E 'dynamic|mix' | head -8",
            "recording clients"   to "dumpsys audio | grep -i -A4 -E 'recording|rec ?id' | head -40",
            "policy inputs"       to "dumpsys media.audio_policy | grep -i -E 'Remote Submix|Input [0-9]|Client|uid|Source' | head -40",
        )
        return buildString {
            for ((label, cmd) in checks) {
                val r = (try { execInternal(cmd) } catch (e: Throwable) { "error: $e" } ?: "no output").trim()
                appendLine("$label: ${r.ifEmpty { "(empty)" }.replace("\n", " | ")}")
            }
        }
    }

    // ── Helpers used by VirtualMicService ────────────────────────────────────

    /** Load ALSA loopback module so processed audio appears as a mic device */
    fun loadAlsaLoopback(): Boolean {
        val out = execWithFallback("modprobe snd-aloop pcm_substreams=2 2>&1 || insmod /system/lib/modules/snd-aloop.ko pcm_substreams=2 2>&1")
        return out != null && !out.contains("error", ignoreCase = true)
    }

    /** Route our audio output to the loopback capture side via tinyalsa */
    fun routeToLoopback(sampleRate: Int = 48000): Boolean {
        val tinymix = execWithFallback("which tinymix") ?: return false
        if (tinymix.isBlank()) return false
        execWithFallback("tinymix 'Loopback Mixer' 1")
        return true
    }

    /** Set microphone as default input (adb-level appops) */
    fun setAppOpsMicDefault(packageName: String): Boolean {
        val out = execWithFallback("appops set $packageName RECORD_AUDIO allow")
        return out != null
    }

    val isReady: Boolean get() = _state.value == ShizukuState.READY

    // ── ALSA loopback support probe (fixes #2) ──────────────────────────────────
    // On some kernels (seen on MediaTek MT6789 e.g. Infinix Note 30) snd-aloop can't be
    // loaded at all — modprobe/insmod "succeed" in the sense of not throwing, but no
    // loopback card ever shows up, so processed audio never actually reaches other apps
    // even though everything looks fine locally (the on-device monitor still works, since
    // that path doesn't depend on the kernel module at all). Previously this failure was
    // invisible: loadAlsaLoopback()'s return value was logged but never acted on. This
    // probes /proc/asound/cards directly, which is the ground truth for whether the
    // loopback device actually exists.
    private var loopbackSupportCache: Boolean? = null
    private var loopbackSupportCacheAt: Long = 0L

    fun hasAlsaLoopbackSupport(forceRecheck: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRecheck) {
            loopbackSupportCache?.let { if (now - loopbackSupportCacheAt < 5000) return it }
        }
        // Attempt the load first — on most devices the card only appears in
        // /proc/asound/cards *after* modprobe/insmod has run at least once, so checking
        // before that would falsely read as "unsupported" even on capable hardware.
        // This is idempotent: harmless to call again if already loaded.
        loadAlsaLoopback()
        val out = execWithFallback("cat /proc/asound/cards 2>/dev/null")
        val supported = out?.contains("Loopback", ignoreCase = true) == true
        loopbackSupportCache = supported
        loopbackSupportCacheAt = now
        return supported
    }
}
