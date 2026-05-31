import requests
import json
from urllib.parse import quote

HEADERS = {
    "Accept": "*/*",
    "Origin": "https://cineby.sc",
    "Referer": "https://cineby.sc/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
}

DEC_API = "https://enc-dec.app/api/dec-videasy"

def clean_encode(s):
    # double url encoding matching Chopper.kt
    return quote(quote(s, safe=""), safe="")

def test_chopper(tmdb_id, title, year, media_type="movie", season=1, episode=1, imdb_id=""):
    enc_title = clean_encode(title)
    
    if media_type == "movie":
        url = f"https://api.videasy.net/mb-flix/sources-with-title?title={enc_title}&mediaType=movie&year={year}&tmdbId={tmdb_id}&imdbId={imdb_id}"
    else:
        url = f"https://api.videasy.net/mb-flix/sources-with-title?title={enc_title}&mediaType=tv&year={year}&episodeId={episode}&seasonId={season}&tmdbId={tmdb_id}&imdbId={imdb_id}"
        
    print(f"Requesting URL: {url}")
    try:
        resp = requests.get(url, headers=HEADERS, timeout=15)
        print(f"Status Code: {resp.status_code}")
        enc_data = resp.text
        print(f"Response length: {len(enc_data)}")
        if not enc_data:
            print("Empty response from videasy API.")
            return
            
        print("Decrypting with enc-dec API...")
        dec_payload = {"text": enc_data, "id": str(tmdb_id)}
        dec_resp = requests.post(DEC_API, headers=HEADERS, json=dec_payload, timeout=15)
        print(f"Decryption API Status Code: {dec_resp.status_code}")
        print("Decryption Response:")
        print(json.dumps(dec_resp.json(), indent=2, ensure_ascii=False))
    except Exception as e:
        print(f"Error occurred: {e}")

if __name__ == "__main__":
    # Test with a known movie: e.g., Person of Interest S01E01 (TMDB: 1411, IMDb: tt1839578)
    print("--- Testing TV Show ---")
    test_chopper(
        tmdb_id=1411,
        title="Person of Interest",
        year="2011",
        media_type="tv",
        season=1,
        episode=1,
        imdb_id="tt1839578"
    )
