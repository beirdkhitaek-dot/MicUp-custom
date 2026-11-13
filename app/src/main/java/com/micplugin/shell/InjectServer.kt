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
            run(args[0].toInt(), args[1].toInt(), args[2], args.getOrElse(3) { "uid" } == "input")
        } catch (t: Throwable) {
            say("FATAL $t")
            var c = t.cause
            while (c != null) { say("  cause $c"); c = c.cause }
            System.exit(1)
        }
    }

    private fun run(targetUid: Int, port: Int, token: String, byInput: Boolean) {
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
        if (byInput) {
            // Redirect every recording that uses the VoiceCommunication source (what call apps use).
            val ab = android.media.AudioAttributes.Builder()
            android.media.AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", intType).invoke(ab, 7)
            ruleBC.getMethod("addMixRule", intType, Any::class.java)
                .invoke(rb, ruleC.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null), ab.build())
            say("rule: capture preset VOICE_COMMUNICATION")
        } else {
            ruleBC.getMethod("addMixRule", intType, Any::class.java)
                .invoke(rb, ruleC.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid))
            say("rule: uid $targetUid")
        }
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
        Thread({ watchRecorders() }, "inject-watch").apply { isDaemon = true }.start()
        if (byInput) Thread({ selfTest() }, "inject-selftest").start()
        say("READY")
        Looper.loop()
    }

    private fun sh(cmd: String): List<String> = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor(); out
    } catch (e: Exception) { listOf("failed: $e") }

    /** Logs every NEW recording start by an app other than MicUp, so we see exactly when Discord starts its mic. */
    private fun watchRecorders() {
        val seen = HashSet<String>()
        var flingerDumps = 0
        while (true) {
            for (l in sh("dumpsys audio | grep -a -E 'rec (update|start|stop)' | grep -a -v HOTWORD | grep -a -v 'pack:com.micplugin' | tail -6")) {
                val t = l.trim()
                if (t.isNotEmpty() && seen.add(t)) {
                    say("REC EVENT $t")
                    if (flingerDumps < 3 && t.contains(" rec update") || t.contains(" rec start")) {
                        flingerDumps++
                        say("  input threads:")
                        sh("dumpsys media.audio_flinger > /data/local/tmp/micup_af.txt 2>&1; grep -a -i -A14 'Input thread' /data/local/tmp/micup_af.txt | grep -a -i -E 'Input thread|Sample rate|Channel count|HAL format|Frame count|source|Active|uid' | head -30")
                            .forEach { say("    " + it.trim().take(150)) }
                    }
                }
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Records as the shell user with the same source the rule redirects (VoiceCommunication). If the
     * redirect works, this recording contains the audio MicUp injects (your processed voice).
     */
    private fun selfTest() {
        try {
            Thread.sleep(6000)
            val rate = 48000
            val minBuf = android.media.AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = android.media.AudioRecord(7, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
            say("SELFTEST recorder state=${rec.state} (1 = ready)")
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { say("SELFTEST failed: recorder not initialized"); return }
            rec.startRecording()
            say("SELFTEST recording started (recordingState=${rec.recordingState})")
            val buf = ShortArray(4800)
            var peak = 0; var n = 0
            var last = System.currentTimeMillis()
            val end = last + 45000
            while (System.currentTimeMillis() < end) {
                val r = rec.read(buf, 0, buf.size)
                if (r < 0) { say("SELFTEST read error $r"); break }
                for (i in 0 until r) { val a = Math.abs(buf[i].toInt()); if (a > peak) peak = a }
                n += r
                if (System.currentTimeMillis() - last >= 2000) {
                    say("SELFTEST heard $n samples, peak ${"%.2f".format(peak / 32768.0)}")
                    peak = 0; n = 0; last = System.currentTimeMillis()
                }
            }
            rec.stop(); rec.release()
            say("SELFTEST finished")
        } catch (t: Throwable) { say("SELFTEST error: $t") }
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
