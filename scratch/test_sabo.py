from curl_cffi import requests
import re
import urllib.parse
import json
import base64

tmdb_api_key = "a2f888b27315e62e471b2d587048f32e"
main_url = "https://cinemacity.cc"
cookie_val = (
    "cf_clearance=vZ.vUOvew2b4sHAQZO_R2J7JmYfBQ0e02IRREp.AQcc-1779647771-1.2.1.1-KWxSuoYkDTQOoEg.f1kX21DoHJVwIL5ArHjAH3zEUejNfUa0GXy9aJkLyTP.xRLHDCbvWwjDFCkzXFuQVu.Jn1vyp_DuPAAdpFYGTF.j8RgYJxL7Bux4qRdE9xvowYL5c1_wmARxAiad0vYElPxIh2tzAWv.Yzrli5pdk789odglPDjfRM_rY9zpnVkq4gbobeTItks9nKKmVETqpZBPrUmg_XUcoQsrKlki1TsxLWs.GHe7gqmHwsvbGgxSN1CzEoplBMeLJ2Q58LQd6PUvafpFD5LK2njA84eQHH.RJnFXDMAvfGHLYWXIjHbp48oNYjdYKMftmI5GbPF5BwSYUuO1n8w1IGPNJXA.m5LmKDn4TyPr0_ZX1TvU.A9wN.tiYATuo3bFF7sa26Fqf_HL9taDv0wAQwXXF76Fef3F.Us; "
    "dle_newpm=0; "
    "dle_password=647e16b9bd081f20506a81128b8317c5; "
    "dle_user_id=35913; "
    "PHPSESSID=dke2gg748oe9e6midv0nk4ngg0; "
    "viewed_ids=1985,1978,1902,1622,1982,1953,1000,1068,1716,1628,1922,1795,1278,1408,1624,1396,1782,1546,1326,1633"
)

# We will use curl_cffi requests session with chrome impersonation
session = requests.Session(impersonate="chrome124")

# Set cookies into the session cookie jar
for item in cookie_val.split(";"):
    item = item.strip()
    if "=" in item:
        k, v = item.split("=", 1)
        session.cookies.set(k, v, domain=".cinemacity.cc")

headers = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    "Cache-Control": "max-age=0",
    "Referer": "https://cinemacity.cc/",
    "Sec-Ch-Ua": '"Not-A.Brand";v="99", "Chromium";v="124", "Google Chrome";v="124"',
    "Sec-Ch-Ua-Mobile": "?0",
    "Sec-Ch-Ua-Platform": '"Windows"',
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    "Upgrade-Insecure-Requests": "1",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

def get_imdb_id(tmdb_id, tmdb_type):
    url = f"https://api.themoviedb.org/3/{tmdb_type}/{tmdb_id}/external_ids?api_key={tmdb_api_key}"
    print(f"Fetching IMDB ID for TMDB ID {tmdb_id} from {url}")
    try:
        res = session.get(url, timeout=10)
        print(f"IMDB API status: {res.status_code}")
        if res.status_code == 200:
            data = res.json()
            imdb_id = data.get("imdb_id")
            print(f"IMDB ID found: {imdb_id}")
            return imdb_id
    except Exception as e:
        print(f"Error fetching IMDB ID: {e}")
    return None

def get_tmdb_title(tmdb_id, tmdb_type):
    url = f"https://api.themoviedb.org/3/{tmdb_type}/{tmdb_id}?api_key={tmdb_api_key}&language=en-US"
    print(f"Fetching Title for TMDB ID {tmdb_id} from {url}")
    try:
        res = session.get(url, timeout=10)
        print(f"TMDB Title API status: {res.status_code}")
        if res.status_code == 200:
            data = res.json()
            title = data.get("title") or data.get("name")
            print(f"TMDB Title found: {title}")
            return title
    except Exception as e:
        print(f"Error fetching TMDB Title: {e}")
    return None

def search(query):
    encoded = urllib.parse.quote(query)
    url = f"{main_url}/?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story={encoded}"
    print(f"Searching on cinemacity.cc: {url}")
    try:
        res = session.get(url, headers=headers, timeout=15)
        print(f"Search status code: {res.status_code}")
        # print first 500 chars of body
        print(res.text[:500])
        
        # Let's parse with BeautifulSoup
        try:
            from bs4 import BeautifulSoup
            soup = BeautifulSoup(res.text, 'html.parser')
            items = []
            for el in soup.select("div.dar-short_item"):
                # Find first child that is an 'a' tag
                a_tag = el.find('a')
                if a_tag and a_tag.get('href'):
                    href = a_tag.get('href')
                    full_url = href if href.startswith("http") else main_url + href
                    items.append(full_url)
            print(f"Found {len(items)} items using BeautifulSoup: {items}")
            return items
        except ImportError:
            # Fallback regex search
            print("BeautifulSoup not available, using regex fallback")
            links = re.findall(r'class=["\']dar-short_item["\'].*?<a\s+href=["\']([^"\']+)["\']', res.text, re.DOTALL)
            print(f"Found {len(links)} items using regex fallback: {links}")
            return links
    except Exception as e:
        print(f"Error searching: {e}")
    return []

def test_run():
    print("--- Fetching homepage ---")
    try:
        res = session.get("https://cinemacity.cc/", headers=headers, timeout=15)
        print(f"Homepage status code: {res.status_code}")
        print(res.text[:500])
    except Exception as e:
        print(f"Error fetching homepage: {e}")

    imdb_id = get_imdb_id(100088, "tv")
    title = get_tmdb_title(100088, "tv")
    if imdb_id:
        print("\n--- Searching by IMDB ID ---")
        search(imdb_id)
    if title:
        print("\n--- Searching by Title ---")
        search(title)

if __name__ == "__main__":
    test_run()
