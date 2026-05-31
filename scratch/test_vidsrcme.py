import requests

url = "https://vidsrcme.ru/embed/movie?tmdb=550"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Referer": "https://vidsrcme.ru/"
}

try:
    print(f"Fetching: {url}")
    resp = requests.get(url, headers=headers, timeout=15)
    print(f"Status Code: {resp.status_code}")
    print("Response text:")
    print(resp.text[:3000])
except Exception as e:
    print(f"Error: {e}")
