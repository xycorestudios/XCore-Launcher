package studio.xycore.xcore.installer

import java.io.File
import java.io.IOException
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.versions.XCoreAssetIndexRef
import studio.xycore.xcore.versions.XCoreDistributionEndpoints
import studio.xycore.xcore.versions.XCoreRemoteDocumentFetcher
import studio.xycore.xcore.versions.fetch
import studio.xycore.xcore.versions.asObjectOrNull
import studio.xycore.xcore.versions.optLong
import studio.xycore.xcore.versions.optString
import studio.xycore.xcore.versions.readTree

/** One file inside an asset index. */
data class XCoreAssetObject(
    val name: String,
    val sha1: XCoreSha1,
    val sizeBytes: Long,
) {
    /** The two-character bucket Mojang's object store shards hashes by. */
    val bucket: String get() = sha1.value.substring(0, 2)

    val objectsRelativePath: String get() = "$OBJECTS_PREFIX$bucket/${sha1.value}"

    fun virtualPath(indexId: String): String = "$VIRTUAL_PREFIX$indexId/$name"

    companion object {
        const val OBJECTS_PREFIX = "assets/objects/"
        const val VIRTUAL_PREFIX = "assets/virtual/"
    }
}

/** A parsed asset index, keyed by the id the game is given on the command line. */
data class XCoreAssetIndex(
    val id: String,
    val objects: List<XCoreAssetObject>,
    val indexSha1: XCoreSha1,
    val sourceUrl: String,
) {
    val count: Int get() = objects.size

    val totalBytes: Long get() = objects.sumOf { it.sizeBytes.coerceAtLeast(0L) }
}

/** What the asset stage decided: what to fetch, and what was already byte-for-byte correct. */
data class XCoreAssetPlan(
    val tasks: List<XCoreDownloadTask>,
    val presentCount: Int,
    val missingBytes: Long,
) {
    val needsTransfer: Boolean get() = tasks.isNotEmpty()
}

/**
 * Reads asset indexes and turns them into transfers against the launcher's object store.
 *
 * The object store is content-addressed (`assets/objects/<2 hex>/<sha1>`), which is what makes
 * planning cheap and repeatable: an object that hashes correctly is never fetched again, whichever
 * version asked for it. [materialiseVirtual] then copies verified objects into the `virtual` tree the
 * game expects at `--assetsDir`. Copying rather than linking is a platform constraint, not a
 * shortcut: app-private storage gives the launcher no portable symlink call, and a copy of a file
 * that has already been digest-checked cannot drift from its object.
 */
