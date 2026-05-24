import requests

def debug_imu():
    url = "https://vidmody.com/vs/tt2560140/s1/e01"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://vidmody.com/",
        "Origin": "https://vidmody.com"
    }
    
    print(f"[*] Fetching: {url}")
    session = requests.Session()
    # Follow redirects manually or automatically
    res = session.get(url, headers=headers, allow_redirects=True)
    print(f"[*] Response Code: {res.status_code}")
    print(f"[*] Final URL: {res.url}")
    
    if res.status_code == 200:
        text = res.text
        print(f"[*] Content Length: {len(text)}")
        # Print first 2000 chars of the response (it should be an M3U8 file)
        print("\n--- M3U8 Playlist Content ---")
        print(text[:2000])
        print("-----------------------------\n")
        
        # Check for subtitles declarations in the manifest
        print("[*] Subtitle lines in M3U8:")
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if "SUBTITLES" in line or "URI=" in line and (".vtt" in line or ".srt" in line):
                print(f"Line {i}: {line}")
    else:
        print("[!] Failed to fetch.")

if __name__ == "__main__":
    debug_imu()
