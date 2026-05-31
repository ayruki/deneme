import requests
import re
import json
import sys

TMDB_API_KEY = 'a2f888b27315e62e471b2d587048f32e'
TMDB_BASE = 'https://api.themoviedb.org/3'
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

def safe_fetch(url, headers=None, method='GET', json_data=None):
    if not headers:
        headers = {
            'User-Agent': UA
        }
    try:
        if method.upper() == 'POST':
            response = requests.post(url, headers=headers, json=json_data, timeout=30)
        else:
            response = requests.get(url, headers=headers, timeout=30)
        
        response.raise_for_status()
        return response
    except Exception as e:
        print(f"[Fetch Error] {url} -> {str(e)}")
        return None

def get_tmdb_meta(tmdb_id, media_type):
    tmdb_type = 'tv' if media_type == 'tv' else 'movie'
    url = f"{TMDB_BASE}/{tmdb_type}/{tmdb_id}?api_key={TMDB_API_KEY}&language=tr-TR"
    try:
        resp = requests.get(url, timeout=10).json()
        title = resp.get("title") or resp.get("name") or "Bilinmeyen Yapım"
        release_date = resp.get("release_date") or resp.get("first_air_date") or ""
        year = release_date.split("-")[0] if "-" in release_date else ""
        return title, year
    except Exception as e:
        print(f"Error fetching TMDB meta: {e}")
        return "Bilinmeyen Yapım", ""

