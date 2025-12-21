/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String? = null,
)

expect object WebFetchApi {
    suspend fun search(query: String, limit: Int = 3): Result<List<WebSearchResult>>

    suspend fun fetch(url: String, maxChars: Int = 2000): Result<String>
}
