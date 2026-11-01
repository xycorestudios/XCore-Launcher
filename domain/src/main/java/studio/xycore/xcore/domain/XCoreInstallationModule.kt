package studio.xycore.xcore.domain

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreModule
import studio.xycore.xcore.core.XCoreModuleContext
import studio.xycore.xcore.core.XCoreModuleDescriptor
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreServiceIds
import studio.xycore.xcore.core.XCoreServiceLocator
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.find
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.requireService
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRun
import studio.xycore.xcore.data.XCoreDownloadRecord
import studio.xycore.xcore.data.XCoreDownloadState
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreInstanceStatus
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreVersionRef
import studio.xycore.xcore.installer.XCoreAssetManager
import studio.xycore.xcore.installer.XCoreByteTransport
import studio.xycore.xcore.installer.XCoreDistributionTermsGate
import studio.xycore.xcore.installer.XCoreDownloadJournal
import studio.xycore.xcore.installer.XCoreDownloadManager
import studio.xycore.xcore.installer.XCoreHashVerifier
import studio.xycore.xcore.installer.XCoreHttpByteTransport
import studio.xycore.xcore.installer.XCoreInstallAudit
import studio.xycore.xcore.installer.XCoreInstallPaths
import studio.xycore.xcore.installer.XCoreInstallPlan
import studio.xycore.xcore.installer.XCoreInstallState
import studio.xycore.xcore.installer.XCoreInstallStateStore
import studio.xycore.xcore.installer.XCoreInstallSummary
import studio.xycore.xcore.installer.XCoreInstallTarget
import studio.xycore.xcore.installer.XCoreInstallTracker
import studio.xycore.xcore.installer.XCoreInstaller
import studio.xycore.xcore.installer.XCoreInstanceAccess
import studio.xycore.xcore.installer.XCoreTransferListener
import studio.xycore.xcore.installer.XCoreTransferProgress
import studio.xycore.xcore.versions.XCoreHttpDocumentFetcher
import studio.xycore.xcore.versions.XCoreRuntimeEnvironment
import studio.xycore.xcore.versions.XCoreVersionCatalog
import studio.xycore.xcore.versions.XCoreVersionListing
import studio.xycore.xcore.versions.XCoreVersionStore

/**
 * [XCoreVersionStore] over the launcher's own SQLite rows plus its cache directory.
 *
 * Manifest and profile documents live as files under `cache/` because they are large, re-fetched
 * whole, and Mojang serves them with an `ETag`; the version rows themselves go through the existing
 * repository so the diagnostics screen and the installer see the same table.
 */
