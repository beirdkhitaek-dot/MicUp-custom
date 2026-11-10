package com.micplugin.shell

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Runs in the Shizuku "shell" process (started with app_process, NOT inside the app).
 * It registers an Android audio policy that redirects what one chosen app (e.g. Discord)
 * records from the microphone, and plays whatever audio MicUp sends over a local socket
 * into that redirected input. Everything uses reflection because these Android APIs are hidden.
 * Progress is printed as "INJ ..." lines, which the app copies into its log.
 */
object InjectServer {
    private const val RATE = 48000

    private fun say(s: String) { println("INJ $s"); System.out.flush() }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args[0].toInt(), args[1].toInt(), args[2])
        } catch (t: Throwable) {
            say("FATAL $t")
            var c = t.cause
            while (c != null) { say("  cause $c"); c = c.cause }
            System.exit(1)
        }
    }

    private fun run(targetUid: Int, port: Int, token: String) {
        Looper.prepareMainLooper()
        val atc = Class.forName("android.app.ActivityThread")
        val at = atc.getMethod("systemMain").invoke(null)
        val ctx = atc.getMethod("getSystemContext").invoke(at) as Context
        say("context ok, running as uid ${android.os.Process.myUid()}, target uid $targetUid")

        val intType = Int::class.javaPrimitiveType!!
        val ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBC = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixC = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBC = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val polC = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polBC = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

        val rb = ruleBC.getConstructor().newInstance()
        ruleBC.getMethod("setTargetMixRole", intType).invoke(rb, ruleC.getField("MIX_ROLE_INJECTOR").getInt(null))
        ruleBC.getMethod("addMixRule", intType, Any::class.java)
            .invoke(rb, ruleC.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid))
        val rule = ruleBC.getMethod("build").invoke(rb)
        say("mixing rule built")

        val fmt = AudioFormat.Builder().setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val mb = mixBC.getConstructor(ruleC).newInstance(rule)
        mixBC.getMethod("setFormat", AudioFormat::class.java).invoke(mb, fmt)
        mixBC.getMethod("setRouteFlags", intType).invoke(mb, mixC.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        val mix = mixBC.getMethod("build").invoke(mb)
        say("mix built")

        val pb = polBC.getConstructor(Context::class.java).newInstance(ctx)
        polBC.getMethod("setLooper", Looper::class.java).invoke(pb, Looper.getMainLooper())
        polBC.getMethod("addMix", mixC).invoke(pb, mix)
        val policy = polBC.getMethod("build").invoke(pb)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val res = AudioManager::class.java.getMethod("registerAudioPolicy", polC).invoke(am, policy) as Int
        say("registerAudioPolicy result=$res (0 = success)")
        if (res != 0) { say("FATAL policy not registered"); System.exit(2) }

        val track = polC.getMethod("createAudioTrackSource", mixC).invoke(policy, mix) as AudioTrack?
        if (track == null) { say("FATAL createAudioTrackSource returned null"); System.exit(3) }
        track!!.play()
        say("injection track playing, state=${track.playState}")

        Thread({
            try { serve(track, port, token) } catch (t: Throwable) { say("FATAL server: $t"); System.exit(4) }
        }, "inject-socket").start()
        say("READY")
        Looper.loop()
    }

    /** Accepts one MicUp client at a time: token line, then [int length][int16 mono samples]... */
    private fun serve(track: AudioTrack, port: Int, token: String) {
        val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val client = server.accept()
            try {
                val input = DataInputStream(client.getInputStream().buffered())
                val tk = ByteArray(token.length)
                input.readFully(tk)
                if (String(tk) != token) { say("client rejected (bad token)"); client.close(); continue }
                say("client connected")
                var frames = 0L
                var peak = 0
                while (true) {
                    val n = input.readInt()
                    if (n <= 0 || n > 65536) throw java.io.IOException("bad length $n")
                    val raw = ByteArray(n * 2)
                    input.readFully(raw)
                    val stereo = ShortArray(n * 2)
                    for (i in 0 until n) {
                        val v = ((raw[2 * i + 1].toInt() shl 8) or (raw[2 * i].toInt() and 0xFF)).toShort()
                        stereo[2 * i] = v; stereo[2 * i + 1] = v
                        val av = Math.abs(v.toInt()); if (av > peak) peak = av
                    }
                    track.write(stereo, 0, stereo.size)
                    frames += n
                    if (frames % (RATE * 5L) < n) {
                        say("injected $frames frames, peak ${"%.2f".format(peak / 32768.0)}, underruns ${track.underrunCount}, track state ${track.playState}")
                        peak = 0
                    }
                }
            } catch (e: Exception) {
                say("client gone: $e")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }
}
