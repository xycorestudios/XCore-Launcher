package studio.xycore.xcore.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreEngine
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreFlowEventBus
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreModule
import studio.xycore.xcore.core.XCoreModuleContext
import studio.xycore.xcore.core.XCoreModuleDescriptor
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreServiceIds
import studio.xycore.xcore.core.XCoreServiceLocator
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.xCoreRun
import studio.xycore.xcore.data.XCoreDatabase
import studio.xycore.xcore.data.XCorePaths
import studio.xycore.xcore.data.XCoreStorageLayout

/**
 * The capabilities a launch needs, and the phase that will supply each one.
 *
 * [plannedPhase] is documentation made checkable: the readiness module compares it against what is
 * actually in the service catalog, so a capability that quietly grew an implementation without the
 * catalog noticing (or the reverse) is reported instead of assumed.
 */
enum class XCoreCapability(
    val serviceId: String,
    val displayName: String,
    val plannedPhase: String,
    /** Whether this build contains the code that provides it. */
    val implemented: Boolean,
) {
    STORAGE_LAYOUT(XCoreServiceIds.STORAGE_LAYOUT, "Storage layout", "Phase 1", true),
    VERSION_INDEX(XCoreServiceIds.VERSION_INDEX, "Version manifest reader", "Phase 2", true),
    DOWNLOAD_ENGINE(XCoreServiceIds.DOWNLOAD_ENGINE, "Download engine", "Phase 2", true),
    RUNTIME_PROVISIONER(XCoreServiceIds.RUNTIME_PROVISIONER, "Java runtime provisioner", "Phase 3", true),
    AUTHENTICATION(XCoreServiceIds.AUTHENTICATION, "Microsoft / Mojang authentication", "Phase 4", false),
    NATIVE_BRIDGE(XCoreServiceIds.NATIVE_BRIDGE, "Native bridge (JNI bootstrap)", "Phase 5", false),
    GRAPHICS_SURFACE(XCoreServiceIds.GRAPHICS_SURFACE, "EGL / GLFW graphics surface", "Phase 5", false),
    LAUNCH_PIPELINE(XCoreServiceIds.LAUNCH_PIPELINE, "Launch pipeline", "Phase 6", false),
    ;

    fun notImplementedError(): XCoreError = XCoreError.NotImplemented(
        detail = "$displayName is not implemented; it is scheduled for $plannedPhase",
        capability = serviceId,
        plannedPhase = plannedPhase,
    )

    companion object {
        val IN_LAUNCH_ORDER: List<XCoreCapability> = entries.toList()

        /** Capabilities the catalog does not currently hold, in launch order. */
        fun missingFrom(locator: XCoreServiceLocator): List<XCoreCapability> =
            IN_LAUNCH_ORDER.filterNot { locator.contains(it.serviceId) }

        /**
         * Catalog entries that disagree with [implemented]. A capability marked unimplemented but
         * present in the catalog is a stale table; one marked implemented but absent means its
         * module never came up. Both are worth surfacing rather than hiding.
         */
        fun inconsistencies(locator: XCoreServiceLocator): List<String> = IN_LAUNCH_ORDER.mapNotNull { capability ->
            val present = locator.contains(capability.serviceId)
            when {
                present && !capability.implemented -> "${capability.serviceId} is installed but the capability table says ${capability.plannedPhase}"
                !present && capability.implemented -> "${capability.serviceId} is listed as implemented but is not installed"
                else -> null
            }
        }
    }
}

/** Which capabilities were still missing when the engine finished starting. */
data class XCoreCapabilityReport(
    val missing: List<XCoreCapability>,
    val inconsistencies: List<String>,
    val installedServiceIds: Set<String>,
) {
    val launchable: Boolean get() = missing.isEmpty() && inconsistencies.isEmpty()

    fun summarize(): String = if (missing.isEmpty()) {
        "all launch capabilities installed"
    } else {
        "${missing.size} missing: ${missing.joinToString(", ") { "${it.displayName} (${it.plannedPhase})" }}"
    }
}

/**
 * Resolves and prepares the game tree, then publishes it as the storage capability.
 *
 * The catalog entry only appears once every directory has been created and write-probed, so
 * `storage.layout` means "a usable root exists" rather than "this class was constructed".
 */
