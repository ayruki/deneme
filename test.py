import requests
import json

headers = {
    'Accept': 'application/json, text/plain, */*',
    'Accept-Language': 'en,tr;q=0.9',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36',
    'x-e-h': 'j1stzlcwDgXT9tI0aHTBsxwdzIrlwd4vKobLjbI2Naax99OELIaH.s1SoKBwGcJ5EX2R2'
}

print('Searching for One Piece (ID 37854)')
r = requests.get('https://animecix.tv/secure/search/One Piece?limit=10', headers=headers)
print('Search by Name status:', r.status_code)
if r.status_code == 200:
    results = r.json().get('results', [])
    for res in results:
        print(f"Name: {res.get('name')}, TMDB: {res.get('tmdb_id')}, ID: {res.get('id')}")

print('\nTesting Tau Video API manually...')
r_tau = requests.get('https://tau-video.xyz/api/video/1199', headers={'User-Agent': headers['User-Agent'], 'Referer': 'https://tau-video.xyz/embed/1199'})
print('Tau API status:', r_tau.status_code)
if r_tau.status_code == 200:
    try:
        urls = r_tau.json().get('urls', [])
        for u in urls:
            print('M3U8:', u['url'])
            r_m3u8 = requests.head(u['url'], headers={'User-Agent': headers['User-Agent'], 'Referer': 'https://tau-video.xyz/embed/1199'})
            print('M3U8 HEAD with headers:', r_m3u8.status_code)
            r_m3u8_no = requests.head(u['url'])
            print('M3U8 HEAD without headers:', r_m3u8_no.status_code)
    except:
        print(r_tau.text)
