package studio.xycore.xcore.domain

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreConfigStore
import studio.xycore.xcore.core.XCoreEngine
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreEventBus
import studio.xycore.xcore.core.XCoreInstanceChange
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreMemoryLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeFailureReason
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.XCoreServiceIds
import studio.xycore.xcore.core.XCoreServiceLocator
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.fold
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.onFailure
import studio.xycore.xcore.core.onSuccess
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRun
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstance
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreLoaderKind
import studio.xycore.xcore.data.XCoreLaunchRecord
import studio.xycore.xcore.data.XCorePaths
import studio.xycore.xcore.data.XCorePreferences
import studio.xycore.xcore.data.XCoreRuntimeComponent
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreVersionRef

/**
 * Installations: create, list, select, remove.
 *
 * A row and its directory are made together, and a row the database rejects is rolled back instead
 * of being left behind as an instance that can never launch.
 */
class XCoreInstanceUseCases(
    private val repositories: XCoreRepositories,
    private val paths: XCorePaths,
    private val services: XCoreServiceLocator,
    private val events: XCoreEventBus,
    private val logger: XCoreLogger,
    private val preferences: XCorePreferences? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val storageLayout: XCoreResult<XCoreStorageLayout>
        get() = services.require(XCoreStorageLayout::class)

    suspend fun create(
        name: String,
        versionId: String,
        loader: XCoreLoaderKind,
        jvmArguments: String = "",
        gameArguments: String = "",
    ): XCoreResult<XCoreInstance> = xCoreRun("instance.create", XCoreErrorKind.STORAGE) {
        val layout = storageLayout.getOrThrow()
        val now = clock()
        val id = XCoreInstance.newId()
        val draft = XCoreInstance(
            id = id,
            name = name.trim(),
            versionId = versionId,
            loader = loader,
            status = XCoreInstanceStatus.ADDED,
            gameDirectoryRelative = XCoreInstance.directoryFor(id),
            jvmArguments = jvmArguments,
            gameArguments = gameArguments,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        // Validate before touching the filesystem so a rejected name cannot leave an empty directory.
        val valid = draft.validate().getOrThrow()
        val directory = paths.instanceDirectory(layout, valid.id).getOrThrow()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create instance directory ${directory.absolutePath}")
        }
        val saved = repositories.instances.save(valid)
            .onFailure {
                // The row is gone, so the directory is the only thing left to clean up.
                val cleaned = runCatching { deleteTree(directory) }.isSuccess
                logger.log(
                    XCoreLogLevel.ERROR,
                    XCoreLogTags.USE_CASE,
                    "instance '${valid.id}' rejected; directory cleanup ${if (cleaned) "done" else "FAILED"}",
                    it.cause,
                )
            }
            .getOrThrow()
        events.publish(XCoreEvent.InstanceChanged(clock(), valid.id, XCoreInstanceChange.CREATED))
        saved
    }

    suspend fun list(): XCoreResult<List<XCoreInstance>> = repositories.instances.list()

    suspend fun find(id: String): XCoreResult<XCoreInstance?> = repositories.instances.find(id)

    suspend fun select(id: String?): XCoreResult<XCoreInstance?> {
        val store = preferences
            ?: return XCoreError.Configuration("no preference store is wired, so selection cannot persist").asXCoreFailure()
        if (id != null) {
            val existing = repositories.instances.find(id).getOrThrow()
                ?: return XCoreError.Database(detail = "cannot select unknown instance '$id'").asXCoreFailure()
            return store.selectInstance(id).map { existing }.onSuccess { instance, _ ->
                events.publish(
                    XCoreEvent.InstanceChanged(clock(), instance.id, XCoreInstanceChange.SELECTED),
                )
            }
        }
        return store.selectInstance(null).map { null }
    }

    suspend fun selected(): XCoreResult<XCoreInstance?> {
        val store = preferences
            ?: return XCoreError.Configuration("no preference store is wired, so selection cannot be read").asXCoreFailure()
        val id = store.selectedInstanceIdNow() ?: return null.asXCoreSuccess()
        return repositories.instances.find(id)
    }

    /**
     * Deletes the metadata row, then the instance directory when asked. The result states exactly
     * which of the two happened: a directory that survives a delete is reported, not hidden.
     */
    suspend fun delete(
        id: String,
        removeFiles: Boolean,
    ): XCoreResult<XCoreInstanceDeletion> = xCoreRun("instance.delete", XCoreErrorKind.STORAGE) {
        val instance = repositories.instances.find(id).getOrThrow()
            ?: failXCore(XCoreError.Database(detail = "no instance row with id '$id'"))
        val rowOutcome = repositories.instances.delete(id)
        if (rowOutcome is XCoreResult.Failure) failXCore(rowOutcome.error)
        events.publish(XCoreEvent.InstanceChanged(clock(), id, XCoreInstanceChange.DELETED))

        if (!removeFiles) {
            return@xCoreRun XCoreInstanceDeletion(instance, rowRemoved = true, filesRemoved = 0L, path = null, error = null)
        }
        val layout = storageLayout.valueOrNull()
            ?: return@xCoreRun XCoreInstanceDeletion(
                instance,
                rowRemoved = true,
                filesRemoved = 0L,
                path = null,
                error = rowOutcome.errorOrNull() ?: XCoreError.Configuration(
                    "instance row is gone but the storage layout is not prepared, so no files were removed",
                ),
            )
        // Both resolutions refuse to leave the prepared root, so a stored path cannot delete /data.
        val target = paths.resolveWithin(layout, instance.gameDirectoryRelative).getOrThrow()
        val removal = xCoreRun("instance.delete.files", XCoreErrorKind.STORAGE) { withContext(io) { deleteTree(target) } }
        when (removal) {
            is XCoreResult.Success -> XCoreInstanceDeletion(
                instance,
                rowRemoved = true,
                filesRemoved = removal.value,
                path = target.absolutePath,
                error = null,
            )
            is XCoreResult.Failure -> XCoreInstanceDeletion(
                instance,
                rowRemoved = true,
                filesRemoved = 0L,
                path = target.absolutePath,
                error = removal.error,
            )
            is XCoreResult.Cancelled -> XCoreInstanceDeletion(
                instance,
                rowRemoved = true,
                filesRemoved = 0L,
                path = target.absolutePath,
                error = XCoreError.Cancelled("instance directory removal was cancelled", operation = "instance.delete.files"),
            )
        }
    }

    suspend fun setStatus(
        id: String,
        status: XCoreInstanceStatus,
    ): XCoreResult<XCoreInstance> = repositories.instances.setStatus(id, status).onSuccess { instance, _ ->
        events.publish(
            XCoreEvent.InstanceChanged(clock(), instance.id, XCoreInstanceChange.UPDATED),
        )
    }

    /** Asks the filesystem what is really in the instance directory; nothing here is inferred. */
    suspend fun inspectDirectory(id: String): XCoreResult<XCoreInstanceDirectory> =
        xCoreRun("instance.inspect", XCoreErrorKind.STORAGE) {
            val instance = repositories.instances.find(id).getOrThrow()
                ?: failXCore(XCoreError.Database(detail = "no instance row with id '$id'"))
            val layout = storageLayout.getOrThrow()
            val directory = paths.instanceDirectory(layout, instance.id).getOrThrow()
            val present = directory.isDirectory
            XCoreInstanceDirectory(
                instanceId = id,
                path = directory.absolutePath,
                exists = present,
                entries = if (present) directory.list()?.size ?: 0 else 0,
                bytesUsed = if (present) directory.totalBytes() else 0L,
                writable = directory.canWrite(),
            )
        }
}

