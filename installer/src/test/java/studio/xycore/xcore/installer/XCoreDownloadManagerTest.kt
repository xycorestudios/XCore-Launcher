package studio.xycore.xcore.installer

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreCancelled
import studio.xycore.xcore.data.XCoreDownloadRecord
import studio.xycore.xcore.data.XCoreDownloadState
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreStoragePlan
import studio.xycore.xcore.versions.XCoreRetryPolicy

/**
 * Transfer semantics driven against a scripted [XCoreByteTransport] instead of a live socket, so
 * resume, retry, cancellation and digest failure each happen on demand. The byte engine is where a
 * truncated or swapped download either becomes a working install or a typed failure, and a test
 * covering only the happy path would not tell those apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XCoreDownloadManagerTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var paths: XCoreInstallPaths
    private lateinit var journal: InMemoryJournal

    private val payload = ByteArray(400) { (it * 31 % 251).toByte() }

    private val payloadSha1: XCoreSha1 by lazy { XCoreSha1.parse(hex(sha1Of(payload))).getOrThrow() }

    private val url = "https://piston-data.mojang.com/v1/objects/${payloadSha1.value}/client.jar"

    @Before
    fun setUp() {
        root = temp.newFolder("game")
        paths = XCoreInstallPaths(
            XCoreStorageLayout(
                plan = XCoreStoragePlan(root, XCoreStorageRoot.INTERNAL, false, 1L shl 34, null),
                directories = XCoreGameDirectory.REQUIRED.associateWith { File(root, it.relativePath) },
            ),
        )
        journal = InMemoryJournal()
    }

    private fun manager(
        transport: XCoreByteTransport,
        attempts: Int = 3,
    ) = XCoreDownloadManager(
        transport = transport,
        journal = journal,
        verifier = XCoreHashVerifier(),
        retryPolicy = XCoreRetryPolicy(attempts, 1L, 1.0, 1L),
        chunkBytes = 64,
        journalEveryBytes = 128L,
        progressEveryMillis = 0L,
    )

    private fun resolved(
        target: String = "versions/1.21.4/1.21.4.jar",
        sha1: XCoreSha1? = payloadSha1,
        expectedBytes: Long = payload.size.toLong(),
        recheckExisting: Boolean = true,
    ): ResolvedDownload = XCoreDownloadTask(
        url = url,
        relativeTarget = target,
        sha1 = sha1,
        expectedBytes = expectedBytes,
        origin = XCoreDownloadOrigin.CLIENT_JAR,
        recheckExisting = recheckExisting,
    ).resolveWith(paths, spaceHeadroomBytes = 0L).valueOrNull()!!

    /** The installer opens a journal row before transferring; these tests mirror that order. */
    private fun tracked(download: ResolvedDownload, state: XCoreDownloadState = XCoreDownloadState.PENDING): ResolvedDownload {
        journal.put(download.task.toRecord(nowMillis = 1L, state = state).getOrThrow())
        return download
    }

    @Test
    fun aCleanTransferWritesTheTargetAndClearsThePartial() = runTest {
        val transport = ScriptedTransport { _, _ -> ok(payload) }
        val download = tracked(resolved())
        val seen = mutableListOf<XCoreTransferProgress>()

        val outcome = manager(transport).transfer(download, listener = { seen += it })

        val written = outcome as XCoreTransferOutcome.Written
        assertEquals(payload.size.toLong(), written.bytes)
        assertEquals(0L, written.resumedFrom)
        assertEquals(download.task.relativeTarget, written.relativeTarget)
        assertContent(payload, File(root, "versions/1.21.4/1.21.4.jar").readBytes())
        assertFalse(download.partial.exists())
        assertEquals(XCoreDownloadState.COMPLETED, journal.statesFor(download.task.id).last())
        assertEquals(payload.size.toLong(), journal.row(download.task.id)!!.writtenBytes)
        assertEquals("1.21.4.jar", seen.last().label)
        assertEquals(payload.size.toLong(), seen.last().aggregateBytesDone)
        assertEquals(1f, seen.last().aggregateFraction)
    }

    @Test
    fun aDigestMismatchNeverBecomesTheFinishedFile() = runTest {
        val transport = ScriptedTransport { _, _ -> ok(payload) }
        val wrong = XCoreSha1.parse(hex(sha1Of("different bytes".toByteArray()))).getOrThrow()
        val download = tracked(resolved(sha1 = wrong))

        val outcome = manager(transport, attempts = 1).transfer(download)

        val failed = outcome as XCoreTransferOutcome.Failed
        assertTrue(failed.error is XCoreError.Storage)
        assertTrue(failed.error.detail.contains("published digest"))
        assertFalse("the corrupt file must not be promoted", download.target.exists())
        assertFalse("the corrupt partial must not be resumed later", download.partial.exists())
        assertEquals(XCoreDownloadState.FAILED, journal.statesFor(download.task.id).last())
    }

    @Test
    fun aTruncatedBodyIsCaughtRatherThanAcceptedAsTheFinishedFile() = runTest {
        val transport = ScriptedTransport { _, _ -> ok(payload.copyOfRange(0, 120)) }
        val download = tracked(resolved())

        val outcome = manager(transport, attempts = 1).transfer(download)

        assertTrue(outcome is XCoreTransferOutcome.Failed)
        assertEquals(payload.size.toLong(), download.task.expectedBytes)
        assertFalse(download.target.exists())
    }

    @Test
    fun resumesFromThePartialWhenTheServerStillValidatesIt() = runTest {
        val alreadyHave = 250
        val download = tracked(resolved(), state = XCoreDownloadState.PAUSED)
        download.partial.parentFile.mkdirs()
        download.partial.writeBytes(payload.copyOfRange(0, alreadyHave))

        val transport = ScriptedTransport { request, _ ->
            if (request.fromBytes == 0L) ok(payload) else ranged(payload.copyOfRange(request.fromBytes.toInt(), payload.size), payload.size.toLong())
        }
        val seen = mutableListOf<XCoreTransferProgress>()

        val outcome = manager(transport).transfer(download, listener = { seen += it })

        assertEquals(1, transport.requests.size)
        assertTrue("the request should have carried a Range", transport.requests.single().isResume)
        assertEquals(alreadyHave.toLong(), (outcome as XCoreTransferOutcome.Written).resumedFrom)
        assertContent(payload, download.target.readBytes())
        assertFalse(download.partial.exists())
        val last = seen.last()
        assertEquals(payload.size.toLong(), last.currentTaskBytes)
        val mid = seen.first { it.currentTaskBytes < payload.size }
        assertTrue("the resumed head start has to show in the byte count", mid.currentTaskBytes > alreadyHave)
        assertEquals(
            "a resumed transfer's total is the finished size, not that size plus its head start",
            payload.size.toLong(),
            mid.currentTaskTotalBytes,
        )
        assertEquals(XCoreDownloadState.COMPLETED, journal.statesFor(download.task.id).last())
    }

    @Test
    fun aServerThatIgnoresTheRangeRestartsWithoutStitchingThePartialIn() = runTest {
        val download = tracked(resolved())
        download.partial.parentFile.mkdirs()
        download.partial.writeBytes(ByteArray(100) { 0x7f })

        val outcome = manager(ScriptedTransport { _, _ -> ok(payload) }).transfer(download)

        assertEquals(0L, (outcome as XCoreTransferOutcome.Written).resumedFrom)
        assertContent(payload, download.target.readBytes())
    }

    @Test
    fun aFileAlreadyOnDiskAndHashingCorrectlyCostsNoRequest() = runTest {
        val download = tracked(resolved())
        download.target.parentFile.mkdirs()
        download.target.writeBytes(payload)
        val transport = ScriptedTransport { _, _ -> ok(payload) }

        val outcome = manager(transport).transfer(download)

        assertEquals(payload.size.toLong(), (outcome as XCoreTransferOutcome.AlreadyPresent).bytes)
        assertEquals(0, transport.requests.size)
        assertEquals(XCoreDownloadState.COMPLETED, journal.statesFor(download.task.id).last())
    }

    @Test
    fun aPartialWithNoPublishedDigestIsReReadFromTheStart() = runTest {
        val download = tracked(resolved(sha1 = null, expectedBytes = payload.size.toLong()))
        download.partial.parentFile.mkdirs()
        download.partial.writeBytes(payload.copyOfRange(0, 100))
        val transport = ScriptedTransport { _, _ -> ok(payload) }

        val outcome = manager(transport).transfer(download)

        assertEquals("no digest means nothing to resume against", 0L, transport.requests.single().fromBytes)
        assertContent(payload, download.target.readBytes())
        assertTrue(outcome is XCoreTransferOutcome.Written)
    }

    @Test
    fun aRetryableTransportFailureIsRetriedAndSucceeds() = runTest {
        val download = tracked(resolved())
        val transport = ScriptedTransport { _, call ->
            if (call == 1) XCoreError.Network("connection reset", url = url).asXCoreFailure() else ok(payload)
        }

        val outcome = manager(transport).transfer(download)

        assertEquals(2, transport.requests.size)
        assertTrue(outcome is XCoreTransferOutcome.Written)
        assertEquals(2, journal.row(download.task.id)!!.attempts)
        assertContent(payload, download.target.readBytes())
    }

    @Test
    fun aPermanentClientErrorIsNotRetried() = runTest {
        val download = tracked(resolved())
        val transport = ScriptedTransport { _, _ -> XCoreError.Network("not found", url = url, httpStatus = 404, retryable = false).asXCoreFailure() }

        val outcome = manager(transport).transfer(download)

        assertEquals("repeating a 404 only costs the user bandwidth", 1, transport.requests.size)
        val error = (outcome as XCoreTransferOutcome.Failed).error as XCoreError.Network
        assertEquals(404, error.httpStatus)
        assertFalse(download.target.exists())
    }

    @Test
    fun aStoppedTransferJournalsPausedAndNamesTheOffsetItStoppedAt() = runTest {
        val download = tracked(resolved())
        val transport = ScriptedTransport { _, _ -> xCoreCancelled("client jar", "the user pressed stop") }

        val outcome = manager(transport).transfer(download)

        val error = outcome as XCoreTransferOutcome.Failed
        assertTrue(error.error is XCoreError.Cancelled)
        assertTrue(error.error.detail.contains("stopped after 0 bytes"))
        assertTrue(journal.statesFor(download.task.id).contains(XCoreDownloadState.PAUSED))
    }

    @Test
    fun a416AgainstAPartialThatCannotBeTrustedDiscardsIt() = runTest {
        val download = tracked(resolved(recheckExisting = false))
        download.partial.parentFile.mkdirs()
        download.partial.writeBytes(ByteArray(300) { 0x11 })
        val transport = ScriptedTransport { _, _ -> status(416, ByteArray(0)) }

        val outcome = manager(transport, attempts = 1).transfer(download)

        assertTrue("got $outcome", outcome is XCoreTransferOutcome.Failed)
        assertEquals(416, ((outcome as XCoreTransferOutcome.Failed).error as XCoreError.Network).httpStatus)
        assertFalse(download.partial.exists())
        assertTrue(journal.statesFor(download.task.id).contains(XCoreDownloadState.PENDING))
    }

    @Test
    fun a416AgainstAFileThatAlreadyHashesCorrectlyIsCompletion() = runTest {
        val download = tracked(resolved(recheckExisting = false))
        download.target.parentFile.mkdirs()
        download.target.writeBytes(payload)

        val outcome = manager(ScriptedTransport { _, _ -> status(416, ByteArray(0)) }).transfer(download)

        assertTrue(outcome is XCoreTransferOutcome.AlreadyPresent)
        assertEquals(XCoreDownloadState.COMPLETED, journal.statesFor(download.task.id).last())
    }

    @Test
    fun aBatchKeepsPerFileFailuresSeparateFromTheRestOfTheInstall() = runTest {
        val digest = XCoreSha1.parse(hex(sha1Of(ByteArray(64) { 0x02 }))).getOrThrow()
        val good = tracked(resolved(target = "libraries/a/b.jar", expectedBytes = 64L, sha1 = digest))
        val bad = tracked(resolved(target = "libraries/c/d.jar", expectedBytes = 64L))
        val transport = ScriptedTransport { _, _ -> ok(ByteArray(64) { 0x02 }) }

        val batch = manager(transport, attempts = 1).transferAll(listOf(good, bad), maxConcurrency = 2, failFast = false).getOrThrow()

        assertEquals(1, batch.written.size)
        assertEquals(1, batch.failures.size)
        assertEquals(2, batch.filesTouched + batch.failures.size)
        assertFalse(batch.isClean)
        assertEquals(64L, batch.bytesWritten)
        assertTrue(batch.describeFailures().contains("d.jar"))
    }

    @Test
    fun anEmptyBatchIsCleanAndTouchesNothing() = runTest {
        val transport = ScriptedTransport { _, _ -> ok(payload) }
        val batch = manager(transport).transferAll(emptyList()).getOrThrow()
        assertTrue(batch.isClean)
        assertEquals(0, batch.filesTouched)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun resumeOpenReplaysWhatTheJournalStillConsidersOpen() = runTest {
        val download = tracked(resolved(target = "libraries/e/f.jar"), state = XCoreDownloadState.RUNNING)
        download.partial.parentFile.mkdirs()
        download.partial.writeBytes(payload.copyOfRange(0, 100))

        val transport = ScriptedTransport { request, _ ->
            if (request.fromBytes > 0L) ranged(payload.copyOfRange(request.fromBytes.toInt(), payload.size), payload.size.toLong()) else ok(payload)
        }
        val batch = manager(transport).resumeOpen(paths).getOrThrow()

        assertEquals(1, batch.written.size)
        assertContent(payload, download.target.readBytes())
        assertTrue(
            "a row stuck in RUNNING is demoted before its partial is continued",
            journal.statesFor(download.task.id).contains(XCoreDownloadState.PAUSED),
        )
    }

    @Test
    fun theAggregateProgressNeverClaimsBytesThatWereNotTransferred() = runTest {
        val downloads = listOf(tracked(resolved(target = "libraries/g/h.jar")), tracked(resolved(target = "libraries/i/j.jar")))
        val totals = payload.size.toLong() * downloads.size
        val transport = ScriptedTransport { _, _ -> ok(payload) }
        val seen = mutableListOf<XCoreTransferProgress>()

        val batch = manager(transport).transferAll(downloads, maxConcurrency = 1, listener = { seen += it }).getOrThrow()

        assertEquals(2, batch.written.size)
        val last = seen.maxByOrNull { it.aggregateBytesDone }!!
        assertEquals(totals, last.aggregateBytesDone)
        assertEquals(1f, last.aggregateFraction)
        assertTrue(seen.all { it.aggregateBytesDone <= totals })
        assertTrue(seen.all { it.totalTasks == 2 })
    }

    private fun ok(body: ByteArray): XCoreResult<XCoreByteResponse> = ScriptedResponse(200, body).asXCoreSuccess()

    private fun ranged(body: ByteArray, total: Long): XCoreResult<XCoreByteResponse> =
        ScriptedResponse(206, body, totalBytesWhenPartial = total).asXCoreSuccess()

    private fun status(code: Int, body: ByteArray): XCoreResult<XCoreByteResponse> = ScriptedResponse(code, body).asXCoreSuccess()

    private fun assertContent(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.size} bytes, got ${actual.size}",
            expected.contentEquals(actual),
        )
    }
}

