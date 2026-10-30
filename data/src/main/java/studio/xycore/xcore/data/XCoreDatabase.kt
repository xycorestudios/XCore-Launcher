package studio.xycore.xcore.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess

/** A stored row that cannot be mapped onto the model it claims to be. */
class XCoreCorruptRowException(
    val table: String,
    val rowKey: String?,
    val field: String,
    val raw: String?,
) : IllegalStateException("$table.$field is unreadable (row=$rowKey value=$raw)")

/** Converts a corrupt row into the typed failure the repository layer reports. */
fun XCoreCorruptRowException.asXCoreDatabaseError(): XCoreError = XCoreError.Database(
    detail = "stored row in '$table' is unreadable at '$field': $raw",
    cause = this,
)

/**
 * Local metadata store for instances, versions, runtimes, the download journal and launch history.
 *
 * Deliberately built on the framework `SQLiteOpenHelper` instead of an ORM: schema correctness and
 * transaction boundaries stay visible, and no annotation-processing toolchain is required to build
 * the launcher. Repositories in the `:domain` module own the coroutine and result wrapping; the DAOs
 * below are synchronous and may throw `SQLiteException`.
 */
class XCoreDatabase(
    context: Context,
    private val fileName: String = DEFAULT_NAME,
    private val logger: XCoreLogger? = null,
) : SQLiteOpenHelper(context.applicationContext, fileName, null, DB_VERSION) {

    init {
        // WAL is a property of the connection pool, so it is requested before the first open.
        setWriteAheadLoggingEnabled(true)
    }

    /** Lazily created DAOs; all share this helper's connections. */
    val instances: XCoreInstanceDao by lazy { XCoreInstanceDao(this) }
    val versions: XCoreVersionDao by lazy { XCoreVersionDao(this) }
    val runtimes: XCoreRuntimeComponentDao by lazy { XCoreRuntimeComponentDao(this) }
    val downloads: XCoreDownloadJournalDao by lazy { XCoreDownloadJournalDao(this) }
    val launches: XCoreLaunchHistoryDao by lazy { XCoreLaunchHistoryDao(this) }
    val keyValue: XCoreKeyValueDao by lazy { XCoreKeyValueDao(this) }

    override fun onConfigure(db: SQLiteDatabase) {
        // Foreign keys are off by default in SQLite on Android; the cascade rules below need them.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in CREATE_STATEMENTS) {
            db.execSQL(statement)
        }
        logger?.log(XCoreLogLevel.INFO, XCoreLogTags.DATABASE, "created schema v$DB_VERSION at $fileName")
    }

    /**
     * Migrations are a numbered table. An unregistered path throws instead of recreating tables,
     * because silently dropping metadata would look like the launcher losing the user's instances.
     */
    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        var version = oldVersion
        while (version < newVersion) {
            val next = version + 1
            val statements = MIGRATIONS[next] ?: throw IllegalStateException(
                "no migration registered from schema v$version to v$next for $fileName",
            )
            db.beginTransaction()
            try {
                statements.forEach { db.execSQL(it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            logger?.log(XCoreLogLevel.INFO, XCoreLogTags.DATABASE, "migrated v$version -> v$next")
            version = next
        }
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // A database written by a newer launcher cannot be read safely; refuse rather than wipe.
        throw IllegalStateException(
            "database $fileName is schema v$oldVersion, this build reads v$newVersion",
        )
    }

    /** Runs [body] inside one transaction, rolling back on any exception. */
    fun <T> transact(body: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = body(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Opens the database so failures surface here rather than inside the first query. */
    fun prepare(): XCoreResult<Unit> = try {
        readableDatabase.version
        Unit.asXCoreSuccess()
    } catch (error: Exception) {
        XCoreError.fromThrowable(error, XCoreErrorKind.DATABASE, "cannot open $fileName").asXCoreFailure()
    }

    /** One-line description for the diagnostics screen, including a schema read-back. */
    fun describe(): String = try {
        val db = readableDatabase
        val tables = db
            .rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)
            .consume { cursor ->
                buildString {
                    while (cursor.moveToNext()) append(cursor.getString(0)).append(' ')
                }
            }
            .trim()
        "v${db.version} [$tables]"
    } catch (error: Exception) {
        "unavailable (${error.javaClass.simpleName})"
    }

    companion object {
        const val DEFAULT_NAME = "xcore-metadata.db"
        const val DB_VERSION = 1

        const val TABLE_INSTANCES = "xcore_instances"
        const val TABLE_VERSIONS = "xcore_versions"
        const val TABLE_RUNTIMES = "xcore_runtime_components"
        const val TABLE_DOWNLOADS = "xcore_download_journal"
        const val TABLE_LAUNCHES = "xcore_launch_history"
        const val TABLE_KEY_VALUE = "xcore_key_value"

        /** Reserved namespaces for launcher bookkeeping that has no table of its own. */
        const val KEY_NAMESPACE_MANIFEST = "manifest."
        const val KEY_NAMESPACE_LAST_RUN = "lastrun."

        private val MIGRATIONS: Map<Int, List<String>> = emptyMap()

        internal val CREATE_STATEMENTS: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_VERSIONS (
                id TEXT PRIMARY KEY NOT NULL,
                release_type TEXT NOT NULL,
                java_version INTEGER NOT NULL,
                asset_index_id TEXT NOT NULL,
                asset_index_count INTEGER NOT NULL DEFAULT 0,
                client_url TEXT NOT NULL,
                client_sha1 TEXT NOT NULL,
                release_time INTEGER NOT NULL,
                inherits_from TEXT,
                installed INTEGER NOT NULL DEFAULT 0,
                last_seen_manifest INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $TABLE_INSTANCES (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                version_id TEXT NOT NULL,
                loader TEXT NOT NULL,
                status TEXT NOT NULL,
                game_dir TEXT NOT NULL,
                runtime_id TEXT,
                jvm_args TEXT NOT NULL DEFAULT '',
                game_args TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_launched_at INTEGER,
                FOREIGN KEY(version_id) REFERENCES $TABLE_VERSIONS(id) ON UPDATE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RUNTIMES (
                id TEXT PRIMARY KEY NOT NULL,
                java_version INTEGER NOT NULL,
                abi TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                sha1 TEXT,
                state TEXT NOT NULL,
                source_url TEXT NOT NULL,
                signature_verified INTEGER NOT NULL DEFAULT 0,
                verified_at INTEGER,
                last_error_code INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DOWNLOADS (
                id TEXT PRIMARY KEY NOT NULL,
                url TEXT NOT NULL,
                relative_target TEXT NOT NULL,
                sha1 TEXT,
                expected_bytes INTEGER NOT NULL DEFAULT -1,
                written_bytes INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                etag TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LAUNCHES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                instance_id TEXT NOT NULL,
                version_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                exit_code INTEGER,
                error_kind TEXT,
                log_path TEXT,
                FOREIGN KEY(instance_id) REFERENCES $TABLE_INSTANCES(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $TABLE_KEY_VALUE (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_instances_updated ON $TABLE_INSTANCES(updated_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_instances_status ON $TABLE_INSTANCES(status)",
            "CREATE INDEX IF NOT EXISTS idx_instances_version ON $TABLE_INSTANCES(version_id)",
            "CREATE INDEX IF NOT EXISTS idx_versions_release_type ON $TABLE_VERSIONS(release_type)",
            "CREATE INDEX IF NOT EXISTS idx_runtimes_lookup ON $TABLE_RUNTIMES(abi, java_version, state)",
            "CREATE INDEX IF NOT EXISTS idx_downloads_state ON $TABLE_DOWNLOADS(state)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_downloads_open_target ON $TABLE_DOWNLOADS(relative_target) WHERE state IN ('PENDING', 'RUNNING', 'PAUSED')",
            "CREATE INDEX IF NOT EXISTS idx_launches_started ON $TABLE_LAUNCHES(started_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_launches_instance ON $TABLE_LAUNCHES(instance_id)",
        )
    }
}

/**
 * `android.database.Cursor` is `Closeable`, but the `use` extension resolves against
 * `java.io.Closeable` and hides leaks in review. Every read here goes through this so the close is
 * visibly tied to the query that produced the cursor.
 */
private inline fun <R> Cursor.consume(block: (Cursor) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}

private inline fun <R> SQLiteDatabase.statement(
    sql: String,
    block: (SQLiteStatement) -> R,
): R {
    val compiled = compileStatement(sql)
    return try {
        block(compiled)
    } finally {
        compiled.close()
    }
}

/** `ContentValues.put` takes primitives, so a nullable column needs an explicit NULL write. */
private fun ContentValues.putLong(
    key: String,
    value: Long?,
) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putInt(
    key: String,
    value: Int?,
) {
    if (value == null) putNull(key) else put(key, value)
}

private fun <T> Cursor.mapEach(transform: (Cursor) -> T): List<T> = consume { cursor ->
    val results = ArrayList<T>(cursor.count.coerceAtLeast(0))
    while (cursor.moveToNext()) {
        results += transform(cursor)
    }
    results
}

private fun Cursor.text(
    column: String,
    table: String,
    rowKey: String?,
): String = try {
    getString(getColumnIndexOrThrow(column))
        ?: throw XCoreCorruptRowException(table, rowKey, column, null)
} catch (already: XCoreCorruptRowException) {
    throw already
} catch (error: Exception) {
    throw XCoreCorruptRowException(table, rowKey, column, error.message)
}

private fun Cursor.textOrNull(column: String): String? =
    if (getColumnIndex(column) < 0 || isNull(getColumnIndex(column))) null else getString(getColumnIndex(column))

private fun Cursor.longValue(
    column: String,
    table: String,
    rowKey: String?,
): Long = try {
    getLong(getColumnIndexOrThrow(column))
} catch (error: Exception) {
    throw XCoreCorruptRowException(table, rowKey, column, error.message)
}

private fun Cursor.longOrNull(column: String): Long? =
    if (getColumnIndex(column) < 0 || isNull(getColumnIndex(column))) null else getLong(getColumnIndex(column))

private fun Cursor.intOrNull(column: String): Int? = longOrNull(column)?.toInt()

private fun Cursor.bool(
    column: String,
    table: String,
    rowKey: String?,
): Boolean = longValue(column, table, rowKey) != 0L

private fun Cursor.requireSha1(
    column: String,
    table: String,
    rowKey: String?,
): XCoreSha1 {
    val raw = text(column, table, rowKey)
    return when (val outcome = XCoreSha1.parse(raw)) {
        is XCoreResult.Success -> outcome.value
        else -> throw XCoreCorruptRowException(table, rowKey, column, raw)
    }
}

private fun Cursor.optionalSha1(column: String): XCoreSha1? {
    val raw = textOrNull(column) ?: return null
    return (XCoreSha1.parse(raw) as? XCoreResult.Success)?.value
}

/** Instance rows; the version foreign key means a version must be registered before use. */
class XCoreInstanceDao(private val database: XCoreDatabase) {

    fun upsert(instance: XCoreInstance): Long {
        val row = ContentValues().apply {
            put("id", instance.id)
            put("name", instance.name)
            put("version_id", instance.versionId)
            put("loader", instance.loader.manifestId)
            put("status", instance.status.name)
            put("game_dir", instance.gameDirectoryRelative)
            put("runtime_id", instance.runtimeComponentId)
            put("jvm_args", instance.jvmArguments)
            put("game_args", instance.gameArguments)
            put("created_at", instance.createdAtMillis)
            put("updated_at", instance.updatedAtMillis)
            putLong("last_launched_at", instance.lastLaunchedAtMillis)
        }
        return database.writableDatabase.insertWithOnConflict(
            XCoreDatabase.TABLE_INSTANCES,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findById(id: String): XCoreInstance? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_INSTANCES,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        )
        .mapEach { it.toInstance() }
        .firstOrNull()

    /** Newest activity first, which is what the instance picker shows. */
    fun listAll(limit: Int = 200): List<XCoreInstance> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_INSTANCES,
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toInstance() }

    fun listByVersion(versionId: String): List<XCoreInstance> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_INSTANCES,
            null,
            "version_id = ?",
            arrayOf(versionId),
            null,
            null,
            "updated_at DESC",
            null,
        )
        .mapEach { it.toInstance() }

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_INSTANCES)

    fun updateStatus(
        id: String,
        status: XCoreInstanceStatus,
        atMillis: Long,
    ): Int {
        val row = ContentValues().apply {
            put("status", status.name)
            put("updated_at", atMillis)
        }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_INSTANCES,
            row,
            "id = ?",
            arrayOf(id),
        )
    }

    fun attachRuntime(
        id: String,
        runtimeComponentId: String?,
        atMillis: Long,
    ): Int {
        val row = ContentValues().apply {
            put("runtime_id", runtimeComponentId)
            put("updated_at", atMillis)
        }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_INSTANCES,
            row,
            "id = ?",
            arrayOf(id),
        )
    }

    fun markLaunched(
        id: String,
        atMillis: Long,
    ): Int {
        val row = ContentValues().apply {
            put("last_launched_at", atMillis)
            put("updated_at", atMillis)
        }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_INSTANCES,
            row,
            "id = ?",
            arrayOf(id),
        )
    }

    fun delete(id: String): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_INSTANCES,
        "id = ?",
        arrayOf(id),
    )

    /** Instances whose version row has disappeared, i.e. orphans left by a data import. */
    fun listOrphans(): List<XCoreInstance> = database.readableDatabase
        .rawQuery(
            "SELECT i.* FROM ${XCoreDatabase.TABLE_INSTANCES} i " +
                "LEFT JOIN ${XCoreDatabase.TABLE_VERSIONS} v ON v.id = i.version_id WHERE v.id IS NULL",
            null,
        )
        .mapEach { it.toInstance() }

    private fun Cursor.toInstance(): XCoreInstance {
        val table = XCoreDatabase.TABLE_INSTANCES
        val id = text("id", table, null)
        val loaderRaw = text("loader", table, id)
        val loader = when (val outcome = XCoreLoaderKind.fromManifestId(loaderRaw)) {
            is XCoreResult.Success -> outcome.value
            else -> throw XCoreCorruptRowException(table, id, "loader", loaderRaw)
        }
        val statusName = text("status", table, id)
        val status = XCoreInstanceStatus.entries.firstOrNull { it.name == statusName }
            ?: throw XCoreCorruptRowException(table, id, "status", statusName)
        return XCoreInstance(
            id = id,
            name = text("name", table, id),
            versionId = text("version_id", table, id),
            loader = loader,
            status = status,
            gameDirectoryRelative = text("game_dir", table, id),
            runtimeComponentId = textOrNull("runtime_id"),
            jvmArguments = text("jvm_args", table, id),
            gameArguments = text("game_args", table, id),
            createdAtMillis = longValue("created_at", table, id),
            updatedAtMillis = longValue("updated_at", table, id),
            lastLaunchedAtMillis = longOrNull("last_launched_at"),
        )
    }
}

/** Version rows produced by the manifest reader in a later phase. */
class XCoreVersionDao(private val database: XCoreDatabase) {

    fun upsert(version: XCoreVersionRef): Long {
        val row = ContentValues().apply {
            put("id", version.id)
            put("release_type", version.releaseType.manifestId)
            put("java_version", version.javaVersion)
            put("asset_index_id", version.assetIndexId)
            put("asset_index_count", version.assetIndexCount)
            put("client_url", version.clientDownloadUrl)
            put("client_sha1", version.clientDownloadSha1.value)
            put("release_time", version.releaseTimeMillis)
            put("inherits_from", version.inheritsFrom)
            put("installed", if (version.installed) 1 else 0)
            putLong("last_seen_manifest", version.lastSeenManifestMillis)
        }
        return database.writableDatabase.insertWithOnConflict(
            XCoreDatabase.TABLE_VERSIONS,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findById(id: String): XCoreVersionRef? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_VERSIONS,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        )
        .mapEach { it.toVersion() }
        .firstOrNull()

    /** Highest Java requirement first so a launcher can pick the newest release quickly. */
    fun listInstalled(limit: Int = 100): List<XCoreVersionRef> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_VERSIONS,
            null,
            "installed = 1",
            null,
            null,
            null,
            "release_time DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toVersion() }

    fun listAll(limit: Int = 500): List<XCoreVersionRef> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_VERSIONS,
            null,
            null,
            null,
            null,
            null,
            "release_time DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toVersion() }

    fun markInstalled(
        id: String,
        installed: Boolean,
    ): Int {
        val row = ContentValues().apply { put("installed", if (installed) 1 else 0) }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_VERSIONS,
            row,
            "id = ?",
            arrayOf(id),
        )
    }

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_VERSIONS)

    fun delete(id: String): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_VERSIONS,
        "id = ?",
        arrayOf(id),
    )

    private fun Cursor.toVersion(): XCoreVersionRef {
        val table = XCoreDatabase.TABLE_VERSIONS
        val id = text("id", table, null)
        val releaseRaw = text("release_type", table, id)
        val releaseType = when (val outcome = XCoreReleaseType.fromManifestId(releaseRaw)) {
            is XCoreResult.Success -> outcome.value
            else -> throw XCoreCorruptRowException(table, id, "release_type", releaseRaw)
        }
        return XCoreVersionRef(
            id = id,
            releaseType = releaseType,
            javaVersion = longValue("java_version", table, id).toInt(),
            assetIndexId = text("asset_index_id", table, id),
            assetIndexCount = longValue("asset_index_count", table, id).toInt(),
            clientDownloadUrl = text("client_url", table, id),
            clientDownloadSha1 = requireSha1("client_sha1", table, id),
            releaseTimeMillis = longValue("release_time", table, id),
            inheritsFrom = textOrNull("inherits_from"),
            lastSeenManifestMillis = longOrNull("last_seen_manifest"),
            installed = bool("installed", table, id),
        )
    }
}