class XCoreStorageModule(
    private val paths: XCorePaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XCoreModule {

    override val descriptor: XCoreModuleDescriptor = XCoreModuleDescriptor(
        id = ID,
        displayName = "Storage layout",
    )

    override suspend fun onStart(context: XCoreModuleContext): XCoreResult<Unit> = withContext(io) {
        xCoreRun("module.storage", XCoreErrorKind.STORAGE) {
            val layout = paths.prepare(context.config).getOrThrow()
            context.services
                .contribute(XCoreServiceIds.STORAGE_LAYOUT, layout)
                .getOrThrow()
            context.events.publish(
                XCoreEvent.StorageProbed(
                    timestampMillis = XCoreFlowEventBus.nowMillis(),
                    rootPath = layout.plan.rootPath,
                    usableBytes = layout.plan.usableBytes,
                    fellBackToInternal = layout.plan.usedFallback,
                ),
            )
            context.logger.log(
                XCoreLogLevel.INFO,
                XCoreLogTags.PATHS,
                "game root ${layout.plan.rootPath} ready with ${layout.directories.size} directories" +
                    (layout.plan.reason?.let { " ($it)" } ?: ""),
            )
            Unit
        }
    }

    override suspend fun onStop() {
        // The layout is a value describing directories; there is no handle to release.
    }

    companion object {
        const val ID = "xcore.storage"
    }
}

/**
 * Opens the metadata database and publishes the repository bundle.
 *
 * Requires the storage module because the database file lives under the prepared root, and starting
 * metadata first would silently create a second tree in internal storage.
 */
class XCoreMetadataModule(
    private val database: XCoreDatabase,
    private val repositories: XCoreRepositories,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XCoreModule {

    override val descriptor: XCoreModuleDescriptor = XCoreModuleDescriptor(
        id = ID,
        displayName = "Metadata store",
        requires = setOf(XCoreStorageModule.ID),
    )

    override suspend fun onStart(context: XCoreModuleContext): XCoreResult<Unit> = withContext(io) {
        xCoreRun("module.metadata", XCoreErrorKind.DATABASE) {
            database.prepare().getOrThrow()
            context.services.contribute(SERVICE_ID, repositories).getOrThrow()
            context.events.publish(
                XCoreEvent.DatabasePrepared(
                    timestampMillis = XCoreFlowEventBus.nowMillis(),
                    version = XCoreDatabase.DB_VERSION,
                    migratedFrom = null,
                ),
            )
            context.logger.log(
                XCoreLogLevel.INFO,
                XCoreLogTags.DATABASE,
                "metadata store ready: ${database.describe()}",
            )
            Unit
        }
    }

    /** Closing the helper is real cleanup; a failure here propagates so shutdown stays honest. */
    override suspend fun onStop() {
        database.close()
    }

    companion object {
        const val ID = "xcore.metadata"

        /** Catalog id the repository bundle is published under. */
        const val SERVICE_ID = "metadata.repositories"
    }
}

/**
 * Records which launch capabilities exist.
 *
 * This module performs no launching. Its whole job is to state, from the live catalog, what a
 * launch would need and what is missing, so the UI can show a truthful progress report.
 */
class XCoreReadinessModule : XCoreModule {

    override val descriptor: XCoreModuleDescriptor = XCoreModuleDescriptor(
        id = ID,
        displayName = "Capability readiness",
        requires = setOf(XCoreMetadataModule.ID),
    )

    override suspend fun onStart(context: XCoreModuleContext): XCoreResult<Unit> =
        xCoreRun("module.readiness", XCoreErrorKind.NOT_IMPLEMENTED) {
            val report = report(context.services)
            context.services.contribute(SERVICE_ID, report).getOrThrow()
            context.logger.log(
                if (report.missing.isEmpty()) XCoreLogLevel.INFO else XCoreLogLevel.WARN,
                XCoreLogTags.ENGINE,
                "readiness: ${report.summarize()}",
            )
            report.inconsistencies.forEach { problem ->
                context.logger.log(XCoreLogLevel.ERROR, XCoreLogTags.ENGINE, "capability table mismatch: $problem")
            }
            Unit
        }

    override suspend fun onStop() {
        // Nothing held.
    }

    companion object {
        const val ID = "xcore.readiness"
        const val SERVICE_ID = "launch.capability_report"

        fun report(locator: XCoreServiceLocator): XCoreCapabilityReport = XCoreCapabilityReport(
            missing = XCoreCapability.missingFrom(locator),
            inconsistencies = XCoreCapability.inconsistencies(locator),
            installedServiceIds = locator.identifiers(),
        )
    }
}

/** The modules this build can install into the engine, in dependency order. */
object XCoreLauncherModules {

    /**
     * The modules this build can install into the engine, in dependency order.
     *
     * [extraModules] is where a capability that lives in a module above this one - the Phase 3
     * runtime engine, which depends on `:domain` rather than the reverse - is inserted before the
     * readiness report is taken. Passing nothing keeps the pre-Phase-3 set exactly as it was.
     */
    fun foundation(
        paths: XCorePaths,
        database: XCoreDatabase,
        repositories: XCoreRepositories,
        io: CoroutineDispatcher = Dispatchers.IO,
        logger: XCoreLogger? = null,
        extraModules: List<XCoreModule> = emptyList(),
    ): List<XCoreModule> = listOf(
        XCoreStorageModule(paths, io),
        XCoreMetadataModule(database, repositories, io),
        XCoreInstallationModule(repositories, io, logger),
    ) + extraModules + listOf(
        XCoreReadinessModule(),
    )

    /** Registers [modules] with [engine], returning the first rejection rather than a partial set. */
    fun install(
        engine: XCoreEngine,
        modules: List<XCoreModule>,
    ): XCoreResult<Unit> {
        for (module in modules) {
            when (val outcome = engine.register(module)) {
                is XCoreResult.Success -> Unit
                is XCoreResult.Failure -> return outcome
                is XCoreResult.Cancelled -> return outcome
            }
        }
        return Unit.asXCoreSuccess()
    }
}
