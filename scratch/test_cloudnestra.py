import requests
import re
import json
import sys

TMDB_API_KEY = '439c478a771f35c05022f9feabcca01c'
TMDB_BASE = 'https://api.themoviedb.org/3'
PROVIDER_ID = 'alas-vidsrc'

try:
    from curl_cffi import requests as requests_cffi
except ImportError:
    requests_cffi = None

def safe_fetch(url, method='GET', headers=None, json_data=None):
    if not headers:
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        }
    elif 'User-Agent' not in headers and 'user-agent' not in headers:
        headers['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

    try:
        if method.upper() == 'POST':
            response = requests.post(url, headers=headers, json=json_data, timeout=30)
        else:
            response = requests.get(url, headers=headers, timeout=30)
        
        response.raise_for_status()
        return response
    except Exception as e:
        # Fallback to curl_cffi only if requests fails and it's available
        if requests_cffi:
            try:
                if method.upper() == 'POST':
                    response = requests_cffi.post(url, headers=headers, json=json_data, timeout=30, impersonate="chrome")
                else:
                    response = requests_cffi.get(url, headers=headers, timeout=30, impersonate="chrome")
                response.raise_for_status()
                return response
            except Exception as e2:
                sys.stderr.write(f"[Fetch Error] {url} -> {str(e2)}\n")
        else:
            sys.stderr.write(f"[Fetch Error] {url} -> {str(e)}\n")
        return None

def infer_quality_score(text):
    value = str(text or '').lower()
    if '2160' in value or '4k' in value: return 2160
    if '1440' in value: return 1440
    if '1080' in value: return 1080
    if '720' in value: return 720
    if '480' in value: return 480
    if '360' in value: return 360
    return 0

def to_quality_label(score):
    if score >= 2160: return '2160p'
    if score >= 1440: return '1440p'
    if score >= 1080: return '1080p'
    return 'Auto'

def max_resolution_from_m3u8_text(text):
    input_text = str(text or '')
    max_y = 0
    re_res = re.compile(r'RESOLUTION=\s*\d+\s*x\s*(\d+)', re.IGNORECASE)
    matches = re_res.findall(input_text)
    for m in matches:
        y = int(m)
        if y > max_y: max_y = y
    return max_y

def detect_playlist_max_quality(url, headers=None):
    try:
        res = safe_fetch(url, headers=headers)
        if res:
            return max_resolution_from_m3u8_text(res.text)
    except:
        pass
    return 0

def tmdb_fetch(path):
    url = f"{TMDB_BASE}{path}"
    params = {'api_key': TMDB_API_KEY}
    try:
        res = requests.get(url, params=params, timeout=10)
        if res.status_code == 200:
            return res.json()
    except:
        pass
    return None

def get_imdb_id(tmdb_id, media_type):
    media_type = 'tv' if media_type == 'tv' else 'movie'
    if media_type == 'movie':
        movie = tmdb_fetch(f"/movie/{tmdb_id}")
        return movie.get('imdb_id') if movie else None
    
    tv = tmdb_fetch(f"/tv/{tmdb_id}")
    if not tv: return None
    ext = tmdb_fetch(f"/tv/{tmdb_id}/external_ids")
    return ext.get('imdb_id') if ext else None

def resolve_cloudnestra_streams(imdb_id, media_type, season_num=1, episode_num=1):
    headers_cloud = {
        'Referer': 'https://cloudnestra.com/',
        'Origin': 'https://cloudnestra.com',
        'User-Agent': 'Mozilla/5.0'
    }

    if media_type == 'tv':
        embed_url = f"https://vsembed.ru/embed/tv?imdb={imdb_id}&season={season_num}&episode={episode_num}"
    else:
        embed_url = f"https://vsembed.ru/embed/{imdb_id}"

    embed_res = safe_fetch(embed_url, headers={'User-Agent': 'Mozilla/5.0'})
    if not embed_res: 
        sys.stderr.write(f"[Debug] Failed to fetch embed_url: {embed_url}\n")
        return [], []
    
    embed_html = embed_res.text
    iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', embed_html)
    iframe_src = iframe_match.group(1) if iframe_match else None
    if not iframe_src: 
        sys.stderr.write(f"[Debug] No iframe found in embed_html\n")
        return [], []

    if iframe_src.startswith('//'):
        iframe_src = 'https:' + iframe_src

    iframe_res = safe_fetch(iframe_src, headers={
        'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'referer': 'https://vsembed.ru/'
    })
    if not iframe_res: 
        sys.stderr.write(f"[Debug] Failed to fetch iframe_src: {iframe_src}\n")
        return [], []

    iframe_html = iframe_res.text
    prorcp_match = re.search(r'src:\s*["\']([^"\']+)["\']', iframe_html)
    prorcp_src = prorcp_match.group(1) if prorcp_match else None
    if not prorcp_src: 
        sys.stderr.write(f"[Debug] No prorcp_src found in iframe_html. Snippet: {iframe_html[:1000]}\n")
        return [], []

    cloud_url = f"https://cloudnestra.com{prorcp_src}"
    cloud_res = safe_fetch(cloud_url, headers={'referer': 'https://cloudnestra.com/'})
    if not cloud_res: 
        sys.stderr.write(f"[Debug] Failed to fetch cloud_url: {cloud_url}\n")
        return [], []
    
    cloud_html = cloud_res.text
    with open("scratch/cloud_dump.html", "w", encoding="utf-8") as f:
        f.write(cloud_html)

    # Extract Subtitles
    subtitles = []
    subs_matches = re.findall(r'default_subtitles\s*=\s*["\'`](.*?)["\'`]', cloud_html, re.S)
    if subs_matches:
        subs_raw = subs_matches[-1]
        if subs_raw:
            parts = subs_raw.split(',')
            for part in parts:
                if '[' in part and ']' in part:
                    lang_match = re.search(r'\[(.*?)\](.*)', part)
                    if lang_match:
                        lang = lang_match.group(1).strip()
                        sub_url_raw = lang_match.group(2).strip()
                        
                        if not lang or not sub_url_raw:
                            continue
                            
                        sub_url = sub_url_raw
                        if not sub_url.startswith('http'):
                            sub_url = f"https://cloudnestra.com{sub_url}"
                        
                        sub_obj = {
                            "lang": lang,
                            "url": sub_url
                        }
                        
                        # Turkish priority
                        l_lower = lang.lower()
                        if 'turkish' in l_lower or 'türkçe' in l_lower or l_lower == 'tr':
                            subtitles.insert(0, sub_obj)
                        else:
                            subtitles.append(sub_obj)

    hidden_match = re.search(r'<div id="([^"]+)"[^>]*style=["\']display\s*:\s*none;?["\'][^>]*>([a-zA-Z0-9:/.,{}\-_=+ ]+)</div>', cloud_html)
    if not hidden_match: 
        sys.stderr.write(f"[Debug] No hidden div found in cloud_html\n")
        return [], subtitles
    
    div_id = hidden_match.group(1)
    div_text = hidden_match.group(2)

    dec_res = safe_fetch('https://enc-dec.app/api/dec-cloudnestra', method='POST', json_data={
        'text': div_text,
        'div_id': div_id
    })
    
    if not dec_res: 
        sys.stderr.write(f"[Debug] Decoding request failed\n")
        return [], subtitles
    urls = dec_res.json().get('result', [])
    sys.stderr.write(f"[Debug] Found {len(urls)} URLs after decoding\n")

    results = []
    for idx, stream_url in enumerate(urls):
        if not stream_url: continue
        score_from_url = infer_quality_score(stream_url)
        max_from_playlist = detect_playlist_max_quality(stream_url, headers=headers_cloud)
        assumed = 1080 if '.m3u8' in stream_url else 0
        score = max(score_from_url, max_from_playlist, assumed)
        
        if score < 1080: continue

        results.append({
            'name': f"{PROVIDER_ID} - Server {idx + 1}",
            'url': stream_url,
            'quality': to_quality_label(score),
            'headers': headers_cloud,
            'provider': PROVIDER_ID,
            '_score': score
        })

    results.sort(key=lambda x: x['_score'], reverse=True)
    for r in results: del r['_score']
    return results, subtitles

def get_streams(tmdb_id, media_type, season_num=1, episode_num=1):
    try:
        imdb_id = get_imdb_id(tmdb_id, media_type)
        if not imdb_id: return {"error": "IMDB ID not found"}
        streams, subtitles = resolve_cloudnestra_streams(imdb_id, media_type, season_num, episode_num)
        return {"sources": streams, "subtitles": subtitles}
    except Exception as e:
        return {"error": str(e)}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python cloudnestra.py <tmdb_id> [season] [episode]"}))
        sys.exit(1)

    tmdb_id = sys.argv[1]
    season = sys.argv[2] if len(sys.argv) > 2 else None
    episode = sys.argv[3] if len(sys.argv) > 3 else None
    
    media_type = 'tv' if season and episode else 'movie'
    
    result = get_streams(tmdb_id, media_type, season, episode)
    print(json.dumps(result, indent=2, ensure_ascii=False))
