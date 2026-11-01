package studio.xycore.xcore.versions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.core.xCoreRun
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreSha1

/**
 * The only network locations X-Core is willing to read game content from.
 *
 * Everything here is a Mojang-owned distribution endpoint, or the Maven Central host that the
 * `libraries` section of a version profile points at for artifacts Mojang does not mirror. The
 * allowlist exists for two reasons: a profile fetched from a hostile host could otherwise redirect
 * the launcher into writing arbitrary bytes into the game tree, and the Minecraft EULA only
 * permits content to be served from Mojang's own distribution endpoints. Nothing in this list is a
 * third-party re-host of game files.
 */
object XCoreDistributionEndpoints {
    const val VERSION_MANIFEST_V2: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    val ALLOWED_HOSTS: Set<String> = setOf(
        "piston-meta.mojang.com",
        "piston-data.mojang.com",
        "launchermeta.mojang.com",
        "launcher.mojang.com",
        "libraries.minecraft.net",
        "download.mojang.com",
        "resources.download.minecraft.net",
        "repo1.maven.org",
    )

    /**
     * Parses and authorises [candidate] in one step. https is required unconditionally: a plain
     * http URL would let a network neighbour swap a jar for a different one, and every published
     * hash in this pipeline is a Mojang hash, not an integrity guarantee we control.
     */
    fun requireAllowed(candidate: String, field: String = "url"): XCoreResult<URL> {
        val trimmed = candidate.trim()
        val parsed = try {
            URL(trimmed)
        } catch (invalid: java.net.MalformedURLException) {
            return XCoreError.Validation(
                detail = "not a valid URL: ${invalid.message}",
                field = field,
                rejected = trimmed,
            ).asXCoreFailure()
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            return XCoreError.Validation(
                detail = "only https distribution endpoints are accepted, got '${parsed.protocol}'",
                field = field,
                rejected = trimmed,
            ).asXCoreFailure()
        }
        val host = parsed.host.lowercase()
        if (host !in ALLOWED_HOSTS) {
            return XCoreError.Permission(
                detail = "host '$host' is not an approved Minecraft distribution endpoint; " +
                    "game content may only be fetched from Mojang-owned hosts or the Maven coordinates " +
                    "named by an official version profile",
                permission = "distribution:$host",
            ).asXCoreFailure()
        }
        return parsed.asXCoreSuccess()
    }

    /** True when the URL is https and its host is approved, without authorising a request. */
    fun isAllowed(candidate: String): Boolean = requireAllowed(candidate).isSuccess
}

/**
 * ISO-8601 timestamps from Mojang metadata, parsed without `java.time`.
 *
 * `java.time` needs API 26 and X-Core supports API 24, so the few fields this launcher actually
 * needs are parsed by hand. Only the fixed-offset forms Mojang publishes are accepted; a zone name
 * would need a tzdb and never appears in this metadata.
 */
object XCoreTimestamps {
    private val ISO_8601 = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?" +
            "(Z|[+-]\\d{2}:?\\d{2})?$",
    )

    /** Millis since the Unix epoch, in UTC. */
    fun parseUtc(candidate: String): XCoreResult<Long> {
        val match = ISO_8601.matchEntire(candidate.trim())
            ?: return XCoreError.Validation(
                detail = "not an ISO-8601 timestamp",
                field = "timestamp",
                rejected = candidate,
            ).asXCoreFailure()
        val (year, month, day, hour, minute, second, fraction, zone) = match.destructured
        val offsetSeconds = parseOffset(zone)
        val days = daysFromCivil(year.toLong(), month.toLong(), day.toLong())
        val seconds = days * 86_400L +
            hour.toLong() * 3_600L +
            minute.toLong() * 60L +
            second.toLong() -
            offsetSeconds
        val millis = if (fraction.isEmpty()) {
            0L
        } else {
            fraction.padEnd(3, '0').take(3).toLong()
        }
        return (seconds * 1_000L + millis).asXCoreSuccess()
    }

    fun parseUtcOrZero(candidate: String?): Long =
        candidate?.let { parseUtc(it).valueOrNull() } ?: 0L

    private fun parseOffset(zone: String): Long = when {
        zone.isEmpty() || zone == "Z" -> 0L
        else -> {
            val sign = if (zone.first() == '-') -1L else 1L
            val body = zone.substring(1).replace(":", "")
            val hours = body.substring(0, 2).toLong()
            val minutes = body.substring(2, 4).toLong()
            sign * (hours * 3_600L + minutes * 60L)
        }
    }

    /** Howard Hinnant's days-from-civil: proleptic Gregorian, no timezone database needed. */
    private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = year - if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 +
            yearOfEra / 4 - yearOfEra / 100 + yearOfEra / 400 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}

