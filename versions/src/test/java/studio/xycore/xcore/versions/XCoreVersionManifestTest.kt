package studio.xycore.xcore.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreReleaseType

/**
 * `version_manifest_v2.json` parsing, the distribution allowlist and the hand-rolled timestamp
 * reader. The JSON below is written to the shape Mojang publishes rather than loaded from a
 * captured file, so the assertions stay readable and a metadata change shows up as a failing test
 * instead of a silently stale fixture.
 */
class XCoreVersionManifestTest {

    private val manifestJson = """
        {
          "latest": {"release": "1.21.4", "snapshot": "24w40a"},
          "versions": [
            {
              "id": "1.21.4",
              "type": "release",
              "url": "https://piston-meta.mojang.com/v1/packages/aaa/1.21.4.json",
              "time": "2024-12-03T11:38:24+00:00",
              "releaseTime": "2024-12-03T10:00:00+00:00",
              "sha1": "0123456789abcdef0123456789abcdef01234567",
              "complianceLevel": 1
            },
            {
              "id": "24w40a",
              "type": "snapshot",
              "url": "https://piston-meta.mojang.com/v1/packages/bbb/24w40a.json",
              "time": "2024-10-02T14:37:33+00:00",
              "releaseTime": "2024-10-02T14:37:33+00:00",
              "sha1": "1111111111111111111111111111111111111111",
              "complianceLevel": 1
            },
            {
              "id": "b1.8.1",
              "type": "old_beta",
              "url": "https://launchermeta.mojang.com/v1/packages/ccc/b1.8.1.json",
              "time": "2011-09-15T14:38:03+00:00",
              "releaseTime": "2011-09-15T14:38:03+00:00"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesEveryEntryAndTheLatestPointers() {
        val parsed = XCoreVersionManifest.parse(manifestJson, fetchedAtMillis = 5L).valueOrNull()
        assertNotNull("manifest should parse", parsed)
        val document = parsed!!
        assertEquals("1.21.4", document.document.latestRelease)
        assertEquals("24w40a", document.document.latestSnapshot)
        assertEquals(3, document.size)
        assertEquals(
            listOf("1.21.4", "24w40a", "b1.8.1"),
            document.entries.map { it.id },
        )
        assertTrue(document.findExact("1.21.4").isSuccess)
        assertEquals(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            document.sourceUrl,
        )
    }

    @Test
    fun mapsBetaAndAlphaWireTypesOntoOldReleases() {
        val entries = XCoreVersionManifest.parse(manifestJson).valueOrNull()!!.entries
        assertEquals(XCoreReleaseType.RELEASE, entries[0].releaseType)
        assertEquals(XCoreReleaseType.SNAPSHOT, entries[1].releaseType)
        assertEquals(XCoreReleaseType.OLD_RELEASE, entries[2].releaseType)
    }

    @Test
    fun readsTimestampsIntoEpochMillisAgreeingWithThePlatformClock() {
        val entries = XCoreVersionManifest.parse(manifestJson).valueOrNull()!!.entries
        assertEquals(jdkMillis("2024-12-03T11:38:24+00:00"), entries[0].timeMillis)
        assertEquals(jdkMillis("2024-12-03T10:00:00+00:00"), entries[0].releaseTimeMillis)
        assertEquals(jdkMillis("2011-09-15T14:38:03+00:00"), entries[2].releaseTimeMillis)
    }

    @Test
    fun aMissingSha1StaysMissingRatherThanBecomingZeroHash() {
        val entries = XCoreVersionManifest.parse(manifestJson).valueOrNull()!!.entries
        assertNotNull(entries[0].profileSha1)
        assertNull(entries[2].profileSha1)
    }

    @Test
    fun oneMalformedEntryFailsTheWholeDocument() {
        val broken = manifestJson.replace("\"url\":", "\"nurl\":")
        val outcome = XCoreVersionManifest.parse(broken)
        assertFalse("dropping a version silently would look like Mojang never published it", outcome.isSuccess)
        assertTrue(outcome.errorOrNull() is XCoreError.Network)
    }

    @Test
    fun rejectsANonObjectBodyAndAMissingVersionsArray() {
        assertTrue(XCoreVersionManifest.parse("[]").errorOrNull() is XCoreError.Network)
        val error = XCoreVersionManifest.parse("""{"latest":{"release":"1.21.4"}}""").errorOrNull()
        assertTrue(error is XCoreError.Network)
    }

    @Test
    fun rejectsAnUnparseableDocumentWithoutThrowing() {
        val error = XCoreVersionManifest.parse("{ not json").errorOrNull()
        assertTrue(error is XCoreError.Network)
    }

    @Test
    fun selectOrdersNewestFirstAndNarrowsByTypeAndQuery() {
        val document = XCoreVersionManifest.parse(manifestJson).valueOrNull()!!
        assertEquals(
            listOf("1.21.4", "b1.8.1"),
            document.select(releaseTypes = setOf(XCoreReleaseType.RELEASE, XCoreReleaseType.OLD_RELEASE))
                .map { it.id },
        )
        assertEquals(listOf("1.21.4"), document.select(query = "1.21").map { it.id })
        assertEquals(
            "an empty search box must not hide the whole list",
            3,
            document.select(query = "  ").size,
        )
        assertEquals(2, document.select(limit = 2).size)
    }

    @Test
    fun aBulkMetadataRefreshCannotFloatAnOldReleaseAboveANewOne() {
        // Mojang rewrites `time` for every entry when it republishes metadata, so ordering the list
        // by `time` alone put 1.20.2 above 26.3 in the live manifest on 2026-10-27.
        val refreshed = """
            {
              "latest": {"release": "26.3", "snapshot": "26.4-snapshot-6"},
              "versions": [
                {
                  "id": "26.3",
                  "type": "release",
                  "url": "https://piston-meta.mojang.com/v1/packages/aaa/26.3.json",
                  "time": "2026-10-27T06:57:57+00:00",
                  "releaseTime": "2026-09-15T11:23:02+00:00"
                },
                {
                  "id": "1.20.2",
                  "type": "release",
                  "url": "https://piston-meta.mojang.com/v1/packages/bbb/1.20.2.json",
                  "time": "2026-10-27T07:01:59+00:00",
                  "releaseTime": "2023-09-20T09:02:57+00:00"
                }
              ]
            }
        """.trimIndent()
        val document = XCoreVersionManifest.parse(refreshed).valueOrNull()
        assertNotNull("a manifest entry with no sha1 or complianceLevel is still readable", document)
        assertEquals(listOf("26.3", "1.20.2"), document!!.select().map { it.id })
    }

    @Test
    fun findExactNamesTheManifestThatWasFetchedWhenAVersionIsAbsent() {
        val document = XCoreVersionManifest(
            XCoreVersionManifestDocument("1.21.4", null, emptyList()),
            sourceUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            fetchedAtMillis = 0L,
            servedFromCache = false,
        )
        val error = document.findExact("1.21.5").errorOrNull()
        assertTrue(error is XCoreError.Network)
        assertTrue(error!!.detail.contains("1.21.5"))
    }

    @Test
    fun onlyHttpsMojangHostsAndMavenCentralAreAuthorised() {
        assertTrue(XCoreDistributionEndpoints.isAllowed(XCoreDistributionEndpoints.VERSION_MANIFEST_V2))
        assertTrue(XCoreDistributionEndpoints.isAllowed("https://repo1.maven.org/maven2/a/b/c.jar"))
    }

    @Test
    fun refusesPlainHttpEvenFromAnApprovedHost() {
        val error = XCoreDistributionEndpoints
            .requireAllowed("http://piston-data.mojang.com/v1/objects/x/client.jar")
            .errorOrNull()
        assertTrue(error is XCoreError.Validation)
    }

    @Test
    fun refusesAnUnapprovedHostAndAGarbageUrl() {
        val hostError = XCoreDistributionEndpoints
            .requireAllowed("https://evil.example.com/client.jar")
            .errorOrNull()
        assertTrue("an unapproved host is a permission decision, not a typo", hostError is XCoreError.Permission)
        assertTrue(XCoreDistributionEndpoints.requireAllowed("not a url").errorOrNull() is XCoreError.Validation)
    }

    @Test
    fun timestampsAcceptTheFixedOffsetFormsMojangPublishes() {
        assertEquals(0L, XCoreTimestamps.parseUtc("1970-01-01T00:00:00Z").valueOrNull())
        assertEquals(1_000L, XCoreTimestamps.parseUtc("1970-01-01T00:00:01Z").valueOrNull())
        assertEquals(1_500L, XCoreTimestamps.parseUtc("1970-01-01T00:00:01.5Z").valueOrNull())
        assertEquals(3_600_000L, XCoreTimestamps.parseUtc("1970-01-01T01:00:00Z").valueOrNull())
        assertEquals(
            "a +01:00 offset shifts the instant earlier, not later",
            XCoreTimestamps.parseUtc("1970-01-01T00:00:00Z").valueOrNull(),
            XCoreTimestamps.parseUtc("1970-01-01T01:00:00+01:00").valueOrNull(),
        )
        assertEquals(
            XCoreTimestamps.parseUtc("2000-02-29T12:00:00Z").valueOrNull(),
            XCoreTimestamps.parseUtc("2000-02-29 12:00:00Z").valueOrNull(),
        )
    }

    @Test
    fun timestampsRejectShapesThatWouldNeedATimezoneDatabase() {
        assertTrue(XCoreTimestamps.parseUtc("tomorrow").errorOrNull() is XCoreError.Validation)
        assertTrue(XCoreTimestamps.parseUtc("2024-06-01T00:00:00 UTC").errorOrNull() is XCoreError.Validation)
        assertEquals(0L, XCoreTimestamps.parseUtcOrZero(null))
        assertEquals(0L, XCoreTimestamps.parseUtcOrZero("nonsense"))
    }

    @Test
    fun retryPolicyBacksOffWithinItsCeilingAndOnlyRepeatsRetryableStatuses() {
        val policy = XCoreRetryPolicy(maxAttempts = 4, initialBackoffMillis = 400L, multiplier = 2.5, maxBackoffMillis = 2_000L)
        assertEquals(400L, policy.delayMillisBefore(1))
        assertEquals(1_000L, policy.delayMillisBefore(2))
        assertEquals(2_000L, policy.delayMillisBefore(3))
        assertEquals(2_000L, policy.delayMillisBefore(9))
        assertTrue(policy.shouldRetryStatus(408))
        assertTrue(policy.shouldRetryStatus(429))
        assertTrue(policy.shouldRetryStatus(503))
        assertFalse("repeating a 404 only costs the user bandwidth", policy.shouldRetryStatus(404))
        assertFalse(policy.shouldRetryStatus(null))
    }

    private fun jdkMillis(text: String): Long =
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
}