class XCoreSqlVersionStore(
    private val repositories: XCoreRepositories,
    private val layout: XCoreStorageLayout,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: XCoreLogger? = null,
) : XCoreVersionStore {

    private val cacheDirectory: File? get() = layout.dir(XCoreGameDirectory.CACHE)

    override suspend fun manifestETag(): XCoreResult<String?> = readSidecar(ETAG_FILE)

    override suspend fun cachedManifest(): XCoreResult<String?> =
        storageCall("versionStore.readManifest") { readFile(manifestFile()) }

    override suspend fun manifestFetchedAtMillis(): XCoreResult<Long?> =
        readSidecar(STAMP_FILE).map { raw -> raw?.toLongOrNull() }

    override suspend fun saveManifest(
        json: String,
        sourceUrl: String,
        etag: String?,
        fetchedAtMillis: Long,
    ): XCoreResult<Unit> = storageCall("versionStore.writeManifest") {
        writeFile(manifestFile(), json)
        writeFile(sidecar(ETAG_FILE), etag ?: "")
        writeFile(sidecar(STAMP_FILE), fetchedAtMillis.toString())
        writeFile(sidecar(URL_FILE), sourceUrl)
    }

    override suspend fun cachedProfile(versionId: String): XCoreResult<String?> =
        profileFile(versionId).flatMap { file -> storageCall("versionStore.readProfile") { readFile(file) } }

    override suspend fun saveProfile(
        versionId: String,
        json: String,
        sourceUrl: String,
    ): XCoreResult<Unit> = profileFile(versionId).flatMap { file ->
        storageCall("versionStore.writeProfile") {
            writeFile(file, json)
            writeFile(File(file.parentFile, "${file.name}.url"), sourceUrl)
        }
    }

    override suspend fun findVersion(versionId: String): XCoreResult<XCoreVersionRef?> =
        repositories.versions.find(versionId)

    override suspend fun saveVersion(version: XCoreVersionRef): XCoreResult<XCoreVersionRef> =
        repositories.versions.save(version)

    override suspend fun listVersions(limit: Int): XCoreResult<List<XCoreVersionRef>> =
        repositories.versions.listAll(limit)

    private fun manifestFile(): File? = cacheDirectory?.let { File(it, MANIFEST_FILE) }

    private fun sidecar(name: String): File? = cacheDirectory?.let { File(it, name) }

    private suspend fun readSidecar(name: String): XCoreResult<String?> {
        val file = sidecar(name) ?: return null.asXCoreSuccess()
        return storageCall("versionStore.read($name)") { readFile(file)?.takeIf { it.isNotBlank() } }
    }

    private fun profileFile(versionId: String): XCoreResult<File> {
        val base = cacheDirectory
            ?: return XCoreError.Storage(
                detail = "the cache directory is missing from the prepared layout",
                path = layout.plan.rootPath,
            ).asXCoreFailure()
        if (!VERSION_ID.matches(versionId)) {
            return XCoreError.Validation(
                detail = "a version id must be alphanumeric before it is used as a cache file name",
                field = "versionId",
                rejected = versionId,
            ).asXCoreFailure()
        }
        return File(base, "profiles/$versionId.json").asXCoreSuccess()
    }

    private suspend fun <T> storageCall(
        origin: String,
        body: suspend () -> T,
    ): XCoreResult<T> = withContext(io) {
        xCoreRun(origin, XCoreErrorKind.STORAGE) {
            try {
                body()
            } catch (failure: IOException) {
                failXCore(
                    XCoreError.Storage(
                        detail = "$origin failed: ${failure.message}",
                        path = cacheDirectory?.absolutePath ?: layout.plan.rootPath,
                        usableBytes = cacheDirectory?.usableSpace ?: 0L,
                        cause = failure,
                    ),
                )
            }
        }
    }

    private fun readFile(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (io: IOException) {
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.PATHS,
                "cached document ${file.name} could not be read: ${io.message}",
            )
            null
        }
    }

    private fun writeFile(
        file: File?,
        text: String,
    ) {
        file ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.writing")
        temp.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) throw IOException("cannot replace ${file.absolutePath}")
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val MANIFEST_FILE = "version_manifest_v2.json"
        const val ETAG_FILE = "version_manifest_v2.etag"
        const val STAMP_FILE = "version_manifest_v2.stamp"
        const val URL_FILE = "version_manifest_v2.url"

        private val VERSION_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}

/** [XCoreDownloadJournal] over the existing `xcore_download_journal` repository. */
class XCoreRepositoryDownloadJournal(
    private val repositories: XCoreRepositories,
) : XCoreDownloadJournal {

    override suspend fun save(record: XCoreDownloadRecord): XCoreResult<XCoreDownloadRecord> =
        repositories.downloads.save(record)

    override suspend fun find(id: String): XCoreResult<XCoreDownloadRecord?> =
        repositories.downloads.find(id)

    override suspend fun addWritten(
        id: String,
        deltaBytes: Long,
        state: XCoreDownloadState,
    ): XCoreResult<XCoreDownloadRecord> = repositories.downloads.addWritten(id, deltaBytes, state)

    override suspend fun transition(
        id: String,
        state: XCoreDownloadState,
        attemptsDelta: Int,
    ): XCoreResult<XCoreDownloadRecord> = repositories.downloads.transition(id, state, attemptsDelta)

    override suspend fun openRecords(limit: Int): XCoreResult<List<XCoreDownloadRecord>> =
        repositories.downloads.listOpen(limit)
}