/** Recorded Java runtimes. Selection prefers the lowest component that still satisfies a version. */
class XCoreRuntimeComponentDao(private val database: XCoreDatabase) {

    fun upsert(component: XCoreRuntimeComponent): Long {
        val row = ContentValues().apply {
            put("id", component.id)
            put("java_version", component.javaVersion)
            put("abi", component.abi)
            put("relative_path", component.relativePath)
            put("size_bytes", component.sizeBytes)
            put("sha1", component.sha1?.value)
            put("state", component.state.name)
            put("source_url", component.sourceUrl)
            put("signature_verified", if (component.signatureVerified) 1 else 0)
            putLong("verified_at", component.verifiedAtMillis)
            putInt("last_error_code", component.lastErrorCode)
        }
        return database.writableDatabase.insertWithOnConflict(
            XCoreDatabase.TABLE_RUNTIMES,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findById(id: String): XCoreRuntimeComponent? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_RUNTIMES,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        )
        .mapEach { it.toComponent() }
        .firstOrNull()

    /**
     * Ready runtimes for [abi] that can run a [minimumJavaVersion] game, smallest requirement
     * first: launching 1.8 on Java 8 is preferable to forcing Java 21 onto it.
     */
    fun findUsable(
        abi: String,
        minimumJavaVersion: Int,
        requireVerified: Boolean,
        limit: Int = 5,
    ): List<XCoreRuntimeComponent> {
        val selection = buildString {
            append("abi = ? AND state = ? AND java_version >= ?")
            if (requireVerified) append(" AND signature_verified = 1")
        }
        return database.readableDatabase
            .query(
                XCoreDatabase.TABLE_RUNTIMES,
                null,
                selection,
                arrayOf(abi, XCoreRuntimeState.READY.name, minimumJavaVersion.toString()),
                null,
                null,
                "java_version ASC",
                limit.coerceAtLeast(0).toString(),
            )
            .mapEach { it.toComponent() }
    }

    fun listAll(): List<XCoreRuntimeComponent> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_RUNTIMES,
            null,
            null,
            null,
            null,
            null,
            "java_version ASC",
            null,
        )
        .mapEach { it.toComponent() }

