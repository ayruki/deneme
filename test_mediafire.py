import requests
import re

def test_mediafire(url):
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"}
    res = requests.get(url, headers=headers)
    print("Status:", res.status_code)
    
    html = res.text
    match1 = re.search(r'href="([^"]+)"[^>]*id="downloadButton"', html)
    match2 = re.search(r'id="downloadButton"[^>]*href="([^"]+)"', html)
    
    if match1:
        print("Found with match1:", match1.group(1))
    elif match2:
        print("Found with match2:", match2.group(1))
    else:
        print("Could NOT find downloadButton!")
        # Let's see if we can find another download button regex
        match3 = re.search(r'href="([^"]+)"[^>]*aria-label="Download file"', html)
        if match3:
             print("Found with aria-label:", match3.group(1))

if __name__ == "__main__":
    test_mediafire("https://www.mediafire.com/file/q7cao3p4y2v57oo/giant1.mp4/file")
