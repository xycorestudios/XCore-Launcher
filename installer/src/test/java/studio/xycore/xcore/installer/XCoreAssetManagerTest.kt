package studio.xycore.xcore.installer

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreStoragePlan
import studio.xycore.xcore.versions.XCoreAssetIndexRef
import studio.xycore.xcore.versions.XCoreRemoteDocument
import studio.xycore.xcore.versions.XCoreRemoteDocumentFetcher

/**
 * The asset index: one document naming hundreds of objects, each fetched by digest. The launcher's
 * whole idea of "this install is complete" rests on these names becoming safe relative paths and on
 * the index itself being the one the version profile signed.
 */
class XCoreAssetManagerTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var paths: XCoreInstallPaths

    private val audioOgg = bytes("audio")
    private val iconPng = bytes("icon")

    private val audioSha1 = digest(audioOgg)

    private val iconSha1 = digest(iconPng)

    private val indexJson = """
        {
          "objects": {
            "minecraft/sounds/icon.png": {"hash": "${iconSha1.value}", "size": ${iconPng.size}},
            "minecraft/sounds/audio.ogg": {"hash": "${audioSha1.value}", "size": ${audioOgg.size}}
          }
        }
    """.trimIndent()

    private val indexBytes = indexJson.toByteArray(Charsets.UTF_8)

    private val ref = XCoreAssetIndexRef(
        id = "17",
        url = "https://piston-meta.mojang.com/v1/packages/${digest(indexBytes).value}/17.json",
        sha1 = digest(indexBytes),
        sizeBytes = indexBytes.size.toLong(),
        totalSizeBytes = audioOgg.size + iconPng.size.toLong(),
    )

    @Before
    fun setUp() {
        val root = temp.newFolder("game")
        paths = XCoreInstallPaths(
            XCoreStorageLayout(
                plan = XCoreStoragePlan(root, XCoreStorageRoot.INTERNAL, false, 1L shl 34, null),
                directories = XCoreGameDirectory.REQUIRED.associateWith { File(root, it.relativePath) },
            ),
        )
    }

    private fun manager(body: String = indexJson): XCoreAssetManager = XCoreAssetManager(
        fetcher = SingleDocumentFetcher(body),
        paths = paths,
    )

    /** Serves one document so the digest and parsing branches can each be aimed at a known body. */
    private class SingleDocumentFetcher(private val body: String) : XCoreRemoteDocumentFetcher {
        override suspend fun fetch(url: String, etag: String?): XCoreResult<XCoreRemoteDocument> =
            XCoreRemoteDocument(
                body = body,
                sourceUrl = url,
                eTag = null,
                lastModifiedMillis = null,
                contentLength = body.length.toLong(),
            ).asXCoreSuccess()
    }

    @Test
    fun objectsAreSortedAndNamedByTheFirstTwoHexDigitsOfTheirDigest() {
        val index = manager().parseIndex(indexJson, ref).getOrThrow()
        assertEquals(listOf("minecraft/sounds/audio.ogg", "minecraft/sounds/icon.png"), index.objects.map { it.name })
        assertEquals(2, index.count)
        assertEquals(audioOgg.size.toLong() + iconPng.size.toLong(), index.totalBytes)
        val audio = index.objects.first()
        assertEquals(audioSha1.value.substring(0, 2), audio.bucket)
        assertEquals("assets/objects/${audio.bucket}/${audioSha1.value}", audio.objectsRelativePath)
        assertEquals("assets/virtual/17/minecraft/sounds/audio.ogg", audio.virtualPath("17"))
    }

    @Test
    fun anIndexThatIsNotTheOneTheProfileSignedIsRefusedBeforeParsing() = runTest {
        val outcome = manager(indexJson).readIndex(ref.copy(sha1 = digest("a different document".toByteArray())))
        val error = outcome.errorOrNull()
        assertTrue("got $error", error is XCoreError.Storage)
        assertTrue(error!!.detail.contains("hashes to"))
    }

    @Test
    fun anIndexFromAnUnapprovedHostIsRefusedWithoutOpeningAConnection() = runTest {
        val outcome = manager().readIndex(ref.copy(url = "https://mirror.example.com/17.json"))
        assertTrue(outcome.errorOrNull() is XCoreError.Permission)
    }

    @Test
    fun theDocumentThatWasServedDoesParseAgainstItsOwnRef() = runTest {
        val index = manager().readIndex(ref).getOrThrow()
        assertEquals("17", index.id)
        assertEquals(2, index.count)
        assertEquals(ref.sha1, index.indexSha1)
    }

    @Test
    fun anAssetNameThatIsNotASafeRelativePathMeansThisIsNotTheIndexWeAskedFor() {
        val manager = manager()
        for (name in listOf("../escape", "/absolute", "a\\b", "double//slash", "")) {
            val json = """{"objects": {"${name.jsonEscape()}": {"hash": "${audioSha1.value}", "size": 5}}}"""
            val error = manager.parseIndex(json, ref).errorOrNull()
            assertNotNull("'$name' must not reach the filesystem layer", error)
            assertTrue("got $error", error is XCoreError.Network)
        }
    }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    @Test
    fun anObjectWithoutAUsableHashOrWithNoObjectsMapIsRejected() {
        val manager = manager()
        assertTrue(manager.parseIndex("""{"objects":{"a/b":{"hash":"zz","size":5}}}""", ref).errorOrNull() is XCoreError.Network)
        assertTrue(manager.parseIndex("""{"objects":{"a/b":{"size":5}}}""", ref).errorOrNull() is XCoreError.Network)
        assertTrue(manager.parseIndex("""{"version":2}""", ref).errorOrNull() is XCoreError.Network)
    }

    @Test
    fun planFetchesOnlyWhatIsMissingAndPricesTheRest() {
        val index = manager().parseIndex(indexJson, ref).getOrThrow()
        val audio = index.objects.first()
        val stored = paths.resolve(audio.objectsRelativePath).getOrThrow()
        stored.parentFile.mkdirs()
        stored.writeBytes(audioOgg)

        val present = manager().objectsPresent(index)
        assertEquals(listOf(audio.name), present.map { it.name })

        val plan = manager().plan(index).getOrThrow()
        assertEquals(1, plan.presentCount)
        assertEquals(1, plan.tasks.size)
        assertEquals(iconSha1.value, plan.tasks.single().sha1?.value)
        assertEquals("assets/objects/${iconSha1.value.substring(0, 2)}/${iconSha1.value}", plan.tasks.single().relativeTarget)
        assertEquals("https://resources.download.minecraft.net/${iconSha1.value.substring(0, 2)}/${iconSha1.value}", plan.tasks.single().url)
        assertEquals(XCoreDownloadOrigin.ASSET, plan.tasks.single().origin)
        assertEquals(iconPng.size.toLong(), plan.missingBytes)
        assertTrue(plan.needsTransfer)
    }

    @Test
    fun anIndexWhoseObjectsAreAllOnDiskNeedsNoTransferAtAll() {
        val index = manager().parseIndex(indexJson, ref).getOrThrow()
        index.objects.forEach { asset ->
            val body = if (asset.name.endsWith("ogg")) audioOgg else iconPng
            val target = paths.resolve(asset.objectsRelativePath).getOrThrow()
            target.parentFile.mkdirs()
            target.writeBytes(body)
        }
        val plan = manager().plan(index).getOrThrow()
        assertTrue(plan.tasks.isEmpty())
        assertFalse(plan.needsTransfer)
        assertEquals(2, plan.presentCount)
        assertEquals(0L, plan.missingBytes)
    }

    @Test
    fun aFileThatExistsButHashesWrongIsNotCountedAsPresent() {
        val index = manager().parseIndex(indexJson, ref).getOrThrow()
        val audio = index.objects.first()
        val stored = paths.resolve(audio.objectsRelativePath).getOrThrow()
        stored.parentFile.mkdirs()
        stored.writeBytes(bytes("wrong"))
        assertTrue(manager().objectsPresent(index).isEmpty())
        assertEquals(2, manager().plan(index).getOrThrow().tasks.size)
    }

    @Test
    fun materialiseVirtualCopiesVerifiedObjectsAndReportsWhatItCouldNotPlace() {
        val index = manager().parseIndex(indexJson, ref).getOrThrow()
        val audio = index.objects.first()
        val stored = paths.resolve(audio.objectsRelativePath).getOrThrow()
        stored.parentFile.mkdirs()
        stored.writeBytes(audioOgg)

        val unresolved = manager().materialiseVirtual(index).getOrThrow()
        assertEquals(listOf("minecraft/sounds/icon.png"), unresolved.map { it.name })
        val virtual = paths.resolve(audio.virtualPath("17")).getOrThrow()
        assertTrue(virtual.isFile)
        assertTrue(audioOgg.contentEquals(virtual.readBytes()))
    }

    private fun bytes(seed: String): ByteArray = ByteArray(32) { (it + seed.hashCode()) .toByte() }

    private fun digest(bytes: ByteArray): XCoreSha1 =
        XCoreSha1.parse(hex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))).getOrThrow()

    private fun hex(bytes: ByteArray): String = buildString {
        bytes.forEach { append("%02x".format(it.toInt() and 0xff)) }
    }
}