class XCoreAssetManager(
    private val fetcher: XCoreRemoteDocumentFetcher,
    private val paths: XCoreInstallPaths,
    private val verifier: XCoreHashVerifier = XCoreHashVerifier(),
    private val logger: XCoreLogger? = null,
) {

    /** Fetches one index and refuses a document that does not match the digest the profile published. */
    suspend fun readIndex(ref: XCoreAssetIndexRef): XCoreResult<XCoreAssetIndex> =
        XCoreDistributionEndpoints.requireAllowed(ref.url, field = "assetIndexUrl")
            .flatMap { authorised -> fetcher.fetch(authorised.toString()) }
            .flatMap { document ->
                verifier.sha1OfText(document.body).flatMap { actual ->
                    if (actual != ref.sha1) {
                        XCoreError.Storage(
                            detail = "asset index '${ref.id}' hashes to ${actual.value} but the version " +
                                "profile published ${ref.sha1.value}",
                            path = document.sourceUrl,
                            retryable = true,
                        ).asXCoreFailure()
                    } else {
                        parseIndex(document.body, ref)
                    }
                }
            }

    /**
     * Parses `{"objects": {"name": {"hash": "...", "size": n}}}`.
     *
     * Names are validated as relative paths while parsing, not later: the game's own asset names are
     * safe, so anything containing `..` or a backslash means this document is not the index it claims
     * to be and must not reach the filesystem layer.
     */
    fun parseIndex(
        json: String,
        ref: XCoreAssetIndexRef,
    ): XCoreResult<XCoreAssetIndex> = xCoreRunCatching(
        origin = "assetIndex.parse(${ref.id})",
        errorKind = XCoreErrorKind.NETWORK,
    ) {
        val root = readTree(json, "asset index '${ref.id}'")
        val objects = root.get("objects").asObjectOrNull()
            ?: failXCoreAsset("asset index '${ref.id}' has no objects map")
        val parsed = ArrayList<XCoreAssetObject>(objects.size())
        for ((name, value) in objects.entrySet()) {
            val entry = value.asObjectOrNull()
                ?: failXCoreAsset("asset '$name' is not an object")
            val hash = entry.optString("hash")
                ?: failXCoreAsset("asset '$name' has no hash")
            val sha1 = XCoreSha1.parse(hash).valueOrNull()
                ?: failXCoreAsset("asset '$name' carries a hash that is not a sha1")
            if (!isSafeAssetName(name)) {
                failXCoreAsset("asset name '$name' cannot be used as a relative path")
            }
            parsed += XCoreAssetObject(
                name = name,
                sha1 = sha1,
                sizeBytes = entry.optLong("size") ?: -1L,
            )
        }
        XCoreAssetIndex(
            id = ref.id,
            objects = parsed.sortedBy { it.name },
            indexSha1 = ref.sha1,
            sourceUrl = ref.url,
        )
    }

    /** Objects already on disk hashing correctly; costs one read per object, no bandwidth. */
    fun objectsPresent(index: XCoreAssetIndex): List<XCoreAssetObject> = index.objects
        .filter { it.sizeBytes > 0L }
        .filter { asset ->
            val file = paths.resolve(asset.objectsRelativePath).valueOrNull() ?: return@filter false
            verifier.matches(file, asset.sha1).valueOrNull() == true
        }

    /** Transfers for the objects that are missing or wrong. */
    fun plan(
        index: XCoreAssetIndex,
        knownPresent: List<XCoreAssetObject> = objectsPresent(index),
    ): XCoreResult<XCoreAssetPlan> {
        val present = knownPresent.map { it.name }.toSet()
        val tasks = ArrayList<XCoreDownloadTask>()
        var missingBytes = 0L
        for (asset in index.objects) {
            if (asset.name in present) continue
            tasks += XCoreDownloadTask(
                url = "$OBJECTS_BASE/${asset.bucket}/${asset.sha1.value}",
                relativeTarget = asset.objectsRelativePath,
                sha1 = asset.sha1,
                expectedBytes = asset.sizeBytes,
                origin = XCoreDownloadOrigin.ASSET,
            )
            missingBytes += asset.sizeBytes.coerceAtLeast(0L)
        }
        return XCoreAssetPlan(tasks, present.size, missingBytes).asXCoreSuccess()
    }

    /** Copies verified objects into the virtual tree, reporting the ones it could not place. */
    fun materialiseVirtual(index: XCoreAssetIndex): XCoreResult<List<XCoreAssetObject>> =
        xCoreRunCatching(origin = "assets.materialise(${index.id})") {
            val unresolved = ArrayList<XCoreAssetObject>()
            for (asset in index.objects) {
                val source = paths.resolve(asset.objectsRelativePath).valueOrNull()
                    ?: run {
                        unresolved += asset
                        continue
                    }
                val destination = paths.resolve(asset.virtualPath(index.id)).valueOrNull()
                    ?: run {
                        unresolved += asset
                        continue
                    }
                if (!source.isFile) {
                    unresolved += asset
                    continue
                }
                if (destination.isFile && destination.length() == source.length()) continue
                try {
                    destination.parentFile?.mkdirs()
                    source.inputStream().use { input ->
                        destination.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                } catch (io: IOException) {
                    logger?.log(
                        XCoreLogLevel.WARN,
                        XCoreLogTags.USE_CASE,
                        "could not place virtual asset ${asset.name}: ${io.message}",
                    )
                    unresolved += asset
                }
            }
            unresolved
        }

    fun objectFile(asset: XCoreAssetObject): XCoreResult<File> =
        paths.resolve(asset.objectsRelativePath)

    fun virtualFile(
        index: XCoreAssetIndex,
        asset: XCoreAssetObject,
    ): XCoreResult<File> = paths.resolve(asset.virtualPath(index.id))

    private fun isSafeAssetName(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith("/") &&
            !name.contains('\\') &&
            name.split('/').none { it.isBlank() || it == ".." }

    private fun failXCoreAsset(detail: String): Nothing =
        throw studio.xycore.xcore.core.XCoreFailureException(
            XCoreError.Network(detail = detail, retryable = false),
        )

    companion object {
        const val OBJECTS_BASE = "https://resources.download.minecraft.net"
    }
}
