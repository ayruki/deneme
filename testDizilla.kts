import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import org.json.JSONObject

fun main() {
    val key = "944208a0d24c084e88383a54d580f4f9"
    val iv = "3368222955519864"
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))

    val url = "https://dizillahd.com/one-piece-1-sezon-1-bolum"
    val connection = URL(url).openConnection()
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    val html = connection.getInputStream().bufferedReader().use { it.readText() }

    val nextData = html.split("<script id=\"__NEXT_DATA__\" type=\"application/json\">")[1].split("</script>")[0]
    val json = JSONObject(nextData)
    val secureData = json.getJSONObject("props").getJSONObject("pageProps").getString("secureData")

    val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(secureData)), Charsets.UTF_8)
    val parsed = JSONObject(decrypted)
    
    val sources = parsed.getJSONObject("RelatedResults").getJSONObject("getEpisodeSources").getJSONArray("result")
    println("Found " + sources.length() + " sources:")
    for (i in 0 until sources.length()) {
        val item = sources.getJSONObject(i)
        println("Name: " + item.getString("source_name"))
        println("Content: " + item.getString("source_content"))
        println("---")
    }
}
