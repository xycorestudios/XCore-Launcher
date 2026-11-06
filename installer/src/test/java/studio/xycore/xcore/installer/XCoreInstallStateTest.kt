package studio.xycore.xcore.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreInstanceStatus

/**
 * The record that says how far an install got. It is what the launcher reads after a process kill,
 * so the encode/decode round trip and the tracker's throttling are the difference between "the app
 * knows it stopped during assets" and "the app guesses from whatever files are on disk".
 */
class XCoreInstallStateTest {

    /** JUnit builds one instance of this class per test, so a fresh store per test falls out of that. */
    private val store = MemoryStore()

    private val failure = XCoreError.Network(
        detail = "the connection dropped mid-transfer",
        url = "https://piston-data.mojang.com/v1/objects/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/client.jar",
    )

    private fun state(phase: XCoreInstallPhase = XCoreInstallPhase.ASSETS) = XCoreInstallState(
        versionId = "1.21.4",
        phase = phase,
        instanceId = "inst-1",
        stage = XCoreStageProgress(completed = 40, total = 120, bytesDone = 4_000_000L, bytesTotal = 10_000_000L),
        label = "assets",
        filesWritten = 40,
        filesSkipped = 3,
        bytesWritten = 4_000_000L,
        assetIndexId = "17",
        assetCount = 120,
        libraryCount = 90,
        clientJarPath = "versions/1.21.4/1.21.4.jar",
        profileJsonPath = "versions/1.21.4/1.21.4.json",
        startedAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
    )

    @Test
    fun everyFieldTheUiDrawsSurvivesAStorageRoundTrip() {
        val original = state()
        val decoded = XCoreInstallStateCodec.decode(XCoreInstallStateCodec.encode(original)).getOrThrow()
        assertEquals(original.copy(error = null), decoded.copy(error = null))
    }

    @Test
    fun aStoredFailureKeepsTheTextTheUserIsShown() {
        val original = state(XCoreInstallPhase.FAILED).copy(error = failure)
        val decoded = XCoreInstallStateCodec.decode(XCoreInstallStateCodec.encode(original)).getOrThrow()
        assertEquals(XCoreInstallPhase.FAILED, decoded.phase)
        assertNotNull("losing the reason would turn a broken install into an unexplained one", decoded.error)
        assertEquals(failure.detail, decoded.error!!.detail)
        assertEquals(original.filesWritten, decoded.filesWritten)
        assertEquals(original.bytesWritten, decoded.bytesWritten)
    }

    @Test
    fun anUnknownPhaseIsAFailureRatherThanAnInnocuousIdleRow() {
        val tampered = XCoreInstallStateCodec.encode(state()).replace("\"ASSETS\"", "\"DONE\"")
        val error = XCoreInstallStateCodec.decode(tampered).errorOrNull()
        assertTrue(error is XCoreError.Database)
        assertTrue(error!!.detail.contains("DONE"))
    }

    @Test
    fun aRowWithoutAVersionIdOrThatIsNotAnObjectIsRejected() {
        assertTrue(XCoreInstallStateCodec.decode("""{"phase":"READY"}""").errorOrNull() is XCoreError.Database)
        assertTrue(XCoreInstallStateCodec.decode("[]").errorOrNull() is XCoreError.Database)
        assertNotNull(XCoreInstallStateCodec.decode("}{").errorOrNull())
    }

    @Test
    fun theKeyIsNamespacedSoOnePrefixListsEveryInstall() {
        assertEquals("install.1.21.4", XCoreInstallState.key("1.21.4"))
        assertEquals("1.21.4", XCoreInstallState.key("1.21.4").removePrefix(LIST_PREFIX))
    }

    @Test
    fun progressIsOnlyAdvertisedWhileBytesAreActuallyMoving() {
        val running = state(XCoreInstallPhase.LIBRARIES)
        assertEquals(0.4f, running.progressFraction!!, 0.0001f)
        assertNull(state(XCoreInstallPhase.READY).progressFraction)
        assertNull(state(XCoreInstallPhase.IDLE).progressFraction)
        assertNull(state(XCoreInstallPhase.FAILED).progressFraction)
        assertFalse(XCoreInstallPhase.READY.isInFlight)
        assertTrue(XCoreInstallPhase.ASSETS.isInFlight)
        assertFalse(XCoreInstallPhase.IDLE.isInFlight)
    }