/** [XCoreInstallStateStore] over the key-value table: one JSON row per version. */
class XCoreKeyValueInstallStateStore(
    private val repositories: XCoreRepositories,
) : XCoreInstallStateStore {

    override suspend fun read(key: String): XCoreResult<String?> = repositories.keyValue.get(key)

    override suspend fun write(
        key: String,
        value: String,
    ): XCoreResult<Unit> = repositories.keyValue.set(key, value)

    /** Forgetting an install that was never stored succeeds; there is nothing left to forget. */
    override suspend fun clear(key: String): XCoreResult<Unit> =
        repositories.keyValue.get(key).flatMap { present ->
            if (present == null) Unit.asXCoreSuccess() else repositories.keyValue.remove(key)
        }

    override suspend fun keys(prefix: String): XCoreResult<Map<String, String>> =
        repositories.keyValue.list(prefix)
}

/** [XCoreInstanceAccess] over the instance repository, mapping to the installer's narrow view. */
class XCoreRepositoryInstanceAccess(
    private val repositories: XCoreRepositories,
) : XCoreInstanceAccess {

    override suspend fun find(instanceId: String): XCoreResult<XCoreInstallTarget?> =
        repositories.instances.find(instanceId).map { instance ->
            instance?.let {
                XCoreInstallTarget(
                    id = it.id,
                    name = it.name,
                    versionId = it.versionId,
                    gameDirectoryRelative = it.gameDirectoryRelative,
                )
            }
        }

    override suspend fun setStatus(
        instanceId: String,
        status: XCoreInstanceStatus,
    ): XCoreResult<XCoreInstallTarget> = repositories.instances.setStatus(instanceId, status).map { instance ->
        XCoreInstallTarget(
            id = instance.id,
            name = instance.name,
            versionId = instance.versionId,
            gameDirectoryRelative = instance.gameDirectoryRelative,
        )
    }
}

/**
 * The EULA acknowledgement, read from and written to the launcher's own key-value table.
 *
 * The value is only ever set by the settings screen after the user taps accept; nothing in the
 * install path writes it. That is what makes [XCoreDistributionTermsGate.acknowledged] a fact about
 * the device rather than a formality.
 */
class XCoreKeyValueTermsGate(
    private val repositories: XCoreRepositories,
) : XCoreDistributionTermsGate {

    override suspend fun acknowledged(): XCoreResult<Boolean> =
        repositories.keyValue.get(KEY).map { value -> value == ACCEPTED_VALUE }

    suspend fun accept(): XCoreResult<Unit> = repositories.keyValue.set(KEY, ACCEPTED_VALUE)

    suspend fun revoke(): XCoreResult<Unit> = repositories.keyValue.set(KEY, "revoked")

    suspend fun state(): XCoreResult<String?> = repositories.keyValue.get(KEY)

    companion object {
        const val KEY = XCoreDistributionTermsGate.PERMISSION_KEY
        const val ACCEPTED_VALUE = "accepted"
    }
}

/** The assembled install pipeline, so the app and the use cases share one object graph. */
data class XCoreInstallationGraph(
    val catalog: XCoreVersionCatalog,
    val store: XCoreSqlVersionStore,
    val paths: XCoreInstallPaths,
    val verifier: XCoreHashVerifier,
    val tracker: XCoreInstallTracker,
    val terms: XCoreKeyValueTermsGate,
    val instanceAccess: XCoreRepositoryInstanceAccess,
    val transport: XCoreByteTransport,
    val downloadManager: XCoreDownloadManager,
    val assetManager: XCoreAssetManager,
    val installer: XCoreInstaller,
    val environmentFor: (String) -> XCoreRuntimeEnvironment,
) {
    suspend fun installState(versionId: String): XCoreResult<XCoreInstallState?> = tracker.state(versionId)

    suspend fun liveStates(): XCoreResult<List<XCoreInstallState>> = tracker.all()
}

