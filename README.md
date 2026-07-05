# Yol Arkadaşım (Transit Companion) 🚌

**Yol Arkadaşım**, özellikle yaşlı ve görme engelli bireylerin toplu taşıma kullanımını kolaylaştırmak amacıyla geliştirilmiş, yüksek hassasiyetli bir Android navigasyon asistanıdır. Uygulama, rakiplerinden (Moovit vb.) farklı olarak "Tam Erişilebilirlik" odaklı bir deneyim sunar: her kritik an hem sesle hem titreşimle bildirilir, sessizlik bir hata olarak kabul edilir.

## ✨ Öne Çıkan Özellikler

### 🎙️ Zeki Sesli Asistan ve Kontrol
- **Sallayarak Konuşma (Shake-to-Talk):** Ekrandaki mikrofon butonunu aramaya gerek kalmadan, telefonu hafifçe sallayarak sesli komut verebilme.
- **Sesle Hedef Belirleme:** "Kızılay'a gitmek istiyorum" gibi doğal cümlelerle varış durağını eller serbest ayarlama. Türkçe'ye özel bulanık eşleştirme (İ/ı kuralları, ad çekim ekleri, konuşma tanıma hatalarına Levenshtein toleransı) sayesinde durak adını birebir söylemek gerekmez.
- **Sesli Hata Geri Bildirimi:** Mikrofon sizi duyamadıysa uygulama sessiz kalmaz; "Sizi duyamadım, tekrar deneyin" diye uyarır.
- **Yolculuk Sırasında Sorgu:** "Neredeyim?", "Kaç durak kaldı?", "Mesafe ne kadar?" gibi sorulara anlık sesli yanıt.
- **Adım Adım Rehberlik:** Kolay Mod'da ekran geçişlerinde ve butonların ne işe yaradığına dair otomatik asistan anlatımı.

### 🆘 Yardım Butonu (Yaşlı Kullanıcı Güvenliği)
- Kolay Mod'un en altında dev turuncu **YARDIM İSTE** butonu: tek dokunuşla ayarlarda kayıtlı yakınınızın numarasını arama ekranında açar.
- "Yardım" veya "imdat" sesli komutuyla da tetiklenir — telefon cebindeyken bile.
- Ekstra izin gerektirmez; yanlışlıkla arama başlatmaz (kullanıcı yeşil tuşa basarak onaylar).

### 📍 Profesyonel Takip Sistemi
- **C++ Navigasyon Motoru:** JNI üzerinden Kalman filtresi + durak durum makinesi (FSM) ile yüksek performanslı konum yumuşatma, mesafe ve sapma hesapları. Her seyahat öncesi motor sıfırlanır; önceki yolculuğun durumu yenisine taşınmaz.
- **Dinamik GPS Örnekleme:** Hareket hızına göre (dururken 2 sn, hızlıyken 1 sn) batarya ve hassasiyet dengesi.
- **Otomatik Biniş Algılama:** Takip başladığında en yakın durağı tespit eder; duraktan uzaksanız sizi durağa yönlendirir.
- **Yanlış Yön Uyarısı:** Ters yöne gidiş algılanınca sesli anons + güçlü titreşim + bildirim.
- **Kesinti Toleransı:** Sistem serviyi kapatsa bile yolculuk otomatik geri yüklenir ve "Takip yeniden başlatıldı" anonsuyla devam eder. Uygulamaya geri dönüldüğünde arayüz servisteki gerçek durumla senkronize edilir.

### 📳 Haptik (Titreşim) Geri Bildirim
Gürültülü otobüste anons duyulmayabilir; kritik anlar ayırt edilebilir titreşim ritimleriyle de bildirilir:
- Sıradaki durak → kısa tek titreşim
- Biniş algılandı / hedefe yaklaşma → çift titreşim
- **Varış → uzun üçlü titreşim**
- Yanlış yön → hızlı alarm ritmi

### 📱 Çift Modlu ve Fonksiyonel Arayüz
- **Kolay Mod:** Dev butonlar, yüksek kontrastlı renkler, tam sesli rehberlik ve YARDIM butonu.
- **Modern Mod (Material 3):** Şık kartlar, canlı ilerleme çubuğu, istatistik paneli ve detaylı ayarlar.
- **Canlı Harita (OpenStreetMap):** Bağımsız bir sekmede güzergah hattını, durakları ve canlı konumunuzu izleyebilme.
- **Tek Konuşma Otoritesi:** Takip sırasında tüm anonslar serviste toplanır; iki ses kaynağının birbirini yarıda kesmesi engellenir. Kritik olmayan anonslar kuyruğa alınır.

### 📊 İstatistikler ve Kişiselleştirme
- **Seyahat Özeti:** Toplam sefer, kat edilen mesafe, geçilen durak, en çok kullanılan hat, yanlış yön ikazı sayısı, ortalama GPS sapması ve pil tüketimi.
- **CSV Dışa Aktarma:** Akademik metrikler tek dokunuşla CSV dosyası olarak kaydedilir (Depolama Erişim Çerçevesi — ek izin gerektirmez).
- **Konuşma Hızı Ayarı:** Anons hızı %50–%150 arasında ayarlanabilir; yaşlı kullanıcılar için yavaş ve net konuşma.
- **Ses Seviyesi ve Rehberlik Tercihleri:** Açılış modu (Modern/Kolay), otomatik sesli rehber ve anons ses düzeyi yönetimi.

## ⚙️ Arka Plan ve Güvenlik
- **Foreground Service:** Ekran kilitliyken kesintisiz GPS takibi ve anonslar; Android 13+ bildirim izni açılışta istenir.
- **Asgari İzin İlkesi:** Arka plan konumu veya depolama izni istenmez; yardım butonu arama iznine ihtiyaç duymaz.

## 🛠 Teknik Mimari
- **Dil:** Kotlin (UI & Service), C++ (Native Engine: Kalman filtresi, navigasyon FSM)
- **Konum:** Google Play Services Fused Location Provider
- **Harita:** osmdroid (OpenStreetMap — API Key gerektirmez)
- **Veri:** JSON tabanlı yerel rota/durak veritabanı (EGO Stop IDs uyumlu)
- **Test:** Durak eşleştirme motoru (`StopMatcher`) saf Kotlin'dir ve birim testlerle doğrulanır (`app/src/test`)

## 🚀 Kurulum
1. Projeyi klonlayın: `git clone https://github.com/mmirac123/YolArkadasim.git`
2. Android Studio ile açın ve Gradle senkronizasyonunu yapın.
3. `app` modülünü cihazınıza yükleyin.

## 📄 Lisans
Bu proje eğitim ve sosyal sorumluluk amacıyla geliştirilmiştir. Tüm hakları saklıdır.
