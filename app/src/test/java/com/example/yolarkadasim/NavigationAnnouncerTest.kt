package com.example.yolarkadasim

import com.example.yolarkadasim.service.NavCue
import com.example.yolarkadasim.service.NavigationAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Güvenlik-kritik anons mantığının testleri: yanlış zamanda ya da hiç yapılmayan
 * bir "ineceğiniz durak geldi" anonsu kullanıcının yanlış durakta inmesi demektir.
 */
class NavigationAnnouncerTest {

    private val FAR = 1000.0
    private val NaN = Double.NaN

    // --- Sıradaki ara durak ---

    @Test
    fun `no cue when far from next stop`() {
        val a = NavigationAnnouncer()
        // cur=1, dest=4, nextIdx=2, hepsi uzak
        assertEquals(emptyList<NavCue>(), a.evaluate(1, 4, 6, FAR, NaN, FAR))
    }

    @Test
    fun `next stop announced once within threshold and not repeated`() {
        val a = NavigationAnnouncer()
        assertEquals(listOf(NavCue.NEXT_STOP), a.evaluate(1, 4, 6, 100.0, NaN, FAR))
        // aynı sıradaki durak: tekrar anons yok
        assertEquals(emptyList<NavCue>(), a.evaluate(1, 4, 6, 90.0, NaN, FAR))
        // ilerledik, yeni sıradaki durak: tekrar anons
        assertEquals(listOf(NavCue.NEXT_STOP), a.evaluate(2, 4, 6, 120.0, NaN, FAR))
    }

    // --- Hedefe yaklaşma ---

    @Test
    fun `approaching destination when next stop is the destination`() {
        val a = NavigationAnnouncer()
        // cur=3, dest=4, nextIdx=4=dest, 120m: yaklaşma ama henüz kapı uyarısı değil
        assertEquals(listOf(NavCue.APPROACHING_DESTINATION), a.evaluate(3, 4, 6, 120.0, NaN, FAR))
    }

    @Test
    fun `approaching and prepare-to-exit both fire under 80m`() {
        val a = NavigationAnnouncer()
        val cues = a.evaluate(3, 4, 6, 70.0, NaN, FAR)
        assertEquals(listOf(NavCue.APPROACHING_DESTINATION, NavCue.PREPARE_TO_EXIT), cues)
        // ikisi de bir daha yapılmamalı
        assertEquals(emptyList<NavCue>(), a.evaluate(3, 4, 6, 60.0, NaN, FAR))
    }

    @Test
    fun `prepare-to-exit fires once even across updates`() {
        val a = NavigationAnnouncer()
        a.evaluate(3, 4, 6, 120.0, NaN, FAR)          // APPROACHING (nextIdx işaretlendi)
        val cues = a.evaluate(3, 4, 6, 75.0, NaN, FAR) // sadece PREPARE bekleniyor
        assertEquals(listOf(NavCue.PREPARE_TO_EXIT), cues)
    }

    // --- Önceki duraktan ayrılma ---

    @Test
    fun `leaving pre-destination stop announced once`() {
        val a = NavigationAnnouncer()
        // cur=3, dest=4 -> preDest=3; önceki duraktan 60m uzaklaşıldı; sıradaki durak uzak
        assertEquals(listOf(NavCue.LEAVING_PRE_DESTINATION), a.evaluate(3, 4, 6, FAR, 60.0, FAR))
        assertEquals(emptyList<NavCue>(), a.evaluate(3, 4, 6, FAR, 70.0, FAR))
    }

    @Test
    fun `no leaving cue while still at pre-destination stop`() {
        val a = NavigationAnnouncer()
        // önceki durakta hâlâ yakınız (40m <= 50m eşiği): ayrılma anonsu yok
        assertEquals(emptyList<NavCue>(), a.evaluate(3, 4, 6, FAR, 40.0, FAR))
    }

    // --- Varış ---