    fun updateState(
        id: String,
        state: XCoreRuntimeState,
        verifiedAtMillis: Long?,
        lastErrorCode: Int?,
    ): Int {
        val row = ContentValues().apply {
            put("state", state.name)
            putLong("verified_at", verifiedAtMillis)
            putInt("last_error_code", lastErrorCode)
        }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_RUNTIMES,
            row,
            "id = ?",
            arrayOf(id),
        )
    }

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_RUNTIMES)

    fun delete(id: String): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_RUNTIMES,
        "id = ?",
        arrayOf(id),
    )

    private fun Cursor.toComponent(): XCoreRuntimeComponent {
        val table = XCoreDatabase.TABLE_RUNTIMES
        val id = text("id", table, null)
        val stateName = text("state", table, id)
        val state = XCoreRuntimeState.entries.firstOrNull { it.name == stateName }
            ?: throw XCoreCorruptRowException(table, id, "state", stateName)
        return XCoreRuntimeComponent(
            id = id,
            javaVersion = longValue("java_version", table, id).toInt(),
            abi = text("abi", table, id),
            relativePath = text("relative_path", table, id),
            sizeBytes = longValue("size_bytes", table, id),
            sha1 = optionalSha1("sha1"),
            state = state,
            sourceUrl = text("source_url", table, id),
            signatureVerified = bool("signature_verified", table, id),
            verifiedAtMillis = longOrNull("verified_at"),
            lastErrorCode = intOrNull("last_error_code"),
        )
    }
}