/** Records every request and replies from a lambda, so each test picks the exact failure it needs. */
private class ScriptedTransport(
    private val reply: (XCoreRangeRequest, Int) -> XCoreResult<XCoreByteResponse>,
) : XCoreByteTransport {

    val requests = mutableListOf<XCoreRangeRequest>()

    override suspend fun open(request: XCoreRangeRequest): XCoreResult<XCoreByteResponse> {
        requests += request
        return reply(request, requests.size)
    }
}

private class ScriptedResponse(
    override val statusCode: Int,
    private val body: ByteArray,
    override val totalBytesWhenPartial: Long? = null,
    override val eTag: String? = "\"v1\"",
    override val lastModifiedMillis: Long? = null,
    override val acceptsByteRanges: Boolean = true,
) : XCoreByteResponse {

    private var cursor = 0

    override val declaredContentLength: Long = body.size.toLong()

    override suspend fun read(buffer: ByteArray): Int {
        if (cursor >= body.size) return -1
        val take = minOf(buffer.size, body.size - cursor)
        body.copyInto(buffer, 0, cursor, cursor + take)
        cursor += take
        return take
    }

    override fun close() = Unit
}

/** A journal that keeps rows and state history the way the SQLite table does. */
private class InMemoryJournal : XCoreDownloadJournal {

