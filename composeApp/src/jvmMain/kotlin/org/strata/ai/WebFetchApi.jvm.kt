/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.URLDecoder
import java.net.URLEncoder

actual object WebFetchApi {
    private val http = HttpClient(CIO)

    actual suspend fun search(
        query: String,
        limit: Int,
    ): Result<List<WebSearchResult>> =
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://duckduckgo.com/html/?q=$encoded"
            val body = http.get(url).bodyAsText()
            val results = parseDuckDuckGo(body, limit)
            results
        }

    actual suspend fun fetch(
        url: String,
        maxChars: Int,
    ): Result<String> =
        runCatching {
            val body = http.get(url).bodyAsText()
            body.take(maxChars)
        }

    private fun parseDuckDuckGo(
        html: String,
        limit: Int,
    ): List<WebSearchResult> {
        val regex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""")
        val results = mutableListOf<WebSearchResult>()
        regex.findAll(html).forEach { match ->
            val rawUrl = match.groupValues[1]
            val title = stripHtml(match.groupValues[2])
            val url = decodeDuckUrl(rawUrl)
            if (title.isNotBlank() && url.isNotBlank()) {
                results += WebSearchResult(title = title, url = url)
            }
            if (results.size >= limit) return@forEach
        }
        return results
    }

    private fun decodeDuckUrl(raw: String): String {
        val decoded = raw.replace("&amp;", "&")
        val uddgIdx = decoded.indexOf("uddg=")
        if (uddgIdx == -1) return decoded
        val param = decoded.substring(uddgIdx + 5)
        val end = param.indexOf('&').let { if (it == -1) param.length else it }
        return runCatching { URLDecoder.decode(param.substring(0, end), "UTF-8") }.getOrElse { decoded }
    }

    private fun stripHtml(input: String): String {
        return input.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()
    }
}