/**
 * Resume journal for byte transfers. The partial unique index keeps at most one open record per
 * target path, so two tasks cannot both claim to be writing the same file.
 */
class XCoreDownloadJournalDao(private val database: XCoreDatabase) {

    fun upsert(record: XCoreDownloadRecord): Long {
        val row = ContentValues().apply {
            put("id", record.id)
            put("url", record.url)
            put("relative_target", record.relativeTarget)
            put("sha1", record.sha1?.value)
            put("expected_bytes", record.expectedBytes)
            put("written_bytes", record.writtenBytes)
            put("state", record.state.name)
            put("etag", record.etag)
            put("attempts", record.attempts)
            put("updated_at", record.updatedAtMillis)
        }
        return database.writableDatabase.insertWithOnConflict(
            XCoreDatabase.TABLE_DOWNLOADS,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Adds bytes actually appended to the partial file, which is what makes resume honest. */
    fun addWritten(
        id: String,
        deltaBytes: Long,
        atMillis: Long,
        state: XCoreDownloadState = XCoreDownloadState.RUNNING,
    ): Int = database.writableDatabase.statement(
        "UPDATE ${XCoreDatabase.TABLE_DOWNLOADS} SET written_bytes = written_bytes + ?, " +
            "state = ?, updated_at = ? WHERE id = ?",
    ) { statement ->
        statement.bindLong(1, deltaBytes)
        statement.bindString(2, state.name)
        statement.bindLong(3, atMillis)
        statement.bindString(4, id)
        statement.executeUpdateDelete()
    }

    fun transition(
        id: String,
        state: XCoreDownloadState,
        atMillis: Long,
        attemptsDelta: Int = 0,
    ): Int = database.writableDatabase.statement(
        "UPDATE ${XCoreDatabase.TABLE_DOWNLOADS} SET state = ?, " +
            "attempts = attempts + ?, updated_at = ? WHERE id = ?",
    ) { statement ->
        statement.bindString(1, state.name)
        statement.bindLong(2, attemptsDelta.toLong())
        statement.bindLong(3, atMillis)
        statement.bindString(4, id)
        statement.executeUpdateDelete()
    }

    fun findById(id: String): XCoreDownloadRecord? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_DOWNLOADS,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        )
        .mapEach { it.toRecord() }
        .firstOrNull()

    fun listOpen(limit: Int = 500): List<XCoreDownloadRecord> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_DOWNLOADS,
            null,
            "state IN ('PENDING', 'RUNNING', 'PAUSED')",
            null,
            null,
            null,
            "updated_at ASC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toRecord() }

    fun listIncomplete(limit: Int = 200): List<XCoreDownloadRecord> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_DOWNLOADS,
            null,
            "state <> 'COMPLETED'",
            null,
            null,
            null,
            "updated_at DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toRecord() }

    fun deleteCompletedBefore(cutoffMillis: Long): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_DOWNLOADS,
        "state = 'COMPLETED' AND updated_at < ?",
        arrayOf(cutoffMillis.toString()),
    )

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_DOWNLOADS)

    private fun Cursor.toRecord(): XCoreDownloadRecord {
        val table = XCoreDatabase.TABLE_DOWNLOADS
        val id = text("id", table, null)
        val stateName = text("state", table, id)
        val state = XCoreDownloadState.entries.firstOrNull { it.name == stateName }
            ?: throw XCoreCorruptRowException(table, id, "state", stateName)
        return XCoreDownloadRecord(
            id = id,
            url = text("url", table, id),
            relativeTarget = text("relative_target", table, id),
            sha1 = optionalSha1("sha1"),
            expectedBytes = longValue("expected_bytes", table, id),
            writtenBytes = longValue("written_bytes", table, id),
            state = state,
            etag = textOrNull("etag"),
            attempts = longValue("attempts", table, id).toInt(),
            updatedAtMillis = longValue("updated_at", table, id),
        )
    }
}

