import requests

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
    "Referer": "https://cineby.sc/",
    "Origin": "https://cineby.sc"
}

# Real M3U8 link from test run
m3u8_url = "https://easy.speedsterwave.app/ceyXc57PgrFATzVaLqVSWQ0UJSaPsQXGxHJH-93VyZVlqFLDaXg5qbqGqu_vuUg8pkA09cjAvykFn30wIgKRyXTcywy9LaVu49H7CHXvNCrWhaHGa0ePR-HHkZeTlfj2w-9VPtI6P-a-Ys6Y8pDaV_PMT-IVd2T3-I3LatISmXdegxyn1-D01ZOUd37Q6d85VZPInBZFsj-MgvQi386Njxqdzv5K1ro2LWlD15W7PaEAHM2hYrE5HLfaTZGtAR8UST4qlek-U82UoTUW6zEX9098HXH6gLiJyio3TiBfJJO7diIkTZO1zp2KpvxWoOReTw3VjF-UQ_8eVW7SXjUg9R_4W6AWrD9kICQFKj5oKKju2H5LbAjno3FaAENZUhYIIRLkllHsFLP9hy7KcIRQeYFqoyoi3q7qUD5AtvUExQ-R9VDAQDua9pQL542SwMleuzxYBUh7ZsOsWRMYYUsKz9-3cpac-X1wQIiTJdjGeaXXjLxYsT16WkycLMhmVD_784pvmNzHZqDUjBusu7yk4Vp0EgrR4Bp_J4KTDuXuDu7XG4-cdi8ck66DTFjE1ASIMDc5okwZjOJgs1ns1S7NZFJsLhf7wdDIBQTj-SLn5rl5IMwuI8Un_UE1nfZ-zGbyfbGN8422fAjYk-YmTWCZ_l/index.m3u8"

print("Testing with HEADERS:")
try:
    r = requests.get(m3u8_url, headers=HEADERS, timeout=10)
    print(f"Status: {r.status_code}")
    print(f"Content snippet: {r.text[:200]}")
except Exception as e:
    print(f"Error with headers: {e}")

print("\nTesting WITHOUT Referer/Origin:")
try:
    r = requests.get(m3u8_url, headers={"User-Agent": HEADERS["User-Agent"]}, timeout=10)
    print(f"Status: {r.status_code}")
    print(f"Content snippet: {r.text[:200]}")
except Exception as e:
    print(f"Error without referer/origin: {e}")
