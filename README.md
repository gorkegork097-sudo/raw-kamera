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
| İki tuşa birlikte uzun bas | Flaş kapalıyken açar ve güç menüsünü gösterir; menü açıkken menüyü kapatır; flaş açık ve menü kapalıyken flaşı kapatır |
| Menü açıkken ses açma / kısma | Değeri bir adım artırır / azaltır (ince / kalın tık + hafif titreşim) |
| Menüdeki cetveli kaydır | Değeri dokunarak değiştirir |
| Üstteki ISO, ENS veya FLŞ yazısına dokun | O menüyü açar |
| Sağ üstteki ayar simgesi | Kılavuz, flaş modu (patlama / sürekli) ve zamanlayıcı (2 / 5 / 10 sn) |
| Seçili SPİRAL'e tekrar dokun | Spirali bir sonraki köşeye çevirir |
| Vizöre dokun | O noktaya odaklar, 3 sn sonra otomatik odağa döner |
| Vizöre basılı tut | O noktada odağı kilitler (AF-L) |
| Menü kapalıyken ses açmaya kısa bas | Fotoğraf çeker (zamanlayıcı açıksa geri sayar) |
| Alttaki çubuğa dokun | Fotoğraf çeker (zamanlayıcı açıksa geri sayar, son 3 sn bip) |
| Alttaki çubuğa basılı tut | Seri çekim (her karede tık, çekilen kare sayısı görünür) |
| Ayar menüsü › MOD | MANUEL (M) · OTOMATİK (A) · ISO ÖNCELİKLİ (Sv) · ENS ÖNCELİKLİ (Tv) |
| Otomatik modda EV'ye dokun | EV telafisi (−3 … +3, 1/3 adım) |
| Otomatik bir ayarın tuşuna uzun bas | O ayar manuele geçer, mod kendiliğinden değişir |

Menüler son ayardan 4 saniye sonra kendiliğinden kapanır.
Telefon sessiz ya da titreşim modundayken uygulama hiç ses çıkarmaz.
Flaş modları: PATLAMA (fener kısa süre yanar, ISO/ENS senin ayarın; ışık en az enstantane süresi kadar yanar),
ANLIK (telefonun gerçek, çok kısa flaş patlaması; o karede ISO/ENS'yi kamera seçer), SÜREKLİ (fener).
Patlama ve anlık flaş seri çekimde kullanılmaz; sürekli modda fener yanmaya devam eder.
EV, mevcut ISO ve enstantaneyle fotoğrafın orta griye göre ne kadar karanlık (−) ya da aydınlık (+) çıkacağını gösteren bir ışık ölçerdir.

## Derleme
`main` dalına her gönderimde GitHub Actions APK'yı derleyip yeni bir sürüm olarak yayınlar.
İmza anahtarı (`app/rawkamera-release.jks`) şifrelidir; şifresi depoda değil, GitHub'ın gizli ayarlarında
`KEYSTORE_PASSWORD` adıyla durur. Anahtar sabit olduğu için yeni sürümler eskisinin üzerine kurulur.