    @Test
    fun stageFractionPrefersBytesAndFallsBackToCounts() {
        assertEquals(0.5f, XCoreStageProgress(completed = 1, total = 4, bytesDone = 5L, bytesTotal = 10L).fraction, 0.0001f)
        assertEquals(0.25f, XCoreStageProgress(completed = 1, total = 4).fraction, 0.0001f)
        assertEquals(0f, XCoreStageProgress().fraction, 0.0001f)
        assertEquals(1f, XCoreStageProgress(completed = 9, total = 4).fraction, 0.0001f)
    }

    @Test
    fun eachPhaseProjectsOntoExactlyOneInstanceStatus() {
        assertEquals(XCoreInstanceStatus.READY, state(XCoreInstallPhase.READY).statusForInstance())
        assertEquals(XCoreInstanceStatus.BROKEN, state(XCoreInstallPhase.FAILED).statusForInstance())
        assertEquals(XCoreInstanceStatus.NEEDS_REPAIR, state(XCoreInstallPhase.CANCELLED).statusForInstance())
        assertEquals(XCoreInstanceStatus.ADDED, state(XCoreInstallPhase.IDLE).statusForInstance())
        for (phase in XCoreInstallPhase.entries.filter { it.isInFlight }) {
            assertEquals("$phase must show as installing", XCoreInstanceStatus.INSTALLING, state(phase).statusForInstance())
        }
    }

    @Test
    fun aBeginWritesImmediatelyBecauseTheRowMustExistBeforeAnyByteMoves() = runTracker {
        val tracker = XCoreInstallTracker(store)
        val state = tracker.begin("1.21.4", "inst-1", XCoreInstallPhase.RESOLVING_METADATA, "resolving").getOrThrow()
        assertEquals(XCoreInstallPhase.RESOLVING_METADATA, state.phase)
        assertEquals(1, store.writes)
        assertEquals("resolving", store.read(XCoreInstallState.key("1.21.4")).valueOrNull()?.let {
            XCoreInstallStateCodec.decode(it).getOrThrow().label
        })
    }

    @Test
    fun progressWritesAreCoalescedButTerminalOnesAlwaysLand() = runTracker {
        var now = 0L
        val tracker = XCoreInstallTracker(store, clock = { now }, persistEveryMillis = 1_000L)
        tracker.begin("1.21.4", null, XCoreInstallPhase.ASSETS, "assets").getOrThrow()
        val writesAfterBegin = store.writes

        for (step in 1..20) {
            now = step * 40L
            tracker.progress("1.21.4", XCoreStageProgress(step, 20, step * 10L, 200L), "assets $step").getOrThrow()
        }
        assertEquals("20 progress ticks inside one interval must not become 20 sqlite writes", writesAfterBegin, store.writes)

        val inMemory = tracker.state("1.21.4").getOrThrow()!!
        assertEquals(20, inMemory.stage.completed)
        assertEquals("assets 20", inMemory.label)

        now = 5_000L
        tracker.finish("1.21.4", XCoreInstallPhase.READY, clientJarPath = "versions/1.21.4/1.21.4.jar").getOrThrow()
        assertEquals(writesAfterBegin + 1, store.writes)

        val persisted = XCoreInstallStateCodec.decode(store.read(XCoreInstallState.key("1.21.4")).getOrThrow()!!).getOrThrow()
        assertEquals(XCoreInstallPhase.READY, persisted.phase)
        assertEquals(20, persisted.stage.completed)
        assertEquals("versions/1.21.4/1.21.4.jar", persisted.clientJarPath)
    }

    @Test
    fun aRestartReadsTheDurableRowWhenMemoryIsCold() = runTracker {
        val first = XCoreInstallTracker(store, clock = { 10L }, persistEveryMillis = 0L)
        first.begin("1.21.4", "inst-1", XCoreInstallPhase.CLIENT_JAR, "client jar").getOrThrow()
        first.advance("1.21.4", XCoreInstallPhase.LIBRARIES, "libraries", XCoreStageProgress(3, 10, 100L, 1_000L)).getOrThrow()

        val afterCrash = XCoreInstallTracker(store, clock = { 11L })
        val restored = afterCrash.state("1.21.4").getOrThrow()
        assertEquals(XCoreInstallPhase.LIBRARIES, restored?.phase)
        assertEquals(3, restored?.stage?.completed)
        assertEquals("inst-1", restored?.instanceId)
    }

