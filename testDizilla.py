import urllib.request
import json
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

key = b'944208a0d24c084e88383a54d580f4f9'
iv = b'3368222955519864'

req = urllib.request.Request('https://dizillahd.com/one-piece-1-sezon-1-bolum', headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
    next_data = html.split('<script id="__NEXT_DATA__" type="application/json">')[1].split('</script>')[0]
    data = json.loads(next_data)
    secure_data = data['props']['pageProps']['secureData']
    
    cipher = AES.new(key, AES.MODE_CBC, iv)
    decrypted = unpad(cipher.decrypt(base64.b64decode(secure_data)), AES.block_size)
    parsed = json.loads(decrypted.decode('utf-8'))
    sources = parsed['RelatedResults']['getEpisodeSources']['result']
    for s in sources:
        print('Name:', s['source_name'], 'Content:', s['source_content'])
except Exception as e:
    print('Error:', e)
