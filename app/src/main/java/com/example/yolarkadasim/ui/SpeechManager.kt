package com.example.yolarkadasim.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.yolarkadasim.R
import com.example.yolarkadasim.data.SettingsStore
import java.util.Locale

/**
 * Activity'nin kendi sesli giriş/çıkışını (TTS + STT) kapsar.
 *
 * Not: Takip sırasındaki anonslar TrackingService'te toplanır (tek konuşma
 * otoritesi); buradaki TTS yalnızca takip yokken ve komut/rehber mesajları için
 * kullanılır. Karar Activity'de kalır ([announce] callback'i uygun yere yönlendirir).
 *
 * @param onCommand  tanınan sesli komut metni (küçük harf, tr-TR)
 * @param announce   kullanıcıya duyurulacak mesaj — Activity bunu tek TTS
 *                   otoritesine (servis ya da yerel TTS) yönlendirir
 */
class SpeechManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val onCommand: (String) -> Unit,
    private val announce: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())

    /** TTS hazır olunca tetiklenir (ör. açılış rehberi başlatmak için). */
    var onTtsReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
            applySpeechRate()
            isTtsReady = true
            onTtsReady?.invoke()
        }
    }

    /** Ayarlardaki konuşma hızını uygular (yaşlı kullanıcı için yavaşlatılabilir). */
    fun applySpeechRate() {
        try { tts?.setSpeechRate(settingsStore.getSpeechRate() / 100f) }
        catch (e: Exception) { Log.e(TAG, "TTS rate fail", e) }
    }

    /** Yerel TTS ile konuşur (takip yokkenki tek çıkış). */
    fun speakLocal(message: String) {
        if (isTtsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "voice_cmd")
    }

    fun stopSpeaking() {
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun setupRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) onCommand(matches[0].lowercase(Locale.forLanguageTag("tr-TR")))
                    else announce(context.getString(R.string.tts_command_not_understood))
                }
                override fun onError(error: Int) {
                    // Görme engelli/yaşlı kullanıcı mikrofonun dinlemediğini göremez;
                    // sessiz kalmak yerine sesli geri bildirim ver.
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> announce(context.getString(R.string.tts_not_heard))
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> { /* geçici durum, anons spam'lemeyelim */ }
                        else -> announce(context.getString(R.string.tts_mic_error))
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (e: Exception) { Log.e(TAG, "STT setup fail", e) }
    }

    /** "Dinliyorum" anonsundan sonra kısa gecikmeyle dinlemeyi başlatır. */
    fun startListening() {
        announce(context.getString(R.string.tts_listening))
        handler.postDelayed({
            try { speechRecognizer?.startListening(speechIntent) } catch (e: Exception) {}
        }, 500)
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        speechRecognizer = null
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
    }

    companion object { private const val TAG = "SpeechManager" }
}
