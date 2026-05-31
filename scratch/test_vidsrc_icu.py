import requests
import json

url = "https://vidsrc.icu/api/movie/550"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Referer": "https://vidsrc.icu/"
}

try:
    print(f"Fetching Fight Club (550) API: {url}")
    resp = requests.get(url, headers=headers, timeout=15)
    print(f"Status Code: {resp.status_code}")
    print("Response text:")
    print(resp.text[:2000])
except Exception as e:
    print(f"Error: {e}")

tv_url = "https://vidsrc.icu/api/tv/1411/1/1"
try:
    print(f"\nFetching Person of Interest S1E1 API: {tv_url}")
    resp = requests.get(tv_url, headers=headers, timeout=15)
    print(f"Status Code: {resp.status_code}")
    print("Response text:")
    print(resp.text[:2000])
except Exception as e:
    print(f"Error: {e}")
