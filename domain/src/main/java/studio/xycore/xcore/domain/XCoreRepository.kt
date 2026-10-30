package studio.xycore.xcore.domain

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRun
import studio.xycore.xcore.data.XCoreCorruptRowException
import studio.xycore.xcore.data.XCoreDatabase
import studio.xycore.xcore.data.XCoreDownloadRecord
import studio.xycore.xcore.data.XCoreDownloadState
import studio.xycore.xcore.data.XCoreInstance
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreLaunchRecord
import studio.xycore.xcore.data.XCoreRuntimeComponent
import studio.xycore.xcore.data.XCoreVersionRef
import studio.xycore.xcore.data.asXCoreDatabaseError

/**
 * Persistence boundary for launcher metadata.
 *
 * Every method is a `suspend` function returning [XCoreResult]: the DAOs in `:data` are synchronous
 * and may throw `SQLiteException`, and this layer converts that into a typed failure so no caller
 * has to guess whether an empty list means "nothing stored" or "the database was unreadable".
 */
interface XCoreInstanceRepository {

    suspend fun list(limit: Int = 200): XCoreResult<List<XCoreInstance>>

    suspend fun find(id: String): XCoreResult<XCoreInstance?>

    /** Inserts or replaces, but only after [XCoreInstance.validate] passes. */
    suspend fun save(instance: XCoreInstance): XCoreResult<XCoreInstance>

    suspend fun setStatus(
        id: String,
        status: XCoreInstanceStatus,
    ): XCoreResult<XCoreInstance>

    suspend fun attachRuntime(
        id: String,
        runtimeComponentId: String?,
    ): XCoreResult<XCoreInstance>

    suspend fun markLaunched(id: String): XCoreResult<XCoreInstance>

    /** Metadata only: removing files is a use-case decision, not a repository side effect. */
    suspend fun delete(id: String): XCoreResult<Unit>

    suspend fun orphans(): XCoreResult<List<XCoreInstance>>

    suspend fun count(): XCoreResult<Long>
}

interface XCoreVersionRepository {

    suspend fun listAll(limit: Int = 500): XCoreResult<List<XCoreVersionRef>>

    suspend fun listInstalled(limit: Int = 100): XCoreResult<List<XCoreVersionRef>>

    suspend fun find(id: String): XCoreResult<XCoreVersionRef?>

    suspend fun save(version: XCoreVersionRef): XCoreResult<XCoreVersionRef>

    /** Saves a batch, reporting the first rejection rather than half-writing silently. */
    suspend fun saveAll(versions: List<XCoreVersionRef>): XCoreResult<List<XCoreVersionRef>>

    suspend fun setInstalled(
        id: String,
        installed: Boolean,
    ): XCoreResult<XCoreVersionRef>

    suspend fun delete(id: String): XCoreResult<Unit>

    suspend fun count(): XCoreResult<Long>
}

interface XCoreRuntimeRepository {

    suspend fun list(): XCoreResult<List<XCoreRuntimeComponent>>

    suspend fun find(id: String): XCoreResult<XCoreRuntimeComponent?>

    suspend fun save(component: XCoreRuntimeComponent): XCoreResult<XCoreRuntimeComponent>

    suspend fun usable(
        abi: String,
        minimumJavaVersion: Int,
        requireVerified: Boolean,
        limit: Int = 5,
    ): XCoreResult<List<XCoreRuntimeComponent>>

    suspend fun setState(
        id: String,
        state: XCoreRuntimeState,
        verifiedAtMillis: Long? = null,
        lastErrorCode: Int? = null,
    ): XCoreResult<XCoreRuntimeComponent>

    suspend fun delete(id: String): XCoreResult<Unit>

    suspend fun count(): XCoreResult<Long>
}

interface XCoreDownloadRepository {

    suspend fun listOpen(limit: Int = 500): XCoreResult<List<XCoreDownloadRecord>>

    suspend fun listIncomplete(limit: Int = 200): XCoreResult<List<XCoreDownloadRecord>>

    /** One row by its transfer id, or a successful `null` when no transfer of that id was journalled. */
    suspend fun find(id: String): XCoreResult<XCoreDownloadRecord?>

    suspend fun save(record: XCoreDownloadRecord): XCoreResult<XCoreDownloadRecord>

