import requests
from bs4 import BeautifulSoup

def test_fetch():
    imdb_id = "tt0944947" # Game of Thrones
    url = f"https://turkcealtyazi.org/{imdb_id}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://turkcealtyazi.org/"
    }
    
    print(f"Fetching: {url}")
    res = requests.get(url, headers=headers, timeout=10)
    print(f"Status Code: {res.status_code}")
    print(f"Final URL: {res.url}")
    
    doc = BeautifulSoup(res.text, "html.parser")
    rows = doc.select(".altsonsez1, .altsonsez2")
    print(f"Found rows: {len(rows)}")
    
    for row in rows[:20]:
        lang_span = row.select_one(".aldil span")
        is_tr = "flagtr" in str(lang_span)
        link = row.select_one(".alisim a.underline")
        title = link.text.strip() if link else "None"
        href = link["href"] if link else "None"
        alcd = row.select_one(".alcd")
        alcd_text = alcd.text.strip() if alcd else "None"
        print(f"Row: Title='{title}', Link='{href}', TR={is_tr}, ALCD='{alcd_text}'")

if __name__ == "__main__":
    test_fetch()
