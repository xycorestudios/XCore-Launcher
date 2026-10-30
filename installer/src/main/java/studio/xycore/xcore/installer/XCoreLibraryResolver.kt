package studio.xycore.xcore.installer

import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.map
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.versions.XCoreDownloadFile
import studio.xycore.xcore.versions.XCoreLibraryEntry
import studio.xycore.xcore.versions.XCoreRule
import studio.xycore.xcore.versions.XCoreRuntimeEnvironment

/** How a resolved jar is used once it is on disk. */
enum class XCoreArtifactRole {
    /** Goes on the classpath. */
    CLASSPATH,

    /** Platform jar carrying `.so`/`.dll` content; recorded, and unpacked by the native bridge. */
    NATIVES,
}

/** Where a resolved artifact's URL came from. */
enum class XCoreArtifactSource {
    /** Mojang published a `downloads` entry with a URL, a size and a SHA-1. */
    PUBLISHED,

    /** The profile named the coordinate but mirrored nothing; resolved through the Maven layout. */
    COORDINATE_ONLY,
}

/** One artifact the resolver decided this device needs. */
data class XCoreResolvedArtifact(
    val coordinate: String,
    val role: XCoreArtifactRole,
    val url: String,
    val sha1: XCoreSha1?,
    val sizeBytes: Long,
    val classifier: String?,
    val relativePath: String,
    val source: XCoreArtifactSource,
) {
    /** A digest this artifact has never had is reported as absent, not as a hash of somebody's guess. */
    val digestIsPublished: Boolean get() = sha1 != null

    fun toTask(): XCoreDownloadTask = XCoreDownloadTask(
        url = url,
        relativeTarget = relativePath,
        sha1 = sha1,
        expectedBytes = sizeBytes,
        origin = if (role == XCoreArtifactRole.NATIVES) XCoreDownloadOrigin.NATIVE else XCoreDownloadOrigin.LIBRARY,
    )
}

/** Why a declared library was not resolved. */
data class XCoreSkippedLibrary(val coordinate: String, val reason: String)

/** The outcome of resolving a version's `libraries` array for one environment. */
data class XCoreLibraryResolution(
    val artifacts: List<XCoreResolvedArtifact>,
    val skipped: List<XCoreSkippedLibrary>,
    val unverified: List<String>,
) {
    private fun byRole(role: XCoreArtifactRole) = artifacts.filter { it.role == role }

    val classpathArtifacts: List<XCoreResolvedArtifact> get() = byRole(XCoreArtifactRole.CLASSPATH)

    val nativeArtifacts: List<XCoreResolvedArtifact> get() = byRole(XCoreArtifactRole.NATIVES)

    /** Root-relative classpath entries, in the order they should be prepended to the classpath. */
    val classpathPaths: List<String> get() = classpathArtifacts.map { it.relativePath }

    val nativePaths: List<String> get() = nativeArtifacts.map { it.relativePath }

    val classpathTasks: List<XCoreDownloadTask> get() = classpathArtifacts.map { it.toTask() }

    val nativeTasks: List<XCoreDownloadTask> get() = nativeArtifacts.map { it.toTask() }

    val expectedBytes: Long get() = artifacts.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    fun describeSkipped(limit: Int = 4): String = skipped.take(limit).joinToString("; ") {
        "${it.coordinate} (${it.reason})"
    } + if (skipped.size > limit) " and ${skipped.size - limit} more" else ""
}

/**
 * Decides which declared libraries this device needs and where each one is stored.
 *
 * Three rules from Mojang's metadata are honoured:
 *  - `rules` are evaluated last-match-wins for [environment], so osx-only and windows-only jars are
 *    skipped rather than downloaded and ignored;
 *  - `natives` maps an os key to a classifier name with `${arch}` substituted by the device's 32/64
 *    token. The classifier jar is recorded under [XCoreArtifactRole.NATIVES] and deliberately not
 *    unpacked here: those objects are built for glibc, and extracting them onto Android would turn a
 *    missing native bridge into an unexplainable crash at first launch. Extraction belongs to the
 *    native bridge phase, alongside X-Core's own EGL/GLFW build;
 *  - a library with no `downloads` block at all is mapped through the Maven layout onto the fallback
 *    repository. Nobody published a digest for it, so it is listed in
 *    [XCoreLibraryResolution.unverified] and transferred without verification instead of being
 *    reported as verified.
 */