    /** Fails when no journal row has [id], rather than reporting a silent no-op as success. */
    suspend fun addWritten(
        id: String,
        deltaBytes: Long,
        state: XCoreDownloadState = XCoreDownloadState.RUNNING,
    ): XCoreResult<XCoreDownloadRecord>

    suspend fun transition(
        id: String,
        state: XCoreDownloadState,
        attemptsDelta: Int = 0,
    ): XCoreResult<XCoreDownloadRecord>

    suspend fun pruneCompleted(olderThanMillis: Long): XCoreResult<Int>

    suspend fun count(): XCoreResult<Long>
}

interface XCoreLaunchHistoryRepository {

    suspend fun start(
        instanceId: String,
        versionId: String,
    ): XCoreResult<XCoreLaunchRecord>

    suspend fun finish(
        id: Long,
        exitCode: Int?,
        errorKind: XCoreErrorKind?,
        logPathRelative: String?,
    ): XCoreResult<XCoreLaunchRecord>

    suspend fun recent(limit: Int = 50): XCoreResult<List<XCoreLaunchRecord>>

    suspend fun forInstance(
        instanceId: String,
        limit: Int = 50,
    ): XCoreResult<List<XCoreLaunchRecord>>

    suspend fun prune(olderThanMillis: Long): XCoreResult<Int>

    suspend fun count(): XCoreResult<Long>
}

interface XCoreKeyValueRepository {

    suspend fun get(key: String): XCoreResult<String?>

    suspend fun set(
        key: String,
        value: String,
    ): XCoreResult<Unit>

    suspend fun remove(key: String): XCoreResult<Unit>

    suspend fun list(prefix: String): XCoreResult<Map<String, String>>

    suspend fun count(): XCoreResult<Long>
}

/**
 * Whole-metadata access in one object, so the app graph hands out one dependency instead of six.
 *
 * `:domain` deliberately owns the coroutine switching and error classification; `:data` stays a
 * synchronous storage detail and the UI never talks to a DAO directly.
 */
class XCoreRepositories(
    private val database: XCoreDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    val instances: XCoreInstanceRepository = XCoreInstanceRepositoryImpl(database, clock, io)
    val versions: XCoreVersionRepository = XCoreVersionRepositoryImpl(database, clock, io)
    val runtimes: XCoreRuntimeRepository = XCoreRuntimeRepositoryImpl(database, clock, io)
    val downloads: XCoreDownloadRepository = XCoreDownloadRepositoryImpl(database, clock, io)
    val launches: XCoreLaunchHistoryRepository = XCoreLaunchHistoryRepositoryImpl(database, clock, io)
    val keyValue: XCoreKeyValueRepository = XCoreKeyValueRepositoryImpl(database, clock, io)

    /** Schema description straight from SQLite, so the diagnostics screen shows the real file. */
    fun describeSchema(): String = database.describe()

    /** Row counts for the diagnostics screen; a `null` means the table could not be read. */
    suspend fun rowCountSnapshot(): XCoreRowCounts {
        val counts = ArrayList<Pair<String, Long?>>(6)
        counts += "instances" to instances.count().valueOrNull()
        counts += "versions" to versions.count().valueOrNull()
        counts += "runtimes" to runtimes.count().valueOrNull()
        counts += "downloads" to downloads.count().valueOrNull()
        counts += "launches" to launches.count().valueOrNull()
        counts += "keyValue" to keyValue.count().valueOrNull()
        return XCoreRowCounts(counts.toMap())
    }
}

data class XCoreRowCounts(val values: Map<String, Long?>) {
    val readable: Boolean get() = values.values.all { it != null }

    fun format(): String = values.entries.joinToString(" ") { (key, value) -> "$key=${value ?: "?"}" }
}

/**
 * Runs [body] on [io] and converts every storage exception into a typed failure.
 *
 * `SQLiteException` is caught here rather than left to [studio.xycore.xcore.core.fromThrowable]
 * because `:core` is a pure JVM module and cannot name an Android exception type; without this,
 * a constraint violation would be reported as `UNKNOWN`.
 */
internal suspend fun <T> databaseCall(
    origin: String,
    io: CoroutineDispatcher,
    body: () -> T,
): XCoreResult<T> = withContext(io) {
    xCoreRun(origin = origin, errorKind = XCoreErrorKind.DATABASE) {
        try {
            body()
        } catch (row: XCoreCorruptRowException) {
            failXCore(row.asXCoreDatabaseError())
        } catch (sqlite: SQLiteException) {
            failXCore(
                XCoreError.Database(
                    detail = "$origin failed: ${sqlite.message}",
                    cause = sqlite,
                ),
            )
        }
    }
}

