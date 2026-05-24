import requests

def test_mediafire_download_ua():
    direct_link = "https://download1350.mediafire.com/jjv2jvub0lzgycM2Mrdg5w88H0kAImsMdrwTniOleQzzAQtkqwi2ONGvp2sr49IFXZjDxTUEKvWMW7cNCRQgE1FRPaRXYUTt8TKo7bMSVvK0tKHK_oxFvxAF09ns8yMMaHyHVgydl8_LbHhQqJYswFfBhX35iN40gbPbnk4A8A/q7cao3p4y2v57oo/giant1.mp4"
    headers = {"User-Agent": "EasyPlex (Android 13; SM-A546E; samsung; tr)"}
    try:
        res = requests.head(direct_link, headers=headers)
        print("EasyPlex UA Status:", res.status_code)
    except Exception as e:
        print("Error:", e)

    headers2 = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
    try:
        res2 = requests.head(direct_link, headers=headers2)
        print("Chrome UA Status:", res2.status_code)
    except Exception as e:
        print("Error:", e)

if __name__ == "__main__":
    test_mediafire_download_ua()
