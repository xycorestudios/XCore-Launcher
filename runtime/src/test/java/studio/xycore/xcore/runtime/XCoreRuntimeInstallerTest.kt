package studio.xycore.xcore.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeChannel
import studio.xycore.xcore.core.XCoreRuntimeFailureReason
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreStoragePlan
import studio.xycore.xcore.installer.XCoreByteResponse
import studio.xycore.xcore.installer.XCoreByteTransport
import studio.xycore.xcore.installer.XCoreRangeRequest

/**
 * Where a runtime may come from, and what the installer refuses before it touches the network.
 *
 * The catalogue is the whole allowlist, so these tests hold it to the brief's two hard rules: every
 * entry must be a real package whose digest can be checked and whose licence permits redistribution,
 * and an entry that fails either is not offered as an install choice at all.
 */
class XCoreRuntimeInstallerTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val arm64 = XCoreJavaSelector.deviceFor("arm64-v8a", apiLevel = 34, totalRamBytes = 6L * 1024 * 1024 * 1024)

    private class RecordingTransport : XCoreByteTransport {
        var openCalls = 0

        override suspend fun open(request: XCoreRangeRequest): XCoreResult<XCoreByteResponse> {
            openCalls++
            return XCoreError.Network(detail = "the test transport never connects", url = request.url).asXCoreFailure()
        }
    }

    private fun installer(transport: XCoreByteTransport = RecordingTransport()) = XCoreRuntimeInstaller(
        device = arm64,
        detector = XCoreRuntimeDetector(arm64),
        journal = null,
        transport = transport,
    )

    private fun source(
        licenseId: String = "GPL-2.0-with-classpath-exception",
        url: String = "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.21-1_aarch64.deb",
        sha256Hex: String = "b".repeat(64),
        compressedBytes: Long = 96_344_020L,
        javaMajor: Int = 17,
        abi: String = "arm64-v8a",
        archiveFormat: XCoreArchiveFormat = XCoreArchiveFormat.DEB_AR,
        memberPath: String? = "data.tar.xz",
    ): XCoreRuntimeSource = XCoreRuntimeSource(
        id = "test-openjdk-$javaMajor-$abi",
        displayName = "Test OpenJDK $javaMajor",
        abi = abi,
        javaMajor = javaMajor,
        versionLabel = "$javaMajor.0.1-1",
        url = url,
        sha256Hex = sha256Hex,
        sha1Hex = null,
        compressedBytes = compressedBytes,
        installedSizeKib = 217_996L,
        licenseId = licenseId,
        archiveFormat = archiveFormat,
        memberPath = memberPath,
        dependsOn = emptyList(),
        notice = "test source",
    )

    @Test
    fun everyCatalogueEntrySurvivesItsOwnValidation() {
        XCoreRuntimeSource.CATALOGUE.forEach { entry ->
            val outcome = entry.validate()
            assertNull("${entry.id} must validate: ${outcome.errorOrNull()?.detail}", outcome.errorOrNull())
            assertTrue("${entry.id} must publish a 64-hex SHA-256", entry.sha256MatchesPublished)
            assertTrue("${entry.id} must be fetched over https", entry.url.startsWith("https://"))
            assertTrue("${entry.id} has a verifiable digest", entry.sha256Hex.isNotBlank())
            assertTrue("${entry.id} must name the payload member", entry.archiveFormat != XCoreArchiveFormat.DEB_AR || !entry.memberPath.isNullOrBlank())
        }
        assertEquals(setOf("packages.termux.dev"), XCoreRuntimeSource.ALLOWED_HOSTS)
        assertEquals(setOf("arm64-v8a", "x86_64"), XCoreRuntimeSource.CATALOGUE.map(XCoreRuntimeSource::abi).toSet())
    }

    @Test
    fun theCatalogueHoldsNoJava8EntryAndSaysWhy() {
        assertTrue(XCoreRuntimeSource.CATALOGUE.none { it.javaMajor == 8 })
        assertNull(XCoreRuntimeSource.forMajor("arm64-v8a", 8))
        assertNull(XCoreRuntimeSource.forMajor("x86_64", 8))
        val refusal = XCoreRuntimeSource.noSourceFor("arm64-v8a", 8)
        assertTrue(refusal.detail.contains("Java 8"))
        assertTrue("the launcher must not guess a digest", refusal.detail.contains("will not guess one"))
        assertEquals(17, XCoreRuntimeSource.forMajor("arm64-v8a", 17)?.javaMajor)
        assertEquals(21, XCoreRuntimeSource.forMajor("x86_64", 21)?.javaMajor)
    }

    @Test
    fun onlyTheDeviceAbiIsOfferedAndEveryOfferedEntryIsValidated() {
        val forArm = XCoreRuntimeSource.forDevice(arm64)
        assertEquals(2, forArm.size)
        assertTrue(forArm.all { it.abi == "arm64-v8a" })
        assertEquals(listOf(21, 17), forArm.map(XCoreRuntimeSource::javaMajor))
        assertEquals(
            "an entry that fails validation is not a choice",
            0,
            XCoreRuntimeSource.forDevice(XCoreJavaSelector.deviceFor("armeabi-v7a", apiLevel = 30)).size,
        )
    }

    @Test
    fun refusesALicenceThatDoesNotPermitRedistribution() {
        listOf("Proprietary", "", "Oracle Binary Code License", null).forEach { licence ->
            val outcome = source(licenseId = licence.orEmpty()).validate()
            val error = outcome.errorOrNull()
            assertNotNull("$licence must be refused", error)
            assertEquals(XCoreErrorKind.PERMISSION, error!!.kind)
            assertTrue(error.detail.contains("licence") || error.detail.contains("Licence"))
        }
        assertTrue(XCoreLicensePolicy.isRedistributable("GPL-2.0-with-classpath-exception").isSuccess)
        assertTrue(XCoreLicensePolicy.isRedistributable("Apache 2.0").isSuccess)
        assertEquals("Apache-2.0", XCoreLicensePolicy.canonicalize("apache2"))
    }

    @Test
    fun refusesAHostNobodyApprovedAndAPlainHttpFetch() {
        val elsewhere = source(url = "https://jdk-mirror.example.com/openjdk-17_aarch64.deb")
        val plainHttp = source(url = "http://packages.termux.dev/apt/termux-main/openjdk-17_aarch64.deb")
        val redirectLooking = source(url = "https://packages.termux.dev.evil.test/apt/openjdk.deb")

        listOf(elsewhere, redirectLooking).forEach { entry ->
            val error = entry.validate().errorOrNull()
            assertNotNull(error)
            assertEquals(XCoreErrorKind.PERMISSION, error!!.kind)
            assertTrue(error.detail.contains("not an approved runtime source"))
        }
        val httpError = plainHttp.validate().errorOrNull()
        assertEquals(XCoreErrorKind.VALIDATION, httpError!!.kind)
        assertTrue(httpError.detail.contains("https"))
    }

    @Test
    fun refusesAnEntryWhoseBytesCouldNeverBeChecked() {
        val noDigest = source(sha256Hex = "")
        val shortDigest = source(sha256Hex = "deadbeef")
        val noSize = source(compressedBytes = 0L)

        assertTrue(noDigest.validate().errorOrNull()!!.detail.contains("no published SHA-256"))
        assertTrue(shortDigest.validate().errorOrNull()!!.detail.contains("no published SHA-256"))
        assertTrue(noSize.validate().errorOrNull()!!.detail.contains("does not declare a compressed size"))
        assertFalse(noDigest.sha256MatchesPublished)
        assertNull(source(sha256Hex = "b".repeat(64)).publishedSha1().valueOrNull())
    }

    @Test
    fun refusesAnArContainerThatNamesNoPayloadMember() {
        val memberless = source(memberPath = null)
        val error = memberless.validate().errorOrNull()
        assertEquals(XCoreErrorKind.VALIDATION, error!!.kind)
        assertTrue(error.detail.contains("names no payload member"))

        val unknownFormat = source(archiveFormat = XCoreArchiveFormat.UNKNOWN)
        assertTrue(unknownFormat.validate().errorOrNull()!!.detail.contains("container format"))
    }

    @Test
    fun theLocalChannelRefusesBeforeATransportIsTouched() {
        val transport = RecordingTransport()
        val outcome = runBlocking {
            installer(transport).install(
                layout = XCoreStorageLayout(
                    plan = XCoreStoragePlan(
                        root = temp.newFolder("game"),
                        requested = studio.xycore.xcore.core.XCoreStorageRoot.INTERNAL,
                        usedFallback = false,
                        usableBytes = 10L * 1024 * 1024 * 1024,
                        reason = null,
                    ),
                    directories = emptyMap(),
                ),
                source = XCoreRuntimeSource.TERMUX_OPENJDK_17_AARCH64,
                channel = XCoreFetchPolicy.LOCAL_ONLY,
            )
        }
        val error = outcome.errorOrNull()
        assertNotNull(error)
        assertEquals("the channel must refuse before a socket is opened", 0, transport.openCalls)
        assertTrue(error!!.detail.contains("forbids network installs"))
    }

    @Test
    fun refusesARuntimeBuiltForAnotherAbiBeforeDownloadingIt() {
        val transport = RecordingTransport()
        val outcome = runBlocking {
            installer(transport).install(
                layout = XCoreStorageLayout(
                    plan = XCoreStoragePlan(
                        root = temp.newFolder("game"),
                        requested = studio.xycore.xcore.core.XCoreStorageRoot.INTERNAL,
                        usedFallback = false,
                        usableBytes = 10L * 1024 * 1024 * 1024,
                        reason = null,
                    ),
                    directories = emptyMap(),
                ),
                source = XCoreRuntimeSource.TERMUX_OPENJDK_21_X86_64,
                channel = XCoreFetchPolicy.VERIFIED_ARCHIVE_SOURCES,
            )
        }
        val error = outcome.errorOrNull()
        assertNotNull(error)
        assertEquals(0, transport.openCalls)
        assertEquals(XCoreRuntimeFailureReason.INCOMPATIBLE_ABI, (error as XCoreError.Runtime).reason)
        assertTrue(error.detail.contains("this device is arm64-v8a"))
    }

    @Test
    fun mapsTheStoredChannelOntoWhatTheRuntimeSubsystemWillDo() {
        assertEquals(XCoreFetchPolicy.LOCAL_ONLY, XCoreFetchPolicy.fromConfig(XCoreRuntimeChannel.LOCAL_ONLY))
        assertEquals(XCoreFetchPolicy.VERIFIED_ARCHIVE_SOURCES, XCoreFetchPolicy.fromConfig(XCoreRuntimeChannel.STABLE))
        assertEquals(XCoreFetchPolicy.VERIFIED_ARCHIVE_SOURCES, XCoreFetchPolicy.fromConfig(XCoreRuntimeChannel.BETA))
        assertFalse(XCoreFetchPolicy.LOCAL_ONLY.allowsNetworkFetch)
        assertTrue(XCoreFetchPolicy.VERIFIED_ARCHIVE_SOURCES.allowsNetworkFetch)
        assertTrue(XCoreFetchPolicy.describeMapping(XCoreRuntimeChannel.BETA).contains("changes nothing"))
        assertTrue(XCoreFetchPolicy.LOCAL_ONLY.refusal().detail.contains("import a runtime"))
    }

    @Test
    fun findsTheJdkHomeTheArchiveCreatedInsteadOfAssumingAPrefix() {
        val root = temp.newFolder("unpacked")
        writeHome(File(root, "usr"), "17.0.13")
        val modular = installer().locateRuntimeHome(root)
        assertEquals(File(root, "usr").absolutePath, modular?.absolutePath)

        val nested = temp.newFolder("nested")
        File(nested, "usr/jre/bin").mkdirs()
        File(nested, "usr/jre/bin/java").writeText("stub")
        File(nested, "usr/jre/release").writeText("JAVA_VERSION=\"1.8.0_382\"\n")
        assertEquals(File(nested, "usr/jre").absolutePath, installer().locateRuntimeHome(nested)?.absolutePath)

        val twoHomes = temp.newFolder("two")
        writeHome(File(twoHomes, "a"), "17.0.2")
        writeHome(File(twoHomes, "b"), "17.0.21")
        assertEquals("the newest patch the tree itself claims must win", File(twoHomes, "b").absolutePath, installer().locateRuntimeHome(twoHomes)?.absolutePath)

        val noJava = temp.newFolder("bare")
        File(noJava, "usr/bin").mkdirs()
        assertNull(installer().locateRuntimeHome(noJava))
    }

    @Test
    fun namesTheLibrariesTheRuntimeNeedsThatNobodyOnTheDeviceSupplies() {
        val home = temp.newFolder("jdk")
        File(home, "bin").mkdirs()
        File(home, "lib/server").mkdirs()
        File(home, "lib").mkdirs()
        File(home, "bin/java").writeBytes(
            buildElf64(
                machine = XCoreElfFacts.EM_AARCH64,
                interpreter = "/apex/com.android.runtime/bin/linker64",
                needed = listOf("libandroid-spawn.so", "libc.so", "libjli.so"),
            ),
        )
        File(home, "release").writeText("JAVA_VERSION=\"17.0.2\"\n")
        File(home, "lib/server/libjvm.so").writeBytes(
            buildElf64(
                machine = XCoreElfFacts.EM_AARCH64,
                interpreter = "/apex/com.android.runtime/bin/linker64",
                needed = listOf("libiconv.so", "libm.so"),
            ),
        )
        File(home, "lib/libjli.so").writeBytes(
            buildElf64(
                machine = XCoreElfFacts.EM_AARCH64,
                interpreter = "/apex/com.android.runtime/bin/linker64",
                needed = listOf("libc.so"),
            ),
        )

        val missing = installer().unresolvedDependencies(home)
        assertEquals(listOf("libandroid-spawn.so", "libiconv.so"), missing)
        assertEquals(
            1,
            installer().unresolvedDependencies(missing).size,
        )
        assertTrue(installer().unresolvedDependencies(missing).first().contains("links libandroid-spawn.so, libiconv.so"))
        assertTrue(installer().unresolvedDependencies(emptyList()).isEmpty())
    }

    private fun writeHome(home: File, version: String) {
        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("stub")
        File(home, "release").writeText("JAVA_VERSION=\"$version\"\n")
    }
}
