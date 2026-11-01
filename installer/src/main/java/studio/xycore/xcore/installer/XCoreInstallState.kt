package studio.xycore.xcore.installer

import com.google.gson.JsonObject
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreInstanceStatus

/** Where an install has reached. */
enum class XCoreInstallPhase(val terminal: Boolean) {
    /** Nothing is running; the row exists only to describe what was last attempted. */
    IDLE(false),

    RESOLVING_METADATA(false),
    CHECKING_STORAGE(false),
    CLIENT_JAR(false),
    LIBRARIES(false),
    NATIVES(false),
    ASSETS(false),
    PROFILE_JSON(false),
    VERIFYING(false),
    READY(true),
    FAILED(true),
    CANCELLED(true),
    ;

    val isInFlight: Boolean get() = !terminal && this != IDLE
}

/** Counts for one stage of an install. */
data class XCoreStageProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0L -> (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
            total > 0 -> (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

/** A persisted description of one version's installation. */
data class XCoreInstallState(
    val versionId: String,
    val phase: XCoreInstallPhase,
    val instanceId: String? = null,
    val stage: XCoreStageProgress = XCoreStageProgress(),
    val label: String = "",
    val filesWritten: Int = 0,
    val filesSkipped: Int = 0,
    val bytesWritten: Long = 0L,
    val assetIndexId: String? = null,
    val assetCount: Int = 0,
    val libraryCount: Int = 0,
    val clientJarPath: String? = null,
    val profileJsonPath: String? = null,
    val error: XCoreError? = null,
    val startedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val isReady: Boolean get() = phase == XCoreInstallPhase.READY

    val isInProgress: Boolean get() = phase.isInFlight

    /** What the UI shows next to a version: a fraction only while bytes are actually moving. */
    val progressFraction: Float? get() = if (phase.isInFlight) stage.fraction else null

    fun statusForInstance(): XCoreInstanceStatus = when (phase) {
        XCoreInstallPhase.READY -> XCoreInstanceStatus.READY
        XCoreInstallPhase.FAILED -> XCoreInstanceStatus.BROKEN
        XCoreInstallPhase.CANCELLED -> XCoreInstanceStatus.NEEDS_REPAIR
        XCoreInstallPhase.IDLE -> XCoreInstanceStatus.ADDED
        else -> XCoreInstanceStatus.INSTALLING
    }

    companion object {
        fun key(versionId: String): String = "install.$versionId"
    }
}

/**
 * Durable install state.
 *
 * Production writes JSON into the launcher's key-value table, which means an install that is
 * interrupted by a process kill or a crash can be described accurately after restart instead of
 * being guessed at from whatever files happen to be on disk.
 */
interface XCoreInstallStateStore {
    suspend fun read(key: String): XCoreResult<String?>

    suspend fun write(
        key: String,
        value: String,
    ): XCoreResult<Unit>

    suspend fun clear(key: String): XCoreResult<Unit>

    suspend fun keys(prefix: String): XCoreResult<Map<String, String>>
}

/** Encodes and decodes [XCoreInstallState] without reflection, so R8 cannot mangle it later. */
object XCoreInstallStateCodec {
    fun encode(state: XCoreInstallState): String {
        val root = JsonObject()
        root.addProperty("versionId", state.versionId)
        root.addProperty("phase", state.phase.name)
        root.addProperty("instanceId", state.instanceId)
        root.addProperty("completed", state.stage.completed)
        root.addProperty("total", state.stage.total)
        root.addProperty("bytesDone", state.stage.bytesDone)
        root.addProperty("bytesTotal", state.stage.bytesTotal)
        root.addProperty("label", state.label)
        root.addProperty("filesWritten", state.filesWritten)
        root.addProperty("filesSkipped", state.filesSkipped)
        root.addProperty("bytesWritten", state.bytesWritten)
        root.addProperty("assetIndexId", state.assetIndexId)
        root.addProperty("assetCount", state.assetCount)
        root.addProperty("libraryCount", state.libraryCount)
        root.addProperty("clientJarPath", state.clientJarPath)
        root.addProperty("profileJsonPath", state.profileJsonPath)
        root.addProperty("startedAtMillis", state.startedAtMillis)
        root.addProperty("updatedAtMillis", state.updatedAtMillis)
        state.error?.let { error ->
            val block = JsonObject()
            block.addProperty("kind", error.kind.name)
            block.addProperty("detail", error.detail)
            block.addProperty("retryable", error.retryable)
            root.add("error", block)
        }
        return root.toString()
    }

    /** A stored row that cannot be read is a failure, not an [XCoreInstallPhase.IDLE] default. */
    fun decode(raw: String): XCoreResult<XCoreInstallState> = xCoreRunCatching(
        origin = "installState.decode",
        errorKind = XCoreErrorKind.DATABASE,
    ) {
        val element = com.google.gson.JsonParser.parseString(raw)
        val root = if (element.isJsonObject) element.asJsonObject else null
            ?: throw studio.xycore.xcore.core.XCoreFailureException(
                XCoreError.Database("stored install state is not a JSON object"),
            )
        val phase = root.str("phase")?.let { name ->
            XCoreInstallPhase.entries.firstOrNull { it.name == name }
        } ?: throw studio.xycore.xcore.core.XCoreFailureException(
            XCoreError.Database("stored install state has an unknown phase '${root.str("phase") ?: "none"}'"),
        )
        val versionId = root.str("versionId")
            ?: throw studio.xycore.xcore.core.XCoreFailureException(
                XCoreError.Database("stored install state has no version id"),
            )
        val errorBlock = root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        XCoreInstallState(
            versionId = versionId,
            phase = phase,
            instanceId = root.str("instanceId"),
            stage = XCoreStageProgress(
                completed = root.num("completed")?.toInt() ?: 0,
                total = root.num("total")?.toInt() ?: 0,
                bytesDone = root.num("bytesDone")?.toLong() ?: 0L,
                bytesTotal = root.num("bytesTotal")?.toLong() ?: 0L,
            ),
            label = root.str("label") ?: "",
            filesWritten = root.num("filesWritten")?.toInt() ?: 0,
            filesSkipped = root.num("filesSkipped")?.toInt() ?: 0,
            bytesWritten = root.num("bytesWritten")?.toLong() ?: 0L,
            assetIndexId = root.str("assetIndexId"),
            assetCount = root.num("assetCount")?.toInt() ?: 0,
            libraryCount = root.num("libraryCount")?.toInt() ?: 0,
            clientJarPath = root.str("clientJarPath"),
            profileJsonPath = root.str("profileJsonPath"),
            error = errorBlock?.let {
                val kind = XCoreErrorKind.entries.firstOrNull { candidate ->
                    candidate.name == it.str("kind")
                } ?: XCoreErrorKind.UNKNOWN
                XCoreError.fromThrowable(
                    IllegalStateException(it.str("detail") ?: "stored failure"),
                    kind,
                )
            },
            startedAtMillis = root.num("startedAtMillis")?.toLong() ?: 0L,
            updatedAtMillis = root.num("updatedAtMillis")?.toLong() ?: 0L,
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.num(key: String): Number? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}

/**
 * Keeps install state readable while a transfer is running.
 *
 * Progress is mirrored in memory and written to storage on a coarse interval, because a UI needs
 * updates many times a second while SQLite does not need one per 64 KB chunk.
 */
class XCoreInstallTracker(
    private val store: XCoreInstallStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistEveryMillis: Long = 1_000L,
) {

    private val live = LinkedHashMap<String, XCoreInstallState>()

    private var lastPersistAtMillis = 0L

    val inMemory: List<XCoreInstallState> get() = live.values.toList()

    suspend fun begin(
        versionId: String,
        instanceId: String?,
        phase: XCoreInstallPhase,
        label: String,
    ): XCoreResult<XCoreInstallState> {
        val now = clock()
        val state = XCoreInstallState(
            versionId = versionId,
            phase = phase,
            instanceId = instanceId,
            label = label,
            startedAtMillis = now,
            updatedAtMillis = now,
        )
        live[versionId] = state
        return persist(state, force = true)
    }

    suspend fun advance(
        versionId: String,
        phase: XCoreInstallPhase,
        label: String = live[versionId]?.label ?: "",
        stage: XCoreStageProgress = live[versionId]?.stage ?: XCoreStageProgress(),
    ): XCoreResult<XCoreInstallState> {
        val previous = live[versionId] ?: return XCoreError.Database(
            "no install is being tracked for '$versionId'",
        ).asXCoreFailure()
        val state = previous.copy(phase = phase, label = label, stage = stage, updatedAtMillis = clock())
        live[versionId] = state
        return persist(state)
    }

    suspend fun progress(
        versionId: String,
        stage: XCoreStageProgress,
        label: String,
        filesWritten: Int = live[versionId]?.filesWritten ?: 0,
        filesSkipped: Int = live[versionId]?.filesSkipped ?: 0,
        bytesWritten: Long = live[versionId]?.bytesWritten ?: 0L,
    ): XCoreResult<XCoreInstallState> {
        val previous = live[versionId] ?: return XCoreError.Database(
            "no install is being tracked for '$versionId'",
        ).asXCoreFailure()
        val state = previous.copy(
            stage = stage,
            label = label,
            filesWritten = filesWritten,
            filesSkipped = filesSkipped,
            bytesWritten = bytesWritten,
            updatedAtMillis = clock(),
        )
        live[versionId] = state
        return persist(state)
    }

    suspend fun finish(
        versionId: String,
        phase: XCoreInstallPhase,
        error: XCoreError? = null,
        clientJarPath: String? = live[versionId]?.clientJarPath,
        profileJsonPath: String? = live[versionId]?.profileJsonPath,
        assetIndexId: String? = live[versionId]?.assetIndexId,
        assetCount: Int = live[versionId]?.assetCount ?: 0,
        libraryCount: Int = live[versionId]?.libraryCount ?: 0,
    ): XCoreResult<XCoreInstallState> {
        val previous = live[versionId] ?: XCoreInstallState(
            versionId = versionId,
            phase = phase,
            startedAtMillis = clock(),
        )
        val state = previous.copy(
            phase = phase,
            error = error,
            clientJarPath = clientJarPath,
            profileJsonPath = profileJsonPath,
            assetIndexId = assetIndexId,
            assetCount = assetCount,
            libraryCount = libraryCount,
            updatedAtMillis = clock(),
        )
        live[versionId] = state
        return persist(state, force = true)
    }

    /** The freshest view: memory first, because a running install is ahead of its last write. */
    suspend fun state(versionId: String): XCoreResult<XCoreInstallState?> {
        live[versionId]?.let { return it.asXCoreSuccess() }
        return store.read(XCoreInstallState.key(versionId)).flatMap { raw ->
            if (raw == null) {
                null.asXCoreSuccess()
            } else {
                XCoreInstallStateCodec.decode(raw).map { decoded ->
                    live[versionId] = decoded
                    decoded as XCoreInstallState?
                }
            }
        }
    }

    suspend fun all(): XCoreResult<List<XCoreInstallState>> =
        store.keys("install.").flatMap { rows ->
            val decoded = ArrayList<XCoreInstallState>(rows.size)
            for ((_, raw) in rows) {
                XCoreInstallStateCodec.decode(raw).valueOrNull()?.let { state ->
                    if (live[state.versionId] == null) decoded += state
                }
            }
            (live.values.toList() + decoded).sortedByDescending { it.updatedAtMillis }.asXCoreSuccess()
        }

    suspend fun forget(versionId: String): XCoreResult<Unit> {
        live.remove(versionId)
        return store.clear(XCoreInstallState.key(versionId))
    }

    private suspend fun persist(
        state: XCoreInstallState,
        force: Boolean = false,
    ): XCoreResult<XCoreInstallState> {
        val now = clock()
        if (!force && now - lastPersistAtMillis < persistEveryMillis) return state.asXCoreSuccess()
        lastPersistAtMillis = now
        return store.write(XCoreInstallState.key(state.versionId), XCoreInstallStateCodec.encode(state))
            .map { state }
    }
}