/** One entry of `version_manifest_v2.json`. */
data class XCoreManifestEntry(
    val id: String,
    val releaseType: XCoreReleaseType,
    val profileUrl: String,
    val timeMillis: Long,
    val releaseTimeMillis: Long,
    val profileSha1: XCoreSha1?,
    val complianceLevel: Int?,
)

/** The parsed manifest document, exactly as the endpoint described it. */
data class XCoreVersionManifestDocument(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val entries: List<XCoreManifestEntry>,
) {
    companion object {
        /**
         * Parses `version_manifest_v2.json`.
         *
         * A single malformed entry fails the whole document rather than being dropped: a silently
         * skipped version would look like "Minecraft never published 1.20.5" to whoever read the
         * list next.
         */
        fun parse(json: String): XCoreResult<XCoreVersionManifestDocument> = xCoreRunCatching(
            origin = "manifest.parse",
            errorKind = XCoreErrorKind.NETWORK,
        ) {
            val root = readTree(json, "version manifest")
            val versions = root.requiredArray("versions", "version manifest")
            val entries = ArrayList<XCoreManifestEntry>(versions.size())
            for (element in versions) {
                entries += parseEntry(element, "version manifest entry")
            }
            val latest = root.get("latest").asObjectOrNull()
            XCoreVersionManifestDocument(
                latestRelease = latest?.optString("release"),
                latestSnapshot = latest?.optString("snapshot"),
                entries = entries,
            )
        }

        internal fun parseEntry(element: JsonElement, context: String): XCoreManifestEntry {
            val obj = element.asObjectOrNull()
                ?: failXCore(XCoreError.Network("$context '$element' is not an object", httpStatus = null))
            val id = obj.requiredString("id", context)
            val type = XCoreReleaseType.fromManifestId(obj.requiredString("type", context))
                .getOrThrowXCore()
            return XCoreManifestEntry(
                id = id,
                releaseType = type,
                profileUrl = obj.requiredString("url", context),
                timeMillis = XCoreTimestamps.parseUtcOrZero(obj.optString("time")),
                releaseTimeMillis = XCoreTimestamps.parseUtcOrZero(obj.optString("releaseTime")),
                profileSha1 = obj.optString("sha1")?.let { XCoreSha1.parse(it).valueOrNullXCore() },
                complianceLevel = obj.optLong("complianceLevel")?.toInt(),
            )
        }
    }
}

/**
 * A fetched, parsed version manifest with the lookups the launcher needs.
 *
 * The document is indexed rather than scanned: version pickers ask "is 1.21.4 in here" on every
 * keystroke, and 900+ entries linearly per recomposition is waste, not simplicity.
 */