/** Append-only launch attempts, so a regression can be traced to the run that introduced it. */
class XCoreLaunchHistoryDao(private val database: XCoreDatabase) {

    fun recordStart(
        instanceId: String,
        versionId: String,
        atMillis: Long,
    ): Long {
        val row = ContentValues().apply {
            put("instance_id", instanceId)
            put("version_id", versionId)
            put("started_at", atMillis)
        }
        return database.writableDatabase.insert(XCoreDatabase.TABLE_LAUNCHES, null, row)
    }

    fun finish(
        id: Long,
        endedAtMillis: Long,
        exitCode: Int?,
        errorKind: XCoreErrorKind?,
        logPathRelative: String?,
    ): Int {
        val row = ContentValues().apply {
            put("ended_at", endedAtMillis)
            putInt("exit_code", exitCode)
            put("error_kind", errorKind?.name)
            put("log_path", logPathRelative)
        }
        return database.writableDatabase.update(
            XCoreDatabase.TABLE_LAUNCHES,
            row,
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun findById(id: Long): XCoreLaunchRecord? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_LAUNCHES,
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        )
        .mapEach { it.toRecord() }
        .firstOrNull()

    fun recent(limit: Int = 50): List<XCoreLaunchRecord> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_LAUNCHES,
            null,
            null,
            null,
            null,
            null,
            "started_at DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toRecord() }

    fun listForInstance(
        instanceId: String,
        limit: Int = 50,
    ): List<XCoreLaunchRecord> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_LAUNCHES,
            null,
            "instance_id = ?",
            arrayOf(instanceId),
            null,
            null,
            "started_at DESC",
            limit.coerceAtLeast(0).toString(),
        )
        .mapEach { it.toRecord() }

