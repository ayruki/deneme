import scrapling

def test_scrapling():
    print("Testing scrapling...")
    try:
        # Launch a stealth scraper
        # Scrapling has Fetcher that automatically bypasses Cloudflare
        fetcher = scrapling.Fetcher(auto_match=True)
        response = fetcher.get("https://cinemacity.cc/")
        print(f"Status code: {response.status_code}")
        print("Page title:", response.title)
        print("Content preview:", response.text[:500])
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_scrapling()
