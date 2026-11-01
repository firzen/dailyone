package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class CloudConfigTest {
    private val s3 = S3Config("https://s3.example.com", "us-east-1", "journals", "access", "secret")
    @Test fun oldConfigDefaultsToWebDav() {
        val config = CloudConfig.parse("""{"url":"https://dav.example.com","username":" user ","password":" password "}""")
        assertEquals(CloudProvider.WEBDAV, config.provider)
        assertEquals("user", config.username)
        assertEquals(" password ", config.password)
        assertTrue(config.configured)
    }
    @Test fun roundTripPreservesBothProvidersAndOnlyActiveProviderControlsReadiness() {
        val config = CloudConfig("https://dav.example.com", "user", "pass", CloudProvider.S3, s3.copy(pathStyle = true))
        assertEquals(config, CloudConfig.parse(config.json()))
        assertFalse(config.copy(s3 = S3Config()).configured)
        assertTrue(config.copy(provider = CloudProvider.WEBDAV, s3 = S3Config()).configured)
        config.validate()
    }
    @Test fun targetIdentityIsStableForSecretsButChangesWithDestinationOrAccount() {
        val config = CloudConfig(provider = CloudProvider.S3, s3 = s3)
        assertEquals(config.targetId(), config.copy(s3 = s3.copy(secretKey = "rotated", pathStyle = true)).targetId())
        assertEquals(config.targetId(), config.copy(s3 = s3.copy(endpoint = "https://s3.example.com/", prefix = "/DailyOne/")).targetId())
        for (other in listOf(s3.copy(bucket = "other"), s3.copy(prefix = "other/"), s3.copy(accessKey = "other"), s3.copy(region = "auto"))) {
            assertNotEquals(config.targetId(), config.copy(s3 = other).targetId())
        }
        val dav = CloudConfig("https://dav.example.com", "user", "pass")
        assertEquals(dav.targetId(), dav.copy(password = "rotated").targetId())
        assertNotEquals(dav.targetId(), config.targetId())
    }
    @Test fun unsafeAndIncompleteConfigIsRejected() {
        for (invalid in listOf(s3.copy(endpoint = "http://example.com"), s3.copy(endpoint = "https://u:p@example.com"),
            s3.copy(endpoint = "https://example.com/?key=secret"), s3.copy(prefix = "../other"), s3.copy(bucket = "bad/bucket"), s3.copy(secretKey = ""))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }
    @Test fun contentComparisonIgnoresUpdateClockButPreservesDeletionAndActualEdits() {
        val entry = Entry(body = "原文")
        assertTrue(sameJournalContent(entry, entry.copy(updatedAt = 1)))
        assertFalse(sameJournalContent(entry, entry.copy(body = "修改")))
        assertFalse(sameJournalContent(entry, entry.copy(deleted = true)))
    }
}
