package studio.xycore.xcore.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.versions.XCoreLibraryEntry
import studio.xycore.xcore.versions.XCoreRuntimeEnvironment
import studio.xycore.xcore.versions.XCoreVersionParser

/**
 * Which declared libraries this device actually needs. A wrong answer here is either a wasted
 * download (osx jars on a phone) or a crash at launch (a missing classpath entry), so the rule
 * handling and the Maven fallback are worth pinning down.
 */
class XCoreLibraryResolverTest {

    private val android = XCoreRuntimeEnvironment.forAndroid("arm64-v8a")

    private val windows = XCoreRuntimeEnvironment(osName = "windows", osArch = "64-bit")

    private val parser = XCoreVersionParser()

    private val libraries: List<XCoreLibraryEntry> by lazy { parser.parse(profileJson).getOrThrow().libraries }

    @Test
    fun osSpecificJarsThatDoNotNameThisDeviceAreSkippedNotDownloaded() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries).getOrThrow()
        val coordinates = resolution.artifacts.map { it.coordinate }
        assertFalse("an osx-only jar has no business on a phone", coordinates.contains("com.mojang:patchy:2.2.10"))
        assertFalse(coordinates.contains("com.mojang:windows-only:1.0"))
        assertTrue("linux is the rule-evaluation profile for Android", coordinates.contains("net.java.dev.jna:jna:5.13.0"))
        assertTrue(resolution.skipped.first { it.coordinate.startsWith("com.mojang:patchy") }.reason.contains("rules exclude"))
    }

    @Test
    fun theSameProfileResolvesADifferentSetOnWindows() {
        val resolution = XCoreLibraryResolver(windows).resolve(libraries).getOrThrow()
        val coordinates = resolution.artifacts.map { it.coordinate }
        assertTrue(coordinates.contains("com.mojang:windows-only:1.0"))
        assertFalse(coordinates.contains("com.mojang:patchy:2.2.10"))
    }

    @Test
    fun aPublishedLibraryKeepsItsMojangUrlAndItsPublishedDigest() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries).getOrThrow()
        val logging = resolution.artifacts.first { it.coordinate.startsWith("com.mojang:logging") }
        assertEquals(XCoreArtifactSource.PUBLISHED, logging.source)
        assertTrue(logging.digestIsPublished)
        assertEquals("https://libraries.minecraft.net/com/mojang/logging/1.1.1/logging-1.1.1.jar", logging.url)
        assertEquals("libraries/com/mojang/logging/1.1.1/logging-1.1.1.jar", logging.relativePath)
        assertEquals(XCoreArtifactRole.CLASSPATH, logging.role)
        assertEquals(1_500L, logging.sizeBytes)
    }

    @Test
    fun aLibraryWithNoDownloadsIsResolvedThroughTheMavenLayoutAndMarkedUnverified() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries).getOrThrow()
        val fastutil = resolution.artifacts.first { it.coordinate.startsWith("it.unimi.dsi:fastutil") }
        assertEquals(XCoreArtifactSource.COORDINATE_ONLY, fastutil.source)
        assertFalse("nobody published a digest for it", fastutil.digestIsPublished)
        assertEquals(-1L, fastutil.sizeBytes)
        assertEquals(
            "https://repo1.maven.org/maven2/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar",
            fastutil.url,
        )
        assertEquals("libraries/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar", fastutil.relativePath)
        assertTrue(resolution.unverified.contains("it.unimi.dsi:fastutil:8.5.12"))
        assertFalse("a published jar carries its digest and is never listed as unverified", resolution.unverified.any { it.startsWith("com.mojang:logging") })
    }

    @Test
    fun substitutingArchIntoANativesKeyUsesTheDevicesWordSize() {
        val entry = libraries.first { it.coordinate.startsWith("org.lwjgl:lwjgl-platform") }
        assertEquals("natives-linux-64", entry.nativeClassifierKey(XCoreRuntimeEnvironment(osName = "linux", osArch = "arm64")))
        assertEquals("natives-linux-64", entry.nativeClassifierKey(android))
        assertEquals(
            "natives-linux-32",
            entry.nativeClassifierKey(XCoreRuntimeEnvironment(osName = "linux", osArch = "armv7l")),
        )
        assertEquals(
            "natives-windows-64",
            entry.nativeClassifierKey(XCoreRuntimeEnvironment(osName = "windows", osArch = "amd64")),
        )
        assertNull(
            "this entry declares no natives for osx, so there is nothing to substitute",
            entry.nativeClassifierKey(XCoreRuntimeEnvironment(osName = "osx", osArch = "64-bit")),
        )
    }

    @Test
    fun aNativeJarIsRecordedAsANativeAndNotPutOnTheClasspath() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries).getOrThrow()
        val natives = resolution.nativeArtifacts
        assertEquals(1, natives.size)
        val native = natives.single()
        assertEquals("org.lwjgl:lwjgl-platform:3.3.4", native.coordinate)
        assertEquals("natives-linux-64", native.classifier)
        assertEquals("libraries/org/lwjgl/lwjgl-platform/3.3.4/lwjgl-platform-3.3.4-natives-linux-64.jar", native.relativePath)
        assertEquals(sha1Value("6"), native.sha1?.value)
        assertFalse(resolution.classpathPaths.contains(native.relativePath))
        assertEquals(XCoreDownloadOrigin.NATIVE, native.toTask().origin)
        assertEquals(XCoreDownloadOrigin.LIBRARY, resolution.classpathTasks.first().origin)
    }

    @Test
    fun aNativesDeclarationWithNoPublishedClassifierIsReportedNotGuessed() {
        val json = profileJson.replace(
            "\"natives-linux-64\": {",
            "\"natives-windows-64\": {",
        )
        val parsed = parser.parse(json).getOrThrow().libraries
        val resolution = XCoreLibraryResolver(android).resolve(parsed).getOrThrow()
        val platform = resolution.skipped.first { it.coordinate.startsWith("org.lwjgl:lwjgl-platform") }
        assertTrue(platform.reason.contains("publishes no"))
        assertTrue(resolution.nativeArtifacts.isEmpty())
    }

    @Test
    fun aNativesOnlyEntryWithNoDownloadAtAllIsSkipped() {
        val resolution = XCoreLibraryResolver(android).resolve(
            libraries + entry(
                """
                {"name": "org.lwjgl:lwjgl-opengl:3.3.4",
                 "natives": {"linux": "natives-linux"},
                 "rules": [{"action": "allow"}]}
                """.trimIndent(),
            ),
        ).getOrThrow()
        assertTrue(resolution.skipped.any { it.coordinate.startsWith("org.lwjgl:lwjgl-opengl") })
        assertFalse(resolution.artifacts.any { it.coordinate.startsWith("org.lwjgl:lwjgl-opengl") })
    }

    @Test
    fun theSamePathClaimedTwiceIsDownloadedOnce() {
        val duplicate = entry(
            """
            {"name": "com.mojang:logging:1.1.1",
             "downloads": {"artifact": {
               "url": "https://libraries.minecraft.net/com/mojang/logging/1.1.1/logging-1.1.1.jar",
               "sha1": "${sha1Value("1")}", "size": 1500,
               "path": "com/mojang/logging/1.1.1/logging-1.1.1.jar"}}}
            """.trimIndent(),
        )
        val resolution = XCoreLibraryResolver(android).resolve(libraries + duplicate).getOrThrow()
        assertEquals(
            1,
            resolution.artifacts.count { it.relativePath == "libraries/com/mojang/logging/1.1.1/logging-1.1.1.jar" },
        )
    }

    @Test
    fun anUnapprovedFallbackRepositoryRefusesToResolveCoordinateOnlyLibraries() {
        val hostile = XCoreLibraryResolver(
            android,
            fallbackRepositoryUrl = "https://rehost.example.com/maven2",
        ).resolve(libraries).getOrThrow()
        assertTrue(
            "a coordinate-only jar must never come from a host outside the allowlist",
            hostile.artifacts.none { it.source == XCoreArtifactSource.COORDINATE_ONLY },
        )
        assertTrue(hostile.skipped.any { it.coordinate.startsWith("it.unimi.dsi:fastutil") })
    }

    @Test
    fun aCoordinateThatIsNotAMavenCoordinateIsSkippedWithItsReason() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries + entry("""{"name": "broken-entry"}""")).getOrThrow()
        val skipped = resolution.skipped.first { it.coordinate == "broken-entry" }
        assertTrue(skipped.reason.contains("Maven coordinate"))
    }

    @Test
    fun expectedBytesIgnoresEntriesWhoseSizeNobodyPublished() {
        val resolution = XCoreLibraryResolver(android).resolve(libraries).getOrThrow()
        val published = resolution.artifacts.filter { it.sizeBytes > 0L }.sumOf { it.sizeBytes }
        assertEquals(published, resolution.expectedBytes)
        assertTrue(resolution.expectedBytes > 0L)
    }

    @Test
    fun describeSkippedNamesTheLimitItWasGiven() {
        val many = List(6) { index -> entry("""{"name": "com.example:a$index:1.0", "rules": [{"action":"disallow"}]}""") }
        val resolution = XCoreLibraryResolver(android).resolve(many).getOrThrow()
        assertEquals(6, resolution.skipped.size)
        assertTrue(resolution.describeSkipped(limit = 2).contains("and 4 more"))
    }

    private fun entry(libraryJson: String): XCoreLibraryEntry =
        parser.parse("""{"id":"x","type":"release","libraries":[$libraryJson]}""").getOrThrow().libraries.single()

    private fun sha1Value(seed: String): String = buildString {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
        digest.forEach { append("%02x".format(it.toInt() and 0xff)) }
    }

    private val profileJson = """
        {
          "id": "1.21.4",
          "type": "release",
          "libraries": [
            {
              "name": "com.mojang:logging:1.1.1",
              "downloads": {"artifact": {
                "url": "https://libraries.minecraft.net/com/mojang/logging/1.1.1/logging-1.1.1.jar",
                "sha1": "${'$'}{LOG}",
                "size": 1500,
                "path": "com/mojang/logging/1.1.1/logging-1.1.1.jar"
              }}
            },
            {"name": "com.mojang:patchy:2.2.10", "rules": [{"action": "allow", "os": {"name": "osx"}}]},
    {"name": "com.mojang:windows-only:1.0", "rules": [{"action": "allow", "os": {"name": "windows"}}]},
            {"name": "net.java.dev.jna:jna:5.13.0", "rules": [{"action": "allow", "os": {"name": "windows"}}, {"action": "allow", "os": {"name": "linux"}}, {"action": "allow", "os": {"name": "osx"}}]},
            {"name": "it.unimi.dsi:fastutil:8.5.12"},
            {
              "name": "org.lwjgl:lwjgl-platform:3.3.4",
              "natives": {"linux": "natives-linux-${'$'}{arch}", "windows": "natives-windows-${'$'}{arch}"},
              "downloads": {"classifiers": {
                "natives-linux-64": {
                  "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl-platform/3.3.4/lwjgl-platform-3.3.4-natives-linux-64.jar",
                  "sha1": "${'$'}{NATIVE}",
                  "size": 900,
                  "path": "org/lwjgl/lwjgl-platform/3.3.4/lwjgl-platform-3.3.4-natives-linux-64.jar"
                }
              }}
            }
          ]
        }
    """.trimIndent()
        .replace("\${LOG}", sha1Value("1"))
        .replace("\${NATIVE}", sha1Value("6"))
}