    private val rows = LinkedHashMap<String, XCoreDownloadRecord>()

    private val transitions = LinkedHashMap<String, MutableList<XCoreDownloadState>>()

    fun row(id: String): XCoreDownloadRecord? = rows[id]

    fun statesFor(id: String): List<XCoreDownloadState> = transitions[id].orEmpty()

    /** Non-suspend seeding so a test can open a row before `runTest` starts. */
    fun put(record: XCoreDownloadRecord) {
        rows[record.id] = record
        history(record.id) += record.state
    }

    override suspend fun save(record: XCoreDownloadRecord): XCoreResult<XCoreDownloadRecord> {
        put(record)
        return record.asXCoreSuccess()
    }

    override suspend fun find(id: String): XCoreResult<XCoreDownloadRecord?> = rows[id].asXCoreSuccess()

    override suspend fun addWritten(
        id: String,
        deltaBytes: Long,
        state: XCoreDownloadState,
    ): XCoreResult<XCoreDownloadRecord> {
        val current = rows[id] ?: return XCoreError.Database("no journal row $id").asXCoreFailure()
        val updated = current.copy(writtenBytes = current.writtenBytes + deltaBytes, state = state)
        rows[id] = updated
        return updated.asXCoreSuccess()
    }

    override suspend fun transition(
        id: String,
        state: XCoreDownloadState,
        attemptsDelta: Int,
    ): XCoreResult<XCoreDownloadRecord> {
        history(id) += state
        val current = rows[id] ?: return XCoreError.Database("no journal row $id").asXCoreFailure()
        val updated = current.copy(state = state, attempts = current.attempts + attemptsDelta, updatedAtMillis = current.updatedAtMillis + 1L)
        rows[id] = updated
        return updated.asXCoreSuccess()
    }

    override suspend fun openRecords(limit: Int): XCoreResult<List<XCoreDownloadRecord>> =
        rows.values.filter { it.state != XCoreDownloadState.COMPLETED }.take(limit).asXCoreSuccess()

    private fun history(id: String): MutableList<XCoreDownloadState> =
        transitions.getOrPut(id) { mutableListOf() }
}

private fun sha1Of(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

private fun hex(bytes: ByteArray): String = buildString {
    bytes.forEach { append("%02x".format(it.toInt() and 0xff)) }
}
