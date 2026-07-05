package com.example.yolarkadasim.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists app settings like preferred UI mode and audio levels.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("yolarkadasim_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_STARTUP_MODE = "startup_mode" // true: Modern, false: Kolay
        const val KEY_VOICE_GUIDANCE = "voice_guidance_enabled"
        const val KEY_VOICE_LEVEL = "voice_level"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_EMERGENCY_CONTACT = "emergency_contact"
    }

    fun isModernModePreferred(): Boolean = prefs.getBoolean(KEY_STARTUP_MODE, false) // Default to Kolay Mod (false)
    fun setModernModePreferred(isModern: Boolean) = prefs.edit().putBoolean(KEY_STARTUP_MODE, isModern).apply()

    fun isVoiceGuidanceEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_GUIDANCE, true)
    fun setVoiceGuidanceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_VOICE_GUIDANCE, enabled).apply()

    fun getVoiceLevel(): Int = prefs.getInt(KEY_VOICE_LEVEL, 80) // 0-100
    fun setVoiceLevel(level: Int) = prefs.edit().putInt(KEY_VOICE_LEVEL, level).apply()

    /** Konuşma hızı yüzdesi (50-150). 100 = normal; yaşlı kullanıcılar için düşürülebilir. */
    fun getSpeechRate(): Int = prefs.getInt(KEY_SPEECH_RATE, 90)
    fun setSpeechRate(rate: Int) = prefs.edit().putInt(KEY_SPEECH_RATE, rate.coerceIn(50, 150)).apply()

    /** Yardım butonunun arayacağı yakın telefonu (boş = ayarlanmamış). */
    fun getEmergencyContact(): String = prefs.getString(KEY_EMERGENCY_CONTACT, "") ?: ""
    fun setEmergencyContact(phone: String) = prefs.edit().putString(KEY_EMERGENCY_CONTACT, phone.trim()).apply()
}
