/*
 * Strata - Kotlin Multiplatform client.
 * Copyright (c) 2025.
 */
package org.strata.ai

actual object WebFetchApi {
    actual suspend fun search(
        query: String,
        limit: Int,
    ): Result<List<WebSearchResult>> {
        return Result.failure(UnsupportedOperationException("Web search not implemented on Android yet"))
    }

    actual suspend fun fetch(
        url: String,
        maxChars: Int,
    ): Result<String> {
        return Result.failure(UnsupportedOperationException("Web fetch not implemented on Android yet"))
    }
}
