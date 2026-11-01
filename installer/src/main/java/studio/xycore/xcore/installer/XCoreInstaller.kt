package studio.xycore.xcore.installer

import java.io.File
import java.io.IOException
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreFailureException
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.onFailure
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreVersionRef
import studio.xycore.xcore.versions.XCoreDownloadFile
import studio.xycore.xcore.versions.XCoreLoggingClient
import studio.xycore.xcore.versions.XCoreRuntimeEnvironment
import studio.xycore.xcore.versions.XCoreVersionCatalog
import studio.xycore.xcore.versions.XCoreVersionParser
import studio.xycore.xcore.versions.XCoreVersionProfile
import studio.xycore.xcore.core.valueOrNull

/** The instance row an install is being run for. */
data class XCoreInstallTarget(
    val id: String,
    val name: String,
    val versionId: String,
    val gameDirectoryRelative: String,
)

/**
 * Reads the instance row the install is for, and moves it through its status.
 *
 * Implemented over the launcher's own repository in `:domain`: the installer must not know about
 * SQLite, and the instance table must not know about HTTP.
 */
interface XCoreInstanceAccess {
    suspend fun find(instanceId: String): XCoreResult<XCoreInstallTarget?>

    suspend fun setStatus(
        instanceId: String,
        status: XCoreInstanceStatus,
    ): XCoreResult<XCoreInstallTarget>
}

/**
 * The distribution-terms gate.
 *
 * Minecraft's content belongs to Mojang and is licensed through the EULA, which the player has to
 * accept themselves. X-Core ships no game files: every byte comes from Mojang's endpoints at install
 * time, into this app's private storage, for a user who has accepted those terms. The gate exists so
 * that is recorded rather than assumed, and a missing acknowledgement is a real failure with a real
 * reason - not a checkbox the launcher clicked for you.
 */
interface XCoreDistributionTermsGate {
    suspend fun acknowledged(): XCoreResult<Boolean>

    fun refusal(): XCoreError = XCoreError.Permission(
        detail = "the Minecraft End-User Agreement has not been accepted in this app, so X-Core will " +
            "not fetch game content. Read it at $EULA_URL and accept it in Settings, then install again.",
        permission = PERMISSION_KEY,
    )

    companion object {
        const val EULA_URL = "https://account.mojang.com/documents/minecraft_eula"
        const val PERMISSION_KEY = "app.terms.minecraft_eula"
        const val OWNERSHIP_STATEMENT = "X-Core ships no Minecraft files; Mojang owns the client, assets " +
            "and mappings and serves them from its own endpoints."
    }
}

/** One stage of an install, with the counts that stage really produced. */
data class XCoreInstallStageReport(
    val phase: XCoreInstallPhase,
    val label: String,
    val transferred: Int,
    val alreadyPresent: Int,
    val bytesWritten: Long,
    val bytesAlreadyPresent: Long,
    val failures: List<String>,
) {
    val filesTouched: Int get() = transferred + alreadyPresent

    val bytesAccounted: Long get() = bytesWritten + bytesAlreadyPresent
}

/** What an install did, derived from the per-stage reports rather than from a plan. */
data class XCoreInstallSummary(
    val versionId: String,
    val instanceId: String?,
    val stages: List<XCoreInstallStageReport>,
    val clientJarPath: String,
    val profileJsonPath: String,
    val assetIndexId: String?,
    val assetCount: Int,
    val libraryCount: Int,
    val nativeCount: Int,
    val skippedLibraries: List<XCoreSkippedLibrary>,
    val unverifiedLibraries: List<String>,
    val bytesWritten: Long,
    val bytesAlreadyPresent: Long,
    val elapsedMillis: Long,
) {
    val filesWritten: Int get() = stages.sumOf { it.transferred }

    val filesAlreadyPresent: Int get() = stages.sumOf { it.alreadyPresent }

    /** Files this install touched, whether or not bytes had to move for them. */
    val filesTouched: Int get() = stages.sumOf { it.filesTouched }

    fun describe(): String = stages.joinToString(" -> ") {
        "${it.phase.name.lowercase()}(${it.transferred}+${it.alreadyPresent})"
    }
}

/** What an install would fetch, counted from metadata before a single byte moves. */
data class XCoreInstallPlan(
    val versionId: String,
    val clientTask: XCoreDownloadTask,
    val loggingTask: XCoreDownloadTask?,
    val libraries: XCoreLibraryResolution,
    val assetIndex: XCoreAssetIndex?,
) {
    val assetIndexId: String? get() = assetIndex?.id

    val assetCount: Int get() = assetIndex?.count ?: 0

    val clientBytes: Long get() = clientTask.expectedBytes.coerceAtLeast(0L)

    val libraryBytes: Long get() = libraries.expectedBytes

    val assetBytes: Long get() = assetIndex?.totalBytes ?: 0L

    /** Files the version calls for, including ones that may already be on disk. */
    val fileCount: Int
        get() = 1 + libraries.artifacts.size + assetCount + (if (loggingTask == null) 0 else 1)

    val totalBytes: Long get() = clientBytes + libraryBytes + assetBytes

    val unverifiedLibraries: List<String> get() = libraries.unverified

    val skippedLibraries: List<XCoreSkippedLibrary> get() = libraries.skipped

    fun describe(): String =
        "$versionId: ${libraries.classpathArtifacts.size} libraries + " +
            "${libraries.nativeArtifacts.size} natives, $assetCount assets " +
            "(${xCoreFormatBytes(totalBytes)} declared)"
}

