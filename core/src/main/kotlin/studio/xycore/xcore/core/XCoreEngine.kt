package studio.xycore.xcore.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class XCoreEngineState {
    NEW,
    INITIALIZING,
    READY,
    DEGRADED,
    SHUTTING_DOWN,
    SHUTDOWN,
    ;
}

enum class XCoreModuleState {
    PENDING,
    STARTING,
    RUNNING,
    FAILED,
    BLOCKED,
    STOPPED,
    ;
}

data class XCoreModuleDescriptor(
    val id: String,
    val displayName: String,
    /** Ids that must be [XCoreModuleState.RUNNING] before this module is started. */
    val requires: Set<String> = emptySet(),
)

data class XCoreModuleStatus(
    val descriptor: XCoreModuleDescriptor,
    val state: XCoreModuleState,
    val error: XCoreError? = null,
    val elapsed: Duration = Duration.ZERO,
) {
    val id: String get() = descriptor.id
}

/**
 * A launcher subsystem the engine can install, order, start and stop.
 *
 * [onStart] must report honestly: a subsystem that has not been built yet returns
 * [XCoreError.NotImplemented] instead of `Success(Unit)`, which is what keeps a half-wired
 * launcher distinguishable from a working one.
 */
interface XCoreModule {

    val descriptor: XCoreModuleDescriptor

    suspend fun onStart(context: XCoreModuleContext): XCoreResult<Unit>

    /** Best-effort cleanup. Exceptions escaping here are collected, not propagated. */
    suspend fun onStop()
}

/**
 * Orchestrates the launcher's modules: config resolution, dependency-ordered startup, per-module
 * failure isolation, and ordered teardown.
 *
 * It is not a game engine and owns no Minecraft behaviour. Launching is the job of a module that
 * implements the launch capability; with no such module installed [launchReadiness] reports the
 * specific gap rather than pretending a launch is possible.
 */
class XCoreEngine(
    private val services: XCoreServiceCatalog,
    private val tasks: XCoreTaskController,
    private val events: XCoreEventBus,
    private val logger: XCoreLogger,
    private val configStore: XCoreConfigStore,
    private val moduleStartupTimeoutMillis: Long = 15_000L,
    private val moduleStopTimeoutMillis: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val moduleLock = Any()
    private val registered = LinkedHashMap<String, XCoreModule>()
    private val startOrder = ArrayList<String>()

    private val stateFlow = MutableStateFlow(XCoreEngineState.NEW)
    private val statusFlow = MutableStateFlow<List<XCoreModuleStatus>>(emptyList())
    private val configFlow = MutableStateFlow(XCoreConfig.DEFAULT)

    private var engineConfig: XCoreConfig = XCoreConfig.DEFAULT
    private var configProblem: XCoreError? = null
    private var startedAtMillis: Long? = null

    val state: StateFlow<XCoreEngineState> = stateFlow.asStateFlow()

    val moduleStatuses: StateFlow<List<XCoreModuleStatus>> = statusFlow.asStateFlow()

    /** Resolved configuration; [XCoreConfig.DEFAULT] until [start] succeeds. */
    val config: StateFlow<XCoreConfig> = configFlow.asStateFlow()

    val engineVersion: String = ENGINE_VERSION

    fun register(module: XCoreModule): XCoreResult<Unit> {
        val id = module.descriptor.id
        if (id.isBlank()) {
            return XCoreError.Configuration("module descriptor id must not be blank").asXCoreFailure()
        }
        synchronized(moduleLock) {
            if (stateFlow.value != XCoreEngineState.NEW) {
                return XCoreError.Configuration(
                    "module '$id' cannot be installed while engine is ${stateFlow.value}; " +
                        "register modules before start()",
                ).asXCoreFailure()
            }
            if (registered.containsKey(id)) {
                return XCoreError.Service(
                    detail = "module id '$id' is already registered",
                    serviceId = id,
                    duplicate = true,
                ).asXCoreFailure()
            }
            registered[id] = module
            statusFlow.value = statusFlow.value + XCoreModuleStatus(
                descriptor = module.descriptor,
                state = XCoreModuleState.PENDING,
            )
        }
        logger.log(
            XCoreLogLevel.INFO,
            XCoreLogTags.MODULES,
            "registered module '$id' (${module.descriptor.requires.joinToString()
                .ifBlank { "no dependencies" }})",
        )
        events.publish(
            XCoreEvent.ModuleRegistered(clock(), id, module.descriptor.requires),
        )
        return Unit.asXCoreSuccess()
    }

    /**
     * Resolves configuration, orders modules by declared dependencies and starts each one.
     *
     * One module failing does not abort the others: the engine ends in
     * [XCoreEngineState.DEGRADED], the report lists every failure, and modules that depend on a
     * failed module are left [XCoreModuleState.BLOCKED].
     */
    suspend fun start(): XCoreResult<XCoreEngineReport> {
        val previous = stateFlow.value
        if (previous != XCoreEngineState.NEW) {
            return XCoreError.Configuration("start() called while engine is $previous").asXCoreFailure()
        }
        stateFlow.value = XCoreEngineState.INITIALIZING
        events.publish(XCoreEvent.EngineStateChanged(clock(), previous, XCoreEngineState.INITIALIZING))

        val mark = TimeSource.Monotonic.markNow()
        val (resolved, configError) = XCoreConfigResolver(configStore).resolveOrDefaults()
        engineConfig = resolved
        configProblem = configError
        configFlow.value = resolved
        if (configError != null) {
            logger.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.CONFIG,
                "configuration fell back to defaults: ${configError.format()}",
            )
        } else {
            logger.log(XCoreLogLevel.DEBUG, XCoreLogTags.CONFIG, "configuration resolved from store")
        }

        val modulesSnapshot = synchronized(moduleLock) { registered.values.toList() }
        when (val ordered = orderModules(modulesSnapshot)) {
            is XCoreResult.Failure -> {
                finishDegraded(started = emptyList(), failed = emptyList(), blocked = emptyList())
                logger.log(XCoreLogLevel.ERROR, XCoreLogTags.ENGINE, "module graph rejected: ${ordered.error.format()}")
                return ordered
            }
            is XCoreResult.Cancelled -> {
                finishDegraded(started = emptyList(), failed = emptyList(), blocked = emptyList())
                return ordered
            }
            is XCoreResult.Success -> {
                val context = XCoreEngineContext(
                    config = resolved,
                    services = services,
                    tasks = tasks,
                    events = events,
                    logger = logger,
                )
                val started = ArrayList<String>()
                val failed = ArrayList<XCoreModuleStatus>()
                val blocked = LinkedHashSet<String>()

                for (module in ordered.value) {
                    val id = module.descriptor.id
                    val unmet = module.descriptor.requires.filter { it in blocked || statusOf(id = it)?.state != XCoreModuleState.RUNNING }
                    if (unmet.isNotEmpty()) {
                        blocked += id
                        setStatus(id, XCoreModuleState.BLOCKED, XCoreError.Configuration(
                            "dependency not running: ${unmet.joinToString()}",
                        ))
                        logger.log(XCoreLogLevel.WARN, XCoreLogTags.MODULES, "'$id' blocked by ${unmet.joinToString()}")
                        continue
                    }

                    setStatus(id, XCoreModuleState.STARTING)
                    val moduleMark = TimeSource.Monotonic.markNow()
                    val outcome = startModule(module, context)
                    val elapsed = moduleMark.elapsedNow()
                    val status = when (outcome) {
                        is XCoreResult.Success -> {
                            started += id
                            XCoreModuleStatus(module.descriptor, XCoreModuleState.RUNNING, elapsed = elapsed)
                        }
                        is XCoreResult.Failure -> {
                            blocked += id
                            failed += XCoreModuleStatus(module.descriptor, XCoreModuleState.FAILED, outcome.error, elapsed)
                            XCoreModuleStatus(module.descriptor, XCoreModuleState.FAILED, outcome.error, elapsed)
                        }
                        is XCoreResult.Cancelled -> {
                            blocked += id
                            val error = XCoreError.Cancelled(
                                detail = "module '$id' start was cancelled: ${outcome.reason ?: "no reason"}",
                                operation = "module.start:$id",
                            )
                            failed += XCoreModuleStatus(module.descriptor, XCoreModuleState.FAILED, error, elapsed)
                            XCoreModuleStatus(module.descriptor, XCoreModuleState.FAILED, error, elapsed)
                        }
                    }
                    setStatus(id, status.state, status.error, status.elapsed)
                }

                synchronized(moduleLock) { startOrder.addAll(started) }

                val elapsed = mark.elapsedNow()
                val finalState = if (failed.isEmpty() && blocked.isEmpty()) {
                    XCoreEngineState.READY
                } else {
                    XCoreEngineState.DEGRADED
                }
                val previousState = stateFlow.value
                stateFlow.value = finalState
                startedAtMillis = clock()
                events.publish(
                    XCoreEvent.EngineStateChanged(
                        timestampMillis = clock(),
                        previous = previousState,
                        current = finalState,
                        note = if (failed.isEmpty()) {
                            null
                        } else {
                            "${failed.size} of ${modulesSnapshot.size} modules failed"
                        },
                    ),
                )
                val report = XCoreEngineReport(
                    state = finalState,
                    startedOrder = started.toList(),
                    failed = failed.toList(),
                    blocked = blocked.toList(),
                    installedModules = modulesSnapshot.size,
                    configProblem = configProblem,
                    elapsed = elapsed,
                )
                logger.log(
                    if (finalState == XCoreEngineState.READY) XCoreLogLevel.INFO else XCoreLogLevel.WARN,
                    XCoreLogTags.ENGINE,
                    "engine ${finalState.name.lowercase()}: ${started.size} started, " +
                        "${failed.size} failed, ${blocked.size} blocked in ${elapsed.inWholeMilliseconds}ms",
                )
                return report.asXCoreSuccess(XCoreOperationMeta(elapsed = elapsed, origin = "engine.start"))
            }
        }
    }

    /** Stops modules in reverse start order, then releases background work. */
    suspend fun shutdown(): XCoreResult<Unit> {
        val previous = stateFlow.value
        if (previous == XCoreEngineState.SHUTDOWN) {
            return XCoreError.Configuration("engine is already shut down").asXCoreFailure()
        }
        stateFlow.value = XCoreEngineState.SHUTTING_DOWN
        events.publish(XCoreEvent.EngineStateChanged(clock(), previous, XCoreEngineState.SHUTTING_DOWN))

        val order = synchronized(moduleLock) { startOrder.reversed().toList() }
        val cleanupErrors = ArrayList<XCoreError>()
        for (id in order) {
            val module = synchronized(moduleLock) { registered[id] } ?: continue
            // A clean stop and a timed-out stop must stay distinguishable, so the attempt is
            // wrapped instead of relying on a null return.
            val attempt = withTimeoutOrNull(moduleStopTimeoutMillis) {
                val thrown: XCoreError? = try {
                    module.onStop()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    XCoreError.fromThrowable(throwable, XCoreErrorKind.CONFIGURATION, "onStop of '$id' threw")
                }
                XCoreStopAttempt(thrown)
            }
            if (attempt == null) {
                cleanupErrors += XCoreError.Unexpected(
                    "module '$id' did not stop within ${moduleStopTimeoutMillis}ms",
                )
            } else {
                attempt.error?.let { cleanupErrors += it }
            }
            setStatus(id, XCoreModuleState.STOPPED)
        }

        val tasksStopped = tasks.shutdown()
        stateFlow.value = XCoreEngineState.SHUTDOWN
        events.publish(XCoreEvent.EngineStateChanged(clock(), XCoreEngineState.SHUTTING_DOWN, XCoreEngineState.SHUTDOWN))
        logger.log(XCoreLogLevel.INFO, XCoreLogTags.ENGINE, "engine shut down (${order.size} modules stopped)")

        val failures = ArrayList(cleanupErrors)
        if (tasksStopped is XCoreResult.Failure) failures += tasksStopped.error
        return if (failures.isEmpty()) {
            Unit.asXCoreSuccess()
        } else {
            XCoreError.Unexpected(
                detail = "shutdown reported ${failures.size} problem(s): ${failures.joinToString(" | ") { it.format() }}",
                cause = failures.firstOrNull()?.cause,
            ).asXCoreFailure()
        }
    }

    suspend fun reloadConfig(): XCoreResult<XCoreConfig> {
        val outcome = XCoreConfigResolver(configStore).resolve()
        when (outcome) {
            is XCoreResult.Success -> {
                val changed = outcome.value.changedKeysAgainst(engineConfig)
                engineConfig = outcome.value
                configProblem = null
                configFlow.value = outcome.value
                if (changed.isNotEmpty()) {
                    events.publish(XCoreEvent.ConfigReloaded(clock(), "store", changed))
                }
            }
            is XCoreResult.Failure -> configProblem = outcome.error
            is XCoreResult.Cancelled -> Unit
        }
        return outcome
    }

    /**
     * What a launch would require right now. Purely diagnostic: the engine never attempts to run
     * Minecraft itself, and an unmet capability is reported as its specific typed gap.
     */
    fun launchReadiness(): XCoreResult<Unit> {
        if (stateFlow.value != XCoreEngineState.READY && stateFlow.value != XCoreEngineState.DEGRADED) {
            return XCoreError.Configuration("engine is ${stateFlow.value}; cannot launch").asXCoreFailure()
        }
        val missing = LAUNCH_CAPABILITY_ORDER.filterNot { services.contains(it) }
        if (missing.isNotEmpty()) {
            return XCoreError.NotImplemented(
                detail = "launch needs ${missing.joinToString()} which ${if (missing.size == 1) "is" else "are"} not installed",
                capability = missing.first(),
                plannedPhase = plannedPhaseFor(missing.first()),
            ).asXCoreFailure()
        }
        return Unit.asXCoreSuccess()
    }

    fun statusOf(id: String): XCoreModuleStatus? = statusFlow.value.firstOrNull { it.id == id }

    fun snapshot(): XCoreEngineSnapshot = XCoreEngineSnapshot(
        engineVersion = ENGINE_VERSION,
        state = stateFlow.value,
        modules = statusFlow.value,
        config = engineConfig,
        configProblem = configProblem,
        installedServiceIds = services.identifiers(),
        activeTasks = tasks.activeCount,
        startedAtMillis = startedAtMillis,
        launchReadiness = launchReadiness(),
    )

    private suspend fun startModule(
        module: XCoreModule,
        context: XCoreModuleContext,
    ): XCoreResult<Unit> {
        val id = module.descriptor.id
        val outcome = try {
            withTimeoutOrNull(moduleStartupTimeoutMillis) { module.onStart(context) }
                ?: return XCoreError.Unexpected(
                    "module '$id' did not complete onStart within ${moduleStartupTimeoutMillis}ms",
                ).asXCoreFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return XCoreError.fromThrowable(throwable, XCoreErrorKind.CONFIGURATION, "module '$id' threw during start")
                .asXCoreFailure()
        }
        when (outcome) {
            is XCoreResult.Failure -> logger.log(
                XCoreLogLevel.ERROR,
                XCoreLogTags.MODULES,
                "'$id' failed: ${outcome.error.format()}",
            )
            is XCoreResult.Success -> logger.log(XCoreLogLevel.DEBUG, XCoreLogTags.MODULES, "'$id' running")
            is XCoreResult.Cancelled -> logger.log(XCoreLogLevel.WARN, XCoreLogTags.MODULES, "'$id' cancelled")
        }
        return outcome
    }

    private fun finishDegraded(
        started: List<String>,
        failed: List<XCoreModuleStatus>,
        blocked: List<String>,
    ) {
        stateFlow.value = XCoreEngineState.DEGRADED
        events.publish(
            XCoreEvent.EngineStateChanged(
                timestampMillis = clock(),
                previous = XCoreEngineState.INITIALIZING,
                current = XCoreEngineState.DEGRADED,
                note = "startup aborted: ${started.size} started, ${failed.size} failed, ${blocked.size} blocked",
            ),
        )
    }

    private fun setStatus(
        id: String,
        state: XCoreModuleState,
        error: XCoreError? = null,
        elapsed: Duration = Duration.ZERO,
    ) {
        val descriptor = synchronized(moduleLock) { registered[id]?.descriptor } ?: return
        statusFlow.value = statusFlow.value.map { current ->
            if (current.id == id) {
                current.copy(state = state, error = error ?: if (state == XCoreModuleState.RUNNING) null else current.error, elapsed = elapsed)
            } else {
                current
            }
        }
        events.publish(
            XCoreEvent.ModuleStateChanged(
                timestampMillis = clock(),
                moduleId = id,
                previous = when (state) {
                    XCoreModuleState.STARTING -> XCoreModuleState.PENDING
                    XCoreModuleState.RUNNING -> XCoreModuleState.STARTING
                    else -> XCoreModuleState.PENDING
                },
                current = state,
                error = error,
            ),
        )
    }

    companion object {
        const val ENGINE_VERSION = "0.1.0"

        /**
         * Service ids a launch needs, in the order they must come up. The engine reports whichever
         * of these the catalog is missing, so the gap shrinks as phases land rather than being
         * asserted by a string.
         */
        val LAUNCH_CAPABILITY_ORDER = listOf(
            XCoreServiceIds.STORAGE_LAYOUT,
            XCoreServiceIds.VERSION_INDEX,
            XCoreServiceIds.DOWNLOAD_ENGINE,
            XCoreServiceIds.AUTHENTICATION,
            XCoreServiceIds.RUNTIME_PROVISIONER,
            XCoreServiceIds.NATIVE_BRIDGE,
            XCoreServiceIds.GRAPHICS_SURFACE,
            XCoreServiceIds.LAUNCH_PIPELINE,
        )

        fun plannedPhaseFor(capabilityId: String): String? = when (capabilityId) {
            XCoreServiceIds.STORAGE_LAYOUT -> "Phase 1"
            XCoreServiceIds.VERSION_INDEX -> "Phase 2"
            XCoreServiceIds.DOWNLOAD_ENGINE -> "Phase 2"
            XCoreServiceIds.AUTHENTICATION -> "Phase 4"
            XCoreServiceIds.RUNTIME_PROVISIONER -> "Phase 3"
            XCoreServiceIds.NATIVE_BRIDGE -> "Phase 5"
            XCoreServiceIds.GRAPHICS_SURFACE -> "Phase 5"
            XCoreServiceIds.LAUNCH_PIPELINE -> "Phase 6"
            else -> null
        }

        /**
         * Kahn's algorithm over declared module dependencies, with deterministic tie-breaking by
         * id so startup order is reproducible across devices and test runs.
         */
        internal fun orderModules(modules: List<XCoreModule>): XCoreResult<List<XCoreModule>> {
            if (modules.isEmpty()) return emptyList<XCoreModule>().asXCoreSuccess()

            val byId = LinkedHashMap<String, XCoreModule>()
            for (module in modules) byId[module.descriptor.id] = module

            for (module in modules) {
                val id = module.descriptor.id
                for (dependency in module.descriptor.requires) {
                    if (dependency == id) {
                        return XCoreError.Configuration("module '$id' declares itself as a dependency").asXCoreFailure()
                    }
                    if (!byId.containsKey(dependency)) {
                        return XCoreError.Configuration("module '$id' requires unknown module '$dependency'").asXCoreFailure()
                    }
                }
            }

            val dependents = HashMap<String, MutableList<String>>()
            val remainingCounts = HashMap<String, Int>()
            for (module in modules) {
                remainingCounts[module.descriptor.id] = module.descriptor.requires.size
                for (dependency in module.descriptor.requires) {
                    dependents.getOrPut(dependency) { ArrayList() } += module.descriptor.id
                }
            }

            val ready = sortedSetOf<String>()
            for ((id, count) in remainingCounts) if (count == 0) ready += id

            val ordered = ArrayList<XCoreModule>(modules.size)
            while (ready.isNotEmpty()) {
                val id = ready.first()
                ready.remove(id)
                val module = byId[id] ?: continue
                ordered += module
                for (dependent in dependents[id].orEmpty()) {
                    val left = (remainingCounts[dependent] ?: 0) - 1
                    remainingCounts[dependent] = left
                    if (left == 0) ready += dependent
                }
            }

            if (ordered.size != modules.size) {
                val cycle = byId.keys.filter { candidate -> ordered.none { it.descriptor.id == candidate } }
                return XCoreError.Configuration(
                    "dependency cycle among modules: ${cycle.joinToString()}",
                ).asXCoreFailure()
            }
            return ordered.asXCoreSuccess()
        }
    }
}

/** Result of an engine start attempt, including partial success detail. */
data class XCoreEngineReport(
    val state: XCoreEngineState,
    val startedOrder: List<String>,
    val failed: List<XCoreModuleStatus>,
    val blocked: List<String>,
    val installedModules: Int,
    val configProblem: XCoreError?,
    val elapsed: Duration,
) {
    val healthy: Boolean get() = failed.isEmpty() && blocked.isEmpty() && configProblem == null
}

data class XCoreEngineSnapshot(
    val engineVersion: String,
    val state: XCoreEngineState,
    val modules: List<XCoreModuleStatus>,
    val config: XCoreConfig,
    val configProblem: XCoreError?,
    val installedServiceIds: Set<String>,
    val activeTasks: Int,
    val startedAtMillis: Long?,
    val launchReadiness: XCoreResult<Unit>,
)

internal class XCoreEngineContext(
    override val config: XCoreConfig,
    override val services: XCoreServiceCatalog,
    override val tasks: XCoreTaskController,
    override val events: XCoreEventBus,
    override val logger: XCoreLogger,
) : XCoreModuleContext

/** Wraps a module stop attempt so `null` can keep meaning "timed out". */
internal class XCoreStopAttempt(val error: XCoreError?)
