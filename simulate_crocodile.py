import requests
import urllib.parse
import json
import base64
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

def search_dizilla(search_term):
    url = f"https://dizillahd.com/api/bg/searchContent?searchterm={urllib.parse.quote(search_term)}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer": "https://dizillahd.com/"
    }
    print(f"[*] Requesting: {url}")
    res = requests.post(url, headers=headers)
    print(f"[*] Status: {res.status_code}")
    if res.status_code == 200:
        try:
            json_data = res.json()
            success = json_data.get("success")
            print(f"[*] Success: {success}")
            if success:
                encrypted_response = json_data.get("response")
                decrypted = decrypt_data(encrypted_response)
                print(f"[*] Decrypted Response:\n{decrypted}")
                return json.loads(decrypted)
        except Exception as e:
            print(f"[!] Error parsing response: {e}")
            print(res.text)
    return None

if __name__ == "__main__":
    # 4630 is likely a TMDB id. First you'd get its IMDB ID from TMDB API.
    # We will simulate the IMDB ID search or Name search here.
    search_term = input("Aranacak kelime veya IMDb ID (örn: tt0290978): ")
    result = search_dizilla(search_term)
    print("Result JSON Object:", result)
