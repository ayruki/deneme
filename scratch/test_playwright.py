from playwright.sync_api import sync_playwright

def test_playwright():
    print("Testing Playwright...")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        # Use stealth or standard page
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        page = context.new_page()
        
        # Inject cookies
        cookies = [
            {"name": "cf_clearance", "value": "vZ.vUOvew2b4sHAQZO_R2J7JmYfBQ0e02IRREp.AQcc-1779647771-1.2.1.1-KWxSuoYkDTQOoEg.f1kX21DoHJVwIL5ArHjAH3zEUejNfUa0GXy9aJkLyTP.xRLHDCbvWwjDFCkzXFuQVu.Jn1vyp_DuPAAdpFYGTF.j8RgYJxL7Bux4qRdE9xvowYL5c1_wmARxAiad0vYElPxIh2tzAWv.Yzrli5pdk789odglPDjfRM_rY9zpnVkq4gbobeTItks9nKKmVETqpZBPrUmg_XUcoQsrKlki1TsxLWs.GHe7gqmHwsvbGgxSN1CzEoplBMeLJ2Q58LQd6PUvafpFD5LK2njA84eQHH.RJnFXDMAvfGHLYWXIjHbp48oNYjdYKMftmI5GbPF5BwSYUuO1n8w1IGPNJXA.m5LmKDn4TyPr0_ZX1TvU.A9wN.tiYATuo3bFF7sa26Fqf_HL9taDv0wAQwXXF76Fef3F.Us", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_newpm", "value": "0", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_password", "value": "647e16b9bd081f20506a81128b8317c5", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_user_id", "value": "35913", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "PHPSESSID", "value": "dke2gg748oe9e6midv0nk4ngg0", "domain": ".cinemacity.cc", "path": "/"}
        ]
        context.add_cookies(cookies)
        
        url = "https://cinemacity.cc/"
        print(f"Navigating to {url}...")
        page.goto(url)
        page.wait_for_timeout(3000)
        
        print("Page title:", page.title())
        content = page.content()
        print("Page content length:", len(content))
        print("Page content preview:", content[:1000])
        
        # Test searching
        search_url = "https://cinemacity.cc/?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=tt3581920"
        print(f"\nNavigating to search: {search_url}...")
        page.goto(search_url)
        page.wait_for_timeout(3000)
        print("Search page title:", page.title())
        search_content = page.content()
        print("Search page content length:", len(search_content))
        # Find if the target search item is in the search page
        if "dar-short_item" in search_content:
            print("[+] Found dar-short_item in search content!")
            # let's extract all matches for links inside dar-short_item
            import re
            links = re.findall(r'class=["\']dar-short_item["\'].*?<a\s+href=["\']([^"\']+)["\']', search_content, re.DOTALL)
            print(f"Links: {links}")
        else:
            print("[-] dar-short_item not found in search content.")
            
        browser.close()

if __name__ == "__main__":
    test_playwright()
