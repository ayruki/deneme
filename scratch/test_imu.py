import requests

# TMDB 46668 external IDs
ext_url = "https://api.themoviedb.org/3/tv/46668/external_ids?api_key=a2f888b27315e62e471b2d587048f32e"
res = requests.get(ext_url).json()
imdb_id = res.get("imdb_id")
print("IMDB ID:", imdb_id)

if imdb_id:
    # Vidmody URL using .to domain
    base = "https://vidmody.to"
    # S1 E1
    vidmody_url = f"{base}/vs/{imdb_id}/s01/e01"
    print("Vidmody URL:", vidmody_url)
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": f"{base}/",
        "Origin": base
    }
    
    try:
        response = requests.get(vidmody_url, headers=headers, allow_redirects=True)
        print("Status:", response.status_code)
        print("Response length:", len(response.text))
        print("Contains 'içerik bulunamadı'?", "içerik bulunamadı" in response.text)
        print("Snippet:", response.text[:1000])
    except Exception as e:
        print("Error:", e)