    @Test
    fun `arrived announced once under 35m at destination`() {
        val a = NavigationAnnouncer()
        assertEquals(listOf(NavCue.ARRIVED), a.evaluate(4, 4, 6, FAR, NaN, 20.0))
        assertEquals(emptyList<NavCue>(), a.evaluate(4, 4, 6, FAR, NaN, 10.0))
    }

    @Test
    fun `no arrival when at destination but still far`() {
        val a = NavigationAnnouncer()
        assertEquals(emptyList<NavCue>(), a.evaluate(4, 4, 6, FAR, NaN, 50.0))
    }

    // --- Yön ve sınır durumları ---

    @Test
    fun `reverse direction uses previous index as next stop`() {
        val a = NavigationAnnouncer()
        // dest=1, cur=4 -> hat geriye gidiyor, nextIdx=3 (ara durak)
        assertEquals(listOf(NavCue.NEXT_STOP), a.evaluate(4, 1, 6, 100.0, NaN, FAR))
    }

    @Test
    fun `destination at index zero has no pre-destination cue`() {
        val a = NavigationAnnouncer()
        // dest=0 -> preDest=-1; ayrılma anonsu asla tetiklenmez, ama varış olur
        val cues = a.evaluate(0, 0, 6, FAR, NaN, 20.0)
        assertTrue(NavCue.LEAVING_PRE_DESTINATION !in cues)
        assertTrue(NavCue.ARRIVED in cues)
    }

    @Test
    fun `NaN pre-destination distance never triggers leaving cue`() {
        val a = NavigationAnnouncer()
        // curIdx != preDest olduğunda servis NaN geçer; yanlışlıkla ayrılma anonsu olmamalı
        assertEquals(emptyList<NavCue>(), a.evaluate(1, 4, 6, FAR, NaN, FAR))
    }

    // --- Tam yolculuk: her anons tam bir kez ---

    @Test
    fun `full trip fires each announcement exactly once`() {
        val a = NavigationAnnouncer()
        val counts = mutableMapOf<NavCue, Int>()
        fun run(cur: Int, next: Double, pre: Double, dest: Double) {
            for (c in a.evaluate(cur, 4, 6, next, pre, dest)) counts.merge(c, 1, Int::plus)
        }
        // durak 1 -> 2 arası yaklaşım
        run(1, 500.0, NaN, 900.0)
        run(1, 100.0, NaN, 800.0)   // NEXT_STOP (idx2)
        run(2, 500.0, NaN, 700.0)
        run(2, 100.0, NaN, 600.0)   // NEXT_STOP (idx3)
        // önceki durakta (3), oradan ayrılış
        run(3, 500.0, 20.0, 400.0)  // henüz ayrılmadı (20<=50)
        run(3, 500.0, 60.0, 300.0)  // LEAVING_PRE_DESTINATION
        // hedefe (4) yaklaşım
        run(3, 120.0, 90.0, 200.0)  // APPROACHING_DESTINATION (nextIdx=4)
        run(3, 70.0, 90.0, 120.0)   // PREPARE_TO_EXIT
        // varış
        run(4, 500.0, NaN, 20.0)    // ARRIVED

        assertEquals(2, counts[NavCue.NEXT_STOP])   // iki ara durak
        assertEquals(1, counts[NavCue.LEAVING_PRE_DESTINATION])
        assertEquals(1, counts[NavCue.APPROACHING_DESTINATION])
        assertEquals(1, counts[NavCue.PREPARE_TO_EXIT])
        assertEquals(1, counts[NavCue.ARRIVED])
    }

    @Test
    fun `reset re-enables all announcements for a new trip`() {
        val a = NavigationAnnouncer()
        a.evaluate(4, 4, 6, FAR, NaN, 20.0) // ARRIVED
        assertEquals(emptyList<NavCue>(), a.evaluate(4, 4, 6, FAR, NaN, 20.0))
        a.reset()
        assertEquals(listOf(NavCue.ARRIVED), a.evaluate(4, 4, 6, FAR, NaN, 20.0))
    }
}
