package studio.xycore.xcore.versions

import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreVersionRef

/**
 * Durable side of the version layer, implemented over the launcher's own SQLite table and cache
 * files.
 *
 * The catalog stays storage-agnostic so manifest parsing and the inheritance walk can be driven from
 * a unit test with an in-memory double. Production never gets a stub: `:domain` supplies the
 * implementation backed by `XCoreRepositories` and `XCorePaths`.
 */
interface XCoreVersionStore {

    /** The `If-None-Match` value belonging to the cached manifest, if one was recorded. */
    suspend fun manifestETag(): XCoreResult<String?>

    suspend fun cachedManifest(): XCoreResult<String?>

    suspend fun manifestFetchedAtMillis(): XCoreResult<Long?>

    suspend fun saveManifest(
        json: String,
        sourceUrl: String,
        etag: String?,
        fetchedAtMillis: Long,
    ): XCoreResult<Unit>

    /** A profile document kept from an earlier run, keyed by version id. */
    suspend fun cachedProfile(versionId: String): XCoreResult<String?>

    suspend fun saveProfile(
        versionId: String,
        json: String,
        sourceUrl: String,
    ): XCoreResult<Unit>

    suspend fun findVersion(versionId: String): XCoreResult<XCoreVersionRef?>

    suspend fun saveVersion(version: XCoreVersionRef): XCoreResult<XCoreVersionRef>

    suspend fun listVersions(limit: Int): XCoreResult<List<XCoreVersionRef>>
}

/** Versions known from the manifest, annotated with what the launcher already registered. */
data class XCoreVersionListing(
    val entries: List<XCoreManifestEntry>,
    val manifestFetchedAtMillis: Long,
    val servedFromCache: Boolean,
    val installedIds: Set<String>,
    val latestRelease: String?,
    val latestSnapshot: String?,
)

/**
 * Reads, caches, parses and resolves Mojang's version metadata.
 *
 * Three behaviours matter for correctness:
 *  - the manifest is revalidated with `If-None-Match` and falls back to the cached copy on 304, so a
 *    refresh costs almost nothing when nothing changed and still works with the network off;
 *  - walking `inheritsFrom` is bounded and cycle-checked, because a loader profile naming itself as
 *    its own parent would otherwise spin the installer;
 *  - a version only becomes a [XCoreVersionRef] once its resolved profile actually carries a client
 *    download and digest, so a registered row always describes something fetchable.
 */
class XCoreVersionCatalog(
    private val store: XCoreVersionStore,
    private val fetcher: XCoreRemoteDocumentFetcher,
    private val parser: XCoreVersionParser = XCoreVersionParser(),
    private val logger: XCoreLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val manifestUrl: String = XCoreDistributionEndpoints.VERSION_MANIFEST_V2,
    private val maxInheritanceDepth: Int = 8,
) {

    private var loaded: XCoreVersionManifest? = null

    val manifest: XCoreVersionManifest? get() = loaded

    /**
     * Fetches `version_manifest_v2.json`, or reuses what is already known.
     *
     * [force] skips the in-process and stored freshness checks but still sends `If-None-Match`, so a
     * forced refresh of an unchanged manifest does not pull 270 KB again.
     */
    suspend fun refreshManifest(force: Boolean = false): XCoreResult<XCoreVersionManifest> {
        val inMemory = loaded
        if (!force && inMemory != null && clock() - inMemory.fetchedAtMillis < MIN_REVALIDATION_MILLIS) {
            return inMemory.asXCoreSuccess()
        }
        if (!force && inMemory == null) {
            val stored = storedManifest()
            val storedAt = stored?.second
            if (stored != null && storedAt != null && clock() - storedAt < MIN_REVALIDATION_MILLIS) {
                return stored.first.asXCoreSuccess()
            }
        }
        val etag = if (force) null else store.manifestETag().valueOrNull()
        return when (val outcome = fetcher.fetch(manifestUrl, etag)) {
            is XCoreResult.Success -> persistManifest(outcome.value)
            is XCoreResult.Cancelled -> {
                inMemory?.asXCoreSuccess()
                    ?: XCoreError.Cancelled(
                        detail = "the manifest read was cancelled before it returned",
                        operation = outcome.operation,
                        byUser = false,
                    ).asXCoreFailure()
            }

            is XCoreResult.Failure -> serveCachedManifest(outcome.error)
        }
    }

    private suspend fun persistManifest(document: XCoreRemoteDocument): XCoreResult<XCoreVersionManifest> =
        XCoreVersionManifest
            .parse(
                json = document.body,
                sourceUrl = document.sourceUrl,
                fetchedAtMillis = clock(),
            )
            .flatMap { parsed ->
                store.saveManifest(document.body, document.sourceUrl, document.eTag, parsed.fetchedAtMillis)
                    .map { remember(parsed) }
            }

    private suspend fun serveCachedManifest(failure: XCoreError): XCoreResult<XCoreVersionManifest> {
        val inMemory = loaded
        if (inMemory != null) {
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "manifest fetch failed (${failure.format()}); serving the copy already in memory",
            )
            return inMemory.asXCoreSuccess()
        }
        val stored = storedManifest()
        if (stored != null) {
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "manifest fetch failed (${failure.format()}); serving the copy stored since " +
                    "${formatAge(clock() - (stored.second ?: clock()))}",
            )
            return remember(stored.first).asXCoreSuccess()
        }
        return XCoreError.Network(
            detail = "could not read the version manifest and no cached copy exists: ${failure.detail}",
            url = manifestUrl,
            httpStatus = (failure as? XCoreError.Network)?.httpStatus,
            retryable = failure.retryable,
            cause = failure.cause,
        ).asXCoreFailure()
    }

    private suspend fun storedManifest(): Pair<XCoreVersionManifest, Long?>? {
        val json = store.cachedManifest().valueOrNull() ?: return null
        val fetchedAt = store.manifestFetchedAtMillis().valueOrNull()
        val parsed = XCoreVersionManifest.parse(
            json = json,
            sourceUrl = manifestUrl,
            fetchedAtMillis = fetchedAt ?: 0L,
            servedFromCache = true,
        ).valueOrNull() ?: run {
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "the stored manifest could not be parsed; it will be re-fetched",
            )
            return null
        }
        return parsed to fetchedAt
    }

    private fun remember(manifest: XCoreVersionManifest): XCoreVersionManifest {
        loaded = manifest
        logger?.log(
            XCoreLogLevel.INFO,
            XCoreLogTags.USE_CASE,
            "manifest has ${manifest.size} entries " +
                "(release ${manifest.document.latestRelease ?: "unknown"}, " +
                "snapshot ${manifest.document.latestSnapshot ?: "unknown"}) " +
                "served from ${if (manifest.servedFromCache) "cache" else "network"}",
        )
        return manifest
    }

    /** Versions from the manifest, newest first, narrowed by type and id substring. */
    suspend fun listVersions(
        releaseTypes: Set<XCoreReleaseType>? = null,
        query: String? = null,
        limit: Int = 200,
    ): XCoreResult<XCoreVersionListing> = refreshManifest().flatMap { current ->
        store.listVersions(limit = LISTING_STORE_LIMIT).map { registered ->
            XCoreVersionListing(
                entries = current.select(releaseTypes, query, limit),
                manifestFetchedAtMillis = current.fetchedAtMillis,
                servedFromCache = current.servedFromCache,
                installedIds = registered.filter { it.installed }.map { it.id }.toSet(),
                latestRelease = current.document.latestRelease,
                latestSnapshot = current.document.latestSnapshot,
            )
        }
    }

    /** The one manifest entry for [versionId], refreshing the manifest if this process has none. */
    suspend fun entry(versionId: String): XCoreResult<XCoreManifestEntry> = refreshManifest().flatMap {
        it.findExact(versionId)
    }

    /**
     * Fetches and parses one profile, then walks `inheritsFrom` until the result stands alone.
     *
     * Every document in the chain is cached, so a second call for the same version is parse-only.
     */
    suspend fun resolveProfile(versionId: String): XCoreResult<XCoreVersionProfile> {
        if (!XCoreVersionRef.VERSION_ID_PATTERN.matches(versionId)) {
            return XCoreError.Validation(
                detail = "'$versionId' cannot be used as a version id",
                field = "versionId",
                rejected = versionId,
            ).asXCoreFailure()
        }
        return loadProfile(versionId).flatMap { profile ->
            resolveChain(profile, visited = linkedSetOf(versionId), depth = 0)
        }
    }

    private suspend fun resolveChain(
        profile: XCoreVersionProfile,
        visited: LinkedHashSet<String>,
        depth: Int,
    ): XCoreResult<XCoreVersionProfile> {
        val parentId = profile.inheritsFrom ?: run {
            if (profile.clientDownload != null) return profile.asXCoreSuccess()
            return XCoreError.Network(
                detail = "profile '${profile.id}' has no client download and declares no parent to inherit one from",
                url = profile.sourceUrl,
                retryable = false,
            ).asXCoreFailure()
        }
        if (depth >= maxInheritanceDepth) {
            return XCoreError.Network(
                detail = "inheritance from '${profile.id}' does not settle within $maxInheritanceDepth levels " +
                    "(${visited.joinToString(" -> ")})",
                url = profile.sourceUrl,
                retryable = false,
            ).asXCoreFailure()
        }
        if (!visited.add(parentId)) {
            return XCoreError.Network(
                detail = "inheritance cycle: '$parentId' already appears in ${visited.joinToString(" -> ")}",
                url = profile.sourceUrl,
                retryable = false,
            ).asXCoreFailure()
        }
        return profileUrl(parentId).flatMap { url -> loadProfileAt(parentId, url) }
            .flatMap { parent -> resolveChain(parent, visited, depth + 1) }
            .flatMap { resolvedParent -> parser.merge(child = profile, parent = resolvedParent) }
            .map { merged -> merged.copy(inheritsFrom = parentId) }
    }

    private suspend fun profileUrl(versionId: String): XCoreResult<String> {
        loaded?.find(versionId)?.let { return it.profileUrl.asXCoreSuccess() }
        return refreshManifest().flatMap { current -> current.findExact(versionId).map { it.profileUrl } }
    }

    private suspend fun loadProfile(versionId: String): XCoreResult<XCoreVersionProfile> {
        val cached = store.cachedProfile(versionId).valueOrNull()
        if (cached != null) {
            val parsed = parser.parse(cached).valueOrNull()
            if (parsed != null && parsed.id == versionId) {
                return parsed.asXCoreSuccess()
            }
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.USE_CASE,
                "the cached profile for '$versionId' is unusable; re-fetching it",
            )
        }
        return profileUrl(versionId).flatMap { url -> loadProfileAt(versionId, url) }
    }

    private suspend fun loadProfileAt(
        versionId: String,
        url: String,
    ): XCoreResult<XCoreVersionProfile> = XCoreDistributionEndpoints
        .requireAllowed(url, field = "profileUrl")
        .flatMap { authorised ->
            fetcher.fetch(url).flatMap { document ->
                parser
                    .parse(document.body, sourceUrl = authorised.toString())
                    .flatMap { profile ->
                        if (profile.id != versionId) {
                            XCoreError.Network(
                                detail = "$url returned a profile for '${profile.id}' while '$versionId' was requested",
                                url = url,
                                retryable = false,
                            ).asXCoreFailure()
                        } else {
                            store.saveProfile(versionId, document.body, document.sourceUrl)
                                .map { profile }
                        }
                    }
            }
        }

    /**
     * Registers a version so the launcher can refer to it, without downloading anything.
     *
     * [assetIndexCount] is only written when the caller has genuinely counted the index; an existing
     * count is never overwritten with zero.
     */
    suspend fun register(
        versionId: String,
        assetIndexCount: Int = 0,
        installed: Boolean? = null,
    ): XCoreResult<XCoreVersionRef> = resolveProfile(versionId).flatMap { profile ->
        store.findVersion(profile.id).flatMap { existing ->
            parser.toVersionRef(
                    profile = profile,
                    lastSeenManifestMillis = loaded?.fetchedAtMillis ?: clock(),
                    assetIndexCount = if (assetIndexCount > 0) assetIndexCount else existing?.assetIndexCount ?: 0,
                    installed = installed ?: existing?.installed ?: false,
                )
                .flatMap { store.saveVersion(it) }
        }
    }

    /** Rows the launcher already knows about; never touches the network. */
    suspend fun registeredVersions(limit: Int = 200): XCoreResult<List<XCoreVersionRef>> =
        store.listVersions(limit)

    suspend fun registered(versionId: String): XCoreResult<XCoreVersionRef?> =
        store.findVersion(versionId)

    /** The published digest of a profile document, when the manifest carried one. */
    suspend fun profileSha1(versionId: String): XCoreResult<XCoreSha1?> =
        refreshManifest().map { it.find(versionId)?.profileSha1 }

    /** Drops the in-process copy so the next read must consult storage and the network again. */
    fun forgetLoadedManifest() {
        loaded = null
    }

    companion object {
        /** Two refreshes inside this window are one refresh; Mojang's manifest changes daily. */
        const val MIN_REVALIDATION_MILLIS: Long = 10 * 60 * 1_000L

        private const val LISTING_STORE_LIMIT = 500

        private fun formatAge(millis: Long): String = when {
            millis < 60_000L -> "less than a minute"
            millis < 3_600_000L -> "${millis / 60_000L} minutes"
            millis < 86_400_000L -> "${millis / 3_600_000L} hours"
            else -> "${millis / 86_400_000L} days"
        }
    }
}
