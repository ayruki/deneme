import requests
from bs4 import BeautifulSoup
import io
import zipfile

def test_full_flow():
    imdb_id = "tt0944947" # Game of Thrones
    season = 1
    episode = 1
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://turkcealtyazi.org/"
    }
    
    # 1. Get search page
    url = f"https://turkcealtyazi.org/{imdb_id}"
    res = requests.get(url, headers=headers, timeout=10)
    doc = BeautifulSoup(res.text, "html.parser")
    rows = doc.select(".altsonsez1, .altsonsez2")
    
    filtered = []
    for row in rows:
        lang_span = row.select_one(".aldil span")
        is_tr = lang_span and "flagtr" in str(lang_span)
        if not is_tr:
            continue
        
        link = row.select_one(".alisim a.underline")
        if not link:
            continue
            
        sub_url = "https://turkcealtyazi.org" + link["href"]
        
        # Check Season / Episode if any
        alcd = row.select_one(".alcd")
        row_season = None
        row_episode = None
        is_package = False
        if alcd:
            text = alcd.text.strip()
            is_package = "Paket" in text
            import re
            s_match = re.search(r"S(\d+)", text, re.IGNORECASE)
            if s_match:
                row_season = int(s_match.group(1))
            e_match = re.search(r"E(\d+)", text, re.IGNORECASE)
            if e_match:
                row_episode = int(e_match.group(1))
                
        if season is not None and row_season is not None and row_season != season:
            continue
        if episode is not None and not is_package and row_episode is not None and row_episode != episode:
            continue
            
        filtered.append(sub_url)
        if len(filtered) >= 3:
            break
            
    print(f"Filtered subtitle page URLs: {filtered}")
    
    for sub_page_url in filtered:
        print(f"\n[*] Fetching subtitle page: {sub_page_url}")
        res = requests.get(sub_page_url, headers=headers, timeout=10)
        doc = BeautifulSoup(res.text, "html.parser")
        form = doc.select_one("form[action=/ind]")
        if not form:
            print("[-] Form action=/ind not found")
            continue
            
        payload = {}
        for inp in form.select("input"):
            name = inp.get("name")
            value = inp.get("value")
            if name:
                payload[name] = value
                
        print(f"[*] Submitting form with payload: {payload}")
        
        zip_headers = {
            "User-Agent": headers["User-Agent"],
            "Referer": sub_page_url,
            "Origin": "https://turkcealtyazi.org",
            "Content-Type": "application/x-www-form-urlencoded"
        }
        
        zip_res = requests.post("https://turkcealtyazi.org/ind", headers=zip_headers, data=payload, timeout=10)
        print(f"[*] Response Status Code: {zip_res.status_code}")
        print(f"[*] Content-Type: {zip_res.headers.get('Content-Type')}")
        
        if zip_res.status_code != 200:
            continue
            
        # Try reading zip
        try:
            zip_file = zipfile.ZipFile(io.BytesIO(zip_res.content))
            files = zip_file.namelist()
            print(f"[+] Files in ZIP: {files}")
            for f in files:
                if f.lower().endswith(".srt") and not f.lower().startswith("__macosx"):
                    content_bytes = zip_file.read(f)
                    print(f"[+] Read SRT file: {f} ({len(content_bytes)} bytes)")
                    
                    # Test decode
                    encodings = ["utf-8", "windows-1254", "iso-8859-9"]
                    decoded = None
                    for enc in encodings:
                        try:
                            decoded = content_bytes.decode(enc)
                            if "-->" in decoded:
                                print(f"[+] Successfully decoded using {enc}!")
                                break
                        except Exception as e:
                            pass
                    if decoded:
                        print("First 200 chars of decoded SRT:")
                        print(decoded[:200])
                    break
        except Exception as e:
            print(f"[!] ZIP reading failed: {e}")

if __name__ == "__main__":
    test_full_flow()
