package studio.xycore.xcore.installer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreStoragePlan

/**
 * Destination containment and transfer preconditions. Every relative path these tests reject would
 * otherwise be written straight into the launcher's file tree, so the rejection is what keeps a
 * hostile version profile from choosing where it lands.
 */
class XCoreInstallPathsTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val sha1 = XCoreSha1.parse("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").valueOrNull()!!

    private val clientUrl = "https://piston-data.mojang.com/v1/objects/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/client.jar"

    private fun layout(root: File): XCoreStorageLayout = XCoreStorageLayout(
        plan = XCoreStoragePlan(
            root = root,
            requested = XCoreStorageRoot.INTERNAL,
            usedFallback = false,
            usableBytes = 10L * 1024 * 1024 * 1024,
            reason = null,
        ),
        directories = XCoreGameDirectory.REQUIRED.associateWith { File(root, it.relativePath) },
    )

    private fun paths(): XCoreInstallPaths {
        val root = temp.newFolder("game")
        return XCoreInstallPaths(layout(root))
    }

    @Test
    fun resolvesAPlainRelativeTargetUnderTheStorageRoot() {
        val paths = paths()
        val target = paths.resolve("versions/1.21.4/1.21.4.jar").valueOrNull()
        assertNotNull(target)
        assertEquals(File(paths.root, "versions/1.21.4/1.21.4.jar").path, target!!.path)
        assertEquals("versions/1.21.4/1.21.4.jar", paths.relativeOf(target))
    }

    @Test
    fun rejectsEveryShapeThatCouldEscapeTheRoot() {
        val paths = paths()
        val rejected = listOf(
            "" to "blank",
            "   " to "whitespace",
            "/etc/passwd" to "absolute",
            "libraries\\..\\escape.jar" to "backslash",
            "C:/x.jar" to "drive prefix",
            "versions/../../escape.jar" to "parent traversal",
            "versions//double.jar" to "empty segment",
            "versions/.hidden.jar" to "dotfile",
        )
        for ((candidate, why) in rejected) {
            val outcome = paths.resolve(candidate)
            assertFalse("$why ($candidate) must not resolve", outcome is XCoreResult.Success)
        }
    }

    @Test
    fun anEscapeIsAPermissionDecisionAndAMalformedPathIsValidation() {
        val paths = paths()
        assertTrue(paths.resolve("../outside.jar").errorOrNull() is XCoreError.Validation)
        val nested = paths.resolve("a/../../b.jar")
        assertTrue(
            "the textual check runs before canonicalisation, so traversal cannot be laundered by the filesystem",
            nested.errorOrNull() is XCoreError.Validation,
        )
    }

    @Test
    fun aDirectoryTheLayoutDoesNotPublishIsAFailureRatherThanANull() {
        val root = temp.newFolder("partial")
        val partial = XCoreStorageLayout(
            plan = XCoreStoragePlan(root, XCoreStorageRoot.INTERNAL, false, 0L, null),
            directories = mapOf(XCoreGameDirectory.LIBRARIES to File(root, "libraries")),
        )
        val paths = XCoreInstallPaths(partial)
        assertNotNull(paths.directory(XCoreGameDirectory.LIBRARIES).valueOrNull())
        assertTrue(paths.directory(XCoreGameDirectory.VERSIONS).errorOrNull() is XCoreError.Storage)
    }

    @Test
    fun prepareParentOfCreatesTheChainAndReportsTheFileItPrepared() {
        val paths = paths()
        val target = paths.resolve("assets/objects/ab/abcdef").valueOrNull()!!
        val prepared = paths.prepareParentOf(target).valueOrNull()
        assertEquals(target, prepared)
        assertTrue(target.parentFile.isDirectory)
    }

    @Test
    fun taskValidationRefusesAnUnapprovedHostAndPlainHttpButAllowsAnUnknownSize() {
        val unknownSize = XCoreDownloadTask(clientUrl, "versions/1.21.4/1.21.4.jar", sha1, -1L, XCoreDownloadOrigin.CLIENT_JAR)
        assertTrue(unknownSize.validate().valueOrNull() === unknownSize)

        val hostile = unknownSize.copy(url = "https://evil.example.com/client.jar")
        assertTrue(hostile.validate().errorOrNull() is XCoreError.Permission)

        val plain = unknownSize.copy(url = "http://piston-data.mojang.com/v1/objects/x/client.jar")
        assertTrue(plain.validate().errorOrNull() is XCoreError.Permission)

        val negative = unknownSize.copy(expectedBytes = -2L)
        assertTrue(negative.validate().errorOrNull() is XCoreError.Validation)
    }

    @Test
    fun aTargetFromTaskValidationIsAlsoCheckedForContainment() {
        val paths = paths()
        val escaping = XCoreDownloadTask(clientUrl, "../outside.jar", sha1, 100L, XCoreDownloadOrigin.CLIENT_JAR)
        val outcome = escaping.resolveWith(paths)
        assertTrue("validate() alone would happily write outside the tree", outcome is XCoreResult.Failure)
        assertTrue(paths.resolve("../outside.jar").errorOrNull() is XCoreError.Validation)
    }

    @Test
    fun refusesATransferTheVolumeCannotHold() {
        val paths = paths()
        val task = XCoreDownloadTask(clientUrl, "versions/1.21.4/1.21.4.jar", sha1, 25_000_000L, XCoreDownloadOrigin.CLIENT_JAR)
        val fiveTerabytes = 5L * 1024 * 1024 * 1024 * 1024
        val outcome = task.resolveWith(paths, spaceHeadroomBytes = fiveTerabytes)
        val storage = outcome.errorOrNull() as? XCoreError.Storage
        assertNotNull("insufficient storage must stop the install before the first byte", storage)
        val failure = storage!!
        assertTrue(failure.detail.contains("free bytes"))
        val usable = failure.usableBytes
        assertNotNull("the error must say how much room the volume actually has", usable)
        assertTrue(usable!! >= 0L)
        assertFalse("a full disk is not something a retry fixes", failure.retryable)
    }

    @Test
    fun anAlreadyPresentFileOnlyNeedsHeadroomForWhatIsStillMissing() {
        val paths = paths()
        val target = paths.resolve("versions/1.21.4/1.21.4.jar").valueOrNull()!!
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(1000))
        val task = XCoreDownloadTask(clientUrl, "versions/1.21.4/1.21.4.jar", sha1, 1000L, XCoreDownloadOrigin.CLIENT_JAR)
        assertTrue(
            "1000 of 1000 bytes are already on disk",
            task.resolveWith(paths, spaceHeadroomBytes = 0L).valueOrNull() != null,
        )
        val shortTask = task.copy(expectedBytes = 4000L)
        assertTrue(
            "the remaining 3000 bytes plus headroom must fit",
            shortTask.resolveWith(paths, spaceHeadroomBytes = 4L * 1024 * 1024 * 1024 * 1024).errorOrNull() is XCoreError.Storage,
        )
    }

    @Test
    fun aResolvedDownloadNamesAPartialFileBesideTheTarget() {
        val paths = paths()
        val resolved = XCoreDownloadTask(clientUrl, "libraries/x/y.jar", sha1, 10L, XCoreDownloadOrigin.LIBRARY)
            .resolveWith(paths, spaceHeadroomBytes = 0L)
            .valueOrNull()!!
        assertEquals("libraries/x/y.jar", resolved.task.relativeTarget)
        assertEquals(File(resolved.target.parentFile, "y.jar.part"), resolved.partial)
        assertEquals(resolved.task.id, XCoreDownloadTask.transferId(clientUrl, "libraries/x/y.jar"))
    }

    @Test
    fun theJournalIdIsStableAndDerivedOnlyFromUrlAndTarget() {
        val first = XCoreDownloadTask.transferId(clientUrl, "versions/1.21.4/1.21.4.jar")
        val second = XCoreDownloadTask.transferId(clientUrl, "versions/1.21.4/1.21.4.jar")
        val other = XCoreDownloadTask.transferId(clientUrl, "versions/1.21.4/other.jar")
        assertEquals(first, second)
        assertFalse(first == other)
        assertEquals(16, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{16}$")))
    }

    @Test
    fun aJournalRowCarriesNoBytesAndNoAttemptsBeforeTheTransferStarts() {
        val task = XCoreDownloadTask(clientUrl, "versions/1.21.4/1.21.4.jar", sha1, 500L, XCoreDownloadOrigin.CLIENT_JAR, eTag = "\"abc\"")
        val record = task.toRecord(nowMillis = 42L).valueOrNull()!!
        assertEquals(42L, record.updatedAtMillis)
        assertEquals(0L, record.writtenBytes)
        assertEquals(0, record.attempts)
        assertEquals(500L, record.remainingBytes)
        assertEquals("\"abc\"", record.etag)
        assertTrue(record.validate().valueOrNull() != null)
    }

    @Test
    fun alreadySatisfiedNeedsAPublishedDigestToTrustAnExistingFile() {
        val paths = paths()
        val verifier = XCoreHashVerifier()
        val body = ByteArray(64) { (it % 7).toByte() }
        val digest = XCoreSha1.parse(sha1OfBytes(body)).valueOrNull()!!
        val resolved = XCoreDownloadTask(clientUrl, "libraries/a/b.jar", digest, body.size.toLong(), XCoreDownloadOrigin.LIBRARY)
            .resolveWith(paths, spaceHeadroomBytes = 0L)
            .valueOrNull()!!

        assertFalse(resolved.alreadySatisfied(verifier))
        resolved.target.writeBytes(ByteArray(body.size) { (it % 5).toByte() })
        assertFalse("same length, wrong bytes", resolved.alreadySatisfied(verifier))
        resolved.target.writeBytes(body)
        assertTrue(resolved.alreadySatisfied(verifier))

        val noDigest = resolved.copy(task = resolved.task.copy(sha1 = null))
        assertFalse(
            "without a digest nothing proves the file is the right one, so it is fetched again",
            noDigest.alreadySatisfied(verifier),
        )
    }

    private fun sha1OfBytes(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        return buildString { digest.forEach { append("%02x".format(it.toInt() and 0xff)) } }
    }
}
