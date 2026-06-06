package com.izlelan.network

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CFClient {
    private val cfKiller by lazy { CloudflareKiller() }
    private val cfMutex = Mutex()

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        val server = response.headers["Server"] ?: ""
        return server.contains("cloudflare", true) && response.code in listOf(403, 503)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        timeout: Long? = null,
        allowRedirects: Boolean? = null
    ): NiceResponse {
        val response = app.get(
            url,
            headers = headers ?: emptyMap(),
            referer = referer,
            params = params ?: emptyMap(),
            cookies = cookies ?: emptyMap(),
            timeout = timeout ?: 30L,
            allowRedirects = allowRedirects ?: true
        )
        return if (isCloudflarePage(response)) {
            cfMutex.withLock {
                val retryResponse = app.get(
                    url,
                    headers = headers ?: emptyMap(),
                    referer = referer,
                    params = params ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    timeout = timeout ?: 30L,
                    interceptor = cfKiller,
                    allowRedirects = allowRedirects ?: true
                )
                if (isCloudflarePage(retryResponse)) {
                    cfKiller.savedCookies.clear()
                    app.get(
                        url,
                        headers = headers ?: emptyMap(),
                        referer = referer,
                        params = params ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        timeout = timeout ?: 30L,
                        interceptor = cfKiller,
                        allowRedirects = allowRedirects ?: true
                    )
                } else {
                    retryResponse
                }
            }
        } else {
            response
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        data: Map<String, String>? = null,
        json: Any? = null,
        timeout: Long? = null,
        allowRedirects: Boolean? = null
    ): NiceResponse {
        val response = app.post(
            url,
            headers = headers ?: emptyMap(),
            referer = referer,
            params = params ?: emptyMap(),
            cookies = cookies ?: emptyMap(),
            data = data,
            json = json,
            timeout = timeout ?: 30L,
            allowRedirects = allowRedirects ?: true
        )
        return if (isCloudflarePage(response)) {
            cfMutex.withLock {
                val retryResponse = app.post(
                    url,
                    headers = headers ?: emptyMap(),
                    referer = referer,
                    params = params ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    data = data,
                    json = json,
                    timeout = timeout ?: 30L,
                    interceptor = cfKiller,
                    allowRedirects = allowRedirects ?: true
                )
                if (isCloudflarePage(retryResponse)) {
                    cfKiller.savedCookies.clear()
                    app.post(
                        url,
                        headers = headers ?: emptyMap(),
                        referer = referer,
                        params = params ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        data = data,
                        json = json,
                        timeout = timeout ?: 30L,
                        interceptor = cfKiller,
                        allowRedirects = allowRedirects ?: true
                    )
                } else {
                    retryResponse
                }
            }
        } else {
            response
        }
    }
}
