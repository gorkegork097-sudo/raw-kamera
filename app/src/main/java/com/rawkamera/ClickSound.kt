package com.rawkamera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.exp
import kotlin.random.Random

/**
 * Kısa mekanik deklanşör tıkı: iki küçük vuruş (perde açılır, kapanır).
 * Ses dosyası kullanmadan, açılışta bir kez üretilir.
 */
class ClickSound {
    private val sampleRate = 44100
    private var track: AudioTrack? = null

    init {
        try {
            val n = (sampleRate * 0.05).toInt()
            val buf = ShortArray(n)
            val rnd = Random(7)
            fun burst(start: Int, len: Int, amp: Double) {
                for (i in 0 until len) {
                    val idx = start + i
                    if (idx >= n) break
                    val env = exp(-i / (len / 5.0))
                    val v = (rnd.nextDouble() * 2 - 1) * env * amp
                    val mixed = buf[idx] + (v * 32767).toInt()
                    buf[idx] = mixed.coerceIn(-32768, 32767).toShort()
                }
            }
            burst(0, 520, 0.55)
            burst((sampleRate * 0.024).toInt(), 420, 0.35)

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
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
            t.write(buf, 0, n)
            track = t
        } catch (e: Exception) {
            track = null
        }
    }

    fun play() {
        val t = track ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        track?.release()
        track = null
    }
}
