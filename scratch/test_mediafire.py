import requests
import re

url = "https://www.mediafire.com/file/q7cao3p4y2v57oo/giant1.mp4/file"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
}

try:
    res = requests.get(url, headers=headers)
    print("Status code:", res.status_code)
    html = res.text
    
    match = re.search(r'href="([^"]+)"[^>]*id="downloadButton"', html) or \
            re.search(r'id="downloadButton"[^>]*href="([^"]+)"', html) or \
            re.search(r'href="(https://download[^"]+mediafire\.com/[^"]+)"', html)
            
    if match:
        print("Match found:", match.group(1))
    else:
        print("No match found.")
        # Print a snippet of the page around downloadButton if present
        idx = html.find("downloadButton")
        if idx != -1:
            print("Snippet around downloadButton:")
            print(html[max(0, idx-500):min(len(html), idx+500)])
        else:
            print("downloadButton not found in HTML!")
            # Save HTML to debug
            with open("mediafire_debug.html", "w", encoding="utf-8") as f:
                f.write(html)
except Exception as e:
    print("Error:", e)