/** What an audit found wrong with an install that claims to be complete. */
data class XCoreInstallAudit(
    val versionId: String,
    val missing: List<String>,
    val mismatched: List<String>,
    val presentBytes: Long,
    val checkedFiles: Int,
    val notes: List<String> = emptyList(),
) {
    val isConsistent: Boolean get() = missing.isEmpty() && mismatched.isEmpty()
}

/** Whether digest-verified objects are also copied into the `assets/virtual/<id>` tree. */
enum class XCoreAssetMode {
    OBJECTS_ONLY,
    VIRTUAL,
}

/** Everything the installer decided before it moved a byte. */
data class ResolvedInstall(
    val version: XCoreVersionRef,
    val profile: XCoreVersionProfile,
    val environment: XCoreRuntimeEnvironment,
    val clientTask: XCoreDownloadTask,
    val loggingTask: XCoreDownloadTask?,
    val libraries: XCoreLibraryResolution,
    val assetIndex: XCoreAssetIndex?,
    val inheritedProfiles: List<XCoreVersionProfile>,
) {
    fun toPlan(): XCoreInstallPlan = XCoreInstallPlan(
        versionId = version.id,
        clientTask = clientTask,
        loggingTask = loggingTask,
        libraries = libraries,
        assetIndex = assetIndex,
    )
}

/** Running totals for one install, used to describe progress without inventing any. */
private class InstallCounters {
    var filesWritten: Int = 0
    var filesAlreadyPresent: Int = 0
    var bytesWritten: Long = 0L
    var bytesAlreadyPresent: Long = 0L

    val filesTouched: Int get() = filesWritten + filesAlreadyPresent

    val bytesAccounted: Long get() = bytesWritten + bytesAlreadyPresent

    fun absorb(report: XCoreInstallStageReport) {
        filesWritten += report.transferred
        filesAlreadyPresent += report.alreadyPresent
        bytesWritten += report.bytesWritten
        bytesAlreadyPresent += report.bytesAlreadyPresent
    }
}

/**
 * Builds the transfer list for a version and drives it to disk.
 *
 * The order is the one that fails usefully: metadata first (so a bad version id costs one request,
 * not a gigabyte), then free-space, then the client jar, then the profile documents, then libraries,
 * then natives, then assets, then verification. Each stage reports what it actually moved, a stage
 * that fails stops the install with that stage's error, and nothing downstream of a failure runs -
 * so a summary is only ever produced from stages that succeeded.
 */
