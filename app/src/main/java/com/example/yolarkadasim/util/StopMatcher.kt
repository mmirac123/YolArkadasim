package com.example.yolarkadasim.util

/**
 * Sesli komuttan durak adı eşleştirme.
 *
 * Türkçe'ye özgü zorlukları ele alır:
 *  - İ/ı büyük-küçük harf kuralları ("ILGAZ" → "ilgaz", "İstasyon" → "istasyon")
 *  - Aksanlı harfler (ç, ğ, ö, ş, ü → c, g, o, s, u) — STT çıktısı tutarsız olabilir
 *  - Ad çekim ekleri ("Kızılay'a gitmek istiyorum" → "Kızılay" durağı)
 *  - STT tanıma hataları (Levenshtein toleransı)
 *
 * Saf mantık: Android bağımlılığı yok, birim testlenebilir.
 */
object StopMatcher {

    /** Komutta niyet bildiren ama durak adı olmayan kelimeler (normalize edilmiş halleri). */
    private val COMMAND_STOPWORDS = setOf(
        "hedef", "hedefi", "hedefim", "hedefe", "gitmek", "git", "gidecegim", "gideyim",
        "istiyorum", "isterim", "ayarla", "yap", "beni", "goture", "gotur",
        "durak", "duragi", "duraga", "duragina", "duragindan",
        "olarak", "lutfen", "sonra", "simdi", "bir", "ve", "ile"
    )

    /** En iyi eşleşme bu puanın altındaysa eşleşme yok sayılır. */
    private const val MATCH_THRESHOLD = 0.75

    /**
     * Türkçe kurallarıyla küçük harfe çevirir, aksanları sadeleştirir,
     * harf/rakam dışını boşluğa indirger.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            // Türkçe büyük harf istisnaları: I → ı, İ → i
            val lower = when (ch) {
                'I' -> 'ı'
                'İ' -> 'i'
                else -> ch.lowercaseChar()
            }
            val mapped = when (lower) {
                'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                else -> lower
            }
            sb.append(if (mapped.isLetterOrDigit()) mapped else ' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun tokenize(text: String): List<String> =
        normalize(text).split(' ').filter { it.length >= 2 }

    /** Klasik dinamik programlama ile düzenleme mesafesi. */
    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * İki normalize token'ın benzerlik puanı [0, 1].
     * Ek toleransı: kısa token uzun token'ın öneki ise ("kizilay" / "kizilaya") yüksek puan.
     */
    private fun tokenScore(a: String, b: String): Double {
        if (a == b) return 1.0
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 4 && longer.startsWith(shorter)) return 0.9
        if (commonPrefixLength(a, b) >= 5) return 0.85
        val maxLen = maxOf(a.length, b.length)
        val lev = levenshtein(a, b)
        if (maxLen >= 5 && lev <= 1) return 0.8
        if (maxLen >= 8 && lev <= 2) return 0.75
        return 0.0
    }

    /**
     * Komuta en iyi uyan durağın indeksini döndürür; güvenilir eşleşme yoksa -1.
     *
     * Puanlama: en güçlü (komut kelimesi, durak kelimesi) çifti esas alınır;
     * durağın birden çok kelimesi komutla eşleşiyorsa küçük bir bonus eklenir
     * ("ümitköy metro" komutu, sadece "metro" içeren duraklardan çok
     * "Ümitköy Metro İstasyonu"nu seçer).
     */
    fun findBestStopIndex(command: String, stopNames: List<String>): Int {
        val cmdTokens = tokenize(command).filter { it !in COMMAND_STOPWORDS }
        if (cmdTokens.isEmpty()) return -1

        var bestIdx = -1
        var bestScore = 0.0
        stopNames.forEachIndexed { idx, name ->
            val stopTokens = tokenize(name)
            if (stopTokens.isEmpty()) return@forEachIndexed

            var bestPair = 0.0
            var matchedStopTokens = 0
            for (st in stopTokens) {
                var tokenBest = 0.0
                for (ct in cmdTokens) {
                    val s = tokenScore(st, ct)
                    if (s > tokenBest) tokenBest = s
                }
                if (tokenBest > 0.0) matchedStopTokens++
                if (tokenBest > bestPair) bestPair = tokenBest
            }
            if (bestPair == 0.0) return@forEachIndexed

            // Bonus tavansız: aynı kelimeyi paylaşan iki duraktan, komutla daha çok
            // kelimesi eşleşen kazanır ("metro" tek başına eşitlik yaratmasın).
            val bonus = 0.05 * (matchedStopTokens - 1).coerceAtLeast(0)
            val score = bestPair + bonus
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) bestIdx else -1
    }
}
