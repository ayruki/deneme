import base64

def decrypt(encrypted_b64, key_str):
    encrypted = base64.b64decode(encrypted_b64)
    key = key_str.encode('utf-8')
    iv = b'\x00' * 16
    
    # Try pycryptodome
    try:
        from Crypto.Cipher import AES
        cipher = AES.new(key, AES.MODE_CBC, iv)
        decrypted = cipher.decrypt(encrypted)
        pad_len = decrypted[-1]
        return decrypted[:-pad_len].decode('utf-8')
    except Exception as e1:
        # Try cryptography
        try:
            from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
            from cryptography.hazmat.backends import default_backend
            cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
            decryptor = cipher.decryptor()
            decrypted = decryptor.update(encrypted) + decryptor.finalize()
            pad_len = decrypted[-1]
            return decrypted[:-pad_len].decode('utf-8')
        except Exception as e2:
            return f"Error: pycryptodome or cryptography not available. {e1} | {e2}"

encrypted = "pzc6PqcXc7uM8FsBKpdy96H0TeQrTp7bSq8iWczSuJocSAsqzitjX5JkCpaS0lcsaiQUOdCnEpbCX9Kcl2XZlLjTQS4Hk6/mrA4B2I06PlFcPHzzxsXtR+RmCidntHt8go+B0Bz3JXXPCjHaY0Xy5kL60T7lbe4uKMgIr/EKtx2YlNuRyfl2zgdnkSdKW2je"
key = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

print("Decrypted:", decrypt(encrypted, key))
