import requests

direct_url = "https://download2273.mediafire.com/y78d80uc64lgRcAzLs28o8jB_FyPFeg7IjO1EqdoJKXmpU63qY-I01S00FbQU1JPxVVmbfKTrxf4d4bqozRtsiGeTJLHwi_KAfixMNHANjwACjktRHRS1YqDdMrKW0PPpwjS3VaySQ-VOxOXT4y21XPLbKEkRS2HDucXC_HFZw/q7cao3p4y2v57oo/giant1.mp4"

# Test 1: EasyPlex User-Agent
headers1 = {
    "User-Agent": "EasyPlex (Android 13; SM-A546E; samsung; tr)"
}
try:
    res1 = requests.head(direct_url, headers=headers1, allow_redirects=True)
    print("Test 1 (EasyPlex UA) Status:", res1.status_code)
    print("Test 1 Headers:", res1.headers)
except Exception as e:
    print("Test 1 Error:", e)

# Test 2: Standard Browser User-Agent
headers2 = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
}
try:
    res2 = requests.head(direct_url, headers=headers2, allow_redirects=True)
    print("Test 2 (Browser UA) Status:", res2.status_code)
    print("Test 2 Headers:", res2.headers)
except Exception as e:
    print("Test 2 Error:", e)
