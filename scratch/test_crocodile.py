import requests
import urllib.parse
import json

imdb_id = "tt5314528"
encoded = urllib.parse.quote(imdb_id)
main_url = "https://dizillahd.com"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": f"{main_url}/"
}

url = f"{main_url}/api/bg/searchContent?searchterm={encoded}"
print("Requesting:", url)
try:
    res = requests.post(url, headers=headers)
    print("Status:", res.status_code)
    print("Response text:", res.text)
except Exception as e:
    print("Error:", e)
