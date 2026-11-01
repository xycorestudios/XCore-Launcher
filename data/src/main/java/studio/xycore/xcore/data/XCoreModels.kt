package studio.xycore.xcore.data

import java.util.UUID
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess

/**
 * Release type as published in Mojang's `version_manifest_v2.json`.
 *
 * [manifestId] is what this app writes to and reads from SQLite; [wireAliases] are the strings the
 * live manifest actually contains. Mojang renamed the pre-1.0 buckets to `old_beta` and `old_alpha`
 * long after these enum constants were chosen, so accepting the old spellings keeps previously
 * stored rows readable while a fresh manifest parse still succeeds. The two pre-1.0 buckets keep
 * their own constants rather than collapsing into one type because the UI labels each row.
 */
enum class XCoreReleaseType(
    val manifestId: String,
    val displayName: String,
    val wireAliases: Set<String> = emptySet(),
) {
    RELEASE("release", "Release"),
    SNAPSHOT("snapshot", "Snapshot"),
    OLD_RELEASE("old-release", "Old release", setOf("old_beta")),
    OLD_SNAPSHOT("old-snapshot", "Old snapshot", setOf("old_alpha")),
    ;

    val acceptedManifestValues: Set<String>
        get() = wireAliases + manifestId

    companion object {
        fun fromManifestId(value: String): XCoreResult<XCoreReleaseType> {
            val match = entries.firstOrNull { candidate ->
                candidate.acceptedManifestValues.any { it.equals(value, ignoreCase = true) }
            }
            return if (match == null) {
                XCoreError.Validation(
                    detail = "unknown version manifest type",
                    field = "releaseType",
                    rejected = value,
                ).asXCoreFailure()
            } else {
                match.asXCoreSuccess()
            }
        }
    }
}

/** Mod/loader families X-Core knows how to install. Nothing here is implemented yet. */
enum class XCoreLoaderKind(val manifestId: String, val displayName: String) {
    VANILLA("vanilla", "Vanilla"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge"),
    OPTIFINE("optifine", "OptiFine"),
    ;

    companion object {
        fun fromManifestId(value: String): XCoreResult<XCoreLoaderKind> {
            val match = entries.firstOrNull { it.manifestId.equals(value, ignoreCase = true) }
            return if (match == null) {
                XCoreError.Validation(
                    detail = "unknown loader kind",
                    field = "loader",
                    rejected = value,
                ).asXCoreFailure()
            } else {
                match.asXCoreSuccess()
            }
        }
    }
}

/** How complete an installation is on disk. */
enum class XCoreInstanceStatus {
    /** Row exists, files have never been downloaded. */
    ADDED,

    /** Files are being fetched; a download journal entry is open. */
    INSTALLING,

    /** Everything the version manifest requires is present and hash-verified. */
    READY,

    /** Previously ready, but something on disk no longer matches the journal. */
    NEEDS_REPAIR,

    /** Unrecoverable with the current metadata; needs a re-install. */
    BROKEN,

