package com.micplugin.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * EXPERIMENTAL. Sends MicUp's processed voice to another app (Discord) as its microphone.
 * Starts a helper in the Shizuku shell (com.micplugin.shell.InjectServer) that redirects that
 * app's microphone input, then streams the processed audio to it over a local socket.
 */
object MicInjector {
    private const val TAG = "MicUp.Inject"
    private const val PORT = 28411

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status

    @Volatile var engine: com.micplugin.audio.AudioEngine? = null
    @Volatile private var active = false
    @Volatile private var proc: Process? = null
    @Volatile private var sock: Socket? = null
    private val queue = ArrayBlockingQueue<ByteArray>(8)

    val isActive: Boolean get() = active

    // The system log keeps only a short window, so this keeps its own history for the diagnostic log.
    private val history = java.util.ArrayDeque<String>()
    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(history) {
            history.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line)
            while (history.size > 500) history.removeFirst()
        }
    }
    fun dump(): String = synchronized(history) {
        if (history.isEmpty()) "(injector was never started in this app session)" else history.joinToString("\n")
    }

    fun start(context: Context, shizuku: ShizukuManager, targetPackage: String, byInput: Boolean = false) {
        if (active) return
        active = true
        _status.value = "Starting…"
        log("start requested for $targetPackage, mode=${if (byInput) "by input type (all VoiceCommunication recordings)" else "by app"}")
        val apk = context.applicationInfo.sourceDir
        thread(name = "MicInjector-start") {
            try {
                val found = if (byInput) ("all call apps" to 0) else findTarget(shizuku, targetPackage)
                if (found == null) { fail("no installed app matching \"$targetPackage\" was found (see the injector log)"); return@thread }
                val (pkgName, uid) = found
                log("target app: $pkgName uid=$uid")

                val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                shizuku.exec("pkill -f micup_inject")
                val p = shizuku.spawn(
                    "CLASSPATH=$apk exec app_process /system/bin --nice-name=micup_inject " +
                        "com.micplugin.shell.InjectServer $uid $PORT $token ${if (byInput) "input" else "uid"}"
                )
                log("helper spawned: ${p != null}")
                if (p == null) { fail("Could not start the Shizuku helper"); return@thread }
                proc = p

                val ready = CountDownLatch(1)
                var failure: String? = null
                thread(name = "MicInjector-out") {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            log(line)
                            if (line == "INJ READY") ready.countDown()
                            if (line.startsWith("INJ MIC OK")) engine?.setExternalInput(true)
                            if (line.startsWith("INJ MIC FAILED")) engine?.setExternalInput(false)
                            if (line.startsWith("INJ SELFTEST heard")) _status.value = "Active — self-test: " + line.removePrefix("INJ SELFTEST ")
                            if (line.startsWith("INJ FATAL")) { failure = line.removePrefix("INJ "); ready.countDown() }
                        }
                    } catch (_: Exception) {}
                    if (failure == null) failure = "helper exited"
                    ready.countDown()
                }
                if (!ready.await(15, TimeUnit.SECONDS)) { fail("helper did not become ready"); return@thread }
                val f = failure
                if (f != null) { fail(f); return@thread }

                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
                s.getOutputStream().write(token.toByteArray())
                sock = s
                log("connected to helper")
                _status.value = "Active for $pkgName — now JOIN or REJOIN the call (or mute and unmute your mic in Discord)"
                // Microphone audio recorded by the helper comes back on the same socket.
                thread(name = "MicInjector-mic") {
                    try {
                        val din = java.io.DataInputStream(s.getInputStream().buffered(32768))
                        var fbuf = FloatArray(4096)
                        while (active) {
                            val n = din.readInt()
                            if (n <= 0 || n > 4096) break
                            val raw = ByteArray(n * 2)
                            din.readFully(raw)
                            val bb = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until n) fbuf[i] = bb.short / 32768f
                            engine?.pushInput(fbuf, n)
                        }
                    } catch (_: Exception) {}
                }
                val out = DataOutputStream(s.getOutputStream().buffered(32768))
                while (active) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(chunk); out.flush()
                }
            } catch (e: Throwable) {
                log("injector failed: $e")
                if (active) fail("$e")
            }
        }
    }

    /** Looks for installed apps whose package name contains [filter], in the main user and the Dual Apps user (999). */
    private fun findTarget(shizuku: ShizukuManager, filter: String): Pair<String, Int>? {
        for (user in listOf("0", "999")) {
            val out = shizuku.exec("cmd package list packages -U --user $user $filter") ?: ""
            log("packages matching \"$filter\" (user $user): ${out.trim().replace("\n", " | ").take(400).ifEmpty { "none" }}")
            val hits = out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.mapNotNull { line ->
                val name = line.removePrefix("package:").substringBefore(" ")
                val uid = line.substringAfter("uid:", "").trim().toIntOrNull()
                if (uid != null) name to uid else null
            }.toList()
            if (hits.isNotEmpty()) return hits.firstOrNull { it.first == "com.discord" } ?: hits.first()
        }
        return null
    }

    private fun fail(msg: String) {
        log("FAILED: $msg")
        stop()
        _status.value = "Failed: $msg"
    }

    fun stop() {
        if (active) log("stopped")
        active = false
        queue.clear()
        try { engine?.setExternalInput(false) } catch (_: Throwable) {}
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        if (!_status.value.startsWith("Failed")) _status.value = "Off"
    }

    /** Called from the audio engine with the processed mono float block. */
    fun offer(buf: FloatArray, size: Int) {
        if (!active || sock == null) return
        val bb = ByteBuffer.allocate(4 + size * 2)
        bb.putInt(size)
        val le = ByteBuffer.allocate(size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            val v = buf[i].coerceIn(-1f, 1f)
            le.putShort((v * 32767f).toInt().toShort())
        }
        bb.put(le.array())
        if (!queue.offer(bb.array())) { queue.poll(); queue.offer(bb.array()) }
    }
}
