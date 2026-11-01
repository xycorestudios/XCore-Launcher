package studio.xycore.xcore.installer

import java.io.File
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map
import studio.xycore.xcore.data.XCoreDownloadRecord
import studio.xycore.xcore.data.XCoreDownloadState
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.versions.XCoreDistributionEndpoints

/** What a transfer is for; drives where resumability and verification are expected. */
enum class XCoreDownloadOrigin(val cacheableForever: Boolean) {
    CLIENT_JAR(false),
    LIBRARY(true),
    NATIVE(true),
    ASSET(true),
    VERSION_PROFILE(false),
    LOGGING_CONFIG(false),
    RUNTIME(false),
    OTHER(false),
}

/**
 * One file the installer needs: where it comes from, where it goes, and what proves it is right.
 *
 * [relativeTarget] is always relative to the prepared storage root. Absolute paths and `..`
 * segments are rejected by [XCoreInstallPaths.resolve], because a target arrives from downloaded
 * metadata and the launcher must not let a document decide where on the device it writes.
 */
data class XCoreDownloadTask(
    val url: String,
    val relativeTarget: String,
    val sha1: XCoreSha1?,
    val expectedBytes: Long,
    val origin: XCoreDownloadOrigin,
    val recheckExisting: Boolean = true,
    val eTag: String? = null,
) {
    /** Deterministic journal id: the same URL and target always name the same row. */
    val id: String = transferId(url, relativeTarget)

    fun validate(): XCoreResult<XCoreDownloadTask> {
        if (expectedBytes < -1L) {
            return XCoreError.Validation(
                detail = "expectedBytes must be a length or -1 when the size is unknown",
                field = "expectedBytes",
                rejected = expectedBytes.toString(),
            ).asXCoreFailure()
        }
        if (!XCoreDistributionEndpoints.isAllowed(url)) {
            return XCoreError.Permission(
                detail = "refusing to fetch from a host that is not an approved distribution endpoint",
                permission = "download:$url",
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    /** A journal row describing this transfer before any byte has moved. */
    fun toRecord(
        nowMillis: Long,
        state: XCoreDownloadState = XCoreDownloadState.PENDING,
    ): XCoreResult<XCoreDownloadRecord> = XCoreDownloadRecord(
        id = id,
        url = url,
        relativeTarget = relativeTarget,
        sha1 = sha1,
        expectedBytes = expectedBytes,
        writtenBytes = 0L,
        state = state,
        etag = eTag,
        attempts = 0,
        updatedAtMillis = nowMillis,
    ).asXCoreSuccess()

    companion object {
        /** 16 hex characters of SHA-256 over `url|target`, stable across processes. */
        fun transferId(
            url: String,
            relativeTarget: String,
        ): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest("$url|$relativeTarget".toByteArray(Charsets.UTF_8))
            return buildString(16) {
                for (i in 0 until 8) {
                    val value = bytes[i].toInt() and 0xff
                    if (value < 0x10) append('0')
                    append(value.toString(16))
                }
            }
        }
    }
}

/** A task whose destination has been resolved against the storage root. */
data class ResolvedDownload(
    val task: XCoreDownloadTask,
    val target: File,
    val partial: File,
) {
    /**
     * True when the finished file is already on disk and hashes to what the metadata promised.
     *
     * Without a published digest there is nothing to prove, so an existing file is re-fetched: a
     * length match alone would let a truncated download from an earlier run pass as complete.
     */
    fun alreadySatisfied(verifier: XCoreHashVerifier): Boolean {
        if (!target.isFile) return false
        val expected = task.sha1 ?: return false
        return verifier.matches(target, expected).valueOrFalse()
    }
}

private fun XCoreResult<Boolean>.valueOrFalse(): Boolean =
    (this as? XCoreResult.Success)?.value == true

/**
 * Resolves and prepares destinations inside a prepared storage layout.
 *
 * This is the installer's own copy of the containment rules `XCorePaths` applies when the layout is
 * built, deliberately re-checked at write time: the relative paths it receives come from downloaded
 * metadata, so the check that matters is the one immediately before the `FileOutputStream`.
 */
class XCoreInstallPaths(val layout: XCoreStorageLayout) {

    val root: File get() = layout.plan.root

    fun directory(kind: XCoreGameDirectory): XCoreResult<File> =
        layout.dir(kind)?.asXCoreSuccess()
            ?: XCoreError.Storage(
                detail = "the ${kind.name.lowercase()} directory is not part of the prepared layout",
                path = layout.plan.rootPath,
            ).asXCoreFailure()

    /**
     * Resolves [relativePath] under the storage root, rejecting anything that could escape it.
     *
     * Rejection is textual first and canonical second, so a path like `a/../../escape` fails even on
     * a device where `getCanonicalFile` would have resolved it inside the root by accident.
     */
    fun resolve(relativePath: String): XCoreResult<File> {
        val trimmed = relativePath.trim()
        if (trimmed.isEmpty()) {
            return XCoreError.Validation("target path must not be blank", field = "relativeTarget").asXCoreFailure()
        }
        if (trimmed.startsWith("/") || trimmed.contains('\\') || trimmed.contains(':')) {
            return XCoreError.Validation(
                detail = "target must be relative to the storage root and use forward slashes",
                field = "relativeTarget",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        val segments = trimmed.split('/')
        if (segments.any { it == ".." || it.isBlank() }) {
            return XCoreError.Validation(
                detail = "target may not contain empty or parent-traversing segments",
                field = "relativeTarget",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        if (segments.last().startsWith(".")) {
            return XCoreError.Validation(
                detail = "target file name may not start with a dot",
                field = "relativeTarget",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        val candidate = File(root, trimmed)
        val rootCanonical = canonical(root)
        val candidateCanonical = canonical(candidate)
        if (!candidateCanonical.path.startsWith(rootCanonical.path + File.separator) &&
            candidateCanonical.path != rootCanonical.path
        ) {
            return XCoreError.Permission(
                detail = "target '${candidate.absolutePath}' resolves outside the storage root",
                permission = "storage-escape",
            ).asXCoreFailure()
        }
        return candidate.asXCoreSuccess()
    }

    /** The root-relative form of an absolute path, for journal rows. */
    fun relativeOf(file: File): String {
        val rootPath = canonical(root).path
        val filePath = canonical(file).path
        return if (filePath.startsWith(rootPath + File.separator)) {
            filePath.substring(rootPath.length + 1).replace(File.separatorChar, '/')
        } else {
            file.absolutePath.replace(File.separatorChar, '/')
        }
    }

    /** Free space at the directory's volume; conservative, since Android reports per-volume. */
    fun usableBytesAt(destination: File): Long {
        val probe = destination.parentFile ?: root
        probe.mkdirs()
        return probe.usableSpace.coerceAtLeast(0L)
    }

    /** Creates the parent chain for [file] and returns it. */
    fun prepareParentOf(file: File): XCoreResult<File> {
        val parent = file.parentFile ?: return file.asXCoreSuccess()
        if (parent.isDirectory || parent.mkdirs()) return file.asXCoreSuccess()
        return XCoreError.Storage(
            detail = "cannot create ${parent.absolutePath}",
            path = parent.absolutePath,
            usableBytes = parent.usableSpace,
            retryable = false,
        ).asXCoreFailure()
    }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (io: java.io.IOException) {
        file.absoluteFile
    }
}

/**
 * Durable journal for byte transfers.
 *
 * Backed in production by the `xcore_download_journal` table, whose partial unique index on
 * `relative_target` is what guarantees two installs cannot both claim the same file. The interface
 * exists so transfer semantics can be tested against a scripted peer instead of a real socket.
 */
interface XCoreDownloadJournal {

    suspend fun save(record: XCoreDownloadRecord): XCoreResult<XCoreDownloadRecord>

    suspend fun find(id: String): XCoreResult<XCoreDownloadRecord?>

    suspend fun addWritten(
        id: String,
        deltaBytes: Long,
        state: XCoreDownloadState = XCoreDownloadState.RUNNING,
    ): XCoreResult<XCoreDownloadRecord>

    suspend fun transition(
        id: String,
        state: XCoreDownloadState,
        attemptsDelta: Int = 0,
    ): XCoreResult<XCoreDownloadRecord>

    suspend fun openRecords(limit: Int = 500): XCoreResult<List<XCoreDownloadRecord>>
}

/** How far a batch has come, in the units the UI actually renders. */
data class XCoreTransferProgress(
    val completedTasks: Int,
    val totalTasks: Int,
    val currentTaskBytes: Long,
    val currentTaskTotalBytes: Long,
    val aggregateBytesDone: Long,
    val aggregateBytesTotal: Long,
    val bytesPerSecond: Long,
    val label: String,
) {
    val aggregateFraction: Float
        get() = if (aggregateBytesTotal > 0L) {
            (aggregateBytesDone.toFloat() / aggregateBytesTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            if (totalTasks > 0) completedTasks.toFloat() / totalTasks.toFloat() else 0f
        }
}

/**
 * Receives progress for a running install.
 *
 * Called from the transfer's own coroutine, so implementations must not block; the launcher's UI
 * adapter forwards into a `StateFlow`.
 */
fun interface XCoreTransferListener {
    fun onProgress(progress: XCoreTransferProgress)
}

/** The result of one transfer, including the case where no bytes needed to move. */
sealed interface XCoreTransferOutcome {

    data class Written(
        val relativeTarget: String,
        val bytes: Long,
        val resumedFrom: Long,
    ) : XCoreTransferOutcome

    data class AlreadyPresent(
        val relativeTarget: String,
        val bytes: Long,
    ) : XCoreTransferOutcome

    data class Failed(
        val relativeTarget: String,
        val error: XCoreError,
    ) : XCoreTransferOutcome
}

/** Everything a batch produced, including per-file failures the caller must decide about. */
data class XCoreBatchOutcome(
    val written: List<XCoreDownloadTask> = emptyList(),
    val alreadyPresent: List<XCoreDownloadTask> = emptyList(),
    val failures: List<Pair<XCoreDownloadTask, XCoreError>> = emptyList(),
    val bytesWritten: Long = 0L,
    val bytesSkipped: Long = 0L,
) {
    val filesTouched: Int get() = written.size + alreadyPresent.size

    val isClean: Boolean get() = failures.isEmpty()

    fun describeFailures(limit: Int = 3): String = failures.take(limit).joinToString("; ") { (task, error) ->
        "${task.origin.name.lowercase()} ${shortName(task.relativeTarget)}: ${error.detail}"
    } + if (failures.size > limit) " and ${failures.size - limit} more" else ""

    private fun shortName(path: String): String = path.substringAfterLast('/')

    companion object {
        fun of(outcomes: List<Pair<XCoreDownloadTask, XCoreTransferOutcome>>): XCoreBatchOutcome {
            var bytes = 0L
            var skipped = 0L
            val written = ArrayList<XCoreDownloadTask>()
            val present = ArrayList<XCoreDownloadTask>()
            val failures = ArrayList<Pair<XCoreDownloadTask, XCoreError>>()
            for ((task, outcome) in outcomes) {
                when (outcome) {
                    is XCoreTransferOutcome.Written -> {
                        written += task
                        bytes += outcome.bytes
                    }

                    is XCoreTransferOutcome.AlreadyPresent -> {
                        present += task
                        skipped += outcome.bytes
                    }

                    is XCoreTransferOutcome.Failed -> failures += task to outcome.error
                }
            }
            return XCoreBatchOutcome(written, present, failures, bytes, skipped)
        }
    }
}

/**
 * Turns [task] into a resolved transfer after checking the destination is writable and the volume
 * can actually hold the result.
 */
fun XCoreDownloadTask.resolveWith(
    paths: XCoreInstallPaths,
    spaceHeadroomBytes: Long = MIN_SPACE_HEADROOM_BYTES,
): XCoreResult<ResolvedDownload> = validate()
    .flatMap { paths.resolve(relativeTarget) }
    .flatMap { target ->
        val alreadyThere = if (target.isFile) target.length() else 0L
        val remaining = if (expectedBytes > 0L) expectedBytes - alreadyThere else 0L
        val required = remaining + spaceHeadroomBytes
        val usable = paths.usableBytesAt(target)
        if (required > 0L && usable < required) {
            XCoreError.Storage(
                detail = "'$relativeTarget' needs $required free bytes (payload plus headroom) but " +
                    "the volume at ${target.parentFile?.absolutePath ?: paths.root.absolutePath} " +
                    "reports $usable",
                path = target.absolutePath,
                usableBytes = usable,
                retryable = false,
            ).asXCoreFailure()
        } else {
            paths.prepareParentOf(target).map {
                ResolvedDownload(task = this, target = target, partial = File(target.parentFile, "${target.name}.part"))
            }
        }
    }

private const val MIN_SPACE_HEADROOM_BYTES = 2L * 1024 * 1024
