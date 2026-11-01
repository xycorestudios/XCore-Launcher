package studio.xycore.xcore.data

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import studio.xycore.xcore.core.XCoreConfig
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.map

/** Fixed sub-directories of the game tree, named after the layout Minecraft expects. */
enum class XCoreGameDirectory(
    val relativePath: String,
    val readOnlyByLauncher: Boolean,
    val purpose: String,
) {
    VERSIONS("versions", false, "per-version client jars and profile json"),
    LIBRARIES("libraries", false, "maven library jars"),
    ASSETS("assets", false, "virtual and objects asset store"),
    RUNTIMES("runtimes", false, "external Java runtimes"),
    NATIVES("natives", false, "extracted platform natives (lwjgl, glfw)"),
    INSTANCES("instances", false, "one directory per instance (.minecraft)"),
    LOGS("logs", true, "launch logs and crash reports"),
    DOWNLOADS("downloads", true, "in-flight and staged downloads"),
    CACHE("cache", true, "manifest, sdk and metadata caches"),
    ;

    companion object {
        val REQUIRED: List<XCoreGameDirectory> = entries.toList()
    }
}

/** A resolved storage decision plus what was found on the device when it was made. */
data class XCoreStoragePlan(
    val root: File,
    val requested: XCoreStorageRoot,
    val usedFallback: Boolean,
    val usableBytes: Long,
    val reason: String?,
) {
    val rootPath: String get() = root.absolutePath
}

/** The directory set a plan produces, after the directories exist and are writable. */
data class XCoreStorageLayout(
    val plan: XCoreStoragePlan,
    val directories: Map<XCoreGameDirectory, File>,
) {
    fun dir(kind: XCoreGameDirectory): File? = directories[kind]

    fun pathOf(kind: XCoreGameDirectory): String =
        directories[kind]?.absolutePath ?: "${plan.root.absolutePath}/${kind.relativePath}"

    val present: Set<XCoreGameDirectory> get() = directories.keys
}

/**
 * Resolves and prepares the launcher's file layout using Android-supported locations only:
 * internal files dir, app-private external dir, or an explicitly configured custom root.
 *
 * Every returned layout has been probed: directories exist and a write-then-delete round trip
 * succeeded. A path that cannot be proven writable comes back as a failure, never as a hopeful
 * `File` the caller has to double-check.
 */
