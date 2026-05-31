import requests
import re
import json

TMDB_API_KEY = 'a2f888b27315e62e471b2d587048f32e'
TMDB_BASE = 'https://api.themoviedb.org/3'
PROVIDER_ID = 'alas-vidsrc'

def safe_fetch(url, headers=None, method='GET', json_data=None):
    if not headers:
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        }
    try:
        if method.upper() == 'POST':
            response = requests.post(url, headers=headers, json=json_data, timeout=30)
        else:
            response = requests.get(url, headers=headers, timeout=30)
        
        response.raise_for_status()
        return response
    except Exception as e:
        print(f"[Fetch Error] {url} -> {str(e)}")
        return None

def get_imdb_id(tmdb_id, media_type):
    tmdb_type = 'tv' if media_type == 'tv' else 'movie'
    url = f"{TMDB_BASE}/{tmdb_type}/{tmdb_id}?api_key={TMDB_API_KEY}&language=en-US"
    ext_url = f"{TMDB_BASE}/{tmdb_type}/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}"
    try:
        res = requests.get(url, timeout=10).json()
        ext_res = requests.get(ext_url, timeout=10).json()
        return ext_res.get('imdb_id')
    except Exception as e:
        print(f"Error fetching TMDB meta: {e}")
    return None

def resolve_vidsrcme_streams(tmdb_id, media_type, season=1, episode=1):
    # Determine the embed URL on vidsrcme.ru
    if media_type == 'tv':
        embed_url = f"https://vidsrcme.ru/embed/tv?tmdb={tmdb_id}&season={season}&episode={episode}"
    else:
        embed_url = f"https://vidsrcme.ru/embed/movie?tmdb={tmdb_id}"

    print(f"1. Fetching embed page: {embed_url}")
    embed_res = safe_fetch(embed_url)
    if not embed_res:
        print("Failed to fetch embed page.")
        return
        
    embed_html = embed_res.text
    
    # Let's search for an iframe or scripts
    iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', embed_html)
    iframe_src = iframe_match.group(1) if iframe_match else None
    if not iframe_src:
        # Sometimes it's loaded via JavaScript, let's look for iframe attributes or patterns
        print("No direct iframe src found. Let's dump part of HTML:")
        print(embed_html[:2000])
        return

    print(f"2. Found Iframe Source: {iframe_src}")
    if iframe_src.startswith('//'):
        iframe_src = 'https:' + iframe_src

    # Fetch the iframe page
    iframe_headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': embed_url
    }
    print(f"3. Fetching Iframe: {iframe_src}")
    iframe_res = safe_fetch(iframe_src, headers=iframe_headers)
    if not iframe_res:
        print("Failed to fetch iframe.")
        return
        
    iframe_html = iframe_res.text
    
    # Search for prorcp_src or script src
    prorcp_match = re.search(r'src:\s*["\']([^"\']+)["\']', iframe_html)
    prorcp_src = prorcp_match.group(1) if prorcp_match else None
    if not prorcp_src:
        print("No prorcp_src found in iframe_html. Searching alternative scripts...")
        print(iframe_html[:1500])
        return
        
    print(f"4. Found prorcp_src: {prorcp_src}")
    
    # cloudnestra.com url
    cloud_url = f"https://cloudnestra.com{prorcp_src}"
    print(f"5. Fetching cloudnestra page: {cloud_url}")
    cloud_headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': 'https://cloudnestra.com/'
    }
    cloud_res = safe_fetch(cloud_url, headers=cloud_headers)
    if not cloud_res:
        print("Failed to fetch cloudnestra url.")
        return
        
    cloud_html = cloud_res.text
    
    # Now let's see if we can find the hidden div or subtitles
    hidden_match = re.search(r'<div id="([^"]+)"[^>]*style=["\']display\s*:\s*none;?["\'][^>]*>([a-zA-Z0-9:/.,{}\-_=+ ]+)</div>', cloud_html)
    if not hidden_match:
        print("Failed to find hidden div. Writing cloud_html to cloud_dump_vidsrcme.html...")
        with open("scratch/cloud_dump_vidsrcme.html", "w", encoding="utf-8") as f:
            f.write(cloud_html)
        return
        
    div_id = hidden_match.group(1)
    div_text = hidden_match.group(2)
    print(f"6. Found Hidden Div: ID={div_id}, Length={len(div_text)}")
    
    # Decrypt using the API
    print("7. Decrypting hidden div text...")
    dec_res = safe_fetch('https://enc-dec.app/api/dec-cloudnestra', method='POST', json_data={
        'text': div_text,
        'div_id': div_id
    })
    
    if not dec_res:
        print("Failed to decode.")
        return
        
    dec_json = dec_res.json()
    print("\n--- RESULTS ---")
    print(json.dumps(dec_json, indent=2, ensure_ascii=False))

if __name__ == "__main__":
    # Test with tmdb_id=550 (Fight Club)
    resolve_vidsrcme_streams(550, "movie")