/** Direct checks on the digest step itself, including the cheap length test that runs first. */
class XCoreHashVerifierTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val verifier = XCoreHashVerifier()

    private val body = ByteArray(5_000) { (it % 97).toByte() }

    private val sha1 = XCoreSha1.parse(
        buildString {
            java.security.MessageDigest.getInstance("SHA-1").digest(body).forEach { append("%02x".format(it.toInt() and 0xff)) }
        },
    ).getOrThrow()

    @Test
    fun theKnownDigestOfAbcIsWhatTheVerifierComputes() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            verifier.sha1OfText("abc").getOrThrow().value,
        )
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            verifier.sha1OfText("").getOrThrow().value,
        )
    }

    @Test
    fun aFileLargerThanTheChunkIsHashedInFull() {
        val file = temp.newFile("client.jar")
        file.writeBytes(body)
        assertEquals(sha1, verifier.sha1Of(file).getOrThrow())
        assertTrue(verifier.matches(file, sha1).getOrThrow())
        assertFalse(verifier.matches(file, XCoreSha1.parse(sha1.value.reversed()).getOrThrow()).getOrThrow())
    }

    @Test
    fun aMissingFileIsAnErrorButAMismatchIsAnAnswer() {
        val absent = File(temp.root, "nope.jar")
        assertTrue(verifier.sha1Of(absent).errorOrNull() is XCoreError.Storage)
        assertTrue(verifier.verify(absent, sha1).errorOrNull() is XCoreError.Storage)
    }

    @Test
    fun aTruncatedFileFailsOnLengthBeforeAnyHashing() {
        val file = temp.newFile("short.jar")
        file.writeBytes(body.copyOfRange(0, 120))
        val error = verifier.verify(file, expectedSha1 = null, expectedBytes = 4_000L).errorOrNull()
        assertTrue(error is XCoreError.Storage)
        assertTrue(error!!.detail.contains("4000"))
    }

    @Test
    fun anOversizeFileIsRejectedToo() {
        val file = temp.newFile("long.jar")
        file.writeBytes(body)
        assertTrue(verifier.verify(file, expectedSha1 = null, expectedBytes = 10L).errorOrNull() is XCoreError.Storage)
    }

    @Test
    fun anUnpublishedDigestStillProducesARecordWithNoClaimAboutIt() {
        val file = temp.newFile("coordonly.jar")
        file.writeBytes(body)
        val verified = verifier.verify(file, expectedSha1 = null, expectedBytes = body.size.toLong()).getOrThrow()
        assertEquals(body.size.toLong(), verified.sizeBytes)
        assertNull(verified.sha1)
    }

    @Test
    fun aDigestMismatchNamesBothDigestsSoTheFailureIsDiagnosable() {
        val file = temp.newFile("swapped.jar")
        file.writeBytes(body.copyOfRange(0, body.size - 1))
        val error = verifier.verify(file, sha1).errorOrNull()
        assertTrue(error is XCoreError.Storage)
        assertTrue(error!!.detail.contains(sha1.value))
        assertTrue(error.detail.contains("does not match its published digest"))
    }
}