data class XCoreInstanceDeletion(
    val instance: XCoreInstance,
    val rowRemoved: Boolean,
    val filesRemoved: Long,
    val path: String?,
    val error: XCoreError?,
) {
    val filesRemovedCleanly: Boolean get() = path != null && error == null
}

data class XCoreInstanceDirectory(
    val instanceId: String,
    val path: String,
    val exists: Boolean,
    val entries: Int,
    val bytesUsed: Long,
    val writable: Boolean,
)

/** Version metadata. Populating these rows honestly requires the manifest reader (Phase 2). */
class XCoreVersionUseCases(
    private val repositories: XCoreRepositories,
) {

    suspend fun register(version: XCoreVersionRef): XCoreResult<XCoreVersionRef> =
        repositories.versions.save(version)

    suspend fun registerAll(versions: List<XCoreVersionRef>): XCoreResult<List<XCoreVersionRef>> =
        repositories.versions.saveAll(versions)

    suspend fun list(
        installedOnly: Boolean,
        limit: Int = 500,
    ): XCoreResult<List<XCoreVersionRef>> = if (installedOnly) {
        repositories.versions.listInstalled(limit)
    } else {
        repositories.versions.listAll(limit)
    }

    suspend fun find(id: String): XCoreResult<XCoreVersionRef?> = repositories.versions.find(id)

    suspend fun setInstalled(
        id: String,
        installed: Boolean,
    ): XCoreResult<XCoreVersionRef> = repositories.versions.setInstalled(id, installed)

    /**
     * A profile that inherits from another cannot launch on its own. Reporting an unresolved parent
     * is a domain rule, so it lives here rather than in the UI.
     */
    suspend fun unresolvedInheritance(id: String): XCoreResult<String?> =
        repositories.versions.find(id).map { version ->
            val parent = version?.inheritsFrom ?: return@map null
            if (parent.isBlank()) parent else null
        }
}