/**
 * Builds the Phase 2 pipeline over the existing storage and metadata layers.
 *
 * Nothing here talks to the network at construction time: the catalog fetches on first use, and the
 * transports carry the configured user agent and timeout, so building the graph on a device that is
 * offline is legitimate and shows up later as a typed network failure.
 */
object XCoreInstallation {

    fun graph(
        repositories: XCoreRepositories,
        layout: XCoreStorageLayout,
        userAgent: String,
        httpTimeoutMillis: Int = 20_000,
        maxConcurrentDownloads: Int = 4,
        apiLevel: Int = android.os.Build.VERSION.SDK_INT,
        logger: XCoreLogger? = null,
        clock: () -> Long = System::currentTimeMillis,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): XCoreInstallationGraph {
        val paths = XCoreInstallPaths(layout)
        val verifier = XCoreHashVerifier()
        val store = XCoreSqlVersionStore(repositories, layout, io, logger)
        val fetcher = XCoreHttpDocumentFetcher(
            userAgent = userAgent,
            timeoutMillis = httpTimeoutMillis,
            io = io,
            logger = logger,
        )
        val environmentFor: (String) -> XCoreRuntimeEnvironment = { abi ->
            XCoreRuntimeEnvironment.forAndroid(abi = abi, apiLevel = apiLevel)
        }
        val catalog = XCoreVersionCatalog(
            store = store,
            fetcher = fetcher,
            logger = logger,
            clock = clock,
        )
        val journal = XCoreRepositoryDownloadJournal(repositories)
        val transport = XCoreHttpByteTransport(
            userAgent = userAgent,
            connectTimeoutMillis = httpTimeoutMillis,
            readTimeoutMillis = httpTimeoutMillis,
            io = io,
        )
        val downloadManager = XCoreDownloadManager(
            transport = transport,
            journal = journal,
            verifier = verifier,
            logger = logger,
            clock = clock,
        )
        val assetManager = XCoreAssetManager(
            fetcher = fetcher,
            paths = paths,
            verifier = verifier,
            logger = logger,
        )
        val tracker = XCoreInstallTracker(XCoreKeyValueInstallStateStore(repositories), clock)
        val terms = XCoreKeyValueTermsGate(repositories)
        val instanceAccess = XCoreRepositoryInstanceAccess(repositories)
        val installer = XCoreInstaller(
            catalog = catalog,
            downloads = downloadManager,
            assets = assetManager,
            paths = paths,
            verifier = verifier,
            tracker = tracker,
            instances = instanceAccess,
            terms = terms,
            environmentFor = environmentFor,
            logger = logger,
            clock = clock,
            maxConcurrency = maxConcurrentDownloads.coerceIn(
                1,
                XCoreDownloadManager.MAX_CONCURRENCY_LIMIT,
            ),
        )
        return XCoreInstallationGraph(
            catalog = catalog,
            store = store,
            paths = paths,
            verifier = verifier,
            tracker = tracker,
            terms = terms,
            instanceAccess = instanceAccess,
            transport = transport,
            downloadManager = downloadManager,
            assetManager = assetManager,
            installer = installer,
            environmentFor = environmentFor,
        )
    }
}

/**
 * Publishes the version catalog and the download/install engine as capabilities.
 *
 * Construction is real work - the layout must already exist and the database must be open, which is
 * why this module requires both - but no request is made until something asks the catalog a question.
 */
