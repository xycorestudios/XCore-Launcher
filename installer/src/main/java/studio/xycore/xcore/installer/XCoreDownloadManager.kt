package studio.xycore.xcore.installer

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.onFailure
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreDownloadRecord
import studio.xycore.xcore.data.XCoreDownloadState
import studio.xycore.xcore.versions.XCoreDistributionEndpoints
import studio.xycore.xcore.versions.XCoreRetryPolicy

/** What the byte engine is being asked to read. */
data class XCoreRangeRequest(
    val url: String,
    val fromBytes: Long,
    val eTag: String?,
    val lastModifiedMillis: Long?,
) {
    val isResume: Boolean get() = fromBytes > 0L
}

/**
 * An open response body, read a chunk at a time.
 *
 * Deliberately narrow: the transfer engine needs the status, the validators for resuming, and bytes.
 * That is also what lets the resume, retry and digest behaviour be driven from a scripted peer in a
 * unit test rather than against a live socket.
 */
interface XCoreByteResponse {
    val statusCode: Int
    val declaredContentLength: Long
    val totalBytesWhenPartial: Long?
    val eTag: String?
    val lastModifiedMillis: Long?
    val acceptsByteRanges: Boolean

    /** Reads up to [buffer] size bytes, returning -1 at end of body. */
    suspend fun read(buffer: ByteArray): Int

    fun close()
}

/** Opens a ranged GET. Production uses [XCoreHttpByteTransport]. */
interface XCoreByteTransport {
    suspend fun open(request: XCoreRangeRequest): XCoreResult<XCoreByteResponse>
}

/** `HttpURLConnection` byte transport with `Range` and `If-Range`. */
class XCoreHttpByteTransport(
    private val userAgent: String,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 30_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XCoreByteTransport {

    override suspend fun open(request: XCoreRangeRequest): XCoreResult<XCoreByteResponse> =
        XCoreDistributionEndpoints
            .requireAllowed(request.url, field = "url")
            .flatMap { authorised ->
                withContext(io) {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = (authorised.openConnection() as? HttpURLConnection)
                            ?: return@withContext XCoreError.Network(
                                "endpoint does not speak HTTP",
                                url = request.url,
                            ).asXCoreFailure()
                        connection.apply {
                            requestMethod = "GET"
                            connectTimeout = connectTimeoutMillis
                            readTimeout = readTimeoutMillis
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept", "application/octet-stream")
                            if (request.isResume) {
                                setRequestProperty("Range", "bytes=${request.fromBytes}-")
                                request.eTag?.let { setRequestProperty("If-Range", it) }
                                    ?: setRequestProperty(
                                        "If-Range",
                                        httpDate(request.lastModifiedMillis ?: return@apply),
                                    )
                            }
                        }
                        val status = connection.responseCode
                        if (status !in 200..299 && status != RANGE_NOT_SATISFIABLE) {
                            return@withContext XCoreError.Network(
                                detail = "unexpected status ${connection.responseMessage?.trim() ?: "n/a"}",
                                url = request.url,
                                httpStatus = status,
                                retryable = status == 408 || status == 429 || status in 500..599,
                            ).asXCoreFailure()
                        }
                        val stream = connection.inputStream
                            ?: return@withContext XCoreError.Network(
                                detail = "no body for a $status response",
                                url = request.url,
                                httpStatus = status,
                            ).asXCoreFailure()
                        XCoreStreamedResponse(
                            statusCode = status,
                            connection = connection,
                            stream = stream,
                        ).asXCoreSuccess()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        connection?.disconnect()
                        throw cancelled
                    } catch (ioFailure: IOException) {
                        connection?.disconnect()
                        XCoreError.Network(
                            detail = "transport failure: ${ioFailure.message}",
                            url = request.url,
                            cause = ioFailure,
                            retryable = true,
                        ).asXCoreFailure()
                    }
                }
            }

    /** `If-Range` wants an HTTP date; `Last-Modified` is the only validator older hosts send. */
    private fun httpDate(millis: Long): String =
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(millis))
}

private const val RANGE_NOT_SATISFIABLE = 416

