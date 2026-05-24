import requests

def search_segments():
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://vidmody.com/",
        "Origin": "https://vidmody.com"
    }
    
    # We will search the first 100 segments
    print("[*] Searching segments 1 to 100 for the duplicated cue...")
    
    # Alternate hosts based on index.gif
    hosts = [
        "https://nodedatastream.top",
        "https://storagegridlink.top"
    ]
    
    for i in range(1, 100):
        # We try both hosts just in case
        found = False
        for host in hosts:
            url = f"{host}/img/tt2560140/s1/e01/lang/tur/sub_tr.vtt/thumbnail-{i}.vtt"
            try:
                res = requests.get(url, headers=headers, timeout=3)
                if res.status_code == 200:
                    text = res.text
                    if "Carla" in text or "merak" in text or "insan" in text:
                        print(f"\n[!] FOUND IN SEGMENT {i} ({url}):")
                        print("------------------------------")
                        print(text)
                        print("------------------------------\n")
                        found = True
                        break
            except Exception as e:
                pass
        if found:
            # Let's keep searching to see if there are multiple occurrences or if we just print this one
            pass

if __name__ == "__main__":
    search_segments()
