# 🚀 İzlelan - Cloudstream Extension

İzlelan, TMDB API altyapısını kullanarak yüksek hızlı akış kaynakları üzerinden dizi, film, anime ve çizgi filmleri izlemenizi sağlayan premium bir Cloudstream eklentisidir.

---

## 🌟 Özellikler
- **TMDB API**: Arama, kategoriler, detaylar, fragmanlar, benzer yapımlar ve oyuncu kadrosu.
- **Türkçe Dil Desteği**: Arayüz, afişler ve metadata tamamen Türkçe (`tr-TR`) gelir.
- **Yüksek Hızlı Video Kaynakları**: `VidSrc.to`, `Vidlink.pro`, `Embed.su`, `VidSrc.me` vb. üzerinden en güncel akış adreslerini otomatik yakalar.
- **Subtitles & Multi-Audio**: Çoklu dil ve altyazı desteği.

---

## 🛠 Kurulum (Telefonunuza Ekleme)

Eklentiyi Cloudstream uygulamanıza eklemek için:

1. Cloudstream uygulamasını açın.
2. **Ayarlar > Eklentiler > Depo Ekle (Add Repository)** yolunu izleyin.
3. İsim kısmına `İzlelan` yazın, URL kısmına ise aşağıdaki adresi yapıştırıp ekleyin:
   ```text
   https://raw.githubusercontent.com/ayruki/deneme/builds/repo.json
   ```
4. Eklentiler listesinden **İzlelan**'ı bulun ve **Yükle (Install)** butonuna basın!

---

## 📦 Yerel Derleme ve Test

Eklentiyi yerel makinenizde test etmek isterseniz:
- Windows: `.\gradlew.bat Izlelan:make`
- Yerel ADB Cihazına Yükleme: `.\gradlew.bat :Izlelan:deployWithAdb`

---

## 📄 Lisans
Bu proje tamamen kamuya açık (public domain) olarak yayınlanmıştır. Dilediğiniz gibi kullanıp geliştirebilirsiniz.
