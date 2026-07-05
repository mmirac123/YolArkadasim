package com.example.yolarkadasim

import com.example.yolarkadasim.util.StopMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class StopMatcherTest {

    private val stops = listOf(
        "Kızılay Meydanı",
        "Ümitköy Metro İstasyonu",
        "Bahçelievler 7. Cadde",
        "Söğütözü Köprüsü",
        "AŞTİ Terminal",
        "Harikalar Diyarı"
    )

    // --- normalize ---

    @Test
    fun `normalize handles Turkish dotted and dotless I`() {
        assertEquals("ilgaz", StopMatcher.normalize("ILGAZ"))
        assertEquals("istasyon", StopMatcher.normalize("İSTASYON"))
        assertEquals("kizilay", StopMatcher.normalize("Kızılay"))
    }

    @Test
    fun `normalize strips diacritics and punctuation`() {
        assertEquals("umitkoy metro istasyonu", StopMatcher.normalize("Ümitköy Metro İstasyonu"))
        assertEquals("sogutozu koprusu", StopMatcher.normalize("Söğütözü Köprüsü"))
        assertEquals("sokak a", StopMatcher.normalize("Sokak'a"))
    }

    // --- levenshtein ---

    @Test
    fun `levenshtein basics`() {
        assertEquals(0, StopMatcher.levenshtein("kizilay", "kizilay"))
        assertEquals(1, StopMatcher.levenshtein("kizilay", "kizilai"))
        assertEquals(3, StopMatcher.levenshtein("abc", ""))
    }

    // --- findBestStopIndex ---

    @Test
    fun `exact stop name matches`() {
        assertEquals(0, StopMatcher.findBestStopIndex("kızılay meydanı hedef yap", stops))
    }

    @Test
    fun `Turkish dative suffix matches stop`() {
        // "Kızılay'a gitmek istiyorum" → Kızılay Meydanı
        assertEquals(0, StopMatcher.findBestStopIndex("kızılaya gitmek istiyorum", stops))
    }

    @Test
    fun `suffixed multi word target picks the right stop`() {
        assertEquals(1, StopMatcher.findBestStopIndex("hedefim ümitköy metroya gitmek", stops))
    }

    @Test
    fun `minor STT misrecognition still matches`() {
        // tek harf hatası: "harikalar" → "harıkalar"
        assertEquals(5, StopMatcher.findBestStopIndex("harıkalar diyarına git", stops))
    }

    @Test
    fun `unrelated command returns no match`() {
        assertEquals(-1, StopMatcher.findBestStopIndex("bugün hava çok güzel", stops))
    }

    @Test
    fun `stopwords alone do not match`() {
        assertEquals(-1, StopMatcher.findBestStopIndex("hedef ayarla lütfen", stops))
    }

    @Test
    fun `shared word prefers stop with more matching words`() {
        val ambiguous = listOf("Batıkent Metro", "Ümitköy Metro İstasyonu")
        assertEquals(1, StopMatcher.findBestStopIndex("ümitköy metro istasyonuna git", ambiguous))
    }
}
