package com.rawkamera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Uygulamanın bütün sesleri; dosya kullanmadan açılışta bir kez üretilir.
 * Telefon sessiz ya da titreşim modundayken hiçbiri çalmaz.
 */
class SoundFx(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val rate = 44100

    private val shutterTrack = track(shutterPcm())
    private val upTrack = track(tone(3600.0, 0.012, 0.20, sustain = false))   // değer artarken: ince
    private val downTrack = track(tone(1700.0, 0.016, 0.24, sustain = false)) // değer azalırken: kalın
    private val openTrack = track(tone(1150.0, 0.030, 0.06, sustain = false))
    private val closeTrack = track(tone(820.0, 0.030, 0.06, sustain = false))
    private val beepTrack = track(tone(2300.0, 0.090, 0.26, sustain = true))

    fun shutter() = play(shutterTrack)
    fun tickUp() = play(upTrack)
    fun tickDown() = play(downTrack)
    fun menuOpen() = play(openTrack)
    fun menuClose() = play(closeTrack)
    fun beep() = play(beepTrack)

    private fun allowed() = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    private fun play(t: AudioTrack?) {
        if (t == null || !allowed()) return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {
        }
    }

    /** Kısa mekanik deklanşör tıkı: iki küçük vuruş (perde açılır, kapanır). */
    private fun shutterPcm(): ShortArray {
        val n = (rate * 0.05).toInt()
        val buf = ShortArray(n)
        val rnd = Random(7)
        fun burst(start: Int, len: Int, amp: Double) {
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val env = exp(-i / (len / 5.0))
                val v = (rnd.nextDouble() * 2 - 1) * env * amp
                buf[idx] = (buf[idx] + (v * 32767).toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        burst(0, 520, 0.55)
        burst((rate * 0.024).toInt(), 420, 0.35)
        return buf
    }

    /** sustain=false: hemen sönen tık; sustain=true: kısa, düz bir bip. */
    private fun tone(freq: Double, seconds: Double, amp: Double, sustain: Boolean): ShortArray {
        val n = (rate * seconds).toInt()
        val total = maxOf(n, 2048)
        val buf = ShortArray(total)
        val attack = rate * 0.0008
        val ramp = rate * 0.005
        for (i in 0 until n) {
            val env = if (sustain) {
                min(1.0, min(i / ramp, (n - i) / ramp))
            } else {
                min(1.0, i / attack) * exp(-i / (n / 5.0))
            }
            val v = sin(2 * PI * freq * i / rate) * env * amp
            buf[i] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buf
    }

    private fun track(pcm: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t
    } catch (e: Exception) {
        null
    }

    fun release() {
        listOf(shutterTrack, upTrack, downTrack, openTrack, closeTrack, beepTrack).forEach { it?.release() }
    }
}
