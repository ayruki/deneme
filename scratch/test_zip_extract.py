import requests
from bs4 import BeautifulSoup
import io
import zipfile
import re
import base64

def srt_to_vtt(srt_content):
    clean_bom = srt_content.replace("\uFEFF", "").replace("\uEFBBBF", "")
    cleaned = clean_bom.replace("\r\n", "\n").replace("\r", "\n")
    
    vtt_lines = []
    for line in cleaned.split("\n"):
        if "-->" in line:
            vtt_lines.append(line.replace(",", "."))
        else:
            vtt_lines.append(line)
            
    return "WEBVTT\n\n" + "\n".join(vtt_lines)

def test_arrow_vtt():
    imdb_id = "tt2193021"
    season = 1
    episode = 1
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://turkcealtyazi.org/"
    }
    
    url = f"https://turkcealtyazi.org/{imdb_id}"
    res = requests.get(url, headers=headers, timeout=10)
    doc = BeautifulSoup(res.text, "html.parser")
    rows = doc.select(".altsonsez1, .altsonsez2")
    
    for row in rows:
        lang_span = row.select_one(".aldil span")
        if lang_span and "flagtr" in str(lang_span):
            link = row.select_one(".alisim a.underline")
            if link:
                sub_url = "https://turkcealtyazi.org" + link["href"]
                # Just take the first TR subtitle
                print(f"Testing subtitle: {link.text.strip()}")
                res2 = requests.get(sub_url, headers=headers)
                doc2 = BeautifulSoup(res2.text, "html.parser")
                form = doc2.select_one('form[action="/ind"]')
                if form:
                    payload = {inp.get("name"): inp.get("value") for inp in form.select("input") if inp.get("name")}
                    zip_res = requests.post("https://turkcealtyazi.org/ind", headers={
                        "User-Agent": headers["User-Agent"],
                        "Referer": sub_url,
                        "Origin": "https://turkcealtyazi.org",
                        "Content-Type": "application/x-www-form-urlencoded"
                    }, data=payload)
                    
                    zip_file = zipfile.ZipFile(io.BytesIO(zip_res.content))
                    for f in zip_file.namelist():
                        if f.lower().endswith(".srt") and "101" in f:
                            srt_bytes = zip_file.read(f)
                            srt_text = srt_bytes.decode("windows-1254")
                            vtt_text = srt_to_vtt(srt_text)
                            print("\n--- Converted VTT Content (First 400 chars) ---")
                            print(vtt_text[:400])
                            print("------------------------------------------------\n")
                            
                            # Let's also check if it contains -->
                            print(f"Contains '-->': {'-->' in vtt_text}")
                            return

if __name__ == "__main__":
    test_arrow_vtt()