    /** Kept for history, not launchable. */
    ARCHIVED,
}

/** State of a byte-transfer record. */
enum class XCoreDownloadState {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    ORPHANED,
}

/**
 * A SHA-1 digest as recorded by Mojang.
 *
 * Validation is structural only: this type cannot prove a file matches its hash, and the
 * verification step lives with the downloader, not here.
 */
@JvmInline
value class XCoreSha1 private constructor(val value: String) {

    companion object {
        private val HEX = Regex("^[0-9a-f]{40}$")

        fun parse(candidate: String): XCoreResult<XCoreSha1> {
            val normalized = candidate.trim().lowercase()
            if (normalized.length != 40) {
                return XCoreError.Validation(
                    detail = "sha1 must be 40 hex characters, got ${normalized.length}",
                    field = "sha1",
                    rejected = candidate,
                ).asXCoreFailure()
            }
            if (!normalized.matches(HEX)) {
                return XCoreError.Validation(
                    detail = "sha1 contains non-hex characters",
                    field = "sha1",
                    rejected = candidate,
                ).asXCoreFailure()
            }
            return XCoreSha1(normalized).asXCoreSuccess()
        }
    }
}

/** A Minecraft Java Edition version as resolved from the manifest plus its profile JSON. */
data class XCoreVersionRef(
    val id: String,
    val releaseType: XCoreReleaseType,
    val javaVersion: Int,
    val assetIndexId: String,
    val assetIndexCount: Int,
    val clientDownloadUrl: String,
    val clientDownloadSha1: XCoreSha1,
    val releaseTimeMillis: Long,
    val inheritsFrom: String? = null,
    val lastSeenManifestMillis: Long? = null,
    val installed: Boolean = false,
) {
    fun validate(): XCoreResult<XCoreVersionRef> {
        if (id.isBlank()) {
            return XCoreError.Validation("version id must not be blank", field = "versionId").asXCoreFailure()
        }
        if (!id.matches(VERSION_ID_PATTERN)) {
            return XCoreError.Validation(
                detail = "version id may only contain letters, digits, dots, hyphens and underscores",
                field = "versionId",
                rejected = id,
            ).asXCoreFailure()
        }
        if (javaVersion < MIN_SUPPORTED_JAVA) {
            return XCoreError.Validation(
                detail = "version '$id' requests Java $javaVersion, below the supported floor $MIN_SUPPORTED_JAVA",
                field = "javaVersion",
                rejected = javaVersion.toString(),
            ).asXCoreFailure()
        }
        if (clientDownloadUrl.isBlank() || (!clientDownloadUrl.startsWith("https://"))) {
            return XCoreError.Validation(
                detail = "client download must use https",
                field = "clientDownloadUrl",
                rejected = clientDownloadUrl,
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    companion object {
        const val MIN_SUPPORTED_JAVA = 8
        val VERSION_ID_PATTERN: Regex = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}

/**
 * A user-facing installation: a name, a version, a loader and a private game directory.
 *
 * `gameDirectoryRelative` is always relative to the storage root resolved by `XCorePaths`; an
 * absolute path here is rejected so a stored instance can never escape the app's own storage.
 */
data class XCoreInstance(
    val id: String,
    val name: String,
    val versionId: String,
    val loader: XCoreLoaderKind,
    val status: XCoreInstanceStatus,
    val gameDirectoryRelative: String,
    val runtimeComponentId: String? = null,
    val jvmArguments: String = "",
    val gameArguments: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastLaunchedAtMillis: Long? = null,
) {
    fun validate(): XCoreResult<XCoreInstance> {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) {
            return XCoreError.Validation(
                detail = "instance name must be 1..$MAX_NAME_LENGTH characters",
                field = "instanceName",
                rejected = name,
            ).asXCoreFailure()
        }
        if (name.any { it in ILLEGAL_NAME_CHARACTERS }) {
            return XCoreError.Validation(
                detail = "instance name contains characters that are unsafe in a directory name",
                field = "instanceName",
                rejected = name,
            ).asXCoreFailure()
        }
        if (!id.matches(ID_PATTERN)) {
            return XCoreError.Validation(
                detail = "instance id must be a lowercase UUID",
                field = "instanceId",
                rejected = id,
            ).asXCoreFailure()
        }
        if (!gameDirectoryRelative.matches(RELATIVE_PATH_PATTERN)) {
            return XCoreError.Validation(
                detail = "game directory must be a relative path without '..' segments",
                field = "gameDirectory",
                rejected = gameDirectoryRelative,
            ).asXCoreFailure()
        }
        if (versionId.isBlank()) {
            return XCoreError.Validation("versionId must not be blank", field = "versionId").asXCoreFailure()
        }
        if (jvmArguments.contains('\n') || gameArguments.contains('\n')) {
            return XCoreError.Validation(
                detail = "argument strings must be a single line; the launcher splits them itself",
                field = "arguments",
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    fun touch(nowMillis: Long): XCoreInstance = copy(updatedAtMillis = nowMillis)

    companion object {
        const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARACTERS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private val ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val RELATIVE_PATH_PATTERN = Regex("^[A-Za-z0-9._\\-](?:[A-Za-z0-9._\\-/]*[A-Za-z0-9._\\-])?$")

        fun newId(): String = UUID.randomUUID().toString().lowercase()

        /** Directory name used for the instance's private `.minecraft` tree. */
        fun directoryFor(id: String): String = "instances/$id"
    }
}

/**
 * A Java runtime X-Core knows about.
 *
 * [signatureVerified] is written only by the verifier, and a component with `READY` state but
 * `signatureVerified = false` is treated as unusable when `requireVerifiedRuntime` is on.
 */
data class XCoreRuntimeComponent(
    val id: String,
    val javaVersion: Int,
    val abi: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha1: XCoreSha1?,
    val state: XCoreRuntimeState,
    val sourceUrl: String,
    val signatureVerified: Boolean,
    val verifiedAtMillis: Long? = null,
    val lastErrorCode: Int? = null,
) {
    fun validate(): XCoreResult<XCoreRuntimeComponent> {
        if (javaVersion < XCoreVersionRef.MIN_SUPPORTED_JAVA) {
            return XCoreError.Validation(
                detail = "runtime targets Java $javaVersion which is unsupported",
                field = "javaVersion",
                rejected = javaVersion.toString(),
            ).asXCoreFailure()
        }
        if (abi !in SUPPORTED_ABIS) {
            return XCoreError.Validation(
                detail = "runtime abi '$abi' is not one of ${SUPPORTED_ABIS.joinToString()}",
                field = "abi",
                rejected = abi,
            ).asXCoreFailure()
        }
        if (sizeBytes < 0) {
            return XCoreError.Validation("sizeBytes must not be negative", field = "sizeBytes").asXCoreFailure()
        }
        if (!relativePath.matches(NO_ESCAPE_PATTERN)) {
            return XCoreError.Validation(
                detail = "runtime path must stay inside the storage root",
                field = "relativePath",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        if (state == XCoreRuntimeState.READY && sha1 == null) {
            return XCoreError.Runtime(
                detail = "runtime '$id' is marked READY without a recorded digest",
                reason = studio.xycore.xcore.core.XCoreRuntimeFailureReason.UNVERIFIED,
                requiredJavaVersion = javaVersion,
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    companion object {
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
        private val NO_ESCAPE_PATTERN = Regex("^[A-Za-z0-9._\\-/]+$")
    }
}

/** A single byte-transfer record; resumability lives in [writtenBytes] plus [etag]. */
data class XCoreDownloadRecord(
    val id: String,
    val url: String,
    val relativeTarget: String,
    val sha1: XCoreSha1?,
    val expectedBytes: Long,
    val writtenBytes: Long,
    val state: XCoreDownloadState,
    val etag: String?,
    val attempts: Int,
    val updatedAtMillis: Long,
) {
    val remainingBytes: Long
        get() = if (expectedBytes <= 0L) -1L else (expectedBytes - writtenBytes).coerceAtLeast(0L)

    fun validate(): XCoreResult<XCoreDownloadRecord> {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return XCoreError.Validation(
                detail = "download url must be http(s)",
                field = "url",
                rejected = url,
            ).asXCoreFailure()
        }
        if (writtenBytes < 0) {
            return XCoreError.Validation("writtenBytes must not be negative", field = "writtenBytes").asXCoreFailure()
        }
        if (expectedBytes in 1 until writtenBytes) {
            return XCoreError.Validation(
                detail = "more bytes recorded than the transfer expected",
                field = "writtenBytes",
                rejected = "$writtenBytes of $expectedBytes",
            ).asXCoreFailure()
        }
        if (!relativeTarget.matches(RELATIVE_TARGET_PATTERN)) {
            return XCoreError.Validation(
                detail = "download target must be a safe relative path",
                field = "relativeTarget",
                rejected = relativeTarget,
            ).asXCoreFailure()
        }
        return this.asXCoreSuccess()
    }

    companion object {
        private val RELATIVE_TARGET_PATTERN = Regex("^[A-Za-z0-9._\\-/]+$")
    }
}

/** One launch attempt, kept so failures are reproducible rather than guessed at. */
data class XCoreLaunchRecord(
    val id: String,
    val instanceId: String,
    val versionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val exitCode: Int?,
    val errorKind: XCoreErrorKind?,
    val logPathRelative: String?,
) {
    val succeeded: Boolean get() = exitCode == 0 && errorKind == null
}