    /** Unfinished rows are runs the process died on; they stay until the age cutoff passes. */
    fun deleteOlderThan(cutoffMillis: Long): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_LAUNCHES,
        "ended_at IS NOT NULL AND ended_at < ?",
        arrayOf(cutoffMillis.toString()),
    )

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_LAUNCHES)

    private fun Cursor.toRecord(): XCoreLaunchRecord {
        val table = XCoreDatabase.TABLE_LAUNCHES
        val id = longValue("id", table, null).toString()
        val errorKindName = textOrNull("error_kind")
        val errorKind = errorKindName?.let { raw ->
            XCoreErrorKind.entries.firstOrNull { it.name == raw }
                ?: throw XCoreCorruptRowException(table, id, "error_kind", raw)
        }
        return XCoreLaunchRecord(
            id = id,
            instanceId = text("instance_id", table, id),
            versionId = text("version_id", table, id),
            startedAtMillis = longValue("started_at", table, id),
            endedAtMillis = longOrNull("ended_at"),
            exitCode = intOrNull("exit_code"),
            errorKind = errorKind,
            logPathRelative = textOrNull("log_path"),
        )
    }
}

/** Small keyed values for metadata that does not deserve its own table. */
class XCoreKeyValueDao(private val database: XCoreDatabase) {

