import urllib.request
import urllib.parse
import json
import re

headers = {
    "signature": "308202c3308201aba0030201020204075cec01300d06092a864886f70d01010b050030123110300e0603550403130753696e65776978301e170d3231303932313233333334395a170d3436303931353233333334395a30123110300e0603550403130753696e6577697830820122300d06092a864886f70d01010105000382010f003082010a0282010100b0a2a1bc5c3f16f19c3b2456cfd0a6128ced9f5e2e2c4cca1a100e17b07b86256258f372e76a95a17e9e4a1c048e364835723a95e8ef6d5bdfb5694b50277c65a64f7b012fdf164e5dc93629561f6ca29b7dc82ebb3d6f3c8e8fc6795847fe331ad4a13ed6c059a83804c43d3747526d769580f3a4153752eb22dac66dd15f1582caa43305dc49f55ac7b1b89013e654d2ca8c94c30956659674cc673256c04208f09118bae14cdd72d78f9ee2aece958084a8c2e315deff45726d4fc1f18ec39569ff1abe4f36a8d01090e5f68c07c28763513b88208bcac1a6e1941f6fd8bfdd52f832098ddb2154c8f565bc5d58c7106a19e03787e75c7f34997000e3bcf30203010001a321301f301d0603551d0e04160414b545fc18e74a791d9402b53940ae38b96e9e209c300d06092a864886f70d01010b05000382010100a8a64d9e7c8b5db102af15d3caf94ff8d3e9be9008bb0021117ca2f0762e68583354b126a041bb1fb6e6308e421e4b5a71f779cde63e5d2fc5976bff966c3c4034e852c077d8e74458fbae2ec1db74b1f4082e188bf8ef7c42a44e3fbfb693bb00ee2a727096b42360ddce1bdcd3536f50c8693bcc62a7b7204bcefe2ecf1f7c820bcd63e1d7a6acc8bf6163086915fc5f607cf51bc7a8635f98bb4c65a8f24b7b5a82c7b06868f565cb0d6ac4775c4aac777536ddd1a565f990fd8cbe539185fa7aab610b7855a687a00f4e55536d72873444552c50fd10727dbf298a9be6ed6ae62148dd1de365f3729915dd31975e28a472d752ac14db3db548405cc31e1e",
    "hash256": "f4d4bc98a3fc4600e7f2c2bab7533f1f03d8a70ff03c256bb11dc57050536bd0",
    "User-Agent": "EasyPlex (Android 13; SM-A546E; samsung; tr)"
}
key = '9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA'
tmdb_id = 46668
season = 1
episode = 1

print(f"[*] TMDB'den ID {tmdb_id} icin bilgi aliniyor...")
tmdb_url = f"https://api.themoviedb.org/3/tv/{tmdb_id}?api_key=a2f888b27315e62e471b2d587048f32e&language=en-US"
try:
    tmdb_req = urllib.request.urlopen(urllib.request.Request(tmdb_url)).read().decode()
    queryTitle = json.loads(tmdb_req).get("name")
except Exception as e:
    print("TMDB Hatasi:", e)
    queryTitle = "Giant Killing"

print(f"[*] Bulunan Dizi Adi: {queryTitle}")
print("[*] SineWix uzerinde arama yapiliyor...")
search_url = f"https://ydfvfdizipanel.ru/public/api/search/{urllib.parse.quote(queryTitle)}/{key}"
try:
    search_req = urllib.request.urlopen(urllib.request.Request(search_url, headers=headers)).read().decode()
    search_res = json.loads(search_req).get("search", [])
    candidates = [x for x in search_res if x["type"] in ["serie", "anime"]]
except Exception as e:
    print("SineWix Arama Hatasi:", e)
    candidates = []

print(f"[*] Bulunan Adaylar: {[c['title'] if 'title' in c else c.get('name') for c in candidates]}")
videoLink = None

for candidate in candidates:
    detail_url = f"https://ydfvfdizipanel.ru/public/api/animes/show/{candidate['id']}/{key}" if candidate['type'] == 'anime' else f"https://ydfvfdizipanel.ru/public/api/series/show/{candidate['id']}/{key}"
    try:
        detail_req = urllib.request.urlopen(urllib.request.Request(detail_url, headers=headers)).read().decode()
        detail_json = json.loads(detail_req)
    except Exception as e:
        print(f"Detay hatasi ({candidate.get('name')}):", e)
        continue

    if str(detail_json.get("tmdb_id")) == str(tmdb_id):
        print(f"[*] TMDB ID Eslesmesi Basarili! SineWix ID: {candidate['id']}")
        seasons = detail_json.get("seasons", [])
        
        for s in seasons:
            if s.get("season_number") == season:
                for ep in s.get("episodes", []):
                    if ep.get("episode_number") == episode:
                        videos = ep.get("videos", [])
                        if videos:
                            videoLink = videos[0].get("link")
                            break
        
        if not videoLink:
            print("[*] Strict match basarisiz, fallback calisiyor...")
            for s in seasons:
                for ep in s.get("episodes", []):
                    epNum = ep.get("episode_number")
                    epName = ep.get("name", "")
                    
                    if epNum == episode:
                        videos = ep.get("videos", [])
                        if videos:
                            videoLink = videos[0].get("link")
                            break
                    
                    nameMatch = re.search(r"(\d+)\.\s*Bölüm", epName, re.IGNORECASE)
                    if nameMatch and int(nameMatch.group(1)) == episode:
                        videos = ep.get("videos", [])
                        if videos:
                            videoLink = videos[0].get("link")
                            break
        break

print(f"\n[*] SineWix API'den Gelen Ham Link: {videoLink}")

if videoLink and "mediafire.com" in videoLink:
    print("[*] Mediafire cozumleme basladi...")
    try:
        # Use standard browser headers to resolve Mediafire page
        mf_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        }
        html = urllib.request.urlopen(urllib.request.Request(videoLink, headers=mf_headers)).read().decode()
        match = re.search(r'href="([^"]+)"[^>]*id="downloadButton"', html) or \
                re.search(r'id="downloadButton"[^>]*href="([^"]+)"', html) or \
                re.search(r'href="(https://download[^"]+mediafire\.com/[^"]+)"', html)
        final_link = match.group(1) if match else videoLink
        print(f"[*] Vegapunk'in Cikardigi SONUC LINK: {final_link}")
        
        if "mediafire.com/file/" in final_link:
            print("[!!!] UYARI: Cikarilan link hala HTML sayfasi! Cloudstream'in kendi kirici motoruna (Extractor) devredilecek.")
        else:
            print("[+] BASARILI: Direkt video dosyasi linki cikarildi!")
            
    except Exception as e:
        print("Mediafire cozulemedi:", e)
else:
    print("[*] Bulunamadi veya Mediafire degil!")
