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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.piano.sequencer.AppLogger
import com.piano.sequencer.MainActivity
import com.piano.sequencer.NativeEngineBridge

class PlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    private val binder = PlaybackBinder()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService

        fun setPolyphony(value: Int) = this@PlaybackService.setPolyphony(value)
        fun getPolyphony(): Int = this@PlaybackService.getPolyphony()
        fun setMasterGain(gain: Float) = this@PlaybackService.setMasterGain(gain)
        fun getMasterGain(): Float = this@PlaybackService.getMasterGain()
        fun loadSoundFont(filePath: String): Int = this@PlaybackService.loadSoundFont(filePath)
        fun unloadSoundFonts() = this@PlaybackService.unloadSoundFonts()
        fun getSoundFontCount(): Int = this@PlaybackService.getSoundFontCount()
        fun getSoundFontPath(): String = this@PlaybackService.getSoundFontPath()
        fun isAudioPlaying(): Boolean = this@PlaybackService.isAudioPlaying()
        fun getInstruments(): String = this@PlaybackService.getInstruments()
        fun setChannelProgram(channel: Int, bank: Int, program: Int): Boolean =
            this@PlaybackService.setChannelProgram(channel, bank, program)
        fun getChannelProgram(channel: Int): Int = this@PlaybackService.getChannelProgram(channel)

        // Recording control
        fun startRecording() = this@PlaybackService.startRecording()
        fun stopRecording() = this@PlaybackService.stopRecording()
        fun isRecording(): Boolean = this@PlaybackService.isRecording()
        fun getBPM(): Double = this@PlaybackService.getBPM()
        fun getPpq(): Int = this@PlaybackService.getPpq()

        // MIDI file slot playback
        fun loadMidiFileSlot(slot: Int, filePath: String, tempo: Double, loop: Boolean, channel: Int, startAfterLoad: Boolean): Int =
            this@PlaybackService.loadMidiFileSlot(slot, filePath, tempo, loop, channel, startAfterLoad)

        fun preloadMidiFile(filePath: String): Int =
            this@PlaybackService.preloadMidiFile(filePath)

        fun startMidiFileSlot(slot: Int): Int =
            this@PlaybackService.startMidiFileSlot(slot)

        fun stopMidiFileSlot(slot: Int): Int =
            this@PlaybackService.stopMidiFileSlot(slot)

        fun isMidiFileSlotPlaying(slot: Int): Boolean =
            this@PlaybackService.isMidiFileSlotPlaying(slot)

        fun setMidiFileSlotLoop(slot: Int, loop: Boolean) =
            this@PlaybackService.setMidiFileSlotLoop(slot, loop)

        fun setMidiFileSlotTempo(slot: Int, bpm: Double) =
            this@PlaybackService.setMidiFileSlotTempo(slot, bpm)

        fun getMidiFileSlotInfo(slot: Int): String =
            this@PlaybackService.getMidiFileSlotInfo(slot)

        fun freeMidiFileSlot(slot: Int) =
            this@PlaybackService.freeMidiFileSlot(slot)

        // Recorded MIDI export
        fun getRecordedEventCount(): Int =
            this@PlaybackService.getRecordedEventCount()

        fun writeRecordedMidiFile(filePath: String, ppq: Int, tempo: Int): Boolean =
            this@PlaybackService.writeRecordedMidiFile(filePath, ppq, tempo)
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
            AppLogger.error("PlaybackService", "Start audio failed: $result")
            mainHandler.post {
                Toast.makeText(this, "Start failed: $result", Toast.LENGTH_SHORT).show()
            }
        } else {
            AppLogger.info("PlaybackService", "Audio started")
        }
    }

    fun isAudioPlaying(): Boolean = NativeEngineBridge.nativeIsAudioPlaying()

    fun isEngineInitialized(): Boolean = NativeEngineBridge.nativeIsEngineInitialized()

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

    fun setPolyphony(value: Int) = NativeEngineBridge.nativeSetPolyphony(value)
    fun getPolyphony(): Int = NativeEngineBridge.nativeGetPolyphony()
    fun getMasterGain(): Float = NativeEngineBridge.nativeGetMasterGain()
    fun unloadSoundFonts() = NativeEngineBridge.nativeUnloadSoundFonts()
    fun getSoundFontCount(): Int = NativeEngineBridge.nativeGetSoundFontCount()
    fun getSoundFontPath(): String = NativeEngineBridge.nativeGetSoundFontPath()

    // Instrument assignment (16 MIDI channels)
    fun getInstruments(): String = NativeEngineBridge.nativeGetInstruments()
    fun setChannelProgram(channel: Int, bank: Int, program: Int): Boolean =
        NativeEngineBridge.nativeSetChannelProgram(channel, bank, program)
    fun getChannelProgram(channel: Int): Int = NativeEngineBridge.nativeGetChannelProgram(channel)

    // Recording control
    fun startRecording() = NativeEngineBridge.nativeStartRecording()
    fun stopRecording() = NativeEngineBridge.nativeStopRecording()
    fun isRecording(): Boolean = NativeEngineBridge.nativeIsRecording()
    fun setRecordArmed(trackId: Int, armed: Boolean) =
        NativeEngineBridge.nativeSetRecordArmed(trackId, armed)
    fun setOverdub(overdub: Boolean) = NativeEngineBridge.nativeSetOverdub(overdub)

    // Transport getters (for export)
    fun getBPM(): Double = NativeEngineBridge.nativeGetBPM()
    fun getPpq(): Int = NativeEngineBridge.nativeGetPpq()

    // MIDI file slot playback
    // NOTE: call from a worker thread, never the main thread.
    // loadMidiFileSlot does blocking file I/O + parse (tens of ms).
    fun loadMidiFileSlot(slot: Int, filePath: String, tempo: Double, loop: Boolean, channel: Int, startAfterLoad: Boolean): Int =
        NativeEngineBridge.nativeLoadMidiFileSlot(slot, filePath, tempo, loop, channel, startAfterLoad)

    fun preloadMidiFile(filePath: String): Int =
        NativeEngineBridge.nativePreloadMidiFile(filePath)

    fun startMidiFileSlot(slot: Int): Int =
        NativeEngineBridge.nativeStartMidiFileSlot(slot)

    fun stopMidiFileSlot(slot: Int): Int =
        NativeEngineBridge.nativeStopMidiFileSlot(slot)

    fun isMidiFileSlotPlaying(slot: Int): Boolean =
        NativeEngineBridge.nativeIsMidiFileSlotPlaying(slot)

    fun setMidiFileSlotLoop(slot: Int, loop: Boolean) =
        NativeEngineBridge.nativeSetMidiFileSlotLoop(slot, loop)

    fun setMidiFileSlotTempo(slot: Int, bpm: Double) =
        NativeEngineBridge.nativeSetMidiFileSlotTempo(slot, bpm)

    fun getMidiFileSlotInfo(slot: Int): String =
        NativeEngineBridge.nativeGetMidiFileSlotInfo(slot)

    fun freeMidiFileSlot(slot: Int) =
        NativeEngineBridge.nativeFreeMidiFileSlot(slot)

    // Timing trace — cheap atomic reads, safe from any thread (unlike the heavy
    // slot operations above, which must run on a worker thread).
    fun getMidiFileSlotLoadFrame(slot: Int): Long =
        NativeEngineBridge.nativeGetMidiFileSlotLoadFrame(slot)

    fun getMidiFileSlotStartFrame(slot: Int): Long =
        NativeEngineBridge.nativeGetMidiFileSlotStartFrame(slot)

    fun getFramePosition(): Long =
        NativeEngineBridge.nativeGetFramePosition()

    // Recorded MIDI export
    // NOTE: call from a worker thread, never the main thread.
    // Recorded ticks follow the transport bpm/ppq; the export tempo param must match.
    fun getRecordedEventCount(): Int =
        NativeEngineBridge.nativeGetRecordedEventCount()

    fun writeRecordedMidiFile(filePath: String, ppq: Int, tempo: Int): Boolean =
        NativeEngineBridge.nativeWriteRecordedMidiFile(filePath, ppq, tempo)

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
                .setOnAudioFocusChangeListener(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager?.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(this)
        }
        audioFocusRequest = null
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // Handle audio focus changes
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

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("Piano Sequencer")
            .setContentText("Audio engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopIntent).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        // Never sticky: a sticky-recreated service (process death, null intent)
        // cannot produce audio on its own — engine init only happens in
        // onServiceConnected — so it would hold audio focus and show "running"
        // while silent. The activity's startForegroundService (re)starts the
        // service whenever the app is present; surviving activity re-creation
        // depends on the started state, not stickiness.
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.piano.sequencer.action.STOP"
    }
}