package studio.xycore.xcore.core

import kotlinx.coroutines.flow.Flow

enum class XCoreEnvironment { DEVELOPMENT, PRODUCTION }

/**
 * Where X-Core looks for the Java runtime it is asked to launch with.
 *
 * `LOCAL_ONLY` never touches the network: only components already recorded as installed are
 * considered. This exists so a broken or unavailable runtime host cannot turn a launch into a
 * silent download.
 */
enum class XCoreRuntimeChannel { STABLE, BETA, LOCAL_ONLY }

/** Which Android-supported storage area holds the game tree. */
enum class XCoreStorageRoot {
    /** `Context.getFilesDir()` — always writable, removed with the app. */
    INTERNAL,

    /** `Context.getExternalFilesDir(null)` — removable storage aware, app-scoped. */
    EXTERNAL_APP_PRIVATE,

    /** A caller-supplied absolute path, validated by [XCoreConfig.validate]. */
    CUSTOM,
}

/**
 * Immutable launcher configuration.
 *
 * Values are deliberately narrow: anything that is a capability gap for a later phase (proxy
 * settings, modpack hosts, renderer selection) is absent rather than present-but-ignored.
 */
data class XCoreConfig(
    val environment: XCoreEnvironment = XCoreEnvironment.DEVELOPMENT,
    val logLevel: XCoreLogLevel = XCoreLogLevel.INFO,
    val runtimeChannel: XCoreRuntimeChannel = XCoreRuntimeChannel.STABLE,
    val storageRoot: XCoreStorageRoot = XCoreStorageRoot.EXTERNAL_APP_PRIVATE,
    val customRootPath: String? = null,
    val maxConcurrentDownloads: Int = 4,
    val httpTimeoutMillis: Int = 20_000,
    val userAgent: String = DEFAULT_USER_AGENT,
    val keepLaunchHistoryDays: Int = 30,
    val logHistoryCapacity: Int = 2_000,
    val requireVerifiedRuntime: Boolean = true,
    val schemaVersion: Int = SCHEMA_VERSION,
) {

    /** Range and consistency checks. Never clamps: a bad stored value must surface as a failure. */
    fun validate(): XCoreResult<XCoreConfig> {
        if (maxConcurrentDownloads !in 1..MAX_CONCURRENT_DOWNLOADS) {
            return XCoreError.Validation(
                detail = "maxConcurrentDownloads must be within 1..$MAX_CONCURRENT_DOWNLOADS",
                field = "maxConcurrentDownloads",
                rejected = maxConcurrentDownloads.toString(),
            ).asXCoreFailure()
        }
        if (httpTimeoutMillis !in MIN_HTTP_TIMEOUT_MILLIS..MAX_HTTP_TIMEOUT_MILLIS) {
            return XCoreError.Validation(
                detail = "httpTimeoutMillis must be within $MIN_HTTP_TIMEOUT_MILLIS..$MAX_HTTP_TIMEOUT_MILLIS",
                field = "httpTimeoutMillis",
                rejected = httpTimeoutMillis.toString(),
            ).asXCoreFailure()
        }
        if (keepLaunchHistoryDays !in 1..365) {
            return XCoreError.Validation(
                detail = "keepLaunchHistoryDays must be within 1..365",
                field = "keepLaunchHistoryDays",
                rejected = keepLaunchHistoryDays.toString(),
            ).asXCoreFailure()
        }
        if (logHistoryCapacity !in 100..50_000) {
            return XCoreError.Validation(
                detail = "logHistoryCapacity must be within 100..50000",
                field = "logHistoryCapacity",
                rejected = logHistoryCapacity.toString(),
            ).asXCoreFailure()
        }
        if (userAgent.isBlank()) {
            return XCoreError.Validation(
                detail = "userAgent must not be blank",
                field = "userAgent",
            ).asXCoreFailure()
        }
        if (storageRoot == XCoreStorageRoot.CUSTOM) {
            val path = customRootPath
            if (path.isNullOrBlank()) {
                return XCoreError.Validation(
                    detail = "storageRoot is CUSTOM but customRootPath is not set",
                    field = "customRootPath",
                ).asXCoreFailure()
            }
            if (!path.startsWith("/")) {
                return XCoreError.Validation(
                    detail = "customRootPath must be an absolute filesystem path",
                    field = "customRootPath",
                    rejected = path,
                ).asXCoreFailure()
            }
        }
        if (storageRoot == XCoreStorageRoot.INTERNAL && customRootPath != null) {
            return XCoreError.Configuration(
                "customRootPath is set while storageRoot is INTERNAL; drop one of them",
            ).asXCoreFailure()
        }
        if (schemaVersion != SCHEMA_VERSION) {
            return XCoreError.Configuration(
                "config schema $schemaVersion cannot be read by engine schema $SCHEMA_VERSION",
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    fun withLogLevel(level: XCoreLogLevel): XCoreConfig = copy(logLevel = level)

    /** Keys that changed, used to report a config reload precisely instead of dumping everything. */
    fun changedKeysAgainst(other: XCoreConfig): Set<String> {
        val changed = linkedSetOf<String>()
        if (environment != other.environment) changed += "environment"
        if (logLevel != other.logLevel) changed += "logLevel"
        if (runtimeChannel != other.runtimeChannel) changed += "runtimeChannel"
        if (storageRoot != other.storageRoot) changed += "storageRoot"
        if (customRootPath != other.customRootPath) changed += "customRootPath"
        if (maxConcurrentDownloads != other.maxConcurrentDownloads) changed += "maxConcurrentDownloads"
        if (httpTimeoutMillis != other.httpTimeoutMillis) changed += "httpTimeoutMillis"
        if (userAgent != other.userAgent) changed += "userAgent"
        if (keepLaunchHistoryDays != other.keepLaunchHistoryDays) changed += "keepLaunchHistoryDays"
        if (logHistoryCapacity != other.logHistoryCapacity) changed += "logHistoryCapacity"
        if (requireVerifiedRuntime != other.requireVerifiedRuntime) changed += "requireVerifiedRuntime"
        if (schemaVersion != other.schemaVersion) changed += "schemaVersion"
        return changed
    }

    companion object {
        const val DEFAULT_USER_AGENT = "XCoreLauncher/0.1.0 (Android; xycorestudios)"
        const val SCHEMA_VERSION = 1
        const val MAX_CONCURRENT_DOWNLOADS = 8
        const val MIN_HTTP_TIMEOUT_MILLIS = 1_000
        const val MAX_HTTP_TIMEOUT_MILLIS = 120_000

        val DEFAULT = XCoreConfig()
    }
}

/**
 * Persistence boundary for [XCoreConfig]; implemented by `XCorePreferences` in the `:data` module.
 *
 * [load] returns [XCoreResult] rather than a bare config because a corrupt or unreadable store is a
 * real state the engine must report instead of papering over with defaults.
 */
interface XCoreConfigStore {

    val config: Flow<XCoreConfig>

    suspend fun load(): XCoreResult<XCoreConfig>

    /** Persist only already-validated configurations; validation is the caller's job. */
    suspend fun save(config: XCoreConfig): XCoreResult<Unit>

    suspend fun reset(): XCoreResult<Unit>
}

/**
 * Merges persisted values over [XCoreConfig.DEFAULT] and refuses to hand back an invalid config.
 * A read error is returned as a failure; the caller decides whether to fall back to defaults.
 */
class XCoreConfigResolver(private val store: XCoreConfigStore) {

    suspend fun resolve(): XCoreResult<XCoreConfig> = when (val loaded = store.load()) {
        is XCoreResult.Failure -> loaded
        is XCoreResult.Cancelled -> loaded
        is XCoreResult.Success -> loaded.value.validate()
    }

    suspend fun resolveOrDefaults(): Pair<XCoreConfig, XCoreError?> {
        val outcome = resolve()
        return when (outcome) {
            is XCoreResult.Success -> outcome.value to null
            is XCoreResult.Failure -> XCoreConfig.DEFAULT to outcome.error
            is XCoreResult.Cancelled -> XCoreConfig.DEFAULT to XCoreError.Cancelled(
                detail = "config read cancelled",
                operation = "config.resolve",
            )
        }
    }

    suspend fun update(config: XCoreConfig): XCoreResult<Unit> =
        config.validate().flatMap { store.save(it) }
}