class XCoreInstallationModule(
    private val repositories: XCoreRepositories,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: XCoreLogger? = null,
) : XCoreModule {

    override val descriptor: XCoreModuleDescriptor = XCoreModuleDescriptor(
        id = ID,
        displayName = "Version and installation engine",
        requires = setOf(XCoreStorageModule.ID, XCoreMetadataModule.ID),
    )

    private var graph: XCoreInstallationGraph? = null

    override suspend fun onStart(context: XCoreModuleContext): XCoreResult<Unit> = withContext(io) {
        xCoreRun("module.installation", XCoreErrorKind.CONFIGURATION) {
            val layout = context.services.requireService<XCoreStorageLayout>().valueOrNull()
                ?: failXCore(
                    XCoreError.Configuration("the storage layout capability is not in the catalog"),
                )
            val built = XCoreInstallation.graph(
                repositories = repositories,
                layout = layout,
                userAgent = context.config.userAgent,
                httpTimeoutMillis = context.config.httpTimeoutMillis,
                maxConcurrentDownloads = context.config.maxConcurrentDownloads,
                logger = context.logger,
            )
            graph = built
            context.services.contribute(XCoreServiceIds.VERSION_INDEX, built.catalog).getOrThrow()
            context.services.contribute(XCoreServiceIds.DOWNLOAD_ENGINE, built.installer).getOrThrow()
            context.services.contribute(SERVICE_ID, built).getOrThrow()
            context.logger.log(
                XCoreLogLevel.INFO,
                XCoreLogTags.MODULES,
                "installation engine ready at ${layout.plan.rootPath}; " +
                    "terms accepted: ${built.terms.acknowledged().valueOrNull() == true}",
            )
            Unit
        }
    }

    override suspend fun onStop() {
        // The graph holds no sockets, threads or cursors of its own; each transfer opens and closes
        // its own connection, so there is nothing to release here beyond dropping the reference.
        graph = null
    }

    companion object {
        const val ID = "xcore.installation"

        /** Catalog id the whole pipeline is published under, for the use cases and the UI. */
        const val SERVICE_ID = "install.graph"
    }
}

/**
 * The slice of the installation engine the UI is allowed to touch.
 *
 * Every method resolves the graph from the live service catalog instead of holding one, so a call
 * made before `xcore.installation` has started returns [XCoreErrorKind.SERVICE] rather than
 * silently working against an object graph that was never prepared. Progress is published as a
 * [StateFlow] fed by the transfer listener, which means the percentage on screen comes from bytes
 * actually written, not from a timer.
 */
