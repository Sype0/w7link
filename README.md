# W7Link

Galaxy Watch7 için Google Play Hizmetleri gerektirmeyen eşlikçi. İki uygulamadan oluşur:
telefona kurulan `phone` ve saate kurulan `wear`. İkisi kendi şifreli Bluetooth LE
kanalı üzerinden konuşur; Wear OS veri katmanını, Galaxy Wearable'ı veya herhangi bir
sunucuyu kullanmaz.

## Ne yapar

- Telefon bildirimlerini saate aktarır; saatten kapatma, eylem seçme ve yanıt yazma
- Gelen aramayı saatte gösterir; yanıtla / reddet / sessize al
- Medya kontrolü (önceki, oynat-duraklat, sonraki, ses)
- Saatten 10 dakikada bir nabız ve günlük adım; telefonda saklanır, CSV olarak dışa aktarılır
- Telefonumu bul, saatimi bul, karşılıklı pil durumu

## Ne yapmaz

Saatin ilk kurulumunu, Play Store'u, Samsung Health eşitlemesini ve eSIM'i yapamaz: bunlar
Play Hizmetleri'nin içindeki kapalı protokole bağlıdır. Saat daha önce bir kez kurulmuş
olmalıdır. Uyku verisi de yoktur; Wear OS bunu uygulamalara açmıyor.

## Kurulum

APK'lar [Releases](../../releases) sayfasındadır.

1. `w7link-phone-*.apk` dosyasını telefona kurun.
2. Saatte Ayarlar → Geliştirici seçenekleri → Kablosuz hata ayıklama'yı açın, sonra:
   ```
   adb pair <saat-ip>:<eşleştirme-portu>
   adb connect <saat-ip>:<port>
   adb install w7link-wear-*.apk
   ```
3. İki uygulamayı da açıp izinleri verin. Telefonda "Bildirim erişimini aç" düğmesi
   bildirim erişimi ayarını açar. Android 13 ve sonrasında bu ayar elle kurulan
   uygulamalar için kilitli gelir: Uygulama bilgisi → ⋮ → "Kısıtlanmış ayarlara izin ver".
4. İki ekranda aynı 6 haneli kod çıkar; aynıysa ikisinde de onaylayın.

## Derleme

Her `main` push'unda GitHub Actions iki APK'yı derleyip sürüm olarak yayınlar.
Yerelde: `gradle assembleRelease` (JDK 17, Gradle 8.11).

## Protokol

Saat, hizmet UUID'si ve L2CAP PSM'i ile BLE reklamı yapar; telefon tarar ve L2CAP kanalını
açar. İki taraf kalıcı P-256 anahtarı tutar. El sıkışmada telefon önce anahtarına ve
nonce'una taahhüt verir, ardından ECDH ile oturum anahtarları türetilir; mesajlar
AES-256-GCM ile şifrelenmiş JSON'dur. Eşleştirme kodu el sıkışma dökümünden türer ve
ilk eşleştirmede araya girilmediğini doğrular. Ayrıntı: `common/src`.
