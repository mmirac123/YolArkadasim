# Yol Arkadaşım (Transit Companion) 🚌

**Yol Arkadaşım**, özellikle görme engelli ve yaşlı bireylerin toplu taşıma kullanımını kolaylaştırmak amacıyla geliştirilmiş, yüksek hassasiyetli bir Android navigasyon asistanıdır. Uygulama, kullanıcının seçtiği durak için gerçek zamanlı GPS takibi yapar ve kademeli sesli anonslarla yolculuk boyunca rehberlik eder.

## ✨ Temel Özellikler

### 📍 Hassas Takip Sistemi
- **C++ Navigasyon Motoru:** JNI altyapısı ile yüksek performanslı mesafe ve sapma hesaplamaları.
- **Dinamik GPS Örnekleme:** Hareket hızına göre (dururken 7sn, hızlıyken 2sn) batarya ve hassasiyet optimizasyonu.
- **Otomatik Biniş Algılama:** Takip başladığında en yakın durağı tespit eder ve kullanıcıyı yönlendirir.

### 🎙️ Sesli Rehberlik ve Asistan
- **Eller Serbest Komutlar:** "Neredeyim?", "Kaç durak kaldı?", "Mesafe ne kadar?" gibi sorulara sesli yanıtlar.
- **Adım Adım Rehber:** Kolay Mod'da ekranlar arası geçişte ve seçim yaparken otomatik asistan anlatımı.
- **Kademeli Anonslar:** Önceki duraktan kalkış, 150m kala yaklaşma, 80m kala hatırlatma ve 35m kala varış uyarıları.

### 📱 Çift Modlu Arayüz
- **Kolay Mod:** Görme engelliler ve yaşlılar için devasa butonlar, yüksek kontrast ve tam sesli rehberlik.
- **Modern Mod:** Material 3 standartlarında, alt menü (Bottom Nav) destekli şık ve detaylı arayüz.

### 📊 İstatistikler ve Ayarlar
- **Seyahat Özeti:** Toplam seyahat sayısı, kat edilen mesafe ve geçilen durak istatistikleri.
- **Gelişmiş Ayarlar:** Açılış modunu (Modern/Kolay) seçebilme, ses seviyesi ve rehberlik tercihlerini yönetme.

### 🌟 Favoriler ve Özelleştirme
- **Favori Hatlar:** Sık kullanılan hatları en üste sabitleme.
- **Özel İsimli Duraklar:** Duraklara "Evim", "İş" gibi takma adlar verebilme ve bu isimlerle anons duyabilme.

## ⚙️ Arka Plan Desteği
- **Foreground Service:** Uygulama kapalıyken veya ekran kilitliyken kesintisiz GPS takibi ve anonslar.
- **Canlı Bildirim:** Bildirim panelinde sıradaki durak ve mesafeyi gösteren dinamik kart.

## 🛠 Teknik Mimari
- **Dil:** Kotlin (UI & Service), C++ (Native Engine)
- **Konum:** Google Play Services Fused Location Provider
- **Veri:** JSON tabanlı yerel rota/durak veritabanı (EGO Stop IDs uyumlu)
- **Haptik:** Kritik uyarılar için özelleştirilmiş titreşim ritimleri

## 🚀 Kurulum
1. Projeyi klonlayın: `git clone https://github.com/mmirac123/YolArkadasim.git`
2. Android Studio ile açın ve Gradle senkronizasyonunu yapın.
3. `app` modülünü cihazınıza yükleyin.

## 📄 Lisans
Bu proje eğitim ve sosyal sorumluluk amacıyla geliştirilmiştir. Tüm hakları saklıdır.
