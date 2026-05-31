import requests
import json
import sys

# Define the base URL. If the user runs it on a custom domain, they can replace this.
BASE_URL = "http://localhost:3000"  # Adjust this if your FlixQuest Scraper API is running on a different port/url

def get_status():
    print(f"Checking status of FlixQuest Scraper API at {BASE_URL}...")
    try:
        r = requests.get(BASE_URL, timeout=10)
        print(f"Status Code: {r.status_code}")
        print(json.dumps(r.json(), indent=2))
        return True
    except Exception as e:
        print(f"Error connecting to API: {e}")
        return False

def test_movie(tmdb_id):
    url = f"{BASE_URL}/stream-movie?tmdbId={tmdb_id}"
    print(f"\n[Movie] Fetching stream links for TMDB ID {tmdb_id}...")
    print(f"URL: {url}")
    try:
        r = requests.get(url, timeout=30)
        print(f"Status Code: {r.status_code}")
        if r.status_code == 200:
            print("Response:")
            print(json.dumps(r.json(), indent=2))
        else:
            print(f"Failed to fetch streams: {r.text}")
    except Exception as e:
        print(f"Error: {e}")

def test_tv(tmdb_id, season, episode):
    url = f"{BASE_URL}/stream-tv?tmdbId={tmdb_id}&season={season}&episode={episode}"
    print(f"\n[TV Show] Fetching stream links for TMDB ID {tmdb_id} S{season}E{episode}...")
    print(f"URL: {url}")
    try:
        r = requests.get(url, timeout=30)
        print(f"Status Code: {r.status_code}")
        if r.status_code == 200:
            print("Response:")
            print(json.dumps(r.json(), indent=2))
        else:
            print(f"Failed to fetch streams: {r.text}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        BASE_URL = sys.argv[1]
    
    if get_status():
        # Test with a popular movie (e.g. Fight Club tmdbId=550)
        test_movie(550)
        
        # Test with a popular TV show episode (e.g. Breaking Bad S1E1 tmdbId=1396)
        test_tv(1396, 1, 1)
