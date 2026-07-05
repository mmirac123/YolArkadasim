package com.example.yolarkadasim

import android.app.Application
import com.example.yolarkadasim.util.CrashReporter

/**
 * Uygulama giriş noktası. Çökme yakalayıcıyı tüm süreç (Activity + Service)
 * için mümkün olan en erken anda kurar.
 */
class YolArkadasimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