class XCoreInstallUseCases(
    private val services: XCoreServiceLocator,
    private val logger: XCoreLogger? = null,
) {

    private val liveProgress = MutableStateFlow<XCoreTransferProgress?>(null)

    /** Bytes moved by the install that is running now, or `null` when nothing is in flight. */
    val progress: StateFlow<XCoreTransferProgress?> = liveProgress.asStateFlow()

    val isBusy: Boolean get() = inFlight

    private var inFlight = false

    fun graphOrNull(): XCoreInstallationGraph? = services.find<XCoreInstallationGraph>()

    private fun graph(): XCoreResult<XCoreInstallationGraph> = graphOrNull()?.asXCoreSuccess()
        ?: serviceMissing()

    /** Whether the EULA acknowledgement is stored on this device. Never writes. */
    suspend fun termsAcknowledged(): XCoreResult<Boolean> =
        graph().flatMap { it.terms.acknowledged() }

    suspend fun termsState(): XCoreResult<String?> = graph().flatMap { it.terms.state() }

    /**
     * Records that the user accepted the EULA.
     *
     * Only a control the user tapped reaches this; the install path never calls it, which is what
     * keeps [XCoreDistributionTermsGate.acknowledged] a fact about the device.
     */
    suspend fun acceptTerms(): XCoreResult<Unit> = graph().flatMap { it.terms.accept() }

    suspend fun revokeTerms(): XCoreResult<Unit> = graph().flatMap { it.terms.revoke() }

    /** The live manifest, narrowed by type and id, with the ids already registered. */
    suspend fun versions(
        query: String? = null,
        includeSnapshots: Boolean = false,
        limit: Int = 200,
        forceRefresh: Boolean = false,
    ): XCoreResult<XCoreVersionListing> {
        val graph = graphOrNull() ?: return serviceMissing<XCoreVersionListing>()
        val types = if (includeSnapshots) null else setOf(XCoreReleaseType.RELEASE)
        val refreshed =
            if (forceRefresh) graph.catalog.refreshManifest(force = true) else graph.catalog.refreshManifest()
        return refreshed.flatMap { graph.catalog.listVersions(releaseTypes = types, query = query, limit = limit) }
    }

    /**
     * Makes sure [versionId] has a `versions` row, fetching only its metadata if it does not.
     *
     * `instances.version_id` is a foreign key, so an instance cannot be saved for a version the
     * launcher has never read a profile for. Registration writes metadata only; the game files still
     * arrive through [install].
     */
    suspend fun ensureRegistered(versionId: String): XCoreResult<XCoreVersionRef> =
        graph().flatMap { graph ->
            graph.catalog.registered(versionId).flatMap { existing ->
                existing?.asXCoreSuccess() ?: graph.catalog.register(versionId)
            }
        }

    /** What installing [versionId] would download, counted from metadata. Writes nothing. */
    suspend fun plan(
        versionId: String,
        abi: String,
    ): XCoreResult<XCoreInstallPlan> = graph().flatMap { it.installer.plan(versionId, abi) }

    /**
     * Downloads and verifies [versionId], then marks [instanceId] ready.
     *
     * The call is single-flight: a second install started while one is running is refused rather than
     * allowed to write the same files twice.
     */
    suspend fun install(
        versionId: String,
        abi: String,
        instanceId: String? = null,
    ): XCoreResult<XCoreInstallSummary> {
        val graph = graph().valueOrNull() ?: return graphResultMissing()
        if (inFlight) {
            return XCoreError.Validation(
                detail = "an install is already running; wait for it to finish or cancel it",
                field = "versionId",
                rejected = versionId,
            ).asXCoreFailure()
        }
        inFlight = true
        liveProgress.value = null
        val listener = XCoreTransferListener { update -> liveProgress.value = update }
        try {
            return graph.installer.install(versionId, abi, instanceId, listener).also { outcome ->
                logger?.log(
                    if (outcome is XCoreResult.Success) XCoreLogLevel.INFO else XCoreLogLevel.WARN,
                    XCoreLogTags.USE_CASE,
                    "install '$versionId' -> ${outcome.summary()}",
                )
            }
        } finally {
            inFlight = false
            liveProgress.value = null
        }
    }

    /** Durable state for one version, read from the key-value table rather than guessed from disk. */
    suspend fun installState(versionId: String): XCoreResult<XCoreInstallState?> =
        graph().flatMap { it.installState(versionId) }

    /** Every state the engine has persisted, including interrupted installs. */
    suspend fun installStates(): XCoreResult<List<XCoreInstallState>> = graph().flatMap { it.liveStates() }

    /** Confirms published digests against the files on disk, without downloading anything. */
    suspend fun audit(
        versionId: String,
        abi: String,
    ): XCoreResult<XCoreInstallAudit> = graph().flatMap { it.installer.audit(versionId, abi) }

    /** Audits the instance's version and moves its row to READY or NEEDS_REPAIR accordingly. */
    suspend fun reconcile(
        instanceId: String,
        abi: String,
    ): XCoreResult<XCoreInstallTarget> = graph().flatMap { it.installer.reconcileInstanceStatus(instanceId, abi) }

    /** Drops the persisted state only; the files on disk stay where they are. */
    suspend fun forget(versionId: String): XCoreResult<Unit> = graph().flatMap { it.installer.forget(versionId) }

    private fun graphResultMissing(): XCoreResult<XCoreInstallSummary> = serviceMissing()

    private fun <T> serviceMissing(): XCoreResult<T> = XCoreError.Service(
        detail = "the installation engine has not started, so there is nothing to install with",
        serviceId = XCoreInstallationModule.SERVICE_ID,
    ).asXCoreFailure()

    companion object {
        private fun XCoreResult<XCoreInstallSummary>.summary(): String = when (this) {
            is XCoreResult.Success ->
                "${value.filesTouched} files, ${value.bytesWritten} bytes written, ${value.elapsedMillis} ms"
            is XCoreResult.Failure -> error.kind.name.lowercase()
            is XCoreResult.Cancelled -> "cancelled: $reason"
        }
    }
}
