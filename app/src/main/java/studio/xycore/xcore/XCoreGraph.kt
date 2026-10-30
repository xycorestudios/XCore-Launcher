package studio.xycore.xcore

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreEngine
import studio.xycore.xcore.core.XCoreEngineReport
import studio.xycore.xcore.core.XCoreEngineState
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreEventBus
import studio.xycore.xcore.core.XCoreFlowEventBus
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogSink
import studio.xycore.xcore.core.XCoreMemoryLogger
import studio.xycore.xcore.core.XCoreModuleStatus
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreTaskHandle
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreDatabase
import studio.xycore.xcore.data.XCorePaths
import studio.xycore.xcore.data.XCorePreferences
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.domain.XCoreCapabilityReport
import studio.xycore.xcore.domain.XCoreConfigUseCases
import studio.xycore.xcore.domain.XCoreDiagnosticsUseCases
import studio.xycore.xcore.domain.XCoreInstanceUseCases
import studio.xycore.xcore.domain.XCoreLauncherModules
import studio.xycore.xcore.domain.XCoreLaunchUseCases
import studio.xycore.xcore.domain.XCoreReadinessModule
import studio.xycore.xcore.domain.XCoreRepositories
import studio.xycore.xcore.domain.XCoreRuntimeUseCases
import studio.xycore.xcore.domain.XCoreVersionUseCases
import studio.xycore.xcore.services.XCoreServiceRegistry
import studio.xycore.xcore.services.XCoreTaskManager

/**
 * Manual dependency graph for the launcher.
 *
 * Construction order is the layering: logging and events first, then the storage and metadata
 * primitives, then the repositories over them, then the use cases, and the engine last because it
 * holds all of them. Nothing here is lazy or reflective, so a missing dependency is a compile error
 * rather than a runtime surprise.
 */
class XCoreGraph(context: Context) {

    private val appContext = context.applicationContext

    private val debuggable =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Forwards retained records to logcat; `:core` itself has no Android dependency. */
    private val logcatSink = XCoreLogSink { record ->
        val tag = "XCore/${record.tag}"
        val line = record.message + (record.error?.let { " <- ${Log.getStackTraceString(it)}" } ?: "")
        when (record.level) {
            XCoreLogLevel.VERBOSE -> Log.v(tag, line)
            XCoreLogLevel.DEBUG -> Log.d(tag, line)
            XCoreLogLevel.INFO -> Log.i(tag, line)
            XCoreLogLevel.WARN -> Log.w(tag, line)
            XCoreLogLevel.ERROR -> Log.e(tag, line)
            XCoreLogLevel.NONE -> Unit
        }
    }

    val logger: XCoreMemoryLogger = XCoreMemoryLogger(
        minimumLevel = if (debuggable) XCoreLogLevel.DEBUG else XCoreLogLevel.INFO,
        capacity = 4_000,
        sinks = listOf(logcatSink),
    )

    val events: XCoreEventBus = XCoreFlowEventBus(historyCapacity = 400)

    val services: XCoreServiceRegistry = XCoreServiceRegistry()

    val tasks: XCoreTaskManager = XCoreTaskManager(events = events, logger = logger)

    val preferences: XCorePreferences = XCorePreferences.create(appContext)

    val paths: XCorePaths = XCorePaths(appContext, logger)

    /**
     * Metadata lives in the app's private `databases/` directory, which Android creates and removes
     * on uninstall. The game tree is separate and goes where [XCorePaths] resolves it, so the large
     * files can live on the external app-private root without moving metadata with them.
     */
    val database: XCoreDatabase = XCoreDatabase(appContext, logger = logger)

    val repositories: XCoreRepositories = XCoreRepositories(database)

    val engine: XCoreEngine = XCoreEngine(
        services = services,
        tasks = tasks,
        events = events,
        logger = logger,
        configStore = preferences,
    )

    val instances: XCoreInstanceUseCases = XCoreInstanceUseCases(
        repositories = repositories,
        paths = paths,
        services = services,
        events = events,
        logger = logger,
        preferences = preferences,
    )

    val versions: XCoreVersionUseCases = XCoreVersionUseCases(repositories)

    val runtimes: XCoreRuntimeUseCases = XCoreRuntimeUseCases(
        repositories = repositories,
        paths = paths,
        services = services,
        events = events,
        logger = logger,
    )

    val launches: XCoreLaunchUseCases = XCoreLaunchUseCases(
        repositories = repositories,
        runtimes = runtimes,
        paths = paths,
        services = services,
        preferences = preferences,
    )

    val configUseCases: XCoreConfigUseCases = XCoreConfigUseCases(preferences)

    val diagnostics: XCoreDiagnosticsUseCases = XCoreDiagnosticsUseCases(
        engine = engine,
        repositories = repositories,
        services = services,
        logger = logger,
        events = events,
    )

    /** Modules are registered once, before the engine can start; a rejection stays visible. */
    val moduleInstall: XCoreResult<Unit> = XCoreLauncherModules.install(
        engine = engine,
        modules = XCoreLauncherModules.foundation(paths, database, repositories),
    )

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val problem = MutableStateFlow<XCoreError?>(moduleInstall.errorOrNull())
    private val startReport = MutableStateFlow<XCoreEngineReport?>(null)
    private val eventLog = MutableStateFlow<List<XCoreEvent>>(emptyList())

    val engineState: StateFlow<XCoreEngineState> = engine.state
    val moduleStatuses: StateFlow<List<XCoreModuleStatus>> = engine.moduleStatuses
    val config: StateFlow<XCoreConfig> = engine.config
    private val taskSnapshot = MutableStateFlow<List<XCoreTaskHandle<*>>>(emptyList())
    val taskHandles: StateFlow<List<XCoreTaskHandle<*>>> = taskSnapshot.asStateFlow()
    val engineProblem: StateFlow<XCoreError?> = problem.asStateFlow()
    val lastStartReport: StateFlow<XCoreEngineReport?> = startReport.asStateFlow()
    val recentEvents: StateFlow<List<XCoreEvent>> = eventLog.asStateFlow()

    init {
        // The task controller publishes a plain Flow, so the latest list is mirrored into a
        // StateFlow the UI can read without re-subscribing on every recomposition.
        scope.launch {
            tasks.tasks.collect { handles -> taskSnapshot.value = handles }
        }
    }

    fun storageLayout(): XCoreStorageLayout? = services.find(XCoreStorageLayout::class)

    fun capabilityReport(): XCoreCapabilityReport = XCoreReadinessModule.report(services)

    fun refreshEvents() {
        eventLog.value = events.recent(limit = 24)
    }

    fun startEngine() {
        scope.launch {
            if (moduleInstall !is XCoreResult.Success) {
                problem.value = moduleInstall.errorOrNull()
                return@launch
            }
            val outcome = engine.start()
            problem.value = outcome.errorOrNull()
            startReport.value = outcome.valueOrNull()
            refreshEvents()
        }
    }

    fun shutdown() {
        scope.launch {
            val engineOutcome = engine.shutdown()
            val registryOutcome = services.closeAll()
            problem.value = engineOutcome.errorOrNull() ?: registryOutcome.errorOrNull()
            refreshEvents()
        }
    }

    /** Runs a suspending read on the graph's scope and hands the typed result back to the UI. */
    fun <T> run(
        block: suspend () -> XCoreResult<T>,
        onResult: (XCoreResult<T>) -> Unit,
    ) {
        scope.launch { onResult(block()) }
    }
}

/**
 * Holds the graph for the life of the process, so rotation does not reopen the database or register
 * the same services twice.
 */
class XCoreApplication : Application() {

    private var createdGraph: XCoreGraph? = null

    val graph: XCoreGraph get() = createdGraph ?: error("XCoreApplication.onCreate has not run")

    override fun onCreate() {
        super.onCreate()
        val graph = XCoreGraph(this)
        createdGraph = graph
        graph.startEngine()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        createdGraph?.logger?.log(
            XCoreLogLevel.WARN,
            XCoreLogLevel.WARN.label,
            "system reported low memory; game tree downloads must not start while under pressure",
        )
    }
}
