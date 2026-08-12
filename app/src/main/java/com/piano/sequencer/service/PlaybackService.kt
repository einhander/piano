package com.piano.sequencer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.widget.Toast
import com.piano.sequencer.MainActivity
import com.piano.sequencer.NativeEngineBridge

class PlaybackService : Service() {

    private val binder = PlaybackBinder()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        stopForeground(true)
        releaseAudioFocus()
        NativeEngineBridge.nativeStopAudio()
        super.onDestroy()
    }

    fun startAudio() {
        val result = NativeEngineBridge.nativeStartAudio()
        if (result != 0) {
            runOnUiThread {
                Toast.makeText(this, "Start failed: $result", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopAudio() {
        NativeEngineBridge.nativeStopAudio()
    }

    fun isAudioPlaying(): Boolean = NativeEngineBridge.nativeIsAudioPlaying()

    fun openAudio(): Int = NativeEngineBridge.nativeOpenAudio()

    fun initEngine(sampleRate: Int, bufferSize: Int): Boolean =
        NativeEngineBridge.nativeInitEngine(sampleRate, bufferSize)

    fun loadSoundFont(filePath: String): Int = NativeEngineBridge.nativeLoadSoundFont(filePath)

    fun noteOn(channel: Int, note: Int, velocity: Int) =
        NativeEngineBridge.nativeNoteOn(channel, note, velocity)

    fun noteOff(channel: Int, note: Int) = NativeEngineBridge.nativeNoteOff(channel, note)

    fun panic() = NativeEngineBridge.nativePanic()

    fun sendMidiMessage(status: Int, data1: Int, data2: Int) =
        NativeEngineBridge.nativeSendMidiMessage(status, data1, data2)

    fun setMasterGain(gain: Float) = NativeEngineBridge.nativeSetMasterGain(gain)

    fun getUnderrunCount(): Int = NativeEngineBridge.nativeGetUnderrunCount()

    // Export recorded MIDI to file
    fun exportMidiFile(callback: (String?) -> Unit) {
        // Get recorded events from native engine
        // For now, return null — full implementation would pass events through JNI
        callback(null)
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            audioManager?.abandonAudioFocus(it)
        }
        audioFocusRequest = null
    }

    private fun buildNotification(): Notification {
        val channelId = "piano-sequencer-channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Piano Sequencer", NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("Piano Sequencer")
            .setContentText("Audio engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}