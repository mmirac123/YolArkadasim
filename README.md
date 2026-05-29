# Yol Arkadaşım (Transit Companion) 🚌

**Yol Arkadaşım**, özellikle görme engelli ve yaşlı bireylerin toplu taşıma kullanımını kolaylaştırmak amacıyla geliştirilmiş, yüksek hassasiyetli bir Android navigasyon asistanıdır. Uygulama, rakiplerinden (Moovit vb.) farklı olarak "Tam Erişilebilirlik" odaklı bir deneyim sunar.

## ✨ Öne Çıkan Özellikler

### 🎙️ Zeki Sesli Asistan ve Kontrol
- **Sallayarak Konuşma (Shake-to-Talk):** Ekrandaki mikrofon butonunu aramaya gerek kalmadan, telefonu hafifçe sallayarak sesli komut verebilme.
- **Sesle Hedef Belirleme:** "Beni Özge Sokak'a götür" gibi doğal cümlelerle varış durağını eller serbest olarak ayarlama.
- **Adım Adım Rehberlik:** Kolay Mod'da ekran geçişlerinde ve butonların ne işe yaradığına dair otomatik asistan anlatımı.

### 📍 Profesyonel Takip Sistemi
- **C++ Navigasyon Motoru:** JNI altyapısı ile yüksek performanslı mesafe ve sapma hesaplamaları.
- **Dinamik GPS Örnekleme:** Hareket hızına göre (dururken 7sn, hızlıyken 1sn) batarya ve hassasiyet optimizasyonu.
- **Otomatik Biniş Algılama:** Takip başladığında en yakın durağı tespit eder, yanlış duraktaysanız sizi yönlendirir.

### 📱 Çift Modlu ve Fonksiyonel Arayüz
- **Kolay Mod:** Dev butonlar, yüksek kontrastlı renkler ve tam sesli rehberlik desteği.
- **Modern Mod (Material 3):** Şık kartlar, canlı ilerleme çubuğu, istatistik paneli ve detaylı ayarlar.
- **Canlı Harita (OpenStreetMap):** Bağımsız bir sekmede güzergah hattını, durakları ve canlı konumunuzu izleyebilme.

### 📊 İstatistikler ve Kişiselleştirme
- **Seyahat Özeti:** Toplam seyahat sayısı, kat edilen mesafe ve favori hatların analizi.
- **Gelişmiş Ayarlar:** Açılış modunu (Modern/Kolay) seçebilme, ses seviyesi ve rehberlik tercihlerini yönetme.
- **Favori Duraklar:** Duraklara "Evim", "İş" gibi takma adlar verebilme.

## ⚙️ Arka Plan ve Güvenlik
- **Foreground Service:** Uygulama kapalıyken veya ekran kilitliyken kesintisiz GPS takibi ve anonslar.
- **Haptik Geri Bildirim:** Kritik anlarda (varış, rota sapması) farklı titreşim ritimleriyle tactile (dokunsal) uyarı.

## 🛠 Teknik Mimari
- **Dil:** Kotlin (UI & Service), C++ (Native Engine)
- **Konum:** Google Play Services Fused Location Provider
- **Harita:** osmdroid (OpenStreetMap - API Key gerektirmez)
- **Veri:** JSON tabanlı yerel rota/durak veritabanı (EGO Stop IDs uyumlu)

## 🚀 Kurulum
1. Projeyi klonlayın: `git clone https://github.com/mmirac123/YolArkadasim.git`
2. Android Studio ile açın ve Gradle senkronizasyonunu yapın.
3. `app` modülünü cihazınıza yükleyin.

## 📄 Lisans
Bu proje eğitim ve sosyal sorumluluk amacıyla geliştirilmiştir. Tüm hakları saklıdır.