def resolve_vidsrcme_streams(tmdb_id, media_type, season=1, episode=1):
    title, year = get_tmdb_meta(tmdb_id, media_type)
    print(f"\n[TMDB] Bulunan Yapım: {title} ({year})")
    
    if media_type == 'tv':
        embed_url = f"https://vidsrcme.ru/embed/tv?tmdb={tmdb_id}&season={season}&episode={episode}"
    else:
        embed_url = f"https://vidsrcme.ru/embed/movie?tmdb={tmdb_id}"

    print(f"\n[1] Oynatıcı Sayfası İsteniyor: {embed_url}")
    embed_res = safe_fetch(embed_url)
    if not embed_res:
        print("[-] Sayfa yüklenemedi.")
        return
        
    embed_html = embed_res.text
    
    # Extract iframe src
    iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', embed_html)
    iframe_src = iframe_match.group(1) if iframe_match else None
    if not iframe_src:
        print("[-] Sayfa içinde iframe bulunamadı. HTML Çıktısı:")
        print(embed_html[:2000])
        return

    if iframe_src.startswith('//'):
        iframe_src = 'https:' + iframe_src

    print(f"[2] Iframe Adresi Yakalandı: {iframe_src}")

    # Fetch the iframe page
    iframe_headers = {
        'User-Agent': UA,
        'Referer': embed_url
    }
    print(f"[3] Iframe Sayfası İsteniyor...")
    iframe_res = safe_fetch(iframe_src, headers=iframe_headers)
    if not iframe_res:
        print("[-] Iframe sayfası yüklenemedi.")
        return
        
    iframe_html = iframe_res.text
    
    # Search for prorcp_src
    prorcp_match = re.search(r'src:\s*["\']([^"\']+)["\']', iframe_html)
    prorcp_src = prorcp_match.group(1) if prorcp_match else None
    if not prorcp_src:
        print("[-] Iframe içinde prorcp_src bulunamadı. Iframe HTML Çıktısı:")
        print(iframe_html[:1500])
        return
        
    print(f"[4] prorcp_src Parametresi Bulundu: {prorcp_src}")
    
    # cloudnestra.com url
    cloud_url = f"https://cloudnestra.com{prorcp_src}"
    print(f"[5] CloudNestra Oynatıcı Sayfası İsteniyor: {cloud_url}")
    cloud_headers = {
        'User-Agent': UA,
        'Referer': 'https://cloudnestra.com/'
    }
    cloud_res = safe_fetch(cloud_url, headers=cloud_headers)
    if not cloud_res:
        print("[-] CloudNestra oynatıcı sayfası yüklenemedi.")
        return
        
    cloud_html = cloud_res.text
    
    # Extract Subtitles
    subtitles = []
    subs_matches = re.findall(r'default_subtitles\s*=\s*["\'`](.*?)["\'`]', cloud_html, re.S)
    if subs_matches:
        subs_raw = subs_matches[-1]
        if subs_raw:
            parts = subs_raw.split(',')
            for part in parts:
                if '[' in part and ']' in part:
                    lang_match = re.search(r'\[(.*?)\](.*)', part)
                    if lang_match:
                        lang = lang_match.group(1).strip()
                        sub_url_raw = lang_match.group(2).strip()
                        if not lang or not sub_url_raw:
                            continue
                        sub_url = sub_url_raw
                        if not sub_url.startswith('http'):
                            sub_url = f"https://cloudnestra.com{sub_url}"
                        subtitles.append({"language": lang, "url": sub_url})

    # Search for hidden div
    hidden_match = re.search(r'<div id="([^"]+)"[^>]*style=["\']display\s*:\s*none;?["\'][^>]*>([a-zA-Z0-9:/.,{}\-_=+ ]+)</div>', cloud_html)
    if not hidden_match:
        print("\n[-] Sayfa içinde gizli div verisi (hidden div) bulunamadı.")
        print("[!] Muhtemelen bulut sunucu IP adresi Cloudflare Turnstile (Captcha) korumasına takıldı.")
        print("[!] HTML Çıktısı 'vidsrcme_cloudflare_dump.html' dosyasına yazıldı.")
        import os
        dump_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vidsrcme_cloudflare_dump.html")
        with open(dump_path, "w", encoding="utf-8") as f:
            f.write(cloud_html)
        if subtitles:
            print("\n--- YALNIZCA ALTYAZILAR BULUNDU ---")
            print(json.dumps(subtitles, indent=2, ensure_ascii=False))
        return
        
    div_id = hidden_match.group(1)
    div_text = hidden_match.group(2)
    print(f"[6] Gizli Div Yakalandı: ID={div_id}, Veri Uzunluğu={len(div_text)}")
    
    # Decrypt using the API
    print("[7] Şifreli Veri enc-dec.app API ile Çözülüyor...")
    dec_res = safe_fetch('https://enc-dec.app/api/dec-cloudnestra', method='POST', json_data={
        'text': div_text,
        'div_id': div_id
    })
    
    if not dec_res:
        print("[-] API üzerinden şifre çözme işlemi başarısız oldu.")
        return
        
    dec_json = dec_res.json()
    print("\n=================== BAŞARILI SONUÇLAR ===================")
    print("VİDEO YAYIN LİNKLERİ (HLS/M3U8):")
    print(json.dumps(dec_json.get("result", []), indent=2, ensure_ascii=False))
    if subtitles:
        print("\nALTYAZI LİNKLERİ:")
        print(json.dumps(subtitles, indent=2, ensure_ascii=False))

def main():
    print("==================================================")
    print("       Vidsrcme.ru (CloudNestra) Oynatıcı Testi    ")
    print("==================================================")
    print("İçerik Türü:")
    print("1. Film (movie)")
    print("2. Dizi (tv)")
    type_choice = input("Tür seçin (1 veya 2): ").strip()
    media_type = "movie" if type_choice == "1" else "tv"
    
    tmdb_id = input("\nTMDB ID girin (Örn: Movie için 550, Dizi için 1411): ").strip()
    if not tmdb_id.isdigit():
        print("TMDB ID sadece sayı olmalıdır.")
        return
    tmdb_id = int(tmdb_id)
    
    season = 1
    episode = 1
    if media_type == "tv":
        season_input = input("Sezon girin (Varsayılan 1): ").strip()
        if season_input.isdigit():
            season = int(season_input)
            
        episode_input = input("Bölüm girin (Varsayılan 1): ").strip()
        if episode_input.isdigit():
            episode = int(episode_input)
            
    resolve_vidsrcme_streams(tmdb_id, media_type, season, episode)

if __name__ == "__main__":
    main()
