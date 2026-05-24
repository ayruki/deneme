import requests

direct_url = "https://download2273.mediafire.com/okgge70543kgUs1SqP_5pZXovoVLGQ0D6odvSlmscQt6TjEY3MPlpcWgZKpMr8UXRqSNQrL96YGV8Skno7V4wFDsE4HbkeWZIHWA1CqsXdEP9-cosiTG4bpou9BOXbhjgG6ve4JZ8ULCFs72lwb2CGGvH4ZbCdu4ns5m-zgb1g/q7cao3p4y2v57oo/giant1.mp4"

# Test 1: With ydfvfdizipanel.ru referer
headers1 = {
    "User-Agent": "EasyPlex (Android 13; SM-A546E; samsung; tr)",
    "Referer": "https://ydfvfdizipanel.ru/"
}
try:
    res1 = requests.head(direct_url, headers=headers1, allow_redirects=True)
    print("Test 1 (Hotlink Referer) Status:", res1.status_code)
except Exception as e:
    print("Test 1 Error:", e)

# Test 2: With mediafire referer
headers2 = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://www.mediafire.com/"
}
try:
    res2 = requests.head(direct_url, headers=headers2, allow_redirects=True)
    print("Test 2 (Mediafire Referer) Status:", res2.status_code)
except Exception as e:
    print("Test 2 Error:", e)
