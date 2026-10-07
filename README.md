# W7Link

Galaxy Watch için Google Play Hizmetleri gerektirmeyen eşlikçi ve kalp sağlığı uygulaması.
İki uygulamadan oluşur: telefona kurulan `phone` ve saate kurulan `wear`. İkisi kendi
şifreli Bluetooth LE kanalı üzerinden konuşur; telefonda Wear OS veri katmanını, Galaxy
Wearable'ı veya herhangi bir sunucuyu kullanmaz ve hiç ağ isteği yapmaz.

> [!IMPORTANT]
> **W7Link tıbbi cihaz değildir.** Teşhis koymaz, tedavi etmez; kalp krizi, inme veya başka
> bir acil durumu algılayamaz. Tansiyon değerleri tahmindir. Bkz.
> [tıbbi uyarı](legal/MEDICAL_DISCLAIMER.md).

## Heartline çatalıdır

Sağlık özellikleri (EKG, tansiyon tahmini, nabız ve ritim izleme, SpO₂, cilt sıcaklığı,
stres, vücut kompozisyonu, saat kartları, telefon geçmişi ve raporlar) Selin ve Heartline
katkıcılarının [Heartline](https://github.com/selin2005/heartline) projesinden gelir.
W7Link **resmi olmayan bir çataldır**; Heartline projesi bu sürümü yayınlamaz, desteklemez
ve onaylamaz. Sorunları Heartline'a değil [buraya](../../issues) bildirin.

Heartline'dan farkları:

- Saat–telefon eşitlemesi Wearable Data Layer yerine W7Link'in kendi bağlantısı üzerinden
  yapılır (`datalayer/…/w7link/common`), bu yüzden telefonda Google servisi gerekmez.
- Eşlikçi özellikleri eklendi (aşağıda).
- Uygulama içi güncelleyici kapalıdır ve telefon uygulamasının internet izni yoktur.
- Ad, simge ve uygulama kimliği farklıdır (Heartline'ın ad ve simgesi lisans kapsamında değildir).

## Eşlikçi özellikleri

- Telefon bildirimlerini saate aktarır; saatten kapatma, eylem seçme ve yanıt yazma
- Gelen aramayı saatte gösterir; yanıtla / reddet / sessize al
- Medya kontrolü (önceki, oynat-duraklat, sonraki, ses)
- Telefonumu bul, saatimi bul, karşılıklı pil durumu, günlük adım

Bunlar uygulama çekmecesindeki ikinci simgeden, **W7Link Eşlikçi**'den yönetilir.
Eşleştirme de oradan yapılır.

## Ne yapmaz

Saatin ilk kurulumunu, Play Store'u, Samsung Health eşitlemesini ve eSIM'i yapamaz: bunlar
Play Hizmetleri'nin içindeki kapalı protokole bağlıdır. Saat daha önce bir kez kurulmuş
olmalıdır.

## Kurulum

APK'lar [Releases](../../releases) sayfasındadır.

1. `w7link-phone-*.apk` dosyasını telefona kurun.
2. Saatte Ayarlar → Geliştirici seçenekleri → Kablosuz hata ayıklama'yı açın, sonra:
   ```
   adb pair <saat-ip>:<eşleştirme-portu>
   adb connect <saat-ip>:<port>
   adb install w7link-wear-*.apk
   ```
3. İki cihazda da **W7Link Eşlikçi**'yi açıp izinleri verin. İki ekranda aynı 6 haneli kod
   çıkar; aynıysa ikisinde de onaylayın. Sağlık verisi de bu bağlantıdan akar, yani
   eşleştirme bitmeden ana uygulama saati göremez.
4. Telefonda "Bildirim erişimini aç" düğmesi bildirim erişimi ayarını açar. Android 13 ve
   sonrasında bu ayar elle kurulan uygulamalar için kilitli gelir: Uygulama bilgisi → ⋮ →
   "Kısıtlanmış ayarlara izin ver".
5. Sağlık sensörleri için saatte **Health Platform** uygulamasının geliştirici modunu açın
   (Ayarlar → Uygulamalar → Health Platform → başlığa yaklaşık 10 kez dokunun). Ayrıntı:
   [docs/SAMSUNG_HEALTH_SENSOR_SDK.md](docs/SAMSUNG_HEALTH_SENSOR_SDK.md).
6. Ana **W7Link** uygulamasını telefonda ve saatte açıp kurulumu (koşullar, profil) tamamlayın.

## Derleme

Her `main` push'unda GitHub Actions iki APK'yı derleyip sürüm olarak yayınlar.
Yerelde: `./gradlew :phone:assembleRelease :wear:assembleRelease` (JDK 21, compile SDK 37).

| Modül | İçerik |
|---|---|
| `shared/` | Modeller, eşitleme protokolü, EKG / HRV / ritim / tansiyon / stres algoritmaları |
| `datalayer/` | Eşitleme taşıyıcısı: şifreli bağlantı (`SecureChannel`), `LinkHub`, `LinkTransport` |
| `phone/` | Telefon uygulaması; eşlikçi kısmı `io/github/sype0/w7link/phone` altında |
| `wear/` | Saat uygulaması; eşlikçi kısmı `io/github/sype0/w7link/wear` altında |

Heartline'ın belgeleri [docs/](docs/README.md) altında duruyor; yayınlama, Play Store ve
ekran görüntüleriyle ilgili bölümleri bu çatal için geçerli değildir.

## Bağlantı protokolü

Saat, hizmet UUID'si ve L2CAP PSM'i ile BLE reklamı yapar; telefon tarar ve L2CAP kanalını
açar. İki taraf kalıcı P-256 anahtarı tutar. El sıkışmada telefon önce anahtarına ve
nonce'una taahhüt verir, ardından ECDH ile oturum anahtarları türetilir; çerçeveler
AES-256-GCM ile şifrelenir. Eşleştirme kodu el sıkışma dökümünden türer ve ilk eşleştirmede
araya girilmediğini doğrular. Çerçeve türleri: eşlikçinin JSON mesajları, Heartline eşitleme
zarfları ve büyük aktarımlar için parçalı akışlar.

## Lisans

[GNU Affero General Public License v3.0 veya sonrası](LICENSE),
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) içindeki bağlama izniyle. Dağıttığınız her
değiştirilmiş sürüm aynı lisansla ve tam kaynak koduyla yayınlanmalıdır. Üçüncü taraf
bileşenler kendi lisanslarını korur: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Telif hakkı © 2026 Selin ve Heartline katkıcıları (Heartline); © 2026 Sype0 (eşlikçi ve bağlantı katmanı).