/** Adapts a live `HttpURLConnection` body to [XCoreByteResponse]. */
private class XCoreStreamedResponse(
    override val statusCode: Int,
    private val connection: HttpURLConnection,
    private val stream: java.io.InputStream,
) : XCoreByteResponse {

    override val declaredContentLength: Long = connection.contentLengthLong

    override val totalBytesWhenPartial: Long? = parseTotal(connection.getHeaderField("Content-Range"))

    override val eTag: String? = connection.getHeaderField("ETag")

    override val lastModifiedMillis: Long? =
        connection.getHeaderFieldDate("Last-Modified", -1L).takeIf { it >= 0L }

    override val acceptsByteRanges: Boolean =
        connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        stream.read(buffer)
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { connection.disconnect() }
    }

    private fun parseTotal(header: String?): Long? {
        val suffix = header?.substringAfter('/', "")?.trim() ?: return null
        return suffix.toLongOrNull()?.takeIf { it > 0L }
    }
}

/**
 * Fetches files with resume, retry, digest verification and a durable journal.
 *
 * The interesting behaviour is all in the edges:
 *  - a partial file is continued with `Range` and only if `If-Range` still validates, so a swapped
 *    artifact on the server restarts the transfer instead of stitching two different files;
 *  - cancellation is observed between chunks, journaling `PAUSED` and keeping the `.part` file, so
 *    the next attempt resumes where bytes were actually written;
 *  - a digest mismatch deletes the partial and journals `FAILED`; nothing is ever renamed into place
 *    without matching the published hash when one exists.
 */
