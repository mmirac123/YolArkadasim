package com.example.yolarkadasim.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cihaz-içi çökme yakalayıcı.
 *
 * Yakalanmayan istisnaları yerel bir dosyaya yazar; hiçbir veri buluta/sunucuya
 * gönderilmez (gizlilik politikasıyla tutarlı). Kullanıcı isterse çökme kaydını
 * Ayarlar’dan kendi başlatarak paylaşır — böylece saha desteği mümkün olur ama
 * sessiz veri aktarımı olmaz.
 */
object CrashReporter {
    private const val FILE = "crash_log.txt"
    private const val MAX_BYTES = 64 * 1024
    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val file = File(app.filesDir, FILE)
                // Dosya şişmesin: sınırı aşınca sıfırla
                if (file.exists() && file.length() > MAX_BYTES) file.delete()
                file.appendText("=== $ts (thread: ${thread.name}) ===\n$sw\n")
            } catch (e: Exception) {
                Log.e(TAG, "crash log write fail", e)
            }
            // Sistemin normal çökme akışını (süreç sonlandırma) bozmadan devret
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Kayıtlı çökme metni; yoksa null. */
    fun lastLog(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }

    fun clear(context: Context) {
        try { File(context.applicationContext.filesDir, FILE).delete() }
        catch (e: Exception) { Log.e(TAG, "clear fail", e) }
    }
}