internal fun missingRowError(
    table: String,
    id: String,
): XCoreError = XCoreError.Database(
    detail = "no row in '$table' with id '$id'",
    sqlState = null,
)

class XCoreInstanceRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreInstanceRepository {

    override suspend fun list(limit: Int): XCoreResult<List<XCoreInstance>> =
        databaseCall("instance.list", io) { database.instances.listAll(limit) }

    override suspend fun find(id: String): XCoreResult<XCoreInstance?> =
        databaseCall("instance.find", io) { database.instances.findById(id) }

    override suspend fun save(instance: XCoreInstance): XCoreResult<XCoreInstance> =
        instance.validate().flatMapResult { valid ->
            databaseCall("instance.save", io) {
                val rowId = database.instances.upsert(valid)
                if (rowId <= 0L) {
                    failXCore(
                        XCoreError.Database(
                            detail = "upsert of instance '${valid.id}' returned $rowId; " +
                                "its version '${valid.versionId}' may not be registered",
                        ),
                    )
                }
                valid
            }
        }

    override suspend fun setStatus(
        id: String,
        status: XCoreInstanceStatus,
    ): XCoreResult<XCoreInstance> = databaseCall("instance.setStatus", io) {
        val changed = database.instances.updateStatus(id, status, clock())
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
        database.instances.findById(id)
            ?: failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
    }

    override suspend fun attachRuntime(
        id: String,
        runtimeComponentId: String?,
    ): XCoreResult<XCoreInstance> = databaseCall("instance.attachRuntime", io) {
        val changed = database.instances.attachRuntime(id, runtimeComponentId, clock())
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
        database.instances.findById(id)
            ?: failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
    }

    override suspend fun markLaunched(id: String): XCoreResult<XCoreInstance> =
        databaseCall("instance.markLaunched", io) {
            val changed = database.instances.markLaunched(id, clock())
            if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
            database.instances.findById(id)
                ?: failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
        }

    override suspend fun delete(id: String): XCoreResult<Unit> = databaseCall("instance.delete", io) {
        val changed = database.instances.delete(id)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_INSTANCES, id))
        Unit
    }

    override suspend fun orphans(): XCoreResult<List<XCoreInstance>> =
        databaseCall("instance.orphans", io) { database.instances.listOrphans() }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("instance.count", io) { database.instances.count() }
}

class XCoreVersionRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreVersionRepository {

    override suspend fun listAll(limit: Int): XCoreResult<List<XCoreVersionRef>> =
        databaseCall("version.list", io) { database.versions.listAll(limit) }

    override suspend fun listInstalled(limit: Int): XCoreResult<List<XCoreVersionRef>> =
        databaseCall("version.listInstalled", io) { database.versions.listInstalled(limit) }

    override suspend fun find(id: String): XCoreResult<XCoreVersionRef?> =
        databaseCall("version.find", io) { database.versions.findById(id) }

    override suspend fun save(version: XCoreVersionRef): XCoreResult<XCoreVersionRef> =
        version.validate().flatMapResult { valid ->
            databaseCall("version.save", io) {
                val rowId = database.versions.upsert(valid)
                if (rowId <= 0L) {
                    failXCore(XCoreError.Database(detail = "upsert of version '${valid.id}' returned $rowId"))
                }
                valid
            }
        }

    override suspend fun saveAll(versions: List<XCoreVersionRef>): XCoreResult<List<XCoreVersionRef>> {
        val validated = ArrayList<XCoreVersionRef>(versions.size)
        for (version in versions) {
            when (val outcome = version.validate()) {
                is XCoreResult.Success -> validated += outcome.value
                is XCoreResult.Failure -> return XCoreError.Database(
                    detail = "refusing batch write: version '${version.id}' is invalid (${outcome.error.detail})",
                    cause = outcome.error.cause,
                ).asXCoreFailure()
                is XCoreResult.Cancelled -> return outcome
            }
        }
        return databaseCall("version.saveAll", io) {
            // The DAOs read `writableDatabase`, which is this same connection, so their writes
            // join the transaction opened here.
            database.transact { validated.forEach { database.versions.upsert(it) } }
            validated
        }
    }

