import requests
import json
from urllib.parse import quote, unquote

TMDB_API_KEY = "a2f888b27315e62e471b2d587048f32e"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

def get_tmdb_meta(tmdb_id, media_type):
    tmdb_type = "movie" if media_type == "movie" else "tv"
    url = f"https://api.themoviedb.org/3/{tmdb_type}/{tmdb_id}?api_key={TMDB_API_KEY}&language=en-US"
    ext_url = f"https://api.themoviedb.org/3/{tmdb_type}/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}"
    
    try:
        resp = requests.get(url, timeout=10).json()
        title = resp.get("title") or resp.get("name") or ""
        release_date = resp.get("release_date") or resp.get("first_air_date") or ""
        year = release_date.split("-")[0] if "-" in release_date else ""
        
        ext_resp = requests.get(ext_url, timeout=10).json()
        imdb_id = ext_resp.get("imdb_id") or ""
        
        return title, year, imdb_id
    except Exception as e:
        print(f"Error fetching TMDB metadata: {e}")
        return "", "", ""

def run_chopper(tmdb_id, media_type, season, episode):
    title, year, imdb_id = get_tmdb_meta(tmdb_id, media_type)
    if not title:
        print("Could not retrieve metadata from TMDB.")
        return
    
    print(f"\n[Chopper] Found Meta: {title} ({year}) | IMDb: {imdb_id}")
    
    enc_title = quote(quote(title, safe=""), safe="")
    headers = {
        "Accept": "*/*",
        "Origin": "https://cineby.sc",
        "Referer": "https://cineby.sc/",
        "User-Agent": UA
    }
    
    servers = ["mb-flix", "cdn"]
    found_any = False
    
    for server in servers:
        if media_type == "movie":
            url = f"https://api.videasy.net/{server}/sources-with-title?title={enc_title}&mediaType=movie&year={year}&tmdbId={tmdb_id}&imdbId={imdb_id}"
        else:
            url = f"https://api.videasy.net/{server}/sources-with-title?title={enc_title}&mediaType=tv&year={year}&episodeId={episode}&seasonId={season}&tmdbId={tmdb_id}&imdbId={imdb_id}"
            
        print(f"\n[{server}] Fetching: {url}")
        try:
            resp = requests.get(url, headers=headers, timeout=15)
            enc_data = resp.text
            if not enc_data:
                print(f"[{server}] No data received.")
                continue
                
            print(f"[{server}] Decrypting response...")
            dec_payload = {"text": enc_data, "id": str(tmdb_id)}
            dec_resp = requests.post("https://enc-dec.app/api/dec-videasy", headers=headers, json=dec_payload, timeout=15)
            
            if dec_resp.status_code == 200:
                res_json = dec_resp.json()
                if res_json.get("status") == 200:
                    print(f"\n--- RESULTS from {server} ---")
                    print(json.dumps(res_json, indent=2, ensure_ascii=False))
                    found_any = True
                else:
                    print(f"[{server}] Decrypted response status: {res_json.get('status')}")
            else:
                print(f"[{server}] Decryption API error: {dec_resp.status_code} - {dec_resp.text}")
        except Exception as e:
            print(f"Error in Chopper source ({server}): {e}")
            
    if not found_any:
        print("\nCould not find working streams from any Chopper servers.")

