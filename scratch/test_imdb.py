import requests

HEADERS = {
    "Accept": "*/*",
    "Origin": "https://cineby.sc",
    "Referer": "https://cineby.sc/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
}

# Test with empty imdbId
url = "https://api.videasy.net/mb-flix/sources-with-title?title=Person%2520of%2520Interest&mediaType=tv&year=2011&episodeId=1&seasonId=1&tmdbId=1411&imdbId="

try:
    r = requests.get(url, headers=HEADERS, timeout=10)
    print(f"Status with empty IMDb: {r.status_code}")
    print(f"Response length: {len(r.text)}")
except Exception as e:
    print(f"Error: {e}")
