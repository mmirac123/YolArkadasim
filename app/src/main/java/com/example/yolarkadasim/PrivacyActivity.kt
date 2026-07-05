package com.example.yolarkadasim

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.yolarkadasim.databinding.ActivityPrivacyBinding

/**
 * KVKK aydınlatma metni / gizlilik politikası ekranı.
 * Onboarding'den ve Ayarlar sayfasından açılır. İçerik statik metindir.
 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPrivacyBack.setOnClickListener { finish() }
    }
}
