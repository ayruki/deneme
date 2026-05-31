import requests

def test_worker():
    url = "https://turkcealtyazi-worker.ayruki.workers.dev/40244/2/1"
    print(f"Fetching from worker: {url}")
    try:
        res = requests.get(url, timeout=10)
        print(f"Status Code: {res.status_code}")
        print(f"Response Content Length: {len(res.content)}")
        
        if res.status_code == 200:
            data = res.json()
            print(f"Successfully parsed JSON. Count: {len(data)}")
            for item in data[:3]:
                print("\nSubtitle Item:")
                print(f"  Title: {item.get('title')}")
                print(f"  Quality: {item.get('quality')}")
                print(f"  Translator: {item.get('translator')}")
                print(f"  Download Link: {item.get('downloadLink')}")
                
                # Fetch a download link to see if it responds with raw VTT/SRT text!
                dl_link = item.get('downloadLink')
                if dl_link:
                    print(f"  Testing Download Link: {dl_link}")
                    dl_res = requests.get(dl_link, timeout=10)
                    print(f"  Download Status Code: {dl_res.status_code}")
                    print(f"  Download Content-Type: {dl_res.headers.get('Content-Type')}")
                    # Print first 3 lines of VTT
                    lines = dl_res.text.strip().split("\n")[:8]
                    print("  First 8 lines of downloaded content:")
                    for l in lines:
                        print(f"    {l}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_worker()