/**
 * Bookkeeping for the externally supplied Java runtime.
 *
 * [verifyOnDisk] proves a recorded archive exists, matches its recorded length and matches its
 * recorded SHA-1. That is a digest check, not an authenticity check:
 * [XCoreRuntimeComponent.signatureVerified] is only ever written by a real signature verifier, and
 * a digest match leaves it exactly as it was.
 */
class XCoreRuntimeUseCases(
    private val repositories: XCoreRepositories,
    private val paths: XCorePaths,
    private val services: XCoreServiceLocator,
    private val events: XCoreEventBus,
    private val logger: XCoreLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun record(component: XCoreRuntimeComponent): XCoreResult<XCoreRuntimeComponent> =
        repositories.runtimes.save(component)

    suspend fun list(): XCoreResult<List<XCoreRuntimeComponent>> = repositories.runtimes.list()

    suspend fun find(id: String): XCoreResult<XCoreRuntimeComponent?> = repositories.runtimes.find(id)

    suspend fun usable(
        abi: String,
        minimumJavaVersion: Int,
        requireVerified: Boolean,
    ): XCoreResult<List<XCoreRuntimeComponent>> =
        repositories.runtimes.usable(abi, minimumJavaVersion, requireVerified)

    /**
     * Picks the smallest recorded runtime that satisfies [version] and confirms its archive is on
     * disk. A row marked READY whose file has vanished is downgraded to
     * [XCoreRuntimeState.BROKEN] before the failure is returned.
     */
    suspend fun resolveFor(
        version: XCoreVersionRef,
        abi: String,
        requireVerified: Boolean,
    ): XCoreResult<XCoreRuntimeComponent> = xCoreRun("runtime.resolve", XCoreErrorKind.RUNTIME) {
        val candidates = repositories.runtimes.usable(abi, version.javaVersion, requireVerified).getOrThrow()
        if (candidates.isEmpty()) {
            failXCore(
                XCoreError.Runtime(
                    detail = "no recorded runtime for $abi satisfies Java ${version.javaVersion}",
                    reason = XCoreRuntimeFailureReason.MISSING,
                    requiredJavaVersion = version.javaVersion,
                ),
            )
        }
        val layout = services.require(XCoreStorageLayout::class).getOrThrow()
        var lastProblem: XCoreError? = null
        for (candidate in candidates) {
            val check = checkPresent(candidate, layout)
            val problem = check.errorOrNull()
            if (problem == null) return@xCoreRun candidate
            lastProblem = problem
            logger.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "runtime '${candidate.id}' unusable: ${problem.format()}",
            )
            repositories.runtimes.setState(candidate.id, XCoreRuntimeState.BROKEN, lastErrorCode = problem.kind.numericCode)
        }
        failXCore(
            lastProblem ?: XCoreError.Runtime(
                detail = "every recorded runtime for $abi failed its on-disk check",
                reason = XCoreRuntimeFailureReason.MISSING,
                requiredJavaVersion = version.javaVersion,
            ),
        )
    }

    /** Re-checks one archive and stores the state the filesystem actually supports. */
    suspend fun verifyOnDisk(id: String): XCoreResult<XCoreRuntimeVerification> =
        xCoreRun("runtime.verify", XCoreErrorKind.RUNTIME) {
            val component = repositories.runtimes.find(id).getOrThrow()
                ?: failXCore(XCoreError.Database(detail = "no runtime row with id '$id'"))
            val layout = services.require(XCoreStorageLayout::class).getOrThrow()
            val check = checkPresent(component, layout)
            val problem = check.errorOrNull()
            val next = if (problem == null) XCoreRuntimeState.READY else XCoreRuntimeState.BROKEN
            val stored = repositories.runtimes
                .setState(
                    id = id,
                    state = next,
                    verifiedAtMillis = if (problem == null) clock() else null,
                    lastErrorCode = problem?.kind?.numericCode,
                )
                .getOrThrow()
            events.publish(
                XCoreEvent.RuntimeComponentChanged(
                    timestampMillis = clock(),
                    componentId = id,
                    previous = component.state,
                    current = next,
                ),
            )
            XCoreRuntimeVerification(stored, problem)
        }

    private suspend fun checkPresent(
        component: XCoreRuntimeComponent,
        layout: XCoreStorageLayout,
    ): XCoreResult<XCoreRuntimeComponent> = xCoreRun("runtime.check", XCoreErrorKind.RUNTIME) {
        val file = paths.resolveWithin(layout, component.relativePath).getOrThrow()
        if (!file.isFile) {
            failXCore(
                XCoreError.Runtime(
                    detail = "runtime archive is missing at ${file.absolutePath}",
                    reason = XCoreRuntimeFailureReason.MISSING,
                    requiredJavaVersion = component.javaVersion,
                ),
            )
        }
        if (component.sizeBytes > 0L && file.length() != component.sizeBytes) {
            failXCore(
                XCoreError.Runtime(
                    detail = "runtime archive is ${file.length()} bytes, recorded as ${component.sizeBytes}",
                    reason = XCoreRuntimeFailureReason.CORRUPT_ARCHIVE,
                    requiredJavaVersion = component.javaVersion,
                ),
            )
        }
        val expected = component.sha1
        if (expected == null) {
            logger.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "runtime '${component.id}' has no recorded digest; presence and length checked only",
            )
        } else {
            val actual = digestOf(file).getOrThrow()
            if (!actual.equals(expected.value, ignoreCase = true)) {
                failXCore(
                    XCoreError.Runtime(
                        detail = "runtime archive digest is $actual, recorded as ${expected.value}",
                        reason = XCoreRuntimeFailureReason.CHECKSUM_MISMATCH,
                        requiredJavaVersion = component.javaVersion,
                    ),
                )
            }
        }
        component
    }

    companion object {
        /** Streaming SHA-1; the archive is never loaded into memory whole. */
        suspend fun digestOf(
            file: File,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): XCoreResult<String> = withContext(io) {
            xCoreRun("runtime.digest", XCoreErrorKind.STORAGE) {
                val digest = MessageDigest.getInstance("SHA-1")
                FileInputStream(file).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }
}

data class XCoreRuntimeVerification(
    val component: XCoreRuntimeComponent,
    val problem: XCoreError?,
) {
    val passed: Boolean get() = problem == null
}

/**
 * Pre-launch verification and launch bookkeeping.
 *
 * There is no function here that pretends to run Minecraft. [preflight] reports the real state of
 * each precondition and [launch] returns the first capability that has not been built yet.
 */
class XCoreLaunchUseCases(
    private val repositories: XCoreRepositories,
    private val runtimes: XCoreRuntimeUseCases,
    private val paths: XCorePaths,
    private val services: XCoreServiceLocator,
    private val preferences: XCorePreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun preflight(
        instanceId: String,
        abi: String,
        config: XCoreConfig,
    ): XCoreResult<XCorePreflightReport> = xCoreRun("launch.preflight", XCoreErrorKind.LAUNCH) {
        val checks = ArrayList<XCorePreflightCheck>(12)
        val layout = services.require(XCoreStorageLayout::class)
        checks += XCorePreflightCheck("storage", "Storage layout prepared", layout.map { it.plan.rootPath })

        val instanceOutcome = repositories.instances.find(instanceId)
        checks += XCorePreflightCheck(
            id = "instance",
            label = "Instance row present",
            outcome = instanceOutcome.map { found ->
                found?.let { "${it.name} [${it.status.name}] -> ${it.gameDirectoryRelative}" }
                    ?: "no row with id $instanceId"
            },
        )
        val instance = instanceOutcome.valueOrNull() ?: return@xCoreRun XCorePreflightReport(instanceId, abi, checks)

        val versionOutcome = repositories.versions.find(instance.versionId)
        checks += XCorePreflightCheck(
            id = "version",
            label = "Version metadata registered",
            outcome = versionOutcome.map { found ->
                found?.id ?: "no version row for '${instance.versionId}' (Phase 2 writes these)"
            },
        )
        val version = versionOutcome.valueOrNull() ?: return@xCoreRun XCorePreflightReport(instanceId, abi, checks)

        val parent = version.inheritsFrom
        val inheritance = if (parent.isNullOrBlank()) {
            "profile ${version.id} does not inherit from another".asXCoreSuccess()
        } else {
            repositories.versions.find(parent).map { found ->
                if (found == null) {
                    failXCore(
                        XCoreError.Loader(
                            detail = "'${version.id}' inherits from '$parent', which is not registered",
                            loader = instance.loader.manifestId,
                        ),
                    )
                }
                "inherits from $parent"
            }
        }
        checks += XCorePreflightCheck("inheritance", "Inherited profile resolvable", inheritance)

        val clientJar = withContext(io) {
            xCoreRun("launch.clientJar", XCoreErrorKind.STORAGE) {
                val versionsDir = layout.getOrThrow().dir(XCoreGameDirectory.VERSIONS)
                    ?: failXCore(XCoreError.Storage("versions directory is absent from the layout"))
                val jar = File(versionsDir, "${version.id}/${version.id}.jar")
                if (!jar.isFile) {
                    failXCore(
                        XCoreError.Storage(
                            detail = "client jar is absent at ${jar.absolutePath}; fetching it needs the download engine",
                            path = jar.absolutePath,
                        ),
                    )
                }
                "${jar.name} is ${jar.length()} bytes at ${jar.absolutePath}"
            }
        }
        checks += XCorePreflightCheck("clientJar", "Client jar on disk", clientJar)

        val instanceDir = withContext(io) {
            xCoreRun("launch.instanceDir", XCoreErrorKind.STORAGE) {
                val directory = paths.instanceDirectory(layout.getOrThrow(), instance.id).getOrThrow()
                if (!directory.isDirectory) {
                    failXCore(
                        XCoreError.Storage(
                            detail = "instance directory ${directory.absolutePath} does not exist",
                            path = directory.absolutePath,
                        ),
                    )
                }
                if (!directory.canWrite()) {
                    failXCore(
                        XCoreError.Storage(
                            detail = "instance directory ${directory.absolutePath} is not writable",
                            path = directory.absolutePath,
                        ),
                    )
                }
                directory.absolutePath
            }
        }
        checks += XCorePreflightCheck("instanceDir", "Instance directory writable", instanceDir)

        val freeSpace = withContext(io) {
            xCoreRun("launch.freeSpace", XCoreErrorKind.STORAGE) {
                val root = layout.getOrThrow().plan.root
                val bytes = paths.usableBytes(root)
                if (bytes < 0L) failXCore(XCoreError.Storage("could not measure free space on ${root.absolutePath}", path = root.absolutePath))
                if (bytes < MIN_FREE_BYTES) {
                    failXCore(
                        XCoreError.Storage(
                            detail = "$bytes bytes free on the game root; a vanilla install needs at least $MIN_FREE_BYTES",
                            path = root.absolutePath,
                            usableBytes = bytes,
                        ),
                    )
                }
                "$bytes bytes free"
            }
        }
        checks += XCorePreflightCheck("freeSpace", "Free space above floor", freeSpace)

        val runtime = runtimes.resolveFor(version, abi, config.requireVerifiedRuntime)
        checks += XCorePreflightCheck(
            id = "runtime",
            label = "Java runtime for $abi",
            outcome = runtime.map { "${it.id} (Java ${it.javaVersion}) at ${it.relativePath}" },
        )

        XCoreCapability.missingFrom(services).forEach { gap ->
            checks += XCorePreflightCheck(gap.serviceId, gap.displayName, gap.notImplementedError().asXCoreFailure())
        }
        XCorePreflightReport(instanceId, abi, checks)
    }

    /**
     * Launching is not implemented. The failure names the first missing capability and writes no
     * history row, because an attempt that never happened must not appear in the log.
     */
    suspend fun launch(instanceId: String): XCoreResult<Nothing> {
        val gap = XCoreCapability.missingFrom(services).firstOrNull()
        return XCoreError.NotImplemented(
            detail = if (gap == null) {
                "instance '$instanceId' has no missing capability, but the launch pipeline is still not implemented"
            } else {
                "cannot launch instance '$instanceId': ${gap.displayName} is not implemented (${gap.plannedPhase})"
            },
            capability = gap?.serviceId ?: XCoreServiceIds.LAUNCH_PIPELINE,
            plannedPhase = gap?.plannedPhase ?: "Phase 6",
        ).asXCoreFailure()
    }

    /** Bookkeeping for an attempt driven by the real native bridge in a later phase. */
    suspend fun recordStart(instanceId: String): XCoreResult<XCoreLaunchRecord> {
        val instance = repositories.instances.find(instanceId).getOrThrow()
            ?: failXCore(XCoreError.Database(detail = "no instance row with id '$instanceId'"))
        return repositories.launches.start(instance.id, instance.versionId)
    }

    suspend fun recordFinish(
        attemptId: Long,
        exitCode: Int?,
        error: XCoreError?,
        logPathRelative: String?,
    ): XCoreResult<XCoreLaunchRecord> =
        repositories.launches.finish(attemptId, exitCode, error?.kind, logPathRelative).flatMap { record ->
            if (!record.succeeded) {
                record.asXCoreSuccess()
            } else {
                preferences.recordLaunchSuccess(clock()).map { record }
            }
        }

    suspend fun history(limit: Int = 50): XCoreResult<List<XCoreLaunchRecord>> =
        repositories.launches.recent(limit)

    suspend fun historyFor(
        instanceId: String,
        limit: Int = 50,
    ): XCoreResult<List<XCoreLaunchRecord>> = repositories.launches.forInstance(instanceId, limit)

    /** Applies `keepLaunchHistoryDays` to the launch table and finished download rows. */
    suspend fun pruneHistory(config: XCoreConfig): XCoreResult<XCorePruning> =
        xCoreRun("launch.prune", XCoreErrorKind.DATABASE) {
            val cutoff = clock() - config.keepLaunchHistoryDays * MILLIS_PER_DAY
            val launches = repositories.launches.prune(cutoff).getOrThrow()
            val downloads = repositories.downloads.pruneCompleted(cutoff).getOrThrow()
            XCorePruning(cutoff, launches, downloads)
        }

    companion object {
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

data class XCorePruning(
    val cutoffMillis: Long,
    val launchRowsRemoved: Int,
    val downloadRowsRemoved: Int,
)

data class XCorePreflightCheck(
    val id: String,
    val label: String,
    val outcome: XCoreResult<out Any>,
) {
    val passed: Boolean get() = outcome is XCoreResult.Success

    val detail: String
        get() = when (outcome) {
            is XCoreResult.Success -> outcome.value.toString()
            is XCoreResult.Failure -> outcome.error.format()
            is XCoreResult.Cancelled -> "cancelled: ${outcome.reason ?: outcome.operation}"
        }
}

data class XCorePreflightReport(
    val instanceId: String,
    val abi: String,
    val checks: List<XCorePreflightCheck>,
) {
    val blocking: List<XCorePreflightCheck> get() = checks.filterNot { it.passed }

    val ready: Boolean get() = blocking.isEmpty()
}

/** Reads, writes and explains the persisted configuration. */
class XCoreConfigUseCases(
    private val store: XCoreConfigStore,
) {

    suspend fun current(): XCoreResult<XCoreConfig> = store.load()

    suspend fun update(transform: (XCoreConfig) -> XCoreConfig): XCoreResult<XCoreConfig> =
        store.load().flatMap { current ->
            transform(current).validate().flatMap { valid -> store.save(valid).map { valid } }
        }

    suspend fun reset(): XCoreResult<XCoreConfig> = store.reset().flatMap { store.load() }

    /** Which keys differ from what is on disk, so a settings screen shows pending changes only. */
    suspend fun pendingChanges(candidate: XCoreConfig): XCoreResult<Set<String>> =
        store.load().map { current -> candidate.changedKeysAgainst(current) }
}

/** A bug report assembled only from state that exists right now. */
class XCoreDiagnosticsUseCases(
    private val engine: XCoreEngine,
    private val repositories: XCoreRepositories,
    private val services: XCoreServiceLocator,
    private val logger: XCoreLogger,
    private val events: XCoreEventBus,
) {

    suspend fun report(): XCoreResult<String> = xCoreRun("diagnostics.report", XCoreErrorKind.UNKNOWN) {
        val snapshot = engine.snapshot()
        val counts = repositories.rowCountSnapshot()
        buildString {
            appendLine("X-Core diagnostics")
            appendLine("engine ${snapshot.state} v${snapshot.engineVersion} started=${snapshot.startedAtMillis ?: "never"}")
            appendLine("config problem: ${snapshot.configProblem?.format() ?: "none"}")
            appendLine("storage root: ${snapshot.config.storageRoot}")
            appendLine("modules:")
            snapshot.modules.forEach { status ->
                appendLine("  ${status.id}: ${status.state}${status.error?.let { " - ${it.format()}" } ?: ""}")
            }
            appendLine("capabilities:")
            XCoreCapability.entries.forEach { capability ->
                appendLine(
                    "  ${capability.serviceId}: " +
                        if (services.contains(capability.serviceId)) {
                            "installed"
                        } else {
                            "not implemented (${capability.plannedPhase})"
                        },
                )
            }
            appendLine("rows: ${counts.format()}${if (counts.readable) "" else " (some tables unreadable)"}")
            appendLine("database: ${snapshot.config.let { repositories.describeSchema() }}")
            appendLine("active tasks: ${snapshot.activeTasks}")
            appendLine(
                "launch readiness: " +
                    snapshot.launchReadiness.fold({ "ready" }, { it.format() }, { "cancelled: $it" }),
            )
            appendLine("recent events:")
            events.recent(limit = 20).forEach { event -> appendLine("  ${describe(event)}") }
            if (logger is XCoreMemoryLogger) {
                appendLine("log tail:")
                append(logger.exportText(limit = 60))
            }
        }
    }

    private fun describe(event: XCoreEvent): String = when (event) {
        is XCoreEvent.EngineStateChanged ->
            ("engine ${event.previous} -> ${event.current}" + (event.note?.let { " ($it)" } ?: "")).trim()

        is XCoreEvent.ModuleRegistered -> "module ${event.moduleId} requires ${event.requires.ifEmpty { "nothing" }}"
        is XCoreEvent.ModuleStateChanged ->
            ("module ${event.moduleId} ${event.previous} -> ${event.current}" +
                (event.error?.let { " (${it.kind.label})" } ?: "")).trim()

        is XCoreEvent.ConfigReloaded -> "config reloaded from ${event.source}: ${event.changedKeys}"
        is XCoreEvent.StorageProbed ->
            "storage ${event.rootPath} (${event.usableBytes} bytes, fallback=${event.fellBackToInternal})"

        is XCoreEvent.DatabasePrepared -> "database v${event.version} from ${event.migratedFrom ?: "scratch"}"
        is XCoreEvent.TaskStateChanged ->
            ("task ${event.taskName} -> ${event.state}" + (event.error?.let { " (${it.kind.label})" } ?: "")).trim()

        is XCoreEvent.InstanceChanged -> "instance ${event.instanceId} ${event.change}"
        is XCoreEvent.RuntimeComponentChanged -> "runtime ${event.componentId} ${event.previous} -> ${event.current}"
        is XCoreEvent.ErrorRaised -> "error from ${event.origin}: ${event.error.format()}"
    }
}

/** Deletes a tree bottom-up so a non-empty directory is never attempted before its children. */
private fun deleteTree(root: File): Long {
    if (!root.exists()) return 0L
    var removed = 0L
    root.walkBottomUp().forEach { entry ->
        if (!entry.delete() && entry.exists()) {
            throw IOException("could not delete ${entry.absolutePath}")
        }
        removed += 1
    }
    return removed
}

/** Total size of a directory tree; unreadable entries count as zero rather than failing the read. */
private fun File.totalBytes(): Long = walkTopDown().filter { it.isFile }.fold(0L) { total, file ->
    total + if (file.canRead()) file.length() else 0L
}
