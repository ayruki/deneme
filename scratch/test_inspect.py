import requests
from bs4 import BeautifulSoup

def inspect_response():
    imdb_id = "tt2193021"
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
                print(f"Fetching subtitle page: {sub_url}")
                
                # We need a Session to persist cookies!
                session = requests.Session()
                session.headers.update(headers)
                
                # Fetch sub page to get cookies
                res2 = session.get(sub_url)
                doc2 = BeautifulSoup(res2.text, "html.parser")
                form = doc2.select_one('form[action="/ind"]')
                if form:
                    payload = {inp.get("name"): inp.get("value") for inp in form.select("input") if inp.get("name")}
                    print(f"Form Payload: {payload}")
                    
                    zip_res = session.post("https://turkcealtyazi.org/ind", headers={
                        "Origin": "https://turkcealtyazi.org",
                        "Content-Type": "application/x-www-form-urlencoded"
                    }, data=payload)
                    
                    print(f"Status Code: {zip_res.status_code}")
                    print(f"Content Type: {zip_res.headers.get('Content-Type')}")
                    print(f"Content Length: {len(zip_res.content)}")
                    print("First 200 chars of response:")
                    print(zip_res.content[:200])
                    return

if __name__ == "__main__":
    inspect_response()
