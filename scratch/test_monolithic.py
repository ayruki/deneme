import requests

def test_monolithic():
    url = "https://vidmody.com/mm/tt2560140/s1/e01/lang/tur/sub_tr.vtt"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://vidmody.com/",
        "Origin": "https://vidmody.com"
    }
    
    print(f"[*] Fetching monolithic VTT: {url}")
    res = requests.get(url, headers=headers)
    print(f"[*] Response Code: {res.status_code}")
    if res.status_code == 200:
        print("\n--- Monolithic Content (First 500 chars) ---")
        try:
            content = res.content.decode('utf-8-sig', errors='replace')
        except:
            content = res.text
        print(content[:1000])
        print("--------------------------------------------\n")
    else:
        print("[!] Failed to fetch.")

if __name__ == "__main__":
    test_monolithic()