    fun set(
        key: String,
        value: String,
        atMillis: Long,
    ): Long {
        val row = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", atMillis)
        }
        return database.writableDatabase.insertWithOnConflict(
            XCoreDatabase.TABLE_KEY_VALUE,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun get(key: String): String? = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_KEY_VALUE,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        )
        .consume { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun list(prefix: String): Map<String, String> = database.readableDatabase
        .query(
            XCoreDatabase.TABLE_KEY_VALUE,
            arrayOf("key", "value"),
            "key LIKE ?",
            arrayOf("$prefix%"),
            null,
            null,
            "key ASC",
            null,
        )
        .consume { cursor ->
            val values = LinkedHashMap<String, String>()
            val keyColumn = cursor.getColumnIndexOrThrow("key")
            val valueColumn = cursor.getColumnIndexOrThrow("value")
            while (cursor.moveToNext()) {
                values[cursor.getString(keyColumn)] = cursor.getString(valueColumn)
            }
            values
        }

    fun remove(key: String): Int = database.writableDatabase.delete(
        XCoreDatabase.TABLE_KEY_VALUE,
        "key = ?",
        arrayOf(key),
    )

    fun count(): Long = singleCount(database, XCoreDatabase.TABLE_KEY_VALUE)
}

private fun singleCount(
    database: XCoreDatabase,
    table: String,
): Long = database.readableDatabase
    .rawQuery("SELECT COUNT(*) FROM $table", null)
    .consume { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
