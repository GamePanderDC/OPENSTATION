package com.openstation.launcher

import java.io.IOException

internal object TrackBatchLoader {
    fun <T> load(ids: List<String>, batchSize: Int = 100,
                 fetch: (List<String>) -> List<T>, idOf: (T) -> String): List<T> {
        require(batchSize > 0)
        val result = ArrayList<T>(ids.size)
        for (batch in ids.chunked(batchSize)) {
            // A temporary network failure in one batch must not discard the
            // tracks already loaded or prevent later batches from loading.
            var items: List<T>? = null
            repeat(2) {
                if (items == null) {
                    try { items = fetch(batch) } catch (_: IOException) { }
                }
            }
            val fetched = (items ?: emptyList()).associateBy(idOf)
            for (id in batch) fetched[id]?.let { result.add(it) }
        }
        return result
    }
}
