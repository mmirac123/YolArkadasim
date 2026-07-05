package com.example.yolarkadasim

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.yolarkadasim.data.SettingsStore
import com.example.yolarkadasim.databinding.ActivityOnboardingBinding
import java.util.Locale

/**
 * İlk açılışta bir kez gösterilen tanıtım/izin gerekçe ekranı.
 *
 * Bu kitle için sesli-öncelikli: metin ekranda büyük puntoyla dururken aynı
 * gerekçe TTS ile de okunur (görme engelli kullanıcı da neden izin istendiğini
 * duyar). Tek büyük buton izinleri ister ve ana ekrana döner.
 */
class OnboardingActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var settingsStore: SettingsStore
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsStore = SettingsStore(this)
        tts = TextToSpeech(this, this)

        binding.btnOnboardingContinue.setOnClickListener {
            settingsStore.markOnboarded() // reddetse bile bir daha gösterme
            try { tts?.stop() } catch (e: Exception) { Log.e(TAG, "tts stop fail", e) }
            requestAllPermissions()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
            try { tts?.setSpeechRate(settingsStore.getSpeechRate() / 100f) } catch (e: Exception) { Log.e(TAG, "tts rate fail", e) }
            if (settingsStore.isVoiceGuidanceEnabled()) {
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        tts?.speak(getString(R.string.onboarding_body), TextToSpeech.QUEUE_FLUSH, null, "onboarding")
                    }
                }, 800)
            }
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_ONBOARDING_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // İzin verilsin veya verilmesin ana ekrana dön; MainActivity eksik izinleri
        // takip anında sesli olarak yeniden ele alır (sessiz başarısızlık kalkanı).
        if (requestCode == REQ_ONBOARDING_PERMS) finish()
    }

    override fun onDestroy() {
        try { tts?.stop() } catch (e: Exception) { Log.e(TAG, "tts stop fail", e) }
        try { tts?.shutdown() } catch (e: Exception) { Log.e(TAG, "tts shutdown fail", e) }
        super.onDestroy()
    }

    companion object {
        private const val REQ_ONBOARDING_PERMS = 2001
        private const val TAG = "OnboardingActivity"
    }
}
