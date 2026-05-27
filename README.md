# Yol Arkadaşım (Transit Companion) 🚌

**Yol Arkadaşım**, özellikle görme engelli ve yaşlı bireylerin toplu taşıma kullanımını kolaylaştırmak amacıyla geliştirilmiş, yüksek hassasiyetli bir Android navigasyon asistanıdır. Uygulama, kullanıcının seçtiği durak için gerçek zamanlı GPS takibi yapar ve kademeli sesli anonslarla yolculuk boyunca rehberlik eder.

## ✨ Temel Özellikler

### 📍 Hassas Takip Sistemi
- **C++ Navigasyon Motoru:** JNI altyapısı ile yüksek performanslı mesafe ve sapma hesaplamaları.
- **Strict Sequence Tracking:** Rota üzerindeki durakları sırasıyla takip eder, dairesel (ring) hatlarda veya birbirine yakın duraklarda yanlış eşleşmeyi önler.
- **Kısa Mesafe Optimizasyonu:** Birbirine çok yakın duraklarda (Örn: Özge Sokak - Emre Sokak) hassas "dur-kalk" tespiti.

### 🔊 Sesli Rehberlik ve Erişilebilirlik
- **Kademeli Anonslar:**
  - Bir önceki duraktan hareket edildiğinde "Hazırlanın" uyarısı.
  - 150m kala "Yaklaştınız" anonsu.
  - 80m kala son kapı hatırlatıcısı.
  - 35m kala "Geldiniz" bildirimi.
- **Sesli Önizleme:** Durak ve hat listesinde seçim yapmadan önce hoparlör ikonuyla isimleri dinleyebilme.
- **Haptik Geri Bildirim:** Kritik anlarda (varış, rota sapması) farklı titreşim ritimleriyle uyarı.

### 📱 Çift Modlu Arayüz
- **Erişilebilir Mod:** Görme engelliler ve yaşlılar için devasa butonlar, yüksek kontrastlı renkler ve minimum karmaşa.
- **Modern Mod:** Material Design 3 standartlarında şık kartlar, canlı ilerleme çubuğu (Progress Bar) ve modern detay paneli.

### 🌟 Favoriler ve Özelleştirme
- **Favori Hatlar:** Sık kullanılan hatları listenin en üstüne sabitleme.
- **Özel İsimli Duraklar:** Duraklara "Evim", "İş", "Okul" gibi takma adlar verebilme.
- **Akıllı Sıralama:** Favori durakların hat listesinde en üstte, özel isimleriyle görünmesi.

### ⚙️ Arka Plan Desteği
- **Foreground Service:** Uygulama kapalıyken veya telefon kilitliyken kesintisiz GPS takibi.
- **Canlı Bildirim:** Bildirim panelinde sıradaki durak ve kalan mesafeyi gösteren sürekli güncellenen kart.

## 🛠 Teknik Mimari
- **Dil:** Kotlin (UI & Service), C++ (Native Engine)
- **Konum:** Google Play Services Fused Location Provider
- **Veri:** JSON tabanlı yerel rota/durak veritabanı (EGO Stop IDs uyumlu)
- **Persist:** SharedPreferences (Favoriler ve Son Hedefler için)

## 🚀 Kurulum
1. Projeyi klonlayın: `git clone https://github.com/mmirac123/YolArkadasim.git`
2. Android Studio ile açın.
3. Projeyi Gradle ile senkronize edin.
4. `app` modülünü cihazınıza yükleyin.

## 📄 Lisans
Bu proje eğitim ve sosyal sorumluluk amacıyla geliştirilmiştir. Tüm hakları saklıdır.
