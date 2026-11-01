package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class ScanHistoryBatchTest {
    @Test fun orderDoesNotChangeBatchIdentityAndBodyIsBounded() {
        val hashes = (1..512).map { "%064x".format(it) }
        val batch = newScanBatch(hashes)
        assertEquals(batch, newScanBatch(hashes.reversed() + hashes.first()))
        assertTrue(batch.document.toByteArray().size < SCAN_BATCH_LIMIT)
        assertEquals(batch, parseScanBatch(batch.id, batch.document))
        assertThrows(IllegalArgumentException::class.java) { newScanBatch(hashes + "f".repeat(64)) }
    }
    @Test fun rejectsWrongDigestVersionAndDuplicateOrInvalidHashes() {
        val hash = "a".repeat(64)
        for (raw in listOf(
            """{"version":2,"hashes":["$hash"]}""",
            """{"version":1,"hashes":["$hash","$hash"]}""",
            """{"version":1,"hashes":["invalid"]}""",
            """{"version":1,"hashes":[]}"""
        )) assertThrows(IllegalArgumentException::class.java) { parseScanBatch(scanBatchDigest(raw), raw) }
        val batch = newScanBatch(listOf(hash))
        assertThrows(IllegalArgumentException::class.java) { parseScanBatch("b".repeat(64), batch.document) }
    }
}