def run_nusjuro(tmdb_id, media_type, season, episode):
    tmdb_type = "movie" if media_type == "movie" else "tv"
    
    print(f"\n[Nusjuro] Searching in database for TMDB ID: {tmdb_id} ({tmdb_type})...")
    db_url = f"https://enc-dec.app/db/flix/find?tmdb_id={tmdb_id}&type={tmdb_type}"
    
    try:
        db_resp = requests.get(db_url, timeout=10).json()
        if not db_resp or len(db_resp) == 0:
            print("No entry found in database.")
            return
            
        entry = db_resp[0]
        episodes_data = entry.get("episodes", {})
        
        s_str = "1" if tmdb_type == "movie" else str(season)
        e_str = "1" if tmdb_type == "movie" else str(episode)
        
        season_obj = episodes_data.get(s_str, {})
        ep_obj = season_obj.get(e_str, {})
        eid = ep_obj.get("eid")
        
        if not eid:
            print(f"Episode / Movie ID (eid) not found for S{s_str}E{e_str}")
            return
            
        print(f"Found EID: {eid}. Encrypting EID...")
        enc_eid_url = f"https://enc-dec.app/api/enc-movies-flix?text={eid}"
        enc_eid = requests.get(enc_eid_url, timeout=10).json().get("result")
        
        if not enc_eid:
            print("Failed to encrypt EID.")
            return
            
        print("Fetching server links...")
        headers = {
            "User-Agent": UA,
            "Referer": "https://yflix.to/",
            "Accept": "application/json"
        }
        
        servers_url = f"https://yflix.to/ajax/links/list?eid={eid}&_={enc_eid}"
        servers_raw_resp = requests.get(servers_url, headers=headers, timeout=10)
        
        try:
            servers_resp = servers_raw_resp.json()
            servers_html = servers_resp.get("result")
        except Exception as json_err:
            print(f"Failed to parse server list JSON: {json_err}")
            print(f"Status Code: {servers_raw_resp.status_code}")
            print(f"Raw Response: {servers_raw_resp.text[:500]}")
            return
        
        if not servers_html:
            print("Failed to load server list HTML.")
            print(f"Status Code: {servers_raw_resp.status_code}")
            print(f"Raw Response: {servers_raw_resp.text[:500]}")
            return
            
        print("Parsing server list HTML...")
        parse_resp = requests.post("https://enc-dec.app/api/parse-html", headers=headers, json={"text": servers_html}, timeout=10).json()
        servers = parse_resp.get("result", {})
        
        found_any = False
        
        for s_type, s_list in servers.items():
            if not isinstance(s_list, dict):
                continue
            for s_id, s_info in s_list.items():
                lid = s_info.get("lid")
                server_name = s_info.get("name", "Unknown Server")
                if not lid:
                    continue
                    
                print(f"\nProcessing server: {server_name} (LID: {lid})")
                enc_lid_url = f"https://enc-dec.app/api/enc-movies-flix?text={lid}"
                enc_lid = requests.get(enc_lid_url, timeout=10).json().get("result")
                
                if not enc_lid:
                    continue
                    
                view_url = f"https://yflix.to/ajax/links/view?id={lid}&_={enc_lid}"
                view_resp = requests.get(view_url, headers=headers, timeout=10).json()
                encrypted_text = view_resp.get("result")
                
                if not encrypted_text:
                    continue
                    
                dec_resp = requests.post("https://enc-dec.app/api/dec-movies-flix", headers=headers, json={"text": encrypted_text}, timeout=10).json()
                result_data = dec_resp.get("result", {})
                embed_url = result_data.get("url")
                
                if not embed_url:
                    continue
                    
                print(f"  Embed URL: {embed_url}")
                found_any = True
                
                # Check for direct rapidshare/vidcloud decryptions
                if "/e/" in embed_url:
                    media_url = embed_url.replace("/e/", "/media/")
                    referer = embed_url.split("/e/")[0] + "/"
                    media_headers = {
                        "Referer": referer,
                        "User-Agent": UA,
                        "Accept": "application/json"
                    }
                    
                    media_resp = requests.get(media_url, headers=media_headers, timeout=10).json()
                    media_enc = media_resp.get("result")
                    if media_enc:
                        print("  Decrypting rapid/vidcloud direct stream links...")
                        dec_rapid_resp = requests.post(
                            "https://enc-dec.app/api/dec-rapid",
                            headers=headers,
                            json={"text": media_enc, "agent": UA},
                            timeout=10
                        ).json()
                        
                        if dec_rapid_resp.get("status") == 200:
                            direct_result = dec_rapid_resp.get("result", {})
                            print("  --- Direct Stream Results ---")
                            print(json.dumps(direct_result, indent=2, ensure_ascii=False))
                        else:
                            print(f"  Failed to decrypt rapid stream: {dec_rapid_resp}")
                            
    except Exception as e:
        print(f"Error in Nusjuro source: {e}")

def main():
    print("==============================================")
    print("      Cloudstream Plugin Tester (Python)      ")
    print("==============================================")
    print("1. Chopper")
    print("2. Nusjuro")
    choice = input("Kaynağı seçin (1 veya 2): ").strip()
    
    if choice not in ["1", "2"]:
        print("Geçersiz seçim.")
        return
        
    print("\nİçerik Türü:")
    print("1. Film (movie)")
    print("2. Dizi (tv)")
    type_choice = input("Tür seçin (1 veya 2): ").strip()
    media_type = "movie" if type_choice == "1" else "tv"
    
    tmdb_id = input("\nTMDB ID girin (Örn: Movie için 550, Dizi için 1411): ").strip()
    if not tmdb_id.isdigit():
        print("TMDB ID sadece sayı olmalıdır.")
        return
    tmdb_id = int(tmdb_id)
    
    season = 1
    episode = 1
    if media_type == "tv":
        season_input = input("Sezon girin (Varsayılan 1): ").strip()
        if season_input.isdigit():
            season = int(season_input)
            
        episode_input = input("Bölüm girin (Varsayılan 1): ").strip()
        if episode_input.isdigit():
            episode = int(episode_input)
            
    if choice == "1":
        run_chopper(tmdb_id, media_type, season, episode)
    else:
        run_nusjuro(tmdb_id, media_type, season, episode)

if __name__ == "__main__":
    main()
