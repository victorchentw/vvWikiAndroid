package com.victor.vvwiki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.max

/** Foreground TTS playback with notification controls for the current selection. */
class TtsService : Service(), TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var chunks: List<String> = emptyList()
    private var currentIndex = 0
    private var paused = false
    private var speed = 1.0f
    private var activeUtterance = ""
    private var chunkStartedAt = 0L
    private var notificationStarted = false

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            if (utteranceId == activeUtterance) {
                chunkStartedAt = SystemClock.elapsedRealtime()
                updateNotification()
            }
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId != activeUtterance || paused) return
            currentIndex++
            if (currentIndex >= chunks.size) finishPlayback() else speakCurrent()
        }

        override fun onError(utteranceId: String) {
            if (utteranceId == activeUtterance) {
                updateNotification("TTS error")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val preferredEngine = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        ).firstOrNull { it.serviceInfo?.packageName == GOOGLE_TTS_PACKAGE }
            ?.serviceInfo?.packageName
        textToSpeech = TextToSpeech(this, this, preferredEngine).also {
            it.setOnUtteranceProgressListener(progressListener)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> beginPlayback(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            ACTION_TOGGLE -> togglePause()
            ACTION_BACK -> seekBy(-10f)
            ACTION_FORWARD -> seekBy(10f)
            ACTION_SPEED -> toggleSpeed()
            ACTION_CLOSE -> finishPlayback()
        }
        return START_NOT_STICKY
    }

    private fun beginPlayback(text: String) {
        val value = text.trim()
        if (value.isBlank()) {
            finishPlayback()
            return
        }
        chunks = splitIntoChunks(value)
        currentIndex = 0
        paused = false
        speed = 1.0f
        startAsForeground("Preparing…")
        if (ttsReady) speakCurrent()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateNotification("No usable TTS engine")
            return
        }
        ttsReady = true
        textToSpeech?.setOnUtteranceProgressListener(progressListener)
        if (chunks.isNotEmpty() && !paused) speakCurrent()
    }

    private fun speakCurrent() {
        val engine = textToSpeech ?: return
        if (!ttsReady || currentIndex !in chunks.indices || paused) return
        val text = chunks[currentIndex]
        val locale = if (text.any { it in '\u4e00'..'\u9fff' }) {
            Locale.TRADITIONAL_CHINESE
        } else {
            Locale.getDefault()
        }
        var language = engine.setLanguage(locale)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            language = engine.setLanguage(Locale.US)
        }
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            updateNotification("TTS language data unavailable")
            return
        }
        engine.setSpeechRate(speed)
        activeUtterance = "vvwiki-${System.nanoTime()}"
        chunkStartedAt = SystemClock.elapsedRealtime()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtterance)
        if (result == TextToSpeech.ERROR) updateNotification("TTS could not read selection") else updateNotification()
    }

    private fun togglePause() {
        if (!ttsReady || chunks.isEmpty()) return
        if (paused) {
            paused = false
            speakCurrent()
        } else {
            paused = true
            textToSpeech?.stop()
            updateNotification()
        }
    }

    private fun toggleSpeed() {
        speed = if (speed == 1.0f) 1.5f else 1.0f
        if (!paused && chunks.isNotEmpty()) {
            textToSpeech?.stop()
            speakCurrent()
        } else {
            updateNotification()
        }
    }

    /** Seek approximately by ten seconds using measured text-duration estimates. */
    private fun seekBy(deltaSeconds: Float) {
        if (chunks.isEmpty()) return
        val target = (playbackPositionSeconds() + deltaSeconds).coerceIn(0f, totalDurationSeconds())
        var elapsed = 0f
        var targetIndex = chunks.lastIndex
        chunks.forEachIndexed { index, chunk ->
            val duration = estimateSeconds(chunk)
            if (target <= elapsed + duration) {
                targetIndex = index
                return@forEachIndexed
            }
            elapsed += duration
        }
        currentIndex = targetIndex
        paused = false
        textToSpeech?.stop()
        speakCurrent()
    }

    private fun playbackPositionSeconds(): Float {
        val before = chunks.take(currentIndex).sumOf { estimateSeconds(it).toDouble() }.toFloat()
        val current = if (!paused && chunkStartedAt > 0L) {
            (SystemClock.elapsedRealtime() - chunkStartedAt) / 1000f
        } else {
            0f
        }
        return (before + current).coerceIn(0f, totalDurationSeconds())
    }

    private fun totalDurationSeconds(): Float = chunks.sumOf { estimateSeconds(it).toDouble() }.toFloat()

    private fun estimateSeconds(text: String): Float {
        val charsPerSecond = if (text.any { it in '\u4e00'..'\u9fff' }) 5f else 14f
        return max(.5f, text.length / charsPerSecond / speed)
    }

    private fun splitIntoChunks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?。！？；;])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return sentences.flatMap { sentence ->
            if (sentence.length <= MAX_CHARS_PER_CHUNK) listOf(sentence)
            else sentence.chunked(MAX_CHARS_PER_CHUNK)
        }
    }

    private fun startAsForeground(message: String) {
        notificationStarted = true
        startForeground(NOTIFICATION_ID, buildNotification(message))
    }

    private fun updateNotification(message: String? = null) {
        if (!notificationStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String? = null): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("vv知識酷 TTS")
            .setContentText(message?.takeUnless { it == "Preparing…" })
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, "", ACTION_TOGGLE, REQUEST_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_rew, "", ACTION_BACK, REQUEST_BACK))
            .addAction(action(android.R.drawable.ic_media_ff, "", ACTION_FORWARD, REQUEST_FORWARD))
            .addAction(action(R.drawable.ic_speed, "", ACTION_SPEED, REQUEST_SPEED))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "", ACTION_CLOSE, REQUEST_CLOSE))
            .build()
    }

    private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            icon,
            title,
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, TtsService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun finishPlayback() {
        textToSpeech?.stop()
        chunks = emptyList()
        currentIndex = 0
        paused = false
        notificationStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "vv知識酷 TTS", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Controls for reading selected text aloud"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        notificationStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SPEAK = "com.victor.vvwiki.tts.SPEAK"
        private const val ACTION_TOGGLE = "com.victor.vvwiki.tts.TOGGLE"
        private const val ACTION_BACK = "com.victor.vvwiki.tts.BACK"
        private const val ACTION_FORWARD = "com.victor.vvwiki.tts.FORWARD"
        private const val ACTION_SPEED = "com.victor.vvwiki.tts.SPEED"
        private const val ACTION_CLOSE = "com.victor.vvwiki.tts.CLOSE"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "vvwiki-tts"
        private const val NOTIFICATION_ID = 1701
        private const val MAX_CHARS_PER_CHUNK = 240
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val REQUEST_TOGGLE = 1702
        private const val REQUEST_BACK = 1703
        private const val REQUEST_FORWARD = 1704
        private const val REQUEST_SPEED = 1705
        private const val REQUEST_CLOSE = 1706

        fun speakIntent(context: android.content.Context, text: String): Intent =
            Intent(context, TtsService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
    }
}
