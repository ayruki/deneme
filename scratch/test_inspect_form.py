import requests
from bs4 import BeautifulSoup

def inspect_download_page():
    url = "https://turkcealtyazi.org/sub/755459/arrow.html"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://turkcealtyazi.org/"
    }
    
    session = requests.Session()
    session.headers.update(headers)
    
    res = session.get(url)
    print(f"Status Code: {res.status_code}")
    
    doc = BeautifulSoup(res.text, "html.parser")
    form = doc.select_one('form[action="/ind"]')
    if form:
        print("Form details:")
        print(f"Action: {form.get('action')}")
        print(f"Method: {form.get('method')}")
        for inp in form.select("input"):
            print(f"Input: name='{inp.get('name')}', value='{inp.get('value')}', type='{inp.get('type')}'")
    else:
        print("No form action=/ind found!")

if __name__ == "__main__":
    inspect_download_page()
