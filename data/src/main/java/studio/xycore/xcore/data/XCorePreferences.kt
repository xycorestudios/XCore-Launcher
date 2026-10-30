package studio.xycore.xcore.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreConfigStore
import studio.xycore.xcore.core.XCoreEnvironment
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreOperationMeta
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeChannel
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.xCoreRun

/**
 * Persistent launcher configuration, plus the few operational values the UI needs to remember.
 *
 * A stored value that cannot be parsed produces [XCoreError.Preferences] instead of being replaced
 * by a default in silence. The engine then falls back to [XCoreConfig.DEFAULT] and reports the
 * problem through `XCoreEngineSnapshot.configProblem`, so a bad setting is never displayed as a
 * working one.
 */
class XCorePreferences private constructor(
    private val dataStore: DataStore<Preferences>,
) : XCoreConfigStore {

    override val config: Flow<XCoreConfig> = dataStore.data.map { preferences ->
        decode(preferences).getOrThrow()
    }

    override suspend fun load(): XCoreResult<XCoreConfig> = xCoreRun(
        origin = "preferences.load",
        errorKind = XCoreErrorKind.PREFERENCES,
    ) {
        decode(currentPreferences()).getOrThrow()
    }

    override suspend fun save(config: XCoreConfig): XCoreResult<Unit> =
        config.validate().flatMap { valid ->
            xCoreRun(origin = "preferences.save", errorKind = XCoreErrorKind.PREFERENCES) {
                dataStore.edit { preferences -> encodeInto(valid, preferences) }
                Unit
            }
        }

    /**
     * Removes configuration keys only. `app.*` keys (selected instance, last launch, onboarding)
     * survive, because resetting settings and losing which instance was selected are different
     * things a user did not ask for.
     */
    override suspend fun reset(): XCoreResult<Unit> = xCoreRun(
        origin = "preferences.reset",
        errorKind = XCoreErrorKind.PREFERENCES,
    ) {
        dataStore.edit { preferences -> CONFIG_KEYS.forEach { preferences.remove(it) } }
        Unit
    }

    /** Read-modify-write inside one DataStore transaction, so concurrent saves cannot interleave. */
    suspend fun updateConfig(transform: (XCoreConfig) -> XCoreConfig): XCoreResult<XCoreConfig> =
        xCoreRun(origin = "preferences.updateConfig", errorKind = XCoreErrorKind.PREFERENCES) {
            var produced = XCoreConfig.DEFAULT
            dataStore.edit { preferences ->
                val current = decode(preferences).getOrThrow()
                produced = transform(current).validate().getOrThrow()
                encodeInto(produced, preferences)
            }
            produced
        }

    val selectedInstanceId: Flow<String?> = dataStore.data.map { it[KEY_SELECTED_INSTANCE] }

    suspend fun selectedInstanceIdNow(): String? = currentPreferences()[KEY_SELECTED_INSTANCE]

    suspend fun selectInstance(instanceId: String?): XCoreResult<Unit> = xCoreRun(
        origin = "preferences.selectInstance",
        errorKind = XCoreErrorKind.PREFERENCES,
    ) {
        dataStore.edit { preferences ->
            if (instanceId == null) {
                preferences.remove(KEY_SELECTED_INSTANCE)
            } else {
                preferences[KEY_SELECTED_INSTANCE] = instanceId
            }
        }
    }

    val onboardingAcknowledged: Flow<Boolean> = dataStore.data.map { it[KEY_ONBOARDING_ACK] == true }

    suspend fun setOnboardingAcknowledged(acknowledged: Boolean): XCoreResult<Unit> = xCoreRun(
        origin = "preferences.onboarding",
        errorKind = XCoreErrorKind.PREFERENCES,
    ) {
        dataStore.edit { it[KEY_ONBOARDING_ACK] = acknowledged }
    }

    suspend fun recordLaunchSuccess(atMillis: Long): XCoreResult<Unit> = xCoreRun(
        origin = "preferences.recordLaunchSuccess",
        errorKind = XCoreErrorKind.PREFERENCES,
    ) {
        dataStore.edit { it[KEY_LAST_LAUNCH_MILLIS] = atMillis }
    }

    suspend fun lastLaunchMillis(): Long? = currentPreferences()[KEY_LAST_LAUNCH_MILLIS]

    /** Snapshot of the current store contents; an unreadable store propagates as an exception. */
    private suspend fun currentPreferences(): Preferences = dataStore.data.first()

    private fun decode(preferences: Preferences): XCoreResult<XCoreConfig> {
        val unreadable = ArrayList<XCoreError>(4)
        val environment = pickEnum(
            raw = preferences[KEY_ENVIRONMENT],
            values = XCoreEnvironment.values(),
            fallback = XCoreConfig.DEFAULT.environment,
            field = "environment",
            problems = unreadable,
        )
        val logLevel = pickEnum(
            raw = preferences[KEY_LOG_LEVEL],
            values = XCoreLogLevel.values(),
            fallback = XCoreConfig.DEFAULT.logLevel,
            field = "logLevel",
            problems = unreadable,
        )
        val runtimeChannel = pickEnum(
            raw = preferences[KEY_RUNTIME_CHANNEL],
            values = XCoreRuntimeChannel.values(),
            fallback = XCoreConfig.DEFAULT.runtimeChannel,
            field = "runtimeChannel",
            problems = unreadable,
        )
        val storageRoot = pickEnum(
            raw = preferences[KEY_STORAGE_ROOT],
            values = XCoreStorageRoot.values(),
            fallback = XCoreConfig.DEFAULT.storageRoot,
            field = "storageRoot",
            problems = unreadable,
        )
        if (unreadable.isNotEmpty()) {
            return unreadable.first()
                .asXCoreFailure(XCoreOperationMeta(origin = "preferences.decode"))
        }
        val candidate = XCoreConfig(
            environment = environment,
            logLevel = logLevel,
            runtimeChannel = runtimeChannel,
            storageRoot = storageRoot,
            customRootPath = preferences[KEY_CUSTOM_ROOT_PATH],
            maxConcurrentDownloads = preferences[KEY_MAX_DOWNLOADS]
                ?: XCoreConfig.DEFAULT.maxConcurrentDownloads,
            httpTimeoutMillis = preferences[KEY_HTTP_TIMEOUT] ?: XCoreConfig.DEFAULT.httpTimeoutMillis,
            userAgent = preferences[KEY_USER_AGENT] ?: XCoreConfig.DEFAULT.userAgent,
            keepLaunchHistoryDays = preferences[KEY_KEEP_HISTORY_DAYS]
                ?: XCoreConfig.DEFAULT.keepLaunchHistoryDays,
            logHistoryCapacity = preferences[KEY_LOG_CAPACITY] ?: XCoreConfig.DEFAULT.logHistoryCapacity,
            requireVerifiedRuntime = preferences[KEY_REQUIRE_VERIFIED_RUNTIME]
                ?: XCoreConfig.DEFAULT.requireVerifiedRuntime,
            schemaVersion = preferences[KEY_SCHEMA_VERSION] ?: XCoreConfig.SCHEMA_VERSION,
        )
        return candidate.validate()
    }

    private fun <E : Enum<E>> pickEnum(
        raw: String?,
        values: Array<E>,
        fallback: E,
        field: String,
        problems: MutableList<XCoreError>,
    ): E {
        if (raw == null) return fallback
        val match = values.firstOrNull { it.name == raw }
        if (match != null) return match
        problems += XCoreError.Preferences(
            detail = "stored value '$raw' is not a known $field constant",
            key = field,
        )
        return fallback
    }

    private fun encodeInto(
        config: XCoreConfig,
        into: MutablePreferences,
    ) {
        into[KEY_ENVIRONMENT] = config.environment.name
        into[KEY_LOG_LEVEL] = config.logLevel.name
        into[KEY_RUNTIME_CHANNEL] = config.runtimeChannel.name
        into[KEY_STORAGE_ROOT] = config.storageRoot.name
        val custom = config.customRootPath
        if (custom == null) {
            into.remove(KEY_CUSTOM_ROOT_PATH)
        } else {
            into[KEY_CUSTOM_ROOT_PATH] = custom
        }
        into[KEY_MAX_DOWNLOADS] = config.maxConcurrentDownloads
        into[KEY_HTTP_TIMEOUT] = config.httpTimeoutMillis
        into[KEY_USER_AGENT] = config.userAgent
        into[KEY_KEEP_HISTORY_DAYS] = config.keepLaunchHistoryDays
        into[KEY_LOG_CAPACITY] = config.logHistoryCapacity
        into[KEY_REQUIRE_VERIFIED_RUNTIME] = config.requireVerifiedRuntime
        into[KEY_SCHEMA_VERSION] = config.schemaVersion
    }

    companion object {
        const val DEFAULT_STORE_NAME = "xcore_settings"

        private val storeLock = Any()
        private val liveStores = HashMap<String, DataStore<Preferences>>()

        private val KEY_ENVIRONMENT = stringPreferencesKey("config.environment")
        private val KEY_LOG_LEVEL = stringPreferencesKey("config.logLevel")
        private val KEY_RUNTIME_CHANNEL = stringPreferencesKey("config.runtimeChannel")
        private val KEY_STORAGE_ROOT = stringPreferencesKey("config.storageRoot")
        private val KEY_CUSTOM_ROOT_PATH = stringPreferencesKey("config.customRootPath")
        private val KEY_MAX_DOWNLOADS = intPreferencesKey("config.maxConcurrentDownloads")
        private val KEY_HTTP_TIMEOUT = intPreferencesKey("config.httpTimeoutMillis")
        private val KEY_USER_AGENT = stringPreferencesKey("config.userAgent")
        private val KEY_KEEP_HISTORY_DAYS = intPreferencesKey("config.keepLaunchHistoryDays")
        private val KEY_LOG_CAPACITY = intPreferencesKey("config.logHistoryCapacity")
        private val KEY_REQUIRE_VERIFIED_RUNTIME = booleanPreferencesKey("config.requireVerifiedRuntime")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("config.schemaVersion")

        private val KEY_SELECTED_INSTANCE = stringPreferencesKey("app.selectedInstanceId")
        private val KEY_LAST_LAUNCH_MILLIS = longPreferencesKey("app.lastLaunchMillis")
        private val KEY_ONBOARDING_ACK = booleanPreferencesKey("app.onboardingAcknowledged")

        private val CONFIG_KEYS: List<Preferences.Key<*>> = listOf(
            KEY_ENVIRONMENT,
            KEY_LOG_LEVEL,
            KEY_RUNTIME_CHANNEL,
            KEY_STORAGE_ROOT,
            KEY_CUSTOM_ROOT_PATH,
            KEY_MAX_DOWNLOADS,
            KEY_HTTP_TIMEOUT,
            KEY_USER_AGENT,
            KEY_KEEP_HISTORY_DAYS,
            KEY_LOG_CAPACITY,
            KEY_REQUIRE_VERIFIED_RUNTIME,
            KEY_SCHEMA_VERSION,
        )

        /**
         * Returns a facade over a process-wide single [DataStore] per file name. DataStore throws
         * when two instances share a file, so creation is memoised instead of repeated per call site.
         */
        fun create(
            context: Context,
            storeName: String = DEFAULT_STORE_NAME,
        ): XCorePreferences {
            val store = synchronized(storeLock) {
                liveStores.getOrPut(storeName) {
                    PreferenceDataStoreFactory.create(
                        produceFile = {
                            File(context.applicationContext.filesDir, "datastore/$storeName.preferences_pb")
                        },
                    )
                }
            }
            return XCorePreferences(store)
        }
    }
}
