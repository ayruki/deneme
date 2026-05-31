import undetected_chromedriver as uc
import time
import re

def test_uc():
    print("Testing undetected-chromedriver...")
    options = uc.ChromeOptions()
    # We can run headless or headed. Headed is much more reliable for bypassing Cloudflare
    options.headless = False
    
    try:
        driver = uc.Chrome(options=options, version_main=148)
        
        # Navigate to homepage to establish domain
        print("Navigating to homepage...")
        driver.get("https://cinemacity.cc/")
        time.sleep(5)
        
        # Inject cookies using execute_cdp_cmd or add_cookie
        print("Injecting cookies...")
        cookies = [
            {"name": "cf_clearance", "value": "vZ.vUOvew2b4sHAQZO_R2J7JmYfBQ0e02IRREp.AQcc-1779647771-1.2.1.1-KWxSuoYkDTQOoEg.f1kX21DoHJVwIL5ArHjAH3zEUejNfUa0GXy9aJkLyTP.xRLHDCbvWwjDFCkzXFuQVu.Jn1vyp_DuPAAdpFYGTF.j8RgYJxL7Bux4qRdE9xvowYL5c1_wmARxAiad0vYElPxIh2tzAWv.Yzrli5pdk789odglPDjfRM_rY9zpnVkq4gbobeTItks9nKKmVETqpZBPrUmg_XUcoQsrKlki1TsxLWs.GHe7gqmHwsvbGgxSN1CzEoplBMeLJ2Q58LQd6PUvafpFD5LK2njA84eQHH.RJnFXDMAvfGHLYWXIjHbp48oNYjdYKMftmI5GbPF5BwSYUuO1n8w1IGPNJXA.m5LmKDn4TyPr0_ZX1TvU.A9wN.tiYATuo3bFF7sa26Fqf_HL9taDv0wAQwXXF76Fef3F.Us", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_newpm", "value": "0", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_password", "value": "647e16b9bd081f20506a81128b8317c5", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "dle_user_id", "value": "35913", "domain": ".cinemacity.cc", "path": "/"},
            {"name": "PHPSESSID", "value": "dke2gg748oe9e6midv0nk4ngg0", "domain": ".cinemacity.cc", "path": "/"}
        ]
        for cookie in cookies:
            driver.add_cookie(cookie)
            
        print("Cookies injected. Loading homepage again...")
        driver.get("https://cinemacity.cc/")
        time.sleep(5)
        print("Page Title:", driver.title)
        
        search_url = "https://cinemacity.cc/?do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=The+Last+of+Us"
        print(f"Navigating to search: {search_url}")
        driver.get(search_url)
        time.sleep(5)
        print("Search Page Title:", driver.title)
        
        search_content = driver.page_source
        with open("scratch/search_page.html", "w", encoding="utf-8") as f:
            f.write(search_content)
        print("Saved search page source to scratch/search_page.html")
        
        if "dar-short_item" in search_content:
            print("[+] Found dar-short_item in search content!")
            links = re.findall(r'class=["\']dar-short_item["\'].*?<a\s+href=["\']([^"\']+)["\']', search_content, re.DOTALL)
            print(f"Links: {links}")
        else:
            print("[-] dar-short_item not found in search content.")
            
        driver.quit()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_uc()
