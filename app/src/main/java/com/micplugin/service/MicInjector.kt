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

    @Volatile private var active = false
    @Volatile private var proc: Process? = null
    @Volatile private var sock: Socket? = null
    private val queue = ArrayBlockingQueue<ByteArray>(8)

    val isActive: Boolean get() = active

    fun start(context: Context, shizuku: ShizukuManager, targetPackage: String) {
        if (active) return
        active = true
        _status.value = "Starting…"
        val apk = context.applicationInfo.sourceDir
        thread(name = "MicInjector-start") {
            try {
                val listing = shizuku.exec("cmd package list packages -U $targetPackage") ?: ""
                val uid = listing.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("package:$targetPackage ") }
                    ?.substringAfter("uid:")?.trim()?.toIntOrNull()
                if (uid == null) { fail("$targetPackage is not installed (or Shizuku cannot see it)"); return@thread }

                val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                shizuku.exec("pkill -f micup_inject")
                val p = shizuku.spawn(
                    "CLASSPATH=$apk exec app_process /system/bin --nice-name=micup_inject " +
                        "com.micplugin.shell.InjectServer $uid $PORT $token"
                )
                if (p == null) { fail("Could not start the Shizuku helper"); return@thread }
                proc = p

                val ready = CountDownLatch(1)
                var failure: String? = null
                thread(name = "MicInjector-out") {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            Log.i(TAG, line)
                            if (line == "INJ READY") ready.countDown()
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
                _status.value = "Active — Discord should now hear MicUp's voice ($targetPackage)"
                val out = DataOutputStream(s.getOutputStream().buffered(32768))
                while (active) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(chunk); out.flush()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "injector failed: $e")
                if (active) fail("$e")
            }
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "FAILED: $msg")
        stop()
        _status.value = "Failed: $msg"
    }

    fun stop() {
        active = false
        queue.clear()
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
