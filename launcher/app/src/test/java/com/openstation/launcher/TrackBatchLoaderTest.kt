package com.openstation.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TrackBatchLoaderTest {
    @Test fun batchesAndPreservesLibraryOrder() {
        val ids = (1..1001).map { it.toString() }
        val sizes = mutableListOf<Int>()
        val result = TrackBatchLoader.load(ids, 100, { batch ->
            sizes.add(batch.size)
            batch.reversed().filterNot { it == "77" }
        }, { it })
        assertEquals(11, sizes.size)
        assertEquals(1, sizes.last())
        assertEquals(ids.filterNot { it == "77" }, result)
    }

    @Test fun retriesAndKeepsOtherBatchesAfterNetworkFailure() {
        val attempts = mutableMapOf<String, Int>()
        val result = TrackBatchLoader.load(listOf("1", "2", "3"), 1, { batch ->
            val id = batch.single()
            val attempt = (attempts[id] ?: 0) + 1
            attempts[id] = attempt
            if (id == "2" || (id == "1" && attempt == 1)) throw IOException("offline")
            batch
        }, { it })
        assertEquals(listOf("1", "3"), result)
        assertEquals(mapOf("1" to 2, "2" to 2, "3" to 1), attempts)
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotHideNonNetworkErrors() {
        TrackBatchLoader.load(listOf("1"), 1, { throw IllegalStateException("unexpected") }, { it: String -> it })
    }
}