class XCoreDownloadManager(
    private val transport: XCoreByteTransport,
    private val journal: XCoreDownloadJournal,
    private val verifier: XCoreHashVerifier = XCoreHashVerifier(),
    private val retryPolicy: XCoreRetryPolicy = XCoreRetryPolicy(),
    private val logger: XCoreLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val chunkBytes: Int = 64 * 1024,
    private val journalEveryBytes: Long = 256L * 1024L,
    private val progressEveryMillis: Long = 400L,
) {

    /** Transfers one task, journalling its state the whole way through. */
    suspend fun transfer(
        download: ResolvedDownload,
        label: String = download.task.relativeTarget.substringAfterLast('/'),
        listener: XCoreTransferListener? = null,
        completedTasks: Int = 0,
        totalTasks: Int = 1,
        aggregateBytesDone: AtomicLong = AtomicLong(0L),
        aggregateBytesTotal: Long = 0L,
    ): XCoreTransferOutcome {
        val task = download.task
        val startedAt = clock()
        val record = journal.find(task.id).valueOrNull()
            ?: task.toRecord(startedAt).valueOrNull()
            ?: return XCoreTransferOutcome.Failed(
                task.relativeTarget,
                XCoreError.Unexpected("no journal row could be opened for ${task.relativeTarget}"),
            )

        if (task.recheckExisting && download.alreadySatisfied(verifier)) {
            if (record.state != XCoreDownloadState.COMPLETED) {
                journal.transition(task.id, XCoreDownloadState.COMPLETED)
            }
            aggregateBytesDone.addAndGet(download.target.length())
            return XCoreTransferOutcome.AlreadyPresent(task.relativeTarget, download.target.length())
        }

        val writtenOnDisk = download.partial.length().coerceAtLeast(0L)
        var offset = if (record.etag == null && task.sha1 == null) 0L else writtenOnDisk
        var attempt = 1
        var lastError: XCoreError = XCoreError.Network("no attempt has run yet", url = task.url)

        while (attempt <= retryPolicy.maxAttempts) {
            val outcome = runAttempt(
                download = download,
                record = record,
                offset = offset,
                attempt = attempt,
                startedAt = startedAt,
                label = label,
                listener = listener,
                completedTasks = completedTasks,
                totalTasks = totalTasks,
                aggregateBytesDone = aggregateBytesDone,
                aggregateBytesTotal = aggregateBytesTotal,
            )
            when {
                outcome is XCoreTransferOutcome.Written || outcome is XCoreTransferOutcome.AlreadyPresent ->
                    return outcome

                outcome is XCoreTransferOutcome.Failed -> {
                    lastError = outcome.error
                    val resumable = outcome.error.retryable || isInterrupted(outcome.error)
                    if (!resumable || attempt == retryPolicy.maxAttempts) return outcome
                    offset = download.partial.length().coerceAtLeast(0L)
                    logger?.log(
                        XCoreLogLevel.WARN,
                        XCoreLogTags.TASKS,
                        "retrying $label (attempt ${attempt + 1}) from $offset bytes: ${outcome.error.detail}",
                    )
                    delay(retryPolicy.delayMillisBefore(attempt))
                }
            }
            attempt++
        }
        return XCoreTransferOutcome.Failed(task.relativeTarget, lastError)
    }

    private suspend fun runAttempt(
        download: ResolvedDownload,
        record: XCoreDownloadRecord,
        offset: Long,
        attempt: Int,
        startedAt: Long,
        label: String,
        listener: XCoreTransferListener?,
        completedTasks: Int,
        totalTasks: Int,
        aggregateBytesDone: AtomicLong,
        aggregateBytesTotal: Long,
    ): XCoreTransferOutcome {
        val task = download.task
        journal.transition(task.id, XCoreDownloadState.RUNNING, attemptsDelta = 1)
            .onFailure { missing ->
                logger?.log(
                    XCoreLogLevel.WARN,
                    XCoreLogTags.TASKS,
                    "journal rejected the RUNNING transition for $label: ${missing.detail}",
                )
            }

        val request = XCoreRangeRequest(
            url = task.url,
            fromBytes = offset,
            eTag = record.etag ?: task.eTag,
            lastModifiedMillis = null,
        )
        return when (val opened = transport.open(request)) {
            is XCoreResult.Cancelled -> finishCancelled(download, label, offset)
            is XCoreResult.Failure -> {
                journal.transition(task.id, XCoreDownloadState.FAILED)
                XCoreTransferOutcome.Failed(task.relativeTarget, opened.error)
            }

            is XCoreResult.Success -> writeBody(
                download = download,
                response = opened.value,
                offset = offset,
                startedAt = startedAt,
                label = label,
                listener = listener,
                completedTasks = completedTasks,
                totalTasks = totalTasks,
                aggregateBytesDone = aggregateBytesDone,
                aggregateBytesTotal = aggregateBytesTotal,
            )
        }
    }

    private suspend fun writeBody(
        download: ResolvedDownload,
        response: XCoreByteResponse,
        offset: Long,
        startedAt: Long,
        label: String,
        listener: XCoreTransferListener?,
        completedTasks: Int,
        totalTasks: Int,
        aggregateBytesDone: AtomicLong,
        aggregateBytesTotal: Long,
    ): XCoreTransferOutcome {
        val task = download.task
        try {
            // 416: the server has fewer bytes than we asked to resume from. If the finished file
            // already hashes correctly the transfer is done; otherwise the partial is stale.
            if (response.statusCode == RANGE_NOT_SATISFIABLE) {
                return if (download.alreadySatisfied(verifier)) {
                    journal.transition(task.id, XCoreDownloadState.COMPLETED)
                    XCoreTransferOutcome.AlreadyPresent(task.relativeTarget, download.target.length())
                } else {
                    download.partial.delete()
                    journal.transition(task.id, XCoreDownloadState.PENDING)
                    XCoreTransferOutcome.Failed(
                        task.relativeTarget,
                        XCoreError.Network(
                            detail = "the server reports $offset bytes already exist but the file does not " +
                                "match its published digest; the partial was discarded and will be re-read",
                            url = task.url,
                            httpStatus = RANGE_NOT_SATISFIABLE,
                        ),
                    )
                }
            }

            // A 200 in answer to a Range request means the validator no longer matches (or the host
            // ignores ranges), so the partial must not be appended to.
            val restarts = response.statusCode == HttpURLConnection.HTTP_OK && offset > 0L
            var writtenBefore = offset
            if (restarts) {
                download.partial.delete()
                writtenBefore = 0L
                logger?.log(
                    XCoreLogLevel.INFO,
                    XCoreLogTags.TASKS,
                    "$label: server returned the whole body, restarting from 0 bytes",
                )
            }

            val totalFromServer = response.totalBytesWhenPartial?.let { it + writtenBefore }
                ?: task.expectedBytes.takeIf { it > 0L }
                ?: (writtenBefore + response.declaredContentLength.coerceAtLeast(0L)).takeIf {
                    response.declaredContentLength >= 0L
                }

            var done = writtenBefore
            var sinceJournal = 0L
            var lastProgressAt = startedAt
            val buffer = ByteArray(chunkBytes)

            download.prepareTarget()
            FileOutputStream(download.partial, writtenBefore > 0L)
                .buffered(chunkBytes)
                .use { sink ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = response.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        sinceJournal += read
                        val now = clock()
                        if (sinceJournal >= journalEveryBytes) {
                            journal.addWritten(task.id, sinceJournal)
                                .onFailure { logger?.log(XCoreLogLevel.WARN, XCoreLogTags.TASKS, it.detail) }
                            sinceJournal = 0L
                        }
                        if (now - lastProgressAt >= progressEveryMillis) {
                            lastProgressAt = now
                            val elapsed = (now - startedAt).coerceAtLeast(1L)
                            listener?.onProgress(
                                XCoreTransferProgress(
                                    completedTasks = completedTasks,
                                    totalTasks = totalTasks,
                                    currentTaskBytes = done,
                                    currentTaskTotalBytes = totalFromServer ?: -1L,
                                    aggregateBytesDone = aggregateBytesDone.get() + done,
                                    aggregateBytesTotal = aggregateBytesTotal,
                                    bytesPerSecond = done * 1000L / elapsed,
                                    label = label,
                                ),
                            )
                        }
                    }
                    sink.flush()
                }

            if (sinceJournal > 0L) journal.addWritten(task.id, sinceJournal)

            val verified = verifier.verify(download.partial, task.sha1, task.expectedBytes)
            return when (verified) {
                is XCoreResult.Failure -> {
                    download.partial.delete()
                    journal.transition(task.id, XCoreDownloadState.FAILED)
                    XCoreTransferOutcome.Failed(
                        task.relativeTarget,
                        XCoreError.Storage(
                            detail = "${task.relativeTarget} failed verification: ${verified.error.detail}",
                            path = download.partial.absolutePath,
                            retryable = true,
                            cause = verified.error.cause,
                        ),
                    )
                }

                is XCoreResult.Cancelled -> finishCancelled(download, label, done)
                is XCoreResult.Success -> {
                    if (!promote(download)) {
                        return XCoreTransferOutcome.Failed(
                            task.relativeTarget,
                            XCoreError.Storage(
                                detail = "could not move the finished transfer into place at ${download.target.absolutePath}",
                                path = download.target.absolutePath,
                                retryable = true,
                            ),
                        )
                    }
                    journal.transition(task.id, XCoreDownloadState.COMPLETED)
                    val bytes = download.target.length()
                    aggregateBytesDone.addAndGet(bytes)
                    listener?.onProgress(
                        XCoreTransferProgress(
                            completedTasks = completedTasks + 1,
                            totalTasks = totalTasks,
                            currentTaskBytes = bytes,
                            currentTaskTotalBytes = bytes,
                            aggregateBytesDone = aggregateBytesDone.get(),
                            aggregateBytesTotal = aggregateBytesTotal,
                            bytesPerSecond = bytes * 1000L / (clock() - startedAt).coerceAtLeast(1L),
                            label = label,
                        ),
                    )
                    return XCoreTransferOutcome.Written(task.relativeTarget, bytes, resumedFrom = writtenBefore)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (ioFailure: IOException) {
            journal.transition(task.id, XCoreDownloadState.PAUSED)
            return XCoreTransferOutcome.Failed(
                task.relativeTarget,
                XCoreError.Storage(
                    detail = "writing ${task.relativeTarget} was interrupted: ${ioFailure.message}",
                    path = download.partial.absolutePath,
                    retryable = true,
                    cause = ioFailure,
                ),
            )
        } finally {
            response.close()
        }
    }

    private suspend fun finishCancelled(
        download: ResolvedDownload,
        label: String,
        offset: Long,
    ): XCoreTransferOutcome {
        journal.transition(download.task.id, XCoreDownloadState.PAUSED)
        logger?.log(
            XCoreLogLevel.INFO,
            XCoreLogTags.TASKS,
            "$label cancelled at $offset bytes; the partial file is kept for the next attempt",
        )
        return XCoreTransferOutcome.Failed(
            download.task.relativeTarget,
            XCoreError.Cancelled(
                detail = "the transfer of $label stopped after $offset bytes",
                operation = label,
            ),
        )
    }

    /** Runs a batch with bounded concurrency, keeping per-file failures separate from systemic ones. */
    suspend fun transferAll(
        downloads: List<ResolvedDownload>,
        maxConcurrency: Int = DEFAULT_CONCURRENCY,
        labels: Map<String, String> = emptyMap(),
        failFast: Boolean = true,
        listener: XCoreTransferListener? = null,
    ): XCoreResult<XCoreBatchOutcome> {
        if (downloads.isEmpty()) return XCoreBatchOutcome().asXCoreSuccess()
        val totalBytes = downloads.sumOf { it.task.expectedBytes.coerceAtLeast(0L) }
        val aggregateDone = AtomicLong(0L)
        val completed = AtomicLong(0L)
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, MAX_CONCURRENCY_LIMIT))
        val outcomes: List<Pair<XCoreDownloadTask, XCoreTransferOutcome>> = coroutineScope {
            downloads.map { download ->
                async {
                    semaphore.withPermit {
                        val outcome = transfer(
                            download = download,
                            label = labels[download.task.id] ?: defaultLabel(download),
                            listener = listener,
                            completedTasks = completed.get().toInt(),
                            totalTasks = downloads.size,
                            aggregateBytesDone = aggregateDone,
                            aggregateBytesTotal = totalBytes,
                        )
                        completed.incrementAndGet()
                        download.task to outcome
                    }
                }
            }.awaitAll()
        }
        val batch = XCoreBatchOutcome.of(outcomes)
        if (failFast && batch.failures.isNotEmpty()) {
            logger?.log(
                XCoreLogLevel.ERROR,
                XCoreLogTags.TASKS,
                "${batch.failures.size} of ${downloads.size} transfers failed: ${batch.describeFailures()}",
            )
        }
        return batch.asXCoreSuccess()
    }

    /**
     * Re-runs everything the journal still calls open.
     *
     * A row in `RUNNING` means the process died mid-transfer: the partial file is still valid, so the
     * row is demoted to `PAUSED` and continued rather than being treated as finished.
     */
    suspend fun resumeOpen(
        paths: XCoreInstallPaths,
        maxConcurrency: Int = DEFAULT_CONCURRENCY,
        listener: XCoreTransferListener? = null,
    ): XCoreResult<XCoreBatchOutcome> {
        val open = journal.openRecords().map { rows ->
            rows.filter { it.state != XCoreDownloadState.COMPLETED }
        }
        if (open is XCoreResult.Failure) return XCoreResult.Failure(open.error)
        val rows = (open as XCoreResult.Success).value
        if (rows.isEmpty()) return XCoreBatchOutcome().asXCoreSuccess()
        val downloads = ArrayList<ResolvedDownload>(rows.size)
        for (row in rows) {
            val resolved = paths.resolve(row.relativeTarget).map { target ->
                ResolvedDownload(
                    task = XCoreDownloadTask(
                        url = row.url,
                        relativeTarget = row.relativeTarget,
                        sha1 = row.sha1,
                        expectedBytes = row.expectedBytes,
                        origin = XCoreDownloadOrigin.OTHER,
                        eTag = row.etag,
                    ),
                    target = target,
                    partial = File(target.parentFile, "${target.name}.part"),
                )
            }
            if (resolved is XCoreResult.Success) {
                downloads += resolved.value
            } else if (row.state == XCoreDownloadState.RUNNING) {
                journal.transition(row.id, XCoreDownloadState.FAILED)
            }
        }
        val runningReset = rows.filter { it.state == XCoreDownloadState.RUNNING }
            .mapNotNull { journal.transition(it.id, XCoreDownloadState.PAUSED).errorOrNull()?.let { error -> it.id to error } }
        if (runningReset.isNotEmpty()) {
            logger?.log(
                XCoreLogLevel.WARN,
                XCoreLogTags.TASKS,
                "${runningReset.size} journal rows were stuck in RUNNING; their partial files are being continued",
            )
        }
        return transferAll(downloads, maxConcurrency, listener = listener)
    }

    private fun defaultLabel(download: ResolvedDownload): String =
        download.task.origin.name.lowercase() + ":" + download.task.relativeTarget.substringAfterLast('/')

    companion object {
        const val DEFAULT_CONCURRENCY = 4
        const val MAX_CONCURRENCY_LIMIT = 8

        private fun isInterrupted(error: XCoreError): Boolean =
            error is XCoreError.Storage && error.retryable
    }
}

private fun ResolvedDownload.prepareTarget() {
    target.parentFile?.mkdirs()
}

/** Renames the partial file over the destination, falling back to copy when rename fails. */
private fun promote(download: ResolvedDownload): Boolean {
    if (download.target.exists() && !download.target.delete()) return false
    if (download.partial.renameTo(download.target)) return true
    return try {
        download.partial.inputStream().use { input ->
            FileOutputStream(download.target).use { output -> input.copyTo(output, 64 * 1024) }
        }
        download.partial.delete()
        download.target.isFile
    } catch (ioFailure: IOException) {
        false
    }
}
