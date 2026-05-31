import scrapling

def test_scrapling():
    print("Testing scrapling...")
    try:
        fetcher = scrapling.Fetcher(auto_match=True)
        response = fetcher.get("https://cinemacity.cc/")
        print("Attributes:", dir(response))
        if hasattr(response, 'status'):
            print("Status:", response.status)
        print("Title:", response.title)
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_scrapling()
