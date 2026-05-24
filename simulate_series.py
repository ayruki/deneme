import requests
import json
import base64
from bs4 import BeautifulSoup
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

def decrypt_data(encrypted_b64):
    try:
        key = b"9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"
        iv = b"\x00" * 16
        cipher = AES.new(key, AES.MODE_CBC, iv)
        data = base64.b64decode(encrypted_b64)
        decrypted = unpad(cipher.decrypt(data), AES.block_size)
        return decrypted.decode('utf-8')
    except Exception as e:
        return str(e)

def get_series_info(slug):
    url = f"https://dizillahd.com/{slug}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://dizillahd.com/"
    }
    print(f"[*] Requesting: {url}")
    res = requests.get(url, headers=headers)
    print(f"[*] Status: {res.status_code}")
    if res.status_code == 200:
        soup = BeautifulSoup(res.text, 'html.parser')
        script = soup.find('script', id='__NEXT_DATA__')
        if script:
            json_data = json.loads(script.string)
            try:
                secure_data = json_data['props']['pageProps']['secureData']
                decrypted = decrypt_data(secure_data)
                parsed = json.loads(decrypted)
                seasons = parsed.get("RelatedResults", {}).get("getSerieSeasonAndEpisodes", {}).get("result", [])
                for season in seasons:
                    season_no = season.get("season_no")
                    episodes = season.get("episodes", [])
                    print(f"Season {season_no} has {len(episodes)} episodes.")
                    for ep in episodes[:2]: # print first 2 episodes
                        print(f"  Ep {ep.get('episode_no')}: {ep.get('used_slug') or ep.get('episode_slug')}")
            except Exception as e:
                print(f"[!] Error: {e}")
        else:
            print("[!] __NEXT_DATA__ not found")

if __name__ == "__main__":
    get_series_info("dizi/the-fairly-oddparents")