    override suspend fun setInstalled(
        id: String,
        installed: Boolean,
    ): XCoreResult<XCoreVersionRef> = databaseCall("version.setInstalled", io) {
        val changed = database.versions.markInstalled(id, installed)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_VERSIONS, id))
        database.versions.findById(id)
            ?: failXCore(missingRowError(XCoreDatabase.TABLE_VERSIONS, id))
    }

    override suspend fun delete(id: String): XCoreResult<Unit> = databaseCall("version.delete", io) {
        val changed = database.versions.delete(id)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_VERSIONS, id))
        Unit
    }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("version.count", io) { database.versions.count() }
}

class XCoreRuntimeRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreRuntimeRepository {

    override suspend fun list(): XCoreResult<List<XCoreRuntimeComponent>> =
        databaseCall("runtime.list", io) { database.runtimes.listAll() }

    override suspend fun find(id: String): XCoreResult<XCoreRuntimeComponent?> =
        databaseCall("runtime.find", io) { database.runtimes.findById(id) }

    override suspend fun save(component: XCoreRuntimeComponent): XCoreResult<XCoreRuntimeComponent> =
        component.validate().flatMapResult { valid ->
            databaseCall("runtime.save", io) {
                val rowId = database.runtimes.upsert(valid)
                if (rowId <= 0L) {
                    failXCore(XCoreError.Database(detail = "upsert of runtime '${valid.id}' returned $rowId"))
                }
                valid
            }
        }

    override suspend fun usable(
        abi: String,
        minimumJavaVersion: Int,
        requireVerified: Boolean,
        limit: Int,
    ): XCoreResult<List<XCoreRuntimeComponent>> = databaseCall("runtime.usable", io) {
        database.runtimes.findUsable(abi, minimumJavaVersion, requireVerified, limit)
    }

    override suspend fun setState(
        id: String,
        state: XCoreRuntimeState,
        verifiedAtMillis: Long?,
        lastErrorCode: Int?,
    ): XCoreResult<XCoreRuntimeComponent> = databaseCall("runtime.setState", io) {
        val changed = database.runtimes.updateState(id, state, verifiedAtMillis, lastErrorCode)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_RUNTIMES, id))
        database.runtimes.findById(id)
            ?: failXCore(missingRowError(XCoreDatabase.TABLE_RUNTIMES, id))
    }

    override suspend fun delete(id: String): XCoreResult<Unit> = databaseCall("runtime.delete", io) {
        val changed = database.runtimes.delete(id)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_RUNTIMES, id))
        Unit
    }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("runtime.count", io) { database.runtimes.count() }
}

class XCoreDownloadRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreDownloadRepository {

    override suspend fun listOpen(limit: Int): XCoreResult<List<XCoreDownloadRecord>> =
        databaseCall("download.listOpen", io) { database.downloads.listOpen(limit) }

    override suspend fun listIncomplete(limit: Int): XCoreResult<List<XCoreDownloadRecord>> =
        databaseCall("download.listIncomplete", io) { database.downloads.listIncomplete(limit) }

    override suspend fun find(id: String): XCoreResult<XCoreDownloadRecord?> =
        databaseCall("download.find", io) { database.downloads.findById(id) }

    override suspend fun save(record: XCoreDownloadRecord): XCoreResult<XCoreDownloadRecord> =
        record.validate().flatMapResult { valid ->
            databaseCall("download.save", io) {
                val rowId = database.downloads.upsert(valid)
                if (rowId <= 0L) {
                    failXCore(
                        XCoreError.Database(
                            detail = "upsert of download '${valid.id}' returned $rowId; " +
                                "another open record may already own '${valid.relativeTarget}'",
                        ),
                    )
                }
                valid
            }
        }

    override suspend fun addWritten(
        id: String,
        deltaBytes: Long,
        state: XCoreDownloadState,
    ): XCoreResult<XCoreDownloadRecord> = databaseCall("download.addWritten", io) {
        if (deltaBytes < 0L) {
            failXCore(
                XCoreError.Validation(
                    detail = "a byte count added to a journal cannot be negative",
                    field = "deltaBytes",
                    rejected = deltaBytes.toString(),
                ),
            )
        }
        val changed = database.downloads.addWritten(id, deltaBytes, clock(), state)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_DOWNLOADS, id))
        database.downloads.findById(id) ?: failXCore(missingRowError(XCoreDatabase.TABLE_DOWNLOADS, id))
    }

    override suspend fun transition(
        id: String,
        state: XCoreDownloadState,
        attemptsDelta: Int,
    ): XCoreResult<XCoreDownloadRecord> = databaseCall("download.transition", io) {
        val changed = database.downloads.transition(id, state, clock(), attemptsDelta)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_DOWNLOADS, id))
        database.downloads.findById(id) ?: failXCore(missingRowError(XCoreDatabase.TABLE_DOWNLOADS, id))
    }

    override suspend fun pruneCompleted(olderThanMillis: Long): XCoreResult<Int> =
        databaseCall("download.prune", io) { database.downloads.deleteCompletedBefore(olderThanMillis) }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("download.count", io) { database.downloads.count() }
}

class XCoreLaunchHistoryRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreLaunchHistoryRepository {

    override suspend fun start(
        instanceId: String,
        versionId: String,
    ): XCoreResult<XCoreLaunchRecord> = databaseCall("launch.start", io) {
        val rowId = database.launches.recordStart(instanceId, versionId, clock())
        if (rowId <= 0L) {
            failXCore(
                XCoreError.Database(
                    detail = "insert into '${XCoreDatabase.TABLE_LAUNCHES}' returned $rowId; " +
                        "instance '$instanceId' may not exist",
                ),
            )
        }
        XCoreLaunchRecord(
            id = rowId.toString(),
            instanceId = instanceId,
            versionId = versionId,
            startedAtMillis = clock(),
            endedAtMillis = null,
            exitCode = null,
            errorKind = null,
            logPathRelative = null,
        )
    }

    override suspend fun finish(
        id: Long,
        exitCode: Int?,
        errorKind: XCoreErrorKind?,
        logPathRelative: String?,
    ): XCoreResult<XCoreLaunchRecord> = databaseCall("launch.finish", io) {
        val changed = database.launches.finish(id, clock(), exitCode, errorKind, logPathRelative)
        if (changed == 0) failXCore(missingRowError(XCoreDatabase.TABLE_LAUNCHES, id.toString()))
        database.launches.findById(id)
            ?: failXCore(missingRowError(XCoreDatabase.TABLE_LAUNCHES, id.toString()))
    }

    override suspend fun recent(limit: Int): XCoreResult<List<XCoreLaunchRecord>> =
        databaseCall("launch.recent", io) { database.launches.recent(limit) }

    override suspend fun forInstance(
        instanceId: String,
        limit: Int,
    ): XCoreResult<List<XCoreLaunchRecord>> =
        databaseCall("launch.forInstance", io) { database.launches.listForInstance(instanceId, limit) }

    override suspend fun prune(olderThanMillis: Long): XCoreResult<Int> =
        databaseCall("launch.prune", io) { database.launches.deleteOlderThan(olderThanMillis) }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("launch.count", io) { database.launches.count() }
}

class XCoreKeyValueRepositoryImpl(
    private val database: XCoreDatabase,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) : XCoreKeyValueRepository {

    override suspend fun get(key: String): XCoreResult<String?> =
        databaseCall("keyValue.get", io) { database.keyValue.get(key) }

    override suspend fun set(
        key: String,
        value: String,
    ): XCoreResult<Unit> = databaseCall("keyValue.set", io) {
        if (key.isBlank()) {
            failXCore(XCoreError.Validation("key must not be blank", field = "key"))
        }
        val rowId = database.keyValue.set(key, value, clock())
        if (rowId <= 0L) failXCore(XCoreError.Database(detail = "could not store key '$key'"))
        Unit
    }

    override suspend fun remove(key: String): XCoreResult<Unit> = databaseCall("keyValue.remove", io) {
        val changed = database.keyValue.remove(key)
        if (changed == 0) failXCore(XCoreError.Database(detail = "no key-value row '$key'"))
        Unit
    }

    override suspend fun list(prefix: String): XCoreResult<Map<String, String>> =
        databaseCall("keyValue.list", io) { database.keyValue.list(prefix) }

    override suspend fun count(): XCoreResult<Long> =
        databaseCall("keyValue.count", io) { database.keyValue.count() }
}

/** `validate()` then write, keeping the validation failure as the returned error. */
internal inline fun <T> XCoreResult<T>.flatMapResult(transform: (T) -> XCoreResult<T>): XCoreResult<T> =
    when (this) {
        is XCoreResult.Success -> transform(value)
        is XCoreResult.Failure -> this
        is XCoreResult.Cancelled -> this
    }
