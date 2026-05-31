import requests
from bs4 import BeautifulSoup
import io
import zipfile

def test_hizmetci():
    imdb_id = "tt27543632" # Hizmetçi (2025)
    season = None
    episode = None
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://turkcealtyazi.org/"
    }
    
    # 1. Get search page
    url = f"https://turkcealtyazi.org/{imdb_id}"
    print(f"Fetching search page: {url}")
    res = requests.get(url, headers=headers, timeout=10)
    doc = BeautifulSoup(res.text, "html.parser")
    rows = doc.select(".altsonsez1, .altsonsez2")
    print(f"Found rows: {len(rows)}")
    
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
        filtered.append(sub_url)
        if len(filtered) >= 2:
            break
            
    print(f"Filtered subtitle URLs: {filtered}")
    
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
        print(f"[*] Content length: {len(zip_res.content)}")
        
        # Write response to a file to inspect if it's actually HTML or a ZIP
        with open("scratch/downloaded_response.bin", "wb") as f:
            f.write(zip_res.content)
            
        if zip_res.content.startswith(b"PK\x03\x04"):
            print("[+] Valid ZIP header found!")
        else:
            print("[-] NOT a valid ZIP header! First 100 bytes:")
            print(zip_res.content[:100])
            
        # Try reading zip
        try:
            zip_file = zipfile.ZipFile(io.BytesIO(zip_res.content))
            files = zip_file.namelist()
            print(f"[+] Files in ZIP: {files}")
            for f_name in files:
                if f_name.lower().endswith(".srt"):
                    content_bytes = zip_file.read(f_name)
                    print(f"[+] Read SRT file: {f_name} ({len(content_bytes)} bytes)")
                    # Test decode
                    decoded = content_bytes.decode("windows-1254", errors="replace")
                    print("First 150 chars of SRT:")
                    print(decoded[:150])
        except Exception as e:
            print(f"[!] ZIP reading failed: {e}")

if __name__ == "__main__":
    test_hizmetci()
