package com.example.yolarkadasim.service

/** Yolculuk anonslarının türü — saf karar mantığının çıktısı. */
enum class NavCue {
    NEXT_STOP,                 // sıradaki ara durak
    APPROACHING_DESTINATION,   // sıradaki durak hedef durağı
    PREPARE_TO_EXIT,           // hedefe çok yaklaşıldı, kapıya doğru ilerle
    LEAVING_PRE_DESTINATION,   // hedeften önceki duraktan ayrıldı, hazırlan
    ARRIVED                    // hedef durağa varıldı
}

/**
 * Yolculuk anonslarının SAF karar mantığı: hangi anonsun ne zaman ve yalnızca
 * bir kez yapılacağına karar verir.
 *
 * Bilinçli olarak Android/native bağımlılığı yoktur (getString, TTS, titreşim ve
 * haversine mesafe hesabı hepsi dışarıda kalır). Böylece güvenlik-kritik "ne zaman
 * ineceğiniz durak geldi denir" mantığı birim testlerle kilitlenebilir.
 *
 * [TrackingService] mesafeleri (native haversine ile) hesaplar, buraya verir ve
 * dönen [NavCue] listesini sese/titreşime/bildirime çevirir.
 */
class NavigationAnnouncer {

    var lastAnnouncedNextIdx = -1
        private set
    var announcedTargetReminder = false
        private set
    var announcedLeavingPreDest = false
        private set
    var announcedArrival = false
        private set

    /** Yeni yolculuk ya da değişen hedef için anons durumunu sıfırlar. */
    fun reset() {
        lastAnnouncedNextIdx = -1
        announcedTargetReminder = false
        announcedLeavingPreDest = false
        announcedArrival = false
    }

    /**
     * Bu konum güncellemesinde yapılacak anonsları (sırayla) döndürür.
     *
     * @param curIdx           motorun bildirdiği aktif durak indeksi
     * @param destinationIndex hedef durak indeksi
     * @param stopCount        hattın durak sayısı
     * @param distToNext       kullanıcıdan sıradaki durağa mesafe (m)
     * @param distToPreDest    hedeften önceki durağa mesafe (m); yalnızca
     *                         curIdx == hedef-1 iken anlamlıdır, aksi halde NaN geçilebilir
     * @param distToDest       kullanıcıdan hedef durağa mesafe (m)
     */
    fun evaluate(
        curIdx: Int,
        destinationIndex: Int,
        stopCount: Int,
        distToNext: Double,
        distToPreDest: Double,
        distToDest: Double
    ): List<NavCue> {
        val cues = ArrayList<NavCue>(2)

        // Güzergâh yönü: hedef ileride ise sıradaki durak cur+1, geride ise cur-1
        val nextIdx = if (destinationIndex >= curIdx) curIdx + 1 else curIdx - 1
        if (nextIdx in 0 until stopCount) {
            if (distToNext <= NEAR_STOP_M && lastAnnouncedNextIdx != nextIdx) {
                cues += if (nextIdx == destinationIndex) NavCue.APPROACHING_DESTINATION else NavCue.NEXT_STOP
                lastAnnouncedNextIdx = nextIdx
            }
            if (nextIdx == destinationIndex && distToNext <= PREPARE_EXIT_M && !announcedTargetReminder) {
                cues += NavCue.PREPARE_TO_EXIT
                announcedTargetReminder = true
            }
        }

        val preDest = if (destinationIndex > 0) destinationIndex - 1 else -1
        if (curIdx == preDest && distToPreDest > LEFT_STOP_M && !announcedLeavingPreDest) {
            cues += NavCue.LEAVING_PRE_DESTINATION
            announcedLeavingPreDest = true
        }

        if (curIdx == destinationIndex && distToDest < ARRIVED_M && !announcedArrival) {
            cues += NavCue.ARRIVED
            announcedArrival = true
        }
        return cues
    }

    companion object {
        const val NEAR_STOP_M = 150.0    // sıradaki durak anonsu eşiği
        const val PREPARE_EXIT_M = 80.0  // "kapıya ilerleyin" eşiği
        const val LEFT_STOP_M = 50.0     // önceki duraktan ayrıldı eşiği
        const val ARRIVED_M = 35.0       // varış eşiği
    }
}