class XCoreVersionManifest(
    val document: XCoreVersionManifestDocument,
    val sourceUrl: String,
    val fetchedAtMillis: Long,
    val servedFromCache: Boolean,
) {
    val entries: List<XCoreManifestEntry> = document.entries

    val entriesById: Map<String, XCoreManifestEntry> =
        document.entries.associateBy { it.id }

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    fun find(id: String): XCoreManifestEntry? = entriesById[id]

    fun findExact(id: String): XCoreResult<XCoreManifestEntry> = find(id)?.asXCoreSuccess()
        ?: XCoreError.Network(
            detail = "version '$id' is not in the manifest fetched from $sourceUrl",
            url = sourceUrl,
            retryable = false,
        ).asXCoreFailure()

    /**
     * Newest release first, optionally narrowed to a set of release types and a substring of the id.
     *
     * `releaseTime` orders the list because Mojang rewrites `time` for every entry whenever it
     * republishes metadata; a bulk touch of an old version would otherwise float it above current
     * releases. [XCoreManifestEntry.timeMillis] only breaks ties between entries released together.
     */
    fun select(
        releaseTypes: Set<XCoreReleaseType>? = null,
        query: String? = null,
        limit: Int = 200,
    ): List<XCoreManifestEntry> {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return entries
            .asSequence()
            .filter { releaseTypes == null || it.releaseType in releaseTypes }
            .filter { needle == null || it.id.lowercase().contains(needle) }
            .sortedWith(
                compareByDescending<XCoreManifestEntry> { it.releaseTimeMillis }
                    .thenByDescending { it.timeMillis }
                    .thenByDescending { it.id },
            )
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    companion object {
        fun parse(
            json: String,
            sourceUrl: String = XCoreDistributionEndpoints.VERSION_MANIFEST_V2,
            fetchedAtMillis: Long = System.currentTimeMillis(),
            servedFromCache: Boolean = false,
        ): XCoreResult<XCoreVersionManifest> = XCoreVersionManifestDocument.parse(json).map {
            XCoreVersionManifest(it, sourceUrl, fetchedAtMillis, servedFromCache)
        }
    }
}

/** A remote JSON document plus the validators needed to revalidate a cached copy. */
data class XCoreRemoteDocument(
    val body: String,
    val sourceUrl: String,
    val eTag: String?,
    val lastModifiedMillis: Long?,
    val contentLength: Long,
    val servedFromCache: Boolean = false,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Reads one JSON document over HTTPS.
 *
 * Kept as an interface so the parsing and install-state tests can drive the whole pipeline with a
 * scripted document while production uses [XCoreHttpDocumentFetcher]. This is a seam for tests, not
 * a place a production caller is ever handed a stub.
 */
fun interface XCoreRemoteDocumentFetcher {
    suspend fun fetch(
        url: String,
        etag: String?,
    ): XCoreResult<XCoreRemoteDocument>
}

/** Read a document without revalidation state. */
suspend fun XCoreRemoteDocumentFetcher.fetch(url: String): XCoreResult<XCoreRemoteDocument> =
    fetch(url, etag = null)

/** Backoff used for both metadata reads and byte transfers. */
data class XCoreRetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMillis: Long = 400L,
    val multiplier: Double = 2.5,
    val maxBackoffMillis: Long = 8_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun delayMillisBefore(attempt: Int): Long =
        (initialBackoffMillis * multiplier.pow((attempt - 1).coerceAtLeast(0).toDouble()))
            .toLong()
            .coerceAtMost(maxBackoffMillis)

    /**
     * 408, 429 and 5xx are worth another attempt; 4xx that is not one of those means the request
     * itself is wrong and repeating it only costs the user bandwidth.
     */
    fun shouldRetryStatus(status: Int?): Boolean = status != null &&
        (status == 408 || status == 429 || status in 500..599)

    companion object {
        val DEFAULT = XCoreRetryPolicy()
    }
}

/**
 * `HttpURLConnection` document reader with retries and conditional GET.
 *
 * `If-None-Match` is sent when the caller has an etag, so refreshing the manifest on every app
 * start costs 304 bytes rather than the whole document. Responses are capped because Mojang
 * metadata is small by contract and an unbounded `readBytes()` would let a bad response exhaust the
 * process heap.
 */
class XCoreHttpDocumentFetcher(
    private val userAgent: String,
    private val timeoutMillis: Int = 20_000,
    private val maxResponseBytes: Long = 8L * 1024 * 1024,
    private val retryPolicy: XCoreRetryPolicy = XCoreRetryPolicy.DEFAULT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: XCoreLogger? = null,
) : XCoreRemoteDocumentFetcher {

    override suspend fun fetch(
        url: String,
        etag: String?,
    ): XCoreResult<XCoreRemoteDocument> = XCoreDistributionEndpoints
        .requireAllowed(url, field = "manifestUrl")
        .flatMap { authorised ->
            xCoreRun(origin = "http.fetch($url)", errorKind = XCoreErrorKind.NETWORK) {
                var lastError: XCoreError? = null
                for (attempt in 1..retryPolicy.maxAttempts) {
                    currentCoroutineContext().ensureActive()
                    when (val outcome = readOnce(authorised, etag)) {
                        is XCoreResult.Success -> return@xCoreRun outcome.value
                        is XCoreResult.Cancelled -> failXCore(
                            XCoreError.Cancelled(
                                detail = "the document read for $url was cancelled",
                                operation = outcome.operation,
                                byUser = false,
                            ),
                        )

                        is XCoreResult.Failure -> {
                            lastError = outcome.error
                            val worthRetrying = outcome.error.retryable && attempt < retryPolicy.maxAttempts
                            logger?.log(
                                if (worthRetrying) XCoreLogLevel.WARN else XCoreLogLevel.ERROR,
                                XCoreLogTags.USE_CASE,
                                "GET ${authorised.host}${authorised.path} attempt $attempt failed: ${outcome.error.format()}",
                            )
                            if (worthRetrying) delay(retryPolicy.delayMillisBefore(attempt))
                        }
                    }
                }
                failXCore(
                    lastError ?: XCoreError.Network(
                        "no attempt to fetch $url produced a result",
                        url = url,
                    ),
                )
            }
        }

    private suspend fun readOnce(
        url: URL,
        etag: String?,
    ): XCoreResult<XCoreRemoteDocument> = withContext(io) {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as? HttpURLConnection)
                ?: failXCore(XCoreError.Network("endpoint does not speak HTTP", url = url.toString()))
            connection.apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                etag?.let { setRequestProperty("If-None-Match", it) }
            }
            val status = connection.responseCode
            val responseEtag = connection.getHeaderField("ETag")
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@withContext XCoreNotModified.error(url.toString(), etag).asXCoreFailure()
            }
            if (status in 300..399) {
                return@withContext XCoreError.Network(
                    detail = "redirect from ${url.host} was not followed to an approved endpoint",
                    url = url.toString(),
                    httpStatus = status,
                    retryable = false,
                ).asXCoreFailure()
            }
            if (status !in 200..299) {
                return@withContext XCoreError.Network(
                    detail = "unexpected status ${connection.responseMessage?.trim() ?: "n/a"}",
                    url = url.toString(),
                    httpStatus = status,
                    retryable = retryPolicy.shouldRetryStatus(status),
                ).asXCoreFailure()
            }
            val declaredLength = connection.contentLengthLong
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream(64 * 1024)
                val chunk = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxResponseBytes) {
                        failXCore(
                            XCoreError.Network(
                                detail = "response exceeded the $maxResponseBytes byte metadata cap after $total bytes",
                                url = url.toString(),
                                retryable = false,
                            ),
                        )
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toString("UTF-8")
            }
            XCoreRemoteDocument(
                body = body,
                sourceUrl = url.toString(),
                eTag = responseEtag,
                lastModifiedMillis = connection.getHeaderFieldDate("Last-Modified", -1L).takeIf { it >= 0L },
                contentLength = if (declaredLength > 0) declaredLength else body.length.toLong(),
                servedFromCache = false,
                fetchedAtMillis = started,
            ).asXCoreSuccess()
        } catch (ioFailure: IOException) {
            XCoreError.Network(
                detail = "transport failure: ${ioFailure.message}",
                url = url.toString(),
                cause = ioFailure,
                retryable = true,
            ).asXCoreFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * A conditional-GET miss.
 *
 * `XCoreResult` has no "unchanged" arm, so 304 travels as a typed failure and [XCoreVersionCatalog]
 * recognises it by [XCoreError.Network.httpStatus] before falling back to the cached document. A
 * caller with no cache treats it as an ordinary network failure rather than inventing a body.
 */
object XCoreNotModified {
    const val HTTP_NOT_MODIFIED: Int = 304

    fun error(
        url: String,
        etag: String?,
    ): XCoreError = XCoreError.Network(
        detail = "server reports the cached document${etag?.let { " for $it" } ?: ""} is unchanged",
        url = url,
        httpStatus = HTTP_NOT_MODIFIED,
        retryable = false,
    )

    fun isNotModified(error: XCoreError): Boolean =
        error is XCoreError.Network && error.httpStatus == HTTP_NOT_MODIFIED
}

fun readTree(
    json: String,
    context: String,
): JsonObject = try {
    val element = JsonParser.parseString(json)
    element.asObjectOrNull()
        ?: failXCore(XCoreError.Network("$context is not a JSON object", retryable = false))
} catch (syntax: JsonSyntaxException) {
    failXCore(
        XCoreError.Network(
            detail = "$context is not parseable JSON: ${syntax.message}",
            retryable = false,
            cause = syntax,
        ),
    )
}

fun JsonElement?.asObjectOrNull(): JsonObject? =
    this?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonElement?.asArrayOrNull(): JsonArray? =
    this?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.optString(key: String): String? {
    val element = get(key) ?: return null
    if (element.isJsonNull) return null
    return if (element.isJsonPrimitive) element.asString else null
}

fun JsonObject.optLong(key: String): Long? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return try {
        element.asLong
    } catch (notNumeric: NumberFormatException) {
        null
    }
}

fun JsonObject.requiredString(
    key: String,
    context: String,
): String = optString(key) ?: failXCore(
    XCoreError.Network(
        detail = "$context is missing the string field '$key'",
        retryable = false,
    ),
)

fun JsonObject.requiredArray(
    key: String,
    context: String,
): JsonArray {
    val element = get(key) ?: failXCore(
        XCoreError.Network(detail = "$context is missing the array field '$key'", retryable = false),
    )
    return element.asArrayOrNull() ?: failXCore(
        XCoreError.Network(detail = "$context field '$key' is not an array", retryable = false),
    )
}

fun <T> XCoreResult<T>.getOrThrowXCore(): T = when (this) {
    is XCoreResult.Success -> value
    is XCoreResult.Failure -> failXCore(error)
    is XCoreResult.Cancelled -> failXCore(
        XCoreError.Cancelled(reason ?: "cancelled", operation = operation),
    )
}

fun <T> XCoreResult<T>.valueOrNullXCore(): T? = (this as? XCoreResult.Success)?.value
