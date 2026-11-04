# RAW Kamera

Honor 400 için minimal, tamamen manuel, sadece RAW (DNG) çeken Android kamera.

## Kurulum
Depodaki **Releases** bölümünden en son `RawKamera.apk` dosyasını telefona indirip aç.
Fotoğraflar `DCIM/RawKamera` klasörüne kaydedilir.

## Kullanım
| Hareket | Ne yapar |
|---|---|
| Ses açma tuşuna uzun bas | ISO menüsünü açar / kapatır |
| Ses kısma tuşuna uzun bas | Enstantane menüsünü açar / kapatır |
| Menü açıkken ses açma / kısma | Değeri bir adım artırır / azaltır (hafif titreşim) |
| Menüdeki cetveli kaydır | Değeri dokunarak değiştirir |
| Üstteki ISO veya ENS yazısına dokun | O menüyü açar |
| İki tuşa birlikte uzun bas | Şimdilik boş, ileride bir özellik için ayrıldı |
| Alttaki çubuğa dokun | Fotoğraf çeker (kısa mekanik tık) |

Menü son ayardan 4 saniye sonra kendiliğinden kapanır.
EV, mevcut ISO ve enstantaneyle fotoğrafın orta griye göre ne kadar karanlık (−) ya da aydınlık (+) çıkacağını gösteren bir ışık ölçerdir.

## Derleme
`main` dalına her gönderimde GitHub Actions APK'yı derleyip yeni bir sürüm olarak yayınlar.
İmza anahtarı (`app/rawkamera.keystore`) sabittir; böylece yeni sürümler eskisinin üzerine kurulur.