class XCoreInstaller(
    private val catalog: XCoreVersionCatalog,
    private val downloads: XCoreDownloadManager,
    private val assets: XCoreAssetManager,
    private val paths: XCoreInstallPaths,
    private val verifier: XCoreHashVerifier = XCoreHashVerifier(),
    private val tracker: XCoreInstallTracker,
    private val instances: XCoreInstanceAccess,
    private val terms: XCoreDistributionTermsGate,
    private val environmentFor: (String) -> XCoreRuntimeEnvironment,
    private val parser: XCoreVersionParser = XCoreVersionParser(),
    private val logger: XCoreLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrency: Int = XCoreDownloadManager.DEFAULT_CONCURRENCY,
    private val assetMode: XCoreAssetMode = XCoreAssetMode.VIRTUAL,
) {

    /** What installing [versionId] would fetch, counted from live metadata. Writes nothing. */
    suspend fun plan(
        versionId: String,
        abi: String,
    ): XCoreResult<XCoreInstallPlan> = resolve(versionId, abi).map { it.toPlan() }

    /**
     * Installs [versionId] and, when [instanceId] is given, marks that instance ready.
     *
     * Re-running an install is safe: a file that already hashes to what the metadata published is
     * reported as already present and is not fetched again, and the profile JSON is rewritten from
     * the document the metadata served.
     */
    suspend fun install(
        versionId: String,
        abi: String,
        instanceId: String? = null,
        listener: XCoreTransferListener? = null,
    ): XCoreResult<XCoreInstallSummary> {
        when (val accepted = terms.acknowledged()) {
            is XCoreResult.Failure -> return XCoreResult.Failure(accepted.error)
            is XCoreResult.Cancelled -> return XCoreResult.Cancelled(versionId, "the terms check was cancelled")
            is XCoreResult.Success -> if (!accepted.value) return terms.refusal().asXCoreFailure()
        }
        val startedAt = clock()
        val target = when (val looked = lookupTarget(instanceId)) {
            is XCoreResult.Failure -> return XCoreResult.Failure(looked.error)
            is XCoreResult.Cancelled -> return XCoreResult.Cancelled(versionId, "the instance lookup was cancelled")
            is XCoreResult.Success -> looked.value
        }
        val begin = tracker.begin(
            versionId = versionId,
            instanceId = instanceId,
            phase = XCoreInstallPhase.RESOLVING_METADATA,
            label = "metadata for $versionId",
        )
        begin.onFailure { couldNotStore ->
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "install state for '$versionId' could not be opened: ${couldNotStore.detail}; " +
                    "the install continues because the files on disk stay the source of truth",
            )
        }
        return when (val resolved = resolve(versionId, abi)) {
            is XCoreResult.Failure -> {
                recordOutcome(versionId, target, resolved.error, startedAt)
                XCoreResult.Failure(resolved.error)
            }

            is XCoreResult.Cancelled -> {
                recordOutcome(versionId, target, resolved.toError(), startedAt)
                resolved
            }

            is XCoreResult.Success -> runInstall(resolved.value, target, listener, startedAt)
        }
    }

    /**
     * Re-checks what is on disk against what the metadata describes.
     *
     * The client jar is re-hashed; the profile document is re-parsed; libraries and assets are
     * checked for presence and declared length, because every one of them was digest-checked on the
     * way in. A file with no published digest is reported in [XCoreInstallAudit.notes] rather than
     * being counted as verified.
     */
    suspend fun audit(
        versionId: String,
        abi: String,
    ): XCoreResult<XCoreInstallAudit> =
        resolve(versionId, abi, register = false).flatMap { resolved -> auditFromInstall(resolved) }

    /** Moves the instance row to NEEDS_REPAIR when an audit finds drift, READY when it does not. */
    suspend fun reconcileInstanceStatus(
        instanceId: String,
        abi: String,
    ): XCoreResult<XCoreInstallTarget> = instances.find(instanceId).flatMap { found ->
        val target = found
            ?: return XCoreError.Database("no instance row '$instanceId'").asXCoreFailure()
        audit(target.versionId, abi).flatMap { outcome ->
            instances.setStatus(
                instanceId,
                if (outcome.isConsistent) XCoreInstanceStatus.READY else XCoreInstanceStatus.NEEDS_REPAIR,
            )
        }
    }

    /** Drops the durable install record for a version, for when an instance is deleted. */
    suspend fun forget(versionId: String): XCoreResult<Unit> = tracker.forget(versionId)

    private suspend fun lookupTarget(instanceId: String?): XCoreResult<XCoreInstallTarget?> =
        if (instanceId == null) {
            null.asXCoreSuccess<XCoreInstallTarget?>()
        } else {
            instances.find(instanceId).flatMap { found ->
                if (found != null) {
                    found.asXCoreSuccess()
                } else {
                    XCoreError.Database(
                        detail = "no instance row '$instanceId' to install into",
                    ).asXCoreFailure()
                }
            }
        }

    /**
     * Reads metadata and turns it into a transfer list.
     *
     * [register] writes the version row; it is switched off for audits, which must not leave a
     * version looking freshly registered just because somebody checked what it holds.
     */
    private suspend fun resolve(
        versionId: String,
        abi: String,
        register: Boolean = true,
    ): XCoreResult<ResolvedInstall> {
        val environment = environmentFor(abi)
        return catalog.resolveProfile(versionId).flatMap { profile ->
            val client = profile.clientDownload
                ?: return XCoreError.Network(
                    detail = "profile '$versionId' publishes no client download; there is nothing to install",
                    url = profile.sourceUrl,
                    retryable = false,
                ).asXCoreFailure()
            if (profile.mainClass.isNullOrBlank()) {
                return XCoreError.Network(
                    detail = "profile '$versionId' publishes no main class; it cannot be launched",
                    url = profile.sourceUrl,
                    retryable = false,
                ).asXCoreFailure()
            }
            val registration = if (register) {
                catalog.register(versionId)
            } else {
                catalog.registered(versionId).flatMap { existing ->
                    if (existing != null) {
                        existing.asXCoreSuccess()
                    } else {
                        XCoreError.Validation(
                            detail = "'$versionId' is not registered, so it cannot be audited",
                            field = "versionId",
                            rejected = versionId,
                        ).asXCoreFailure()
                    }
                }
            }
            registration.flatMap { ref ->
                XCoreLibraryResolver(environment).resolve(profile.libraries).flatMap { libraries ->
                    ancestorsOf(profile).flatMap { ancestors ->
                        val assetRef = profile.assetIndex
                        if (assetRef == null) {
                            build(
                                ref = ref,
                                profile = profile,
                                environment = environment,
                                client = client,
                                libraries = libraries,
                                assetIndex = null,
                                inheritedProfiles = ancestors,
                            )
                        } else {
                            when (val index = assets.readIndex(assetRef)) {
                                is XCoreResult.Failure -> XCoreResult.Failure(index.error)
                                is XCoreResult.Cancelled -> index
                                is XCoreResult.Success -> build(
                                    ref = ref,
                                    profile = profile,
                                    environment = environment,
                                    client = client,
                                    libraries = libraries,
                                    assetIndex = index.value,
                                    inheritedProfiles = ancestors,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun build(
        ref: XCoreVersionRef,
        profile: XCoreVersionProfile,
        environment: XCoreRuntimeEnvironment,
        client: XCoreDownloadFile,
        libraries: XCoreLibraryResolution,
        assetIndex: XCoreAssetIndex?,
        inheritedProfiles: List<XCoreVersionProfile>,
    ): XCoreResult<ResolvedInstall> = ResolvedInstall(
        version = ref,
        profile = profile,
        environment = environment,
        clientTask = clientTask(ref.id, client.url, client.sha1, client.sizeBytes),
        loggingTask = profile.logging?.let { loggingTask(it) },
        libraries = libraries,
        assetIndex = assetIndex,
        inheritedProfiles = inheritedProfiles,
    ).asXCoreSuccess()

    private suspend fun runInstall(
        resolved: ResolvedInstall,
        target: XCoreInstallTarget?,
        listener: XCoreTransferListener?,
        startedAt: Long,
    ): XCoreResult<XCoreInstallSummary> {
        val versionId = resolved.version.id
        val plan = resolved.toPlan()
        val counters = InstallCounters()
        val stages = ArrayList<XCoreInstallStageReport>()
        if (target != null) {
            instances.setStatus(target.id, XCoreInstanceStatus.INSTALLING).onFailure { couldNotMark ->
                logger?.log(
                    XCoreLogLevel.WARN,
                    XCoreLogTags.USE_CASE,
                    "instance '${target.id}' could not be marked INSTALLING: ${couldNotMark.detail}",
                )
            }
        }
        val steps: List<suspend () -> XCoreResult<XCoreInstallStageReport>> = listOf(
            { spaceStage(resolved, plan) },
            { transferStage(resolved, XCoreInstallPhase.CLIENT_JAR, "client jar", listOf(resolved.clientTask), listener, plan) },
            { documentStage(resolved, plan, listener) },
            {
                transferStage(
                    resolved,
                    XCoreInstallPhase.LIBRARIES,
                    "libraries",
                    resolved.libraries.classpathTasks,
                    listener,
                    plan,
                )
            },
            {
                transferStage(
                    resolved,
                    XCoreInstallPhase.NATIVES,
                    "natives",
                    resolved.libraries.nativeTasks,
                    listener,
                    plan,
                )
            },
            { assetStage(resolved, plan, listener) },
            { verifyStage(resolved) },
        )
        for (step in steps) {
            when (val outcome = step()) {
                is XCoreResult.Success -> {
                    counters.absorb(outcome.value)
                    stages += outcome.value
                    val stored = tracker.progress(
                        versionId = versionId,
                        stage = XCoreStageProgress(
                            completed = counters.filesTouched,
                            total = plan.fileCount,
                            bytesDone = counters.bytesAccounted,
                            bytesTotal = plan.totalBytes,
                        ),
                        label = outcome.value.label,
                        filesWritten = counters.filesWritten,
                        filesSkipped = counters.filesAlreadyPresent,
                        bytesWritten = counters.bytesWritten,
                    )
                    stored.onFailure { couldNotStore ->
                        logger?.log(
                            XCoreLogLevel.WARN,
                            XCoreLogTags.USE_CASE,
                            "progress for '$versionId' could not be stored: ${couldNotStore.detail}",
                        )
                    }
                }

                is XCoreResult.Failure -> {
                    recordOutcome(versionId, target, outcome.error, startedAt)
                    return XCoreResult.Failure(outcome.error)
                }

                is XCoreResult.Cancelled -> {
                    recordOutcome(versionId, target, outcome.toError(), startedAt)
                    return outcome
                }
            }
        }
        return completeInstall(resolved, plan, target, stages, startedAt)
    }

    /** Fails early, with real numbers, when the volume cannot hold this version. */
    private suspend fun spaceStage(
        resolved: ResolvedInstall,
        plan: XCoreInstallPlan,
    ): XCoreResult<XCoreInstallStageReport> {
        advance(resolved.version.id, XCoreInstallPhase.CHECKING_STORAGE, "free space")
        val probe = paths.directory(XCoreGameDirectory.VERSIONS).valueOrNull() ?: paths.root
        val usable = paths.usableBytesAt(probe)
        val required = plan.totalBytes + HEADROOM_BYTES
        if (usable < required) {
            return XCoreError.Storage(
                detail = "'${resolved.version.id}' declares ${xCoreFormatBytes(plan.totalBytes)} of content " +
                    "and needs ${xCoreFormatBytes(required)} free including headroom, but the volume at " +
                    "${probe.absolutePath} reports ${xCoreFormatBytes(usable)}",
                path = probe.absolutePath,
                usableBytes = usable,
                retryable = false,
            ).asXCoreFailure()
        }
        return XCoreInstallStageReport(
            phase = XCoreInstallPhase.CHECKING_STORAGE,
            label = "free space",
            transferred = 0,
            alreadyPresent = 0,
            bytesWritten = 0L,
            bytesAlreadyPresent = 0L,
            failures = emptyList(),
        ).asXCoreSuccess()
    }

    private suspend fun transferStage(
        resolved: ResolvedInstall,
        phase: XCoreInstallPhase,
        label: String,
        tasks: List<XCoreDownloadTask>,
        listener: XCoreTransferListener?,
        plan: XCoreInstallPlan,
    ): XCoreResult<XCoreInstallStageReport> {
        val versionId = resolved.version.id
        if (tasks.isEmpty()) {
            advance(versionId, phase, "$label: none for this device")
            return XCoreInstallStageReport(phase, label, 0, 0, 0L, 0L, emptyList()).asXCoreSuccess()
        }
        return prepareTasks(tasks, label).flatMap { prepared ->
            val stageBytes = prepared.sumOf { it.task.expectedBytes.coerceAtLeast(0L) }
            tracker.advance(
                versionId = versionId,
                phase = phase,
                label = label,
                stage = XCoreStageProgress(
                    completed = 0,
                    total = prepared.size,
                    bytesDone = 0L,
                    bytesTotal = stageBytes,
                ),
            ).onFailure { couldNotStore ->
                logger?.log(
                    XCoreLogLevel.WARN,
                    XCoreLogTags.USE_CASE,
                    "could not record the start of the $label stage: ${couldNotStore.detail}",
                )
            }
            downloads
                .transferAll(
                    prepared,
                    maxConcurrency = maxConcurrency.coerceIn(1, XCoreDownloadManager.MAX_CONCURRENCY_LIMIT),
                    listener = listener,
                )
                .flatMap { batch ->
                    val report = XCoreInstallStageReport(
                        phase = phase,
                        label = label,
                        transferred = batch.written.size,
                        alreadyPresent = batch.alreadyPresent.size,
                        bytesWritten = batch.bytesWritten,
                        bytesAlreadyPresent = batch.bytesSkipped,
                        failures = batch.failures.map { (task, error) -> "${task.relativeTarget}: ${error.detail}" },
                    )
                    if (batch.failures.isNotEmpty()) {
                        logger?.log(
                            XCoreLogLevel.ERROR,
                            XCoreLogTags.TASKS,
                            "$label stage: ${batch.describeFailures()}",
                        )
                        // The first failure keeps its own type, status and cause; the rest are logged.
                        XCoreResult.Failure(batch.failures.first().second)
                    } else {
                        logger?.log(
                            XCoreLogLevel.DEBUG,
                            XCoreLogTags.TASKS,
                            "$label stage: ${report.transferred} fetched, ${report.alreadyPresent} already " +
                                "present, ${xCoreFormatBytes(report.bytesAccounted)} accounted of " +
                                "${xCoreFormatBytes(plan.totalBytes)} planned",
                        )
                        report.asXCoreSuccess()
                    }
                }
        }
    }

    /** Writes the version documents and fetches the log configuration the profile points at. */
    private suspend fun documentStage(
        resolved: ResolvedInstall,
        plan: XCoreInstallPlan,
        listener: XCoreTransferListener?,
    ): XCoreResult<XCoreInstallStageReport> {
        val versionId = resolved.version.id
        advance(versionId, XCoreInstallPhase.PROFILE_JSON, "version json")
        val documents = buildList {
            add(resolved.version.id to resolved.profile.rawJson)
            for (parent in resolved.inheritedProfiles) add(parent.id to parent.rawJson)
        }
        var writtenBytes = 0L
        for ((id, json) in documents) {
            val outcome = paths.resolve(XCoreInstaller.profileJsonRelative(id)).flatMap { file ->
                xCoreWrite(file, json)
            }
            if (outcome is XCoreResult.Failure) return XCoreResult.Failure(outcome.error)
            writtenBytes += json.toByteArray(Charsets.UTF_8).size
        }
        val logging = plan.loggingTask
            ?: return XCoreInstallStageReport(
                XCoreInstallPhase.PROFILE_JSON,
                "version json",
                transferred = documents.size,
                alreadyPresent = 0,
                bytesWritten = writtenBytes,
                bytesAlreadyPresent = 0L,
                failures = emptyList(),
            ).asXCoreSuccess()
        return prepareTasks(listOf(logging), "logging config").flatMap { prepared ->
            downloads.transferAll(prepared, maxConcurrency = 1, listener = listener).flatMap { batch ->
                if (batch.failures.isNotEmpty()) {
                    XCoreResult.Failure(batch.failures.first().second)
                } else {
                    XCoreInstallStageReport(
                        XCoreInstallPhase.PROFILE_JSON,
                        "version json and logging config",
                        transferred = documents.size + batch.written.size,
                        alreadyPresent = batch.alreadyPresent.size,
                        bytesWritten = writtenBytes + batch.bytesWritten,
                        bytesAlreadyPresent = batch.bytesSkipped,
                        failures = batch.failures.map { (task, error) -> "${task.relativeTarget}: ${error.detail}" },
                    ).asXCoreSuccess()
                }
            }
        }
    }

    private suspend fun assetStage(
        resolved: ResolvedInstall,
        plan: XCoreInstallPlan,
        listener: XCoreTransferListener?,
    ): XCoreResult<XCoreInstallStageReport> {
        val versionId = resolved.version.id
        val index = resolved.assetIndex
        if (index == null || index.objects.isEmpty()) {
            advance(versionId, XCoreInstallPhase.ASSETS, "no asset index")
            return XCoreInstallStageReport(
                XCoreInstallPhase.ASSETS,
                "assets",
                transferred = 0,
                alreadyPresent = 0,
                bytesWritten = 0L,
                bytesAlreadyPresent = 0L,
                failures = emptyList(),
            ).asXCoreSuccess()
        }
        advance(versionId, XCoreInstallPhase.ASSETS, "assets ${index.id}")
        return assets.plan(index).flatMap { assetPlan ->
            prepareTasks(assetPlan.tasks, "assets").flatMap { prepared ->
                downloads.transferAll(prepared, maxConcurrency = maxConcurrency, listener = listener).flatMap { batch ->
                    if (batch.failures.isNotEmpty()) {
                        logger?.log(
                            XCoreLogLevel.ERROR,
                            XCoreLogTags.TASKS,
                            "asset stage: ${batch.describeFailures()}",
                        )
                        return@flatMap XCoreResult.Failure(batch.failures.first().second)
                    }
                    val presentBytes = (index.totalBytes - assetPlan.missingBytes).coerceAtLeast(0L)
                    if (assetMode != XCoreAssetMode.VIRTUAL) {
                        return@flatMap XCoreInstallStageReport(
                            XCoreInstallPhase.ASSETS,
                            "assets ${index.id} (objects only)",
                            batch.written.size,
                            batch.alreadyPresent.size + assetPlan.presentCount,
                            batch.bytesWritten,
                            batch.bytesSkipped + presentBytes,
                            emptyList(),
                        ).asXCoreSuccess()
                    }
                    when (val placed = assets.materialiseVirtual(index)) {
                        is XCoreResult.Failure -> XCoreResult.Failure(placed.error)
                        is XCoreResult.Cancelled -> placed
                        is XCoreResult.Success -> {
                            if (placed.value.isNotEmpty()) {
                                val first = placed.value.first()
                                XCoreError.Storage(
                                    detail = "${placed.value.size} of ${index.count} assets could not be " +
                                        "placed in the virtual tree, starting with '${first.name}' " +
                                        "(its object is ${if (objectExists(first)) "present" else "absent"})",
                                    path = paths.root.absolutePath,
                                    retryable = true,
                                ).asXCoreFailure()
                            } else {
                                XCoreInstallStageReport(
                                    XCoreInstallPhase.ASSETS,
                                    "assets ${index.id}",
                                    batch.written.size,
                                    batch.alreadyPresent.size + assetPlan.presentCount,
                                    batch.bytesWritten,
                                    batch.bytesSkipped + presentBytes,
                                    emptyList(),
                                ).asXCoreSuccess()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun verifyStage(resolved: ResolvedInstall): XCoreResult<XCoreInstallStageReport> {
        val versionId = resolved.version.id
        advance(versionId, XCoreInstallPhase.VERIFYING, "verification")
        return auditFromInstall(resolved).flatMap { outcome ->
            val report = XCoreInstallStageReport(
                phase = XCoreInstallPhase.VERIFYING,
                label = "verification of $versionId",
                transferred = 0,
                alreadyPresent = outcome.checkedFiles,
                bytesWritten = 0L,
                bytesAlreadyPresent = outcome.presentBytes,
                failures = (outcome.missing + outcome.mismatched),
            )
            if (outcome.isConsistent) {
                logger?.log(
                    XCoreLogLevel.INFO,
                    XCoreLogTags.USE_CASE,
                    "verified $versionId: ${outcome.checkedFiles} files present, " +
                        "${xCoreFormatBytes(outcome.presentBytes)} accounted" +
                        if (outcome.notes.isEmpty()) "" else " (${outcome.notes.first()})",
                )
                report.asXCoreSuccess()
            } else {
                logger?.log(
                    XCoreLogLevel.ERROR,
                    XCoreLogTags.USE_CASE,
                    "$versionId did not verify: ${report.failures.joinToString()}",
                )
                XCoreError.Storage(
                    detail = "the install did not produce what the metadata describes: " +
                        report.failures.take(3).joinToString(", ") +
                        if (report.failures.size > 3) " and ${report.failures.size - 3} more" else "",
                    path = paths.root.absolutePath,
                    usableBytes = paths.usableBytesAt(paths.root),
                    retryable = true,
                ).asXCoreFailure()
            }
        }
    }

    private suspend fun completeInstall(
        resolved: ResolvedInstall,
        plan: XCoreInstallPlan,
        target: XCoreInstallTarget?,
        stages: List<XCoreInstallStageReport>,
        startedAt: Long,
    ): XCoreResult<XCoreInstallSummary> {
        val versionId = resolved.version.id
        return catalog
            .register(versionId, assetIndexCount = plan.assetCount, installed = true)
            .flatMap { registered ->
                val state = tracker.finish(
                    versionId = versionId,
                    phase = XCoreInstallPhase.READY,
                    clientJarPath = resolved.clientTask.relativeTarget,
                    profileJsonPath = XCoreInstaller.profileJsonRelative(versionId),
                    assetIndexId = plan.assetIndexId,
                    assetCount = plan.assetCount,
                    libraryCount = resolved.libraries.classpathArtifacts.size,
                )
                if (state is XCoreResult.Failure) {
                    logger?.log(
                        XCoreLogLevel.ERROR,
                        XCoreLogTags.USE_CASE,
                        "$versionId is installed on disk but its READY state could not be stored: " +
                            "${state.error.detail}",
                    )
                    return@flatMap XCoreResult.Failure(state.error)
                }
                if (target != null) {
                    val marked = instances.setStatus(target.id, XCoreInstanceStatus.READY)
                    if (marked is XCoreResult.Failure) return@flatMap XCoreResult.Failure(marked.error)
                }
                val counters = InstallCounters().also { all -> stages.forEach { all.absorb(it) } }
                logger?.log(
                    XCoreLogLevel.INFO,
                    XCoreLogTags.USE_CASE,
                    "installed $versionId: ${counters.filesWritten} files fetched, " +
                        "${counters.filesAlreadyPresent} already present, " +
                        "${xCoreFormatBytes(counters.bytesWritten)} written in ${clock() - startedAt}ms",
                )
                XCoreInstallSummary(
                    versionId = registered.id,
                    instanceId = target?.id,
                    stages = stages,
                    clientJarPath = resolved.clientTask.relativeTarget,
                    profileJsonPath = XCoreInstaller.profileJsonRelative(versionId),
                    assetIndexId = plan.assetIndexId,
                    assetCount = plan.assetCount,
                    libraryCount = resolved.libraries.classpathArtifacts.size,
                    nativeCount = resolved.libraries.nativeArtifacts.size,
                    skippedLibraries = resolved.libraries.skipped,
                    unverifiedLibraries = resolved.libraries.unverified,
                    bytesWritten = counters.bytesWritten,
                    bytesAlreadyPresent = counters.bytesAlreadyPresent,
                    elapsedMillis = clock() - startedAt,
                ).asXCoreSuccess()
            }
    }

    /** Records how an install ended; a failure here is logged, never converted into another result. */
    private suspend fun recordOutcome(
        versionId: String,
        target: XCoreInstallTarget?,
        error: XCoreError,
        startedAt: Long,
    ) {
        val cancelled = error is XCoreError.Cancelled
        val phase = if (cancelled) XCoreInstallPhase.CANCELLED else XCoreInstallPhase.FAILED
        tracker.finish(versionId, phase, error = error).onFailure { couldNotStore ->
            logger?.log(
                XCoreLogLevel.ERROR,
                XCoreLogTags.USE_CASE,
                "the failure of '$versionId' could not be stored: ${couldNotStore.detail}",
            )
        }
        if (target != null) {
            instances.setStatus(
                target.id,
                if (cancelled) XCoreInstanceStatus.NEEDS_REPAIR else XCoreInstanceStatus.BROKEN,
            ).onFailure { couldNotMark ->
                logger?.log(
                    XCoreLogLevel.ERROR,
                    XCoreLogTags.USE_CASE,
                    "instance '${target.id}' could not be marked after $versionId failed: " +
                        couldNotMark.detail,
                )
            }
        }
        logger?.log(
            XCoreLogLevel.ERROR,
            XCoreLogTags.USE_CASE,
            "install of $versionId stopped after ${clock() - startedAt}ms: ${error.format()}",
        )
    }

    private suspend fun advance(
        versionId: String,
        phase: XCoreInstallPhase,
        label: String,
    ) {
        tracker.advance(versionId, phase, label).onFailure { couldNotStore ->
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "could not record phase $phase for '$versionId': ${couldNotStore.detail}",
            )
        }
    }

    /**
     * Turns tasks into resolved destinations.
     *
     * A task that cannot be prepared - a path that would escape the storage root, or a volume that
     * cannot hold the file - fails the stage. It is never dropped, because a stage that quietly
     * skipped files would still report the install as complete.
     */
    private suspend fun prepareTasks(
        tasks: List<XCoreDownloadTask>,
        label: String,
    ): XCoreResult<List<ResolvedDownload>> {
        val prepared = ArrayList<ResolvedDownload>(tasks.size)
        val refused = ArrayList<String>()
        for (task in tasks) {
            when (val outcome = task.resolveWith(paths)) {
                is XCoreResult.Success -> prepared += outcome.value
                is XCoreResult.Failure -> refused += "${task.relativeTarget}: ${outcome.error.detail}"
                is XCoreResult.Cancelled -> return XCoreResult.Cancelled(label, "$label preparation was cancelled")
            }
        }
        if (refused.isNotEmpty()) {
            return XCoreError.Storage(
                detail = "${refused.size} $label files could not be prepared: ${refused.first()}",
                path = paths.root.absolutePath,
                usableBytes = paths.usableBytesAt(paths.root),
                retryable = false,
            ).asXCoreFailure()
        }
        return prepared.asXCoreSuccess()
    }

    private fun clientTask(
        versionId: String,
        url: String,
        sha1: XCoreSha1,
        sizeBytes: Long,
    ): XCoreDownloadTask = XCoreDownloadTask(
        url = url,
        relativeTarget = XCoreInstaller.clientJarRelative(versionId),
        sha1 = sha1,
        expectedBytes = sizeBytes,
        origin = XCoreDownloadOrigin.CLIENT_JAR,
    )

    private fun loggingTask(logging: XCoreLoggingClient): XCoreDownloadTask {
        val name = logging.id
            ?: logging.file.url.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "log4j2.xml"
        return XCoreDownloadTask(
            url = logging.file.url,
            relativeTarget = "logs/config/$name",
            sha1 = logging.file.sha1,
            expectedBytes = logging.file.sizeBytes,
            origin = XCoreDownloadOrigin.LOGGING_CONFIG,
        )
    }

    private suspend fun ancestorsOf(profile: XCoreVersionProfile): XCoreResult<List<XCoreVersionProfile>> {
        val chain = ArrayList<XCoreVersionProfile>()
        var parentId = profile.inheritsFrom
        var guard = 0
        while (parentId != null) {
            if (guard++ >= XCoreInstaller.MAX_INHERITANCE) {
                return XCoreError.Network(
                    detail = "'${profile.id}' inherits more than $guard versions deep; the chain is " +
                        "${chain.map { it.id }.joinToString(" -> ")}",
                    url = profile.sourceUrl,
                    retryable = false,
                ).asXCoreFailure()
            }
            val parent = catalog.resolveProfile(parentId)
            if (parent is XCoreResult.Failure) return XCoreResult.Failure(parent.error)
            if (parent is XCoreResult.Cancelled) return parent
            val value = (parent as XCoreResult.Success).value
            if (value.id == profile.id || chain.any { it.id == value.id }) break
            chain += value
            parentId = value.inheritsFrom
        }
        return chain.asXCoreSuccess()
    }

    /** The presence half of an audit, without the register write the install path performs. */
    private suspend fun auditFromInstall(resolved: ResolvedInstall): XCoreResult<XCoreInstallAudit> {
        val missing = ArrayList<String>()
        val mismatched = ArrayList<String>()
        val notes = ArrayList<String>()
        var bytes = 0L
        var checked = 0
        val jarOutcome = paths.resolve(resolved.clientTask.relativeTarget)
        if (jarOutcome !is XCoreResult.Success) {
            return if (jarOutcome is XCoreResult.Failure) {
                XCoreResult.Failure(jarOutcome.error)
            } else {
                XCoreResult.Cancelled(resolved.version.id, "the client jar destination could not be resolved")
            }
        }
        val jar = jarOutcome.value
        when (val verified = verifier.verify(jar, resolved.version.clientDownloadSha1)) {
            is XCoreResult.Failure -> {
                if (!jar.isFile) missing += resolved.clientTask.relativeTarget
                else mismatched += "${resolved.clientTask.relativeTarget}: ${verified.error.detail}"
            }

            is XCoreResult.Cancelled -> return XCoreResult.Cancelled(resolved.version.id, "verification was cancelled")
            is XCoreResult.Success -> {
                bytes += verified.value.sizeBytes
                checked++
            }
        }
        for (id in listOf(resolved.version.id) + resolved.inheritedProfiles.map { it.id }) {
            val relative = XCoreInstaller.profileJsonRelative(id)
            val file = paths.resolve(relative).valueOrNull()
            if (file == null || !file.isFile) {
                missing += relative
                continue
            }
            checked++
            bytes += file.length()
            if (!profileOnDiskMatches(file, id)) mismatched += "$relative does not describe '$id'"
        }
        for (artifact in resolved.libraries.artifacts) {
            val file = paths.resolve(artifact.relativePath).valueOrNull()
            if (file == null || !file.isFile) {
                missing += artifact.relativePath
            } else {
                val mismatch = presenceCheck(file, artifact.sizeBytes)
                if (mismatch != null) {
                    mismatched += "${artifact.relativePath}: $mismatch"
                } else {
                    checked++
                    bytes += file.length()
                }
            }
            if (artifact.sha1 == null) notes += "${artifact.coordinate} publishes no digest"
        }
        val index = resolved.assetIndex
        if (index != null && index.count <= MAX_PRESENCE_CHECK) {
            var absent = 0
            for (asset in index.objects) {
                val file = paths.resolve(asset.objectsRelativePath).valueOrNull()
                if (file == null || !file.isFile) absent++
                else {
                    checked++
                    bytes += file.length()
                }
            }
            if (absent > 0) missing += "$absent asset objects of '${index.id}' are absent from the store"
        } else if (index != null) {
            notes += "${index.count} assets were trusted from their write-time digests"
        }
        return XCoreInstallAudit(resolved.version.id, missing, mismatched, bytes, checked, notes).asXCoreSuccess()
    }

    private fun objectExists(asset: XCoreAssetObject): Boolean =
        paths.resolve(asset.objectsRelativePath).valueOrNull()?.isFile == true

    /** Re-reads a stored profile document, because a truncated write would otherwise audit clean. */
    private fun profileOnDiskMatches(
        file: File,
        versionId: String,
    ): Boolean {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return (parser.parse(text) as? XCoreResult.Success)?.value?.id == versionId
    }

    companion object {
        /** Inheritance deeper than this is not what Mojang's own metadata produces. */
        const val MAX_INHERITANCE = 6

        /** Space the launcher insists on keeping free so a failed install cannot fill the device. */
        const val HEADROOM_BYTES = 64L * 1024 * 1024

        /** Above this many files, an audit trusts write-time digests instead of re-statting each one. */
        const val MAX_PRESENCE_CHECK = 20_000

        fun clientJarRelative(versionId: String): String = "versions/$versionId/$versionId.jar"

        fun profileJsonRelative(versionId: String): String = "versions/$versionId/$versionId.json"
    }
}

/** The declared length of a file that exists, or a description of how it disagrees. Null when it matches. */
private fun presenceCheck(
    file: File,
    expectedBytes: Long,
): String? {
    if (expectedBytes in 1L until file.length()) {
        return "${file.name} is ${file.length()} bytes, metadata declares $expectedBytes"
    }
    return null
}

private fun XCoreResult<*>.toError(): XCoreError =
    (this as? XCoreResult.Cancelled)?.let { cancelled ->
        XCoreError.Cancelled(
            detail = cancelled.reason ?: "${cancelled.operation} stopped",
            operation = cancelled.operation,
        )
    } ?: XCoreError.Unexpected("a result that was neither a value nor a failure was reported")

internal fun xCoreFormatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "%.1f KB".format(bytes / 1_000.0)
    bytes < 1_000_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.2f GB".format(bytes / 1_000_000_000.0)
}

private suspend fun xCoreWrite(
    file: File,
    text: String,
): XCoreResult<File> = xCoreRunCatching(
    origin = "write(${file.name})",
    errorKind = XCoreErrorKind.STORAGE,
) {
    try {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            throw IOException("cannot replace ${file.absolutePath}")
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        file
    } catch (io: IOException) {
        throw XCoreFailureException(
            XCoreError.Storage(
                detail = "could not write ${file.absolutePath}: ${io.message}",
                path = file.absolutePath,
                retryable = false,
                cause = io,
            ),
        )
    }
}