    @Test
    fun advancingSomethingThatNeverBeganIsATypedErrorNotASilentNoOp() = runTracker {
        val tracker = XCoreInstallTracker(store)
        assertTrue(tracker.advance("ghost", XCoreInstallPhase.ASSETS, "assets").errorOrNull() is XCoreError.Database)
        assertTrue(tracker.progress("ghost", XCoreStageProgress(), "assets").errorOrNull() is XCoreError.Database)
        assertNull(tracker.state("ghost").getOrThrow())
    }

    @Test
    fun aStoreWriteFailureIsReturnedRatherThanSwallowed() = runTracker {
        store.failing = XCoreError.Database("the key-value table is locked")
        val tracker = XCoreInstallTracker(store)
        val outcome = tracker.begin("1.21.4", null, XCoreInstallPhase.ASSETS, "assets")
        assertTrue(outcome.errorOrNull() is XCoreError.Database)
    }

    @Test
    fun allListsLiveInstallsAheadOfPersistedOnesNewestFirst() = runTracker {
        val tracker = XCoreInstallTracker(store, clock = { 100L })
        store.rows[XCoreInstallState.key("1.20.4")] = XCoreInstallStateCodec.encode(
            state(XCoreInstallPhase.READY).copy(versionId = "1.20.4", updatedAtMillis = 50L),
        )
        store.rows[XCoreInstallState.key("b1.8.1")] = XCoreInstallStateCodec.encode(
            state(XCoreInstallPhase.FAILED).copy(versionId = "b1.8.1", updatedAtMillis = 999L),
        )
        tracker.begin("1.21.4", null, XCoreInstallPhase.ASSETS, "assets").getOrThrow()

        val listed = tracker.all().getOrThrow()
        assertEquals(
            listOf("b1.8.1", "1.21.4", "1.20.4"),
            listed.map { it.versionId },
        )
        assertEquals(XCoreInstallPhase.ASSETS, listed.first { it.versionId == "1.21.4" }.phase)
    }

    @Test
    fun forgettingANonexistentRowIsNotAnError() = runTracker {
        val tracker = XCoreInstallTracker(store)
        tracker.begin("1.21.4", null, XCoreInstallPhase.ASSETS, "assets").getOrThrow()
        assertEquals(Unit, tracker.forget("1.21.4").getOrThrow())
        assertNull(tracker.state("1.21.4").getOrThrow())
        assertEquals(Unit, tracker.forget("never-installed").getOrThrow())
    }

    @Test
    fun anEmptyStoreListsNoInstalls() = runTracker {
        assertEquals(emptyList<XCoreInstallState>(), XCoreInstallTracker(store).all().getOrThrow())
    }

    private class MemoryStore : XCoreInstallStateStore {

        val rows = LinkedHashMap<String, String>()

        var writes = 0

        var failing: XCoreError? = null

        override suspend fun read(key: String): XCoreResult<String?> = rows[key].asXCoreSuccess()

        override suspend fun write(key: String, value: String): XCoreResult<Unit> {
            failing?.let { return it.asXCoreFailure() }
            writes++
            rows[key] = value
            return Unit.asXCoreSuccess()
        }

        override suspend fun clear(key: String): XCoreResult<Unit> {
            failing?.let { return it.asXCoreFailure() }
            rows.remove(key)
            return Unit.asXCoreSuccess()
        }

        override suspend fun keys(prefix: String): XCoreResult<Map<String, String>> =
            rows.filterKeys { it.startsWith(prefix) }.asXCoreSuccess()
    }

    private fun runTracker(body: suspend () -> Unit) {
        kotlinx.coroutines.test.runTest { body() }
    }

    private companion object {

        /** The prefix `XCoreInstallTracker.all()` lists installs under. */
        const val LIST_PREFIX = "install."
    }
}