class XCorePaths(
    context: Context,
    private val logger: XCoreLogger? = null,
    private val subdirectory: String = "xcore",
) {

    private val appContext = context.applicationContext

    /** `Context.getFilesDir()` — always present, removed with the app, never needs a permission. */
    fun internalRoot(): File = File(appContext.filesDir, subdirectory)

    /**
     * `Context.getExternalFilesDir(null)`, or `null` when the volume is not currently available.
     * Mountedness is inferred from the directory being creatable and writable rather than from
     * `Environment.getExternalStorageState`, whose per-volume form needs API 29.
     */
    fun externalAppRoot(): File? {
        val external = appContext.getExternalFilesDir(null) ?: return null
        return File(external, subdirectory)
    }

    fun cacheRoot(): File = File(appContext.cacheDir, subdirectory)

    /**
     * Chooses a root according to [config], falling back to internal storage when the requested
     * volume is unavailable. The fallback is reported in [XCoreStoragePlan.reason] rather than
     * happening silently.
     */
    fun planFor(config: XCoreConfig): XCoreResult<XCoreStoragePlan> = when (config.storageRoot) {
        XCoreStorageRoot.INTERNAL -> rootAt(internalRoot(), XCoreStorageRoot.INTERNAL, fallbackAllowed = false)

        XCoreStorageRoot.EXTERNAL_APP_PRIVATE -> {
            val external = externalAppRoot()
            if (external == null) {
                val fallback = rootAt(internalRoot(), XCoreStorageRoot.EXTERNAL_APP_PRIVATE, fallbackAllowed = true)
                fallback.map {
                    it.copy(
                        usedFallback = true,
                        reason = "external app-private volume is unavailable; using internal storage",
                    )
                }
            } else {
                rootAt(external, XCoreStorageRoot.EXTERNAL_APP_PRIVATE, fallbackAllowed = true)
            }
        }

        XCoreStorageRoot.CUSTOM -> {
            val configured = config.customRootPath
                ?: return XCoreError.Configuration("storageRoot is CUSTOM but customRootPath is unset").asXCoreFailure()
            validateCustomPath(configured).flatMap { directory ->
                rootAt(directory, XCoreStorageRoot.CUSTOM, fallbackAllowed = false)
            }
        }
    }

    /**
     * Creates every directory in [XCoreGameDirectory.REQUIRED] under [plan] and proves each is
     * writable. A partial failure reports exactly which directory could not be prepared.
     */
    fun prepareLayout(plan: XCoreStoragePlan): XCoreResult<XCoreStorageLayout> {
        val prepared = LinkedHashMap<XCoreGameDirectory, File>()
        for (kind in XCoreGameDirectory.REQUIRED) {
            val directory = File(plan.root, kind.relativePath)
            val outcome = ensureWritable(directory)
            when (outcome) {
                is XCoreResult.Failure -> return XCoreError.Storage(
                    detail = "cannot prepare ${kind.name.lowercase()} directory: ${outcome.error.detail}",
                    path = directory.absolutePath,
                    usableBytes = plan.usableBytes,
                    cause = outcome.error.cause,
                ).asXCoreFailure()
                is XCoreResult.Cancelled -> return outcome
                is XCoreResult.Success -> prepared[kind] = directory
            }
        }
        logger?.log(
            XCoreLogLevel.DEBUG,
            XCoreLogTags.PATHS,
            "layout ready at ${plan.root.absolutePath} (${prepared.size} directories)",
        )
        return XCoreStorageLayout(plan, prepared).asXCoreSuccess()
    }

    /** Convenience for the common `planFor(config)` + `prepareLayout` pair. */
    fun prepare(config: XCoreConfig): XCoreResult<XCoreStorageLayout> =
        planFor(config).flatMap { prepareLayout(it) }

    fun instanceDirectory(layout: XCoreStorageLayout, instanceId: String): XCoreResult<File> {
        val base = layout.dir(XCoreGameDirectory.INSTANCES)
            ?: return XCoreError.Storage(
                detail = "instances directory is not part of the prepared layout",
                path = layout.plan.rootPath,
            ).asXCoreFailure()
        if (!instanceId.matches(INSTANCE_ID)) {
            return XCoreError.Validation(
                detail = "instance id is not a UUID and cannot be used as a directory name",
                field = "instanceId",
                rejected = instanceId,
            ).asXCoreFailure()
        }
        return File(base, instanceId).asXCoreSuccess()
    }

    /**
     * Resolves a path inside [layout] while refusing anything that escapes the root, including
     * `..` segments and absolute overrides.
     */
    fun resolveWithin(layout: XCoreStorageLayout, relativePath: String): XCoreResult<File> {
        if (relativePath.isBlank()) {
            return XCoreError.Validation("relative path must not be blank", field = "relativePath").asXCoreFailure()
        }
        if (relativePath.startsWith("/") || relativePath.contains('\\')) {
            return XCoreError.Validation(
                detail = "path must be relative to the storage root and use forward slashes",
                field = "relativePath",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        if (relativePath.split('/').any { it == ".." }) {
            return XCoreError.Validation(
                detail = "path may not traverse above the storage root",
                field = "relativePath",
                rejected = relativePath,
            ).asXCoreFailure()
        }
        val rootCanonical = canonicalOrSelf(layout.plan.root)
        val candidate = File(layout.plan.root, relativePath)
        val candidateCanonical = canonicalOrSelf(candidate)
        if (!candidateCanonical.path.startsWith(rootCanonical.path + File.separator) &&
            candidateCanonical.path != rootCanonical.path
        ) {
            return XCoreError.Storage(
                detail = "resolved path escapes the storage root",
                path = candidate.path,
            ).asXCoreFailure()
        }
        return candidate.asXCoreSuccess()
    }

    /** Bytes available to the launcher on the volume holding [directory]. */
    fun usableBytes(directory: File): Long = try {
        StatFs(directory.absolutePath).availableBytes
    } catch (error: IllegalArgumentException) {
        logger?.log(
            XCoreLogLevel.WARN,
            XCoreLogTags.PATHS,
            "StatFs failed for ${directory.absolutePath}: ${error.message}",
            error,
        )
        -1L
    }

    /**
     * A custom root is only accepted when it is absolute, not a system directory, and already
     * exists or can be created by the app. Android gives no app a general write location, so this
     * is a guard against a config that can never work rather than a promise of success.
     */
    fun validateCustomPath(path: String): XCoreResult<File> {
        if (!path.startsWith("/")) {
            return XCoreError.Validation(
                detail = "custom root must be absolute",
                field = "customRootPath",
                rejected = path,
            ).asXCoreFailure()
        }
        val normalized = path.trimEnd('/')
        if (normalized in FORBIDDEN_ROOTS) {
            return XCoreError.Validation(
                detail = "refusing to use a system directory as the game root",
                field = "customRootPath",
                rejected = normalized,
            ).asXCoreFailure()
        }
        val directory = File(normalized)
        if (directory.exists() && !directory.isDirectory) {
            return XCoreError.Storage(
                detail = "custom root exists but is not a directory",
                path = normalized,
            ).asXCoreFailure()
        }
        return directory.asXCoreSuccess()
    }

    private fun rootAt(
        directory: File,
        requested: XCoreStorageRoot,
        fallbackAllowed: Boolean,
    ): XCoreResult<XCoreStoragePlan> {
        val ensure = ensureWritable(directory)
        if (ensure is XCoreResult.Failure) {
            if (fallbackAllowed && requested == XCoreStorageRoot.EXTERNAL_APP_PRIVATE) {
                val internal = ensureWritable(internalRoot())
                if (internal is XCoreResult.Success) {
                    return XCoreStoragePlan(
                        root = internalRoot(),
                        requested = requested,
                        usedFallback = true,
                        usableBytes = usableBytes(internalRoot()),
                        reason = "external app-private root was not writable: ${ensure.error.detail}",
                    ).asXCoreSuccess()
                }
            }
            return XCoreError.Storage(
                detail = "root ${directory.absolutePath} is not writable: ${ensure.error.detail}",
                path = directory.absolutePath,
                cause = ensure.error.cause,
            ).asXCoreFailure()
        }
        return XCoreStoragePlan(
            root = directory,
            requested = requested,
            usedFallback = false,
            usableBytes = usableBytes(directory),
            reason = null,
        ).asXCoreSuccess()
    }

    /**
     * Creates the directory when missing and proves it accepts writes by creating, flushing and
     * deleting a probe file. Directory creation alone is not treated as proof.
     */
    private fun ensureWritable(directory: File): XCoreResult<File> {
        if (!directory.exists() && !directory.mkdirs()) {
            return XCoreError.Storage(
                detail = "mkdirs returned false",
                path = directory.absolutePath,
            ).asXCoreFailure()
        }
        if (!directory.isDirectory) {
            return XCoreError.Storage(
                detail = "path exists but is not a directory",
                path = directory.absolutePath,
            ).asXCoreFailure()
        }
        if (!directory.canWrite()) {
            return XCoreError.Storage(
                detail = "directory is not writable",
                path = directory.absolutePath,
            ).asXCoreFailure()
        }
        val probe = File(directory, PROBE_FILE_NAME)
        return try {
            probe.writeBytes(PROBE_CONTENT)
            val readBack = probe.readBytes()
            if (!readBack.contentEquals(PROBE_CONTENT)) {
                throw IOException("probe read-back did not match the bytes written")
            }
            directory.asXCoreSuccess()
        } catch (error: IOException) {
            XCoreError.Storage(
                detail = "write probe failed: ${error.message}",
                path = directory.absolutePath,
                usableBytes = usableBytes(directory),
                cause = error,
            ).asXCoreFailure()
        } finally {
            runCatching { if (probe.exists() && !probe.delete()) throw IOException("probe file could not be deleted") }
                .onFailure {
                    logger?.log(
                        XCoreLogLevel.WARN,
                        XCoreLogTags.PATHS,
                        "could not remove write probe in ${directory.absolutePath}",
                        it,
                    )
                }
        }
    }

    private fun canonicalOrSelf(file: File): File = runCatching { file.canonicalFile }.getOrElse { file }

    companion object {
        private const val PROBE_FILE_NAME = ".xcore-write-probe"
        private val PROBE_CONTENT = byteArrayOf(0x58, 0x43, 0x6F, 0x72, 0x65)
        private val INSTANCE_ID = Regex("^[0-9a-f-]{36}$")
        private val FORBIDDEN_ROOTS = setOf("/", "/system", "/data", "/data/data", "/storage", "/sdcard")
    }
}