class XCoreLibraryResolver(
    private val environment: XCoreRuntimeEnvironment,
    private val fallbackRepositoryUrl: String = MAVEN_CENTRAL_BASE,
) {

    fun resolve(libraries: List<XCoreLibraryEntry>): XCoreResult<XCoreLibraryResolution> {
        val artifacts = ArrayList<XCoreResolvedArtifact>(libraries.size)
        val skipped = ArrayList<XCoreSkippedLibrary>()
        val unverified = ArrayList<String>()
        val claimed = HashSet<String>()

        for (library in libraries) {
            if (!XCoreRule.evaluate(library.rules, environment)) {
                skipped += XCoreSkippedLibrary(
                    coordinate = library.coordinate,
                    reason = "rules exclude this device (${environment.osName}/${environment.architectureToken})",
                )
                continue
            }

            library.artifact?.let { published ->
                val path = rootRelative(published.relativePath ?: coordinatePath(library.coordinate))
                if (claimed.add(path)) {
                    artifacts += published.artifact(library.coordinate, XCoreArtifactRole.CLASSPATH, null, path)
                }
            }

            val classifierKey = library.nativeClassifierKey(environment)
            if (classifierKey != null) {
                val classifierFile = library.classifiers[classifierKey]
                if (classifierFile == null) {
                    skipped += XCoreSkippedLibrary(
                        coordinate = library.coordinate,
                        reason = "declares natives for '${environment.osName}' but publishes no '$classifierKey' classifier",
                    )
                } else {
                    val path = rootRelative(
                        classifierFile.relativePath
                            ?: coordinatePath(library.coordinate, classifierKey),
                    )
                    if (claimed.add(path)) {
                        artifacts += classifierFile.artifact(
                            library.coordinate,
                            XCoreArtifactRole.NATIVES,
                            classifierKey,
                            path,
                        )
                    }
                }
            }

            if (library.artifact == null && library.classifiers.isEmpty()) {
                if (library.declaresNatives) {
                    skipped += XCoreSkippedLibrary(
                        coordinate = library.coordinate,
                        reason = "natives-only entry with no published download for any platform",
                    )
                } else {
                    val outcome = coordinateOnlyArtifact(library.coordinate)
                    if (outcome is XCoreResult.Failure) {
                        skipped += XCoreSkippedLibrary(library.coordinate, outcome.error.detail)
                        continue
                    }
                    val artifact = (outcome as XCoreResult.Success).value
                    if (claimed.add(artifact.relativePath)) {
                        artifacts += artifact
                        unverified += artifact.coordinate
                    }
                }
            }
        }
        return XCoreLibraryResolution(artifacts, skipped, unverified).asXCoreSuccess()
    }

    private fun XCoreDownloadFile.artifact(
        coordinate: String,
        role: XCoreArtifactRole,
        classifier: String?,
        relativePath: String,
    ): XCoreResolvedArtifact = XCoreResolvedArtifact(
        coordinate = coordinate,
        role = role,
        url = url,
        sha1 = sha1,
        sizeBytes = sizeBytes,
        classifier = classifier,
        relativePath = relativePath,
        source = XCoreArtifactSource.PUBLISHED,
    )

    private fun coordinateOnlyArtifact(coordinate: String): XCoreResult<XCoreResolvedArtifact> {
        val parsed = XCoreMavenCoordinate.parse(coordinate)
            ?: return XCoreError.Validation(
                detail = "not a usable Maven coordinate",
                field = "libraries.name",
                rejected = coordinate,
            ).asXCoreFailure()
        val url = "$fallbackRepositoryUrl/${parsed.repositoryPath}"
        return XCoreDistributionUrlGuard.allows(url).map {
            XCoreResolvedArtifact(
                coordinate = parsed.asCoordinate,
                role = XCoreArtifactRole.CLASSPATH,
                url = url,
                sha1 = null,
                sizeBytes = -1L,
                classifier = parsed.classifier,
                relativePath = rootRelative(parsed.repositoryPath),
                source = XCoreArtifactSource.COORDINATE_ONLY,
            )
        }
    }

    companion object {
        const val LIBRARIES_BASE = "https://libraries.minecraft.net"
        const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"

        /** The tree the launcher keeps jars in, relative to the storage root. */
        const val LIBRARIES_PREFIX = "libraries/"

        fun rootRelative(repositoryPath: String): String =
            LIBRARIES_PREFIX + repositoryPath.removePrefix("/")

        fun coordinatePath(
            coordinate: String,
            classifier: String? = null,
        ): String = XCoreMavenCoordinate.parse(coordinate)
            ?.copy(classifier = classifier ?: XCoreMavenCoordinate.parse(coordinate)?.classifier)
            ?.repositoryPath
            ?: coordinate.replace(':', '/').replace('.', '/')
    }
}

/** Checks a derived URL against the approved hosts without opening it. */
internal object XCoreDistributionUrlGuard {
    fun allows(url: String): XCoreResult<String> =
        studio.xycore.xcore.versions.XCoreDistributionEndpoints
            .requireAllowed(url, field = "libraryUrl")
            .map { url }
}

/**
 * A Maven coordinate as version metadata writes it: `group:artifact:version[:classifier][@ext]`.
 */
data class XCoreMavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    val repositoryPath: String
        get() {
            val suffix = if (classifier.isNullOrBlank()) "" else "-$classifier"
            return "${group.replace('.', '/')}/$artifact/$version/$artifact-$version$suffix.$extension"
        }

    val asCoordinate: String
        get() = buildString {
            append(group); append(':'); append(artifact); append(':'); append(version)
            if (!classifier.isNullOrBlank()) append(':').append(classifier)
        }

    companion object {
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._\\-]+$")

        fun parse(candidate: String): XCoreMavenCoordinate? {
            val head = candidate.substringBefore('@')
            val extension = candidate.substringAfter('@', "").takeIf { it.isNotEmpty() } ?: "jar"
            val parts = head.split(':')
            if (parts.size < 3 || parts.size > 4) return null
            val group = parts[0]
            val artifact = parts[1]
            val version = parts[2]
            val classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            if (group.isBlank() || artifact.isBlank() || version.isBlank()) return null
            if (!SAFE_SEGMENT.matches(artifact) || !SAFE_SEGMENT.matches(version)) return null
            if (group.split('.').any { !SAFE_SEGMENT.matches(it) }) return null
            if (!extension.matches(SAFE_SEGMENT)) return null
            return XCoreMavenCoordinate(group, artifact, version, classifier, extension)
        }
    }
}

/** The classifier key a `natives` map resolves to for this environment, after `${arch}` substitution. */
fun XCoreLibraryEntry.nativeClassifierKey(environment: XCoreRuntimeEnvironment): String? {
    if (natives.isEmpty()) return null
    val raw = natives[environment.osName.lowercase()] ?: return null
    val resolved = raw.replace("\${arch}", environment.architectureToken)
    return environment.nativeClassifierKeys.takeIf { it.isNotEmpty() }
        ?.let { allowed -> if (resolved in allowed) resolved else null }
        ?: resolved
}
