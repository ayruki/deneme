import requests

url = "https://vidlop.com/cdn/hls/4799a938f34aedf56258e1ce6f0c0ed4/master.m3u8?md5=aZH-bpJC8zjmTkzbcC462w&expires=1780239412"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    "Referer": "https://vidlop.com/"
}

try:
    resp = requests.get(url, headers=headers, timeout=10)
    print(f"Status Code: {resp.status_code}")
    print("Content:")
    print(resp.text[:2000])
except Exception as e:
    print(f"Error: {e}")
