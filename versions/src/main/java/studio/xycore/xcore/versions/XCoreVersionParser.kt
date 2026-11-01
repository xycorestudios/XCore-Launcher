package studio.xycore.xcore.versions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreVersionRef

/** Which side of an install the metadata was written for. */
enum class XCoreRuleAction {
    ALLOW,
    DISALLOW,
    ;

    companion object {
        fun fromWire(value: String): XCoreResult<XCoreRuleAction> = when (value.lowercase()) {
            "allow" -> ALLOW.asXCoreSuccess()
            "disallow" -> DISALLOW.asXCoreSuccess()
            else -> XCoreError.Validation(
                detail = "rule action must be allow or disallow",
                field = "rules.action",
                rejected = value,
            ).asXCoreFailure()
        }
    }
}

/**
 * The machine this launcher is resolving metadata for.
 *
 * Android is deliberately reported as `linux` for rule evaluation. Mojang's `allow/disallow` rules
 * only ever mention windows, osx and linux, so treating the device as linux is the difference
 * between resolving the common classpath and resolving nothing at all. It is *not* a claim that
 * Mojang's `natives-linux` jars load on bionic: those are recorded for the native bridge to replace
 * with X-Core's own EGL/GLFW build, which is why [nativeClassifierKeys] defaults to empty on
 * Android and the extraction step lives in a later phase.
 */
data class XCoreRuntimeEnvironment(
    val osName: String,
    val osArch: String,
    val osVersion: String? = null,
    val features: Set<String> = emptySet(),
    val nativeClassifierKeys: Set<String> = emptySet(),
) {
    /** `64` or `32`, the token Mojang substitutes into `${arch}` inside a `natives` value. */
    val architectureToken: String
        get() = if (osArch.contains("64")) "64" else "32"

    fun withFeature(feature: String): XCoreRuntimeEnvironment =
        copy(features = features + feature)

    companion object {
        val SUPPORTED_FEATURES: Set<String> = setOf(
            "is_demo_user",
            "has_custom_resolution",
            "has_quick_plays_support",
            "is_quick_play_singleplayer",
            "is_quick_play_multiplayer",
            "is_quick_play_realms",
        )

        /** The device profile used on Android: linux rules, the app's own ABI, no optional features. */
        fun forAndroid(
            abi: String,
            apiLevel: Int? = null,
        ): XCoreRuntimeEnvironment = XCoreRuntimeEnvironment(
            osName = "linux",
            osArch = if ("64" in abi) "64-bit" else "32-bit",
            osVersion = apiLevel?.toString(),
        )
    }
}

/** The `os` block of a rule: name, a version regex, and a 32/64 architecture token. */
data class XCoreOsConstraint(
    val name: String? = null,
    val versionPattern: Regex? = null,
    val arch: String? = null,
) {
    fun matches(environment: XCoreRuntimeEnvironment): Boolean {
        if (name != null && !name.equals(environment.osName, ignoreCase = true)) return false
        if (arch != null && arch != environment.architectureToken) return false
        val pattern = versionPattern ?: return true
        val version = environment.osVersion ?: return false
        return pattern.containsMatchIn(version)
    }

    companion object {
        internal fun parse(obj: JsonObject, context: String): XCoreOsConstraint {
            val rawVersion = obj.optString("version")
            val pattern = rawVersion?.let { declared ->
                try {
                    Regex(declared)
                } catch (invalid: IllegalArgumentException) {
                    failXCore(
                        XCoreError.Network(
                            detail = "$context has an unusable os.version pattern '$declared': ${invalid.message}",
                            retryable = false,
                        ),
                    )
                }
            }
            return XCoreOsConstraint(
                name = obj.optString("name"),
                versionPattern = pattern,
                arch = obj.optString("arch"),
            )
        }
    }
}

/** One `features` entry; `required = false` inverts the test. */
data class XCoreFeatureRequirement(val name: String, val required: Boolean) {
    fun matches(environment: XCoreRuntimeEnvironment): Boolean =
        environment.features.contains(name) == required
}

/**
 * One `rules` entry.
 *
 * Rules are evaluated in document order and the last one that matches wins, so an
 * `allow` followed by a `disallow` correctly removes an artifact Mojang shipped but stopped using.
 * An entry with no constraints matches every environment, which is what `{"action":"allow"}` means.
 */
data class XCoreRule(
    val action: XCoreRuleAction,
    val os: XCoreOsConstraint? = null,
    val features: List<XCoreFeatureRequirement> = emptyList(),
) {
    fun matches(environment: XCoreRuntimeEnvironment): Boolean =
        (os == null || os.matches(environment)) && features.all { it.matches(environment) }

    companion object {
        fun evaluate(
            rules: List<XCoreRule>,
            environment: XCoreRuntimeEnvironment,
        ): Boolean {
            if (rules.isEmpty()) return true
            var allowed = false
            for (rule in rules) {
                if (rule.matches(environment)) {
                    allowed = rule.action == XCoreRuleAction.ALLOW
                }
            }
            return allowed
        }

        internal fun parseList(
            element: JsonElement?,
            context: String,
        ): List<XCoreRule> {
            val array = element as? JsonArray ?: return emptyList()
            return array.map { entry ->
                val obj = entry.asObjectOrNull()
                    ?: failXCore(XCoreError.Network("$context rule '$entry' is not an object", retryable = false))
                val osElement = obj.get("os")
                XCoreRule(
                    action = XCoreRuleAction.fromWire(obj.requiredString("action", context)).getOrThrowXCore(),
                    os = when {
                        osElement == null || osElement.isJsonNull -> null
                        else -> XCoreOsConstraint.parse(
                            osElement.asObjectOrNull()
                                ?: failXCore(
                                    XCoreError.Network("$context 'os' rule is not an object", retryable = false),
                                ),
                            "$context os",
                        )
                    },
                    features = obj.get("features").asObjectOrNull()?.let { features ->
                        features.entrySet().mapNotNull { (name, value) ->
                            val holder = value.asObjectOrNull() ?: return@mapNotNull null
                            XCoreFeatureRequirement(
                                name = name,
                                required = holder.optBoolean("required") ?: true,
                            )
                        }
                    }.orEmpty(),
                )
            }
        }
    }
}

/** A published file: where it lives, how big it is, and the digest Mojang says it should have. */
data class XCoreDownloadFile(
    val url: String,
    val sha1: XCoreSha1,
    val sizeBytes: Long,
    val relativePath: String?,
) {
    companion object {
        internal fun parse(
            element: JsonElement?,
            context: String,
        ): XCoreDownloadFile? {
            val obj = element.asObjectOrNull() ?: return null
            return XCoreDownloadFile(
                url = obj.requiredString("url", context),
                sha1 = XCoreSha1.parse(obj.requiredString("sha1", context)).getOrThrowXCore(),
                sizeBytes = obj.optLong("size") ?: -1L,
                relativePath = obj.optString("path"),
            )
        }
    }
}

/**
 * One entry of a profile's `libraries` array.
 *
 * `natives` values are classifier keys with `${arch}` still in them, e.g. `natives-linux` or
 * `natives-macos-arm64`; `downloads.classifiers` holds the actual files. Keeping both here lets the
 * resolver decide without re-reading JSON.
 */
data class XCoreLibraryEntry(
    /** Maven coordinate `group:artifact:version[:classifier]`. */
    val coordinate: String,
    val artifact: XCoreDownloadFile?,
    val classifiers: Map<String, XCoreDownloadFile>,
    val natives: Map<String, String>,
    val extractExcludes: List<String>,
    val rules: List<XCoreRule>,
) {
    /** True when the profile expects this jar to contribute platform `.so`/`.dll` content. */
    val declaresNatives: Boolean get() = natives.isNotEmpty()

    /** True when Mojang published no download for it; the caller must resolve it from Maven. */
    val needsMavenCentral: Boolean get() = artifact == null && classifiers.isEmpty()
}

/** A launch argument: either a fixed list, or a list gated by rules. */
sealed interface XCoreArgumentClause {
    val values: List<String>

    fun resolveFor(environment: XCoreRuntimeEnvironment): List<String>
}

data class XCoreLiteralArgument(override val values: List<String>) : XCoreArgumentClause {
    override fun resolveFor(environment: XCoreRuntimeEnvironment): List<String> = values
}

data class XCoreConditionalArgument(
    val rules: List<XCoreRule>,
    override val values: List<String>,
) : XCoreArgumentClause {
    override fun resolveFor(environment: XCoreRuntimeEnvironment): List<String> =
        if (XCoreRule.evaluate(rules, environment)) values else emptyList()
}

/** The `assetIndex` block: what to fetch and how much work fetching it will be. */
data class XCoreAssetIndexRef(
    val id: String,
    val url: String,
    val sha1: XCoreSha1,
    val sizeBytes: Long,
    val totalSizeBytes: Long,
)

/** The `logging.client` block: a log4j config Mojang publishes and the flag that points at it. */
data class XCoreLoggingClient(
    /** The published name of the config, e.g. `log4j2-1.12.xml`; these files carry no `path`. */
    val id: String?,
    val file: XCoreDownloadFile,
    val argument: String,
    val type: String?,
)

/** A fully parsed version profile, after `arguments`/`minecraftArguments` normalisation. */
data class XCoreVersionProfile(
    val id: String,
    val releaseType: XCoreReleaseType,
    val timeMillis: Long,
    val releaseTimeMillis: Long,
    val inheritsFrom: String?,
    val mainClass: String?,
    val minimumLauncherVersion: Int?,
    val complianceLevel: Int?,
    val javaRuntimeComponent: String?,
    val javaMajorVersion: Int,
    val assetIndex: XCoreAssetIndexRef?,
    val assetsVersion: String?,
    val downloads: Map<String, XCoreDownloadFile>,
    val libraries: List<XCoreLibraryEntry>,
    val jvmArguments: List<XCoreArgumentClause>,
    val gameArguments: List<XCoreArgumentClause>,
    val defaultUserJvmArguments: List<XCoreArgumentClause>,
    val legacyGameArguments: String?,
    val logging: XCoreLoggingClient?,
    val sourceUrl: String,
    val rawJson: String,
) {
    val requiresInheritedParent: Boolean get() = !inheritsFrom.isNullOrBlank()

    val clientDownload: XCoreDownloadFile? get() = downloads["client"]

    /** Flattened classpath-facing argument list, ignoring rule-gated entries the device does not meet. */
    fun jvmArgumentsFor(environment: XCoreRuntimeEnvironment): List<String> =
        jvmArguments.flatMap { it.resolveFor(environment) }

    fun gameArgumentsFor(environment: XCoreRuntimeEnvironment): List<String> =
        gameArguments.flatMap { it.resolveFor(environment) }
}

/**
 * Turns the two shapes of Mojang metadata into typed models.
 *
 * Two documents are parsed: `version_manifest_v2.json` (handled by
 * [XCoreVersionManifestDocument.parse]) and the per-version profile this class reads. Both are
 * strict: a field the launcher needs is a hard failure with the offending context named, never a
 * silent default. Optional fields are optional because the real documents omit them for older
 * versions, not because parsing them was skipped.
 */
class XCoreVersionParser {

    /**
     * Parses a version profile.
     *
     * [sourceUrl] is recorded on the result so a later "where did this come from" question can be
     * answered from the stored row rather than from a guess.
     */
    fun parse(
        json: String,
        sourceUrl: String = "",
    ): XCoreResult<XCoreVersionProfile> = xCoreRunCatching(
        origin = "profile.parse",
        errorKind = XCoreErrorKind.NETWORK,
    ) {
        val root = readTree(json, "version profile")
        val id = root.requiredString("id", "version profile")
        val releaseType = XCoreReleaseType
            .fromManifestId(root.optString("type") ?: "release")
            .getOrThrowXCore()
        val javaBlock = root.get("javaVersion")?.asObjectOrNull()

        XCoreVersionProfile(
            id = id,
            releaseType = releaseType,
            timeMillis = XCoreTimestamps.parseUtcOrZero(root.optString("time")),
            releaseTimeMillis = XCoreTimestamps.parseUtcOrZero(root.optString("releaseTime")),
            inheritsFrom = root.optString("inheritsFrom")?.takeIf { it.isNotBlank() },
            mainClass = root.optString("mainClass"),
            minimumLauncherVersion = root.optLong("minimumLauncherVersion")?.toInt(),
            complianceLevel = root.optLong("complianceLevel")?.toInt(),
            javaRuntimeComponent = javaBlock?.optString("component"),
            javaMajorVersion = javaBlock?.let { majorVersionOf(it.get("majorVersion")) }
                ?: DEFAULT_JAVA_MAJOR,
            assetIndex = parseAssetIndex(root.get("assetIndex"), "version profile assetIndex"),
            assetsVersion = root.optString("assets"),
            downloads = parseDownloads(root.get("downloads"), "version profile downloads"),
            libraries = parseLibraries(root.get("libraries"), "version profile libraries"),
            jvmArguments = parseArguments(root.get("arguments")?.asObjectOrNull()?.get("jvm"), "jvm"),
            gameArguments = parseGameArguments(root),
            defaultUserJvmArguments =
                parseArguments(root.get("arguments")?.asObjectOrNull()?.get("default-user-jvm"), "default-user-jvm"),
            legacyGameArguments = root.optString("minecraftArguments"),
            logging = parseLogging(
                root.get("logging").asObjectOrNull()?.get("client").asObjectOrNull(),
            ),
            sourceUrl = sourceUrl,
            rawJson = json,
        )
    }

    /**
     * Reads the `majorVersion` field, which is a scalar in current profiles and a one-element array
     * in some 1.18/1.19-era ones.
     */
    private fun majorVersionOf(element: JsonElement?): Int? = when {
        element == null || element.isJsonNull -> null
        element.isJsonArray -> {
            val array = element.asJsonArray
            if (array.size() == 0) {
                null
            } else {
                array[0].takeIf { it.isJsonPrimitive }?.let { it.asInt }
            }
        }

        element.isJsonPrimitive -> runCatching { element.asInt }.getOrNull()
        else -> null
    }

    private fun parseDownloads(
        element: JsonElement?,
        context: String,
    ): Map<String, XCoreDownloadFile> {
        val obj = element.asObjectOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, XCoreDownloadFile>(obj.size())
        for ((key, value) in obj.entrySet()) {
            val file = XCoreDownloadFile.parse(value, "$context.$key") ?: continue
            result[key] = file
        }
        return result
    }

    private fun parseAssetIndex(
        element: JsonElement?,
        context: String,
    ): XCoreAssetIndexRef? {
        val obj = element.asObjectOrNull() ?: return null
        return XCoreAssetIndexRef(
            id = obj.requiredString("id", context),
            url = obj.requiredString("url", context),
            sha1 = XCoreSha1.parse(obj.requiredString("sha1", context)).getOrThrowXCore(),
            sizeBytes = obj.optLong("size") ?: -1L,
            totalSizeBytes = obj.optLong("totalSize") ?: -1L,
        )
    }

    private fun parseLogging(
        obj: JsonObject?,
    ): XCoreLoggingClient? {
        val client = obj ?: return null
        val file = XCoreDownloadFile.parse(client.get("file"), "logging.client.file") ?: return null
        return XCoreLoggingClient(
            id = client.get("file").asObjectOrNull()?.optString("id"),
            file = file,
            argument = client.optString("argument") ?: "",
            type = client.optString("type"),
        )
    }

    private fun parseLibraries(
        element: JsonElement?,
        context: String,
    ): List<XCoreLibraryEntry> {
        val array = element as? JsonArray ?: return emptyList()
        return array.map { entry ->
            val obj = entry.asObjectOrNull()
                ?: failXCore(XCoreError.Network("$context entry is not an object", retryable = false))
            val coordinate = obj.requiredString("name", "$context entry")
            val downloads = obj.get("downloads")?.asObjectOrNull()
            XCoreLibraryEntry(
                coordinate = coordinate,
                artifact = XCoreDownloadFile.parse(downloads?.get("artifact"), "$coordinate artifact"),
                classifiers = downloads?.get("classifiers").asObjectOrNull()?.let { classifiers ->
                    classifiers.entrySet().mapNotNull { (key, value) ->
                        XCoreDownloadFile.parse(value, "$coordinate classifier $key")?.let { key to it }
                    }.toMap()
                }.orEmpty(),
                natives = obj.get("natives").asObjectOrNull()?.entrySet()
                    ?.mapNotNull { (key, value) ->
                        if (value.isJsonPrimitive) key to value.asString else null
                    }?.toMap().orEmpty(),
                extractExcludes = obj.get("extract").asObjectOrNull()?.get("exclude")
                    ?.asArrayOrNull()?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                    .orEmpty(),
                rules = XCoreRule.parseList(obj.get("rules"), "$coordinate rules"),
            )
        }
    }

    /**
     * Pre-1.13 profiles carry a single `minecraftArguments` string and no `arguments` object; those
     * tokens are turned into one unconditional clause so callers only ever see [XCoreArgumentClause].
     */
    private fun parseGameArguments(root: JsonObject): List<XCoreArgumentClause> {
        val fromObject = parseArguments(root.get("arguments")?.asObjectOrNull()?.get("game"), "game")
        if (fromObject.isNotEmpty()) return fromObject
        val legacy = root.optString("minecraftArguments") ?: return emptyList()
        val tokens = legacy.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        return listOf(XCoreLiteralArgument(tokens))
    }

    private fun parseArguments(
        element: JsonElement?,
        context: String,
    ): List<XCoreArgumentClause> {
        val array = element as? JsonArray ?: return emptyList()
        return array.map { entry ->
            if (entry.isJsonPrimitive) {
                XCoreLiteralArgument(listOf(entry.asString))
            } else {
                val obj = entry.asObjectOrNull()
                    ?: failXCore(XCoreError.Network("$context argument '$entry' is neither string nor object", retryable = false))
                val values = obj.get("value").let { value ->
                    when {
                        value == null || value.isJsonNull -> emptyList()
                        value.isJsonArray -> value.asJsonArray.mapNotNull {
                            if (it.isJsonPrimitive) it.asString else null
                        }

                        value.isJsonPrimitive -> listOf(value.asString)
                        else -> failXCore(
                            XCoreError.Network("$context argument 'value' has an unusable type", retryable = false),
                        )
                    }
                }
                XCoreConditionalArgument(
                    rules = XCoreRule.parseList(obj.get("rules"), "$context argument rules"),
                    values = values,
                )
            }
        }
    }

    /**
     * Applies an inherited profile over its parent.
     *
     * Loader profiles (`inheritsFrom` is set) publish only what they add: their own libraries, main
     * class and extra arguments. Everything else comes from the parent, so the merged result is what
     * actually gets installed and launched. Order follows the official launcher: parent classpath
     * first, loader jars after, so a loader can override a class without duplicate entries winning
     * at random.
     */
    fun merge(
        child: XCoreVersionProfile,
        parent: XCoreVersionProfile,
    ): XCoreResult<XCoreVersionProfile> {
        if (child.inheritsFrom != parent.id) {
            return XCoreError.Configuration(
                detail = "refusing to merge profile '${child.id}' with parent '${parent.id}': " +
                    "it declares inheritsFrom '${child.inheritsFrom}'",
            ).asXCoreFailure()
        }
        val childDeclaredArguments = child.jvmArguments.isNotEmpty() || child.gameArguments.isNotEmpty()
        return XCoreVersionProfile(
            id = child.id,
            releaseType = child.releaseType,
            timeMillis = child.timeMillis,
            releaseTimeMillis = if (child.releaseTimeMillis > 0) child.releaseTimeMillis else parent.releaseTimeMillis,
            inheritsFrom = parent.id,
            mainClass = child.mainClass ?: parent.mainClass,
            minimumLauncherVersion = child.minimumLauncherVersion ?: parent.minimumLauncherVersion,
            complianceLevel = child.complianceLevel ?: parent.complianceLevel,
            javaRuntimeComponent = child.javaRuntimeComponent ?: parent.javaRuntimeComponent,
            javaMajorVersion = maxOf(child.javaMajorVersion, parent.javaMajorVersion),
            assetIndex = child.assetIndex ?: parent.assetIndex,
            assetsVersion = child.assetsVersion ?: parent.assetsVersion,
            downloads = if (child.clientDownload == null) parent.downloads else child.downloads,
            libraries = parent.libraries + child.libraries,
            jvmArguments = if (childDeclaredArguments) parent.jvmArguments + child.jvmArguments else parent.jvmArguments,
            gameArguments = if (childDeclaredArguments) parent.gameArguments + child.gameArguments else parent.gameArguments,
            defaultUserJvmArguments =
                if (child.defaultUserJvmArguments.isEmpty()) {
                    parent.defaultUserJvmArguments
                } else {
                    child.defaultUserJvmArguments
                },
            legacyGameArguments = child.legacyGameArguments ?: parent.legacyGameArguments,
            logging = child.logging ?: parent.logging,
            sourceUrl = child.sourceUrl,
            rawJson = child.rawJson,
        ).asXCoreSuccess()
    }

    /**
     * Projects a resolved profile onto the persisted `xcore_versions` row.
     *
     * [XCoreVersionRef.assetIndexCount] is recorded as zero until the index itself has been read:
     * the object count is a fact about the asset index document, and inventing a plausible number
     * here would make a later "is this install complete" check agree with a file it never counted.
     */
    fun toVersionRef(
        profile: XCoreVersionProfile,
        lastSeenManifestMillis: Long? = null,
        assetIndexCount: Int = 0,
        installed: Boolean = false,
    ): XCoreResult<XCoreVersionRef> {
        val client = profile.clientDownload
            ?: return XCoreError.Validation(
                detail = "profile '${profile.id}' has no client download${profile.inheritsFrom?.let { " and inherits from '$it'" } ?: ""}; " +
                    "resolve the inheritance chain before registering it",
                field = "downloads.client",
            ).asXCoreFailure()
        return XCoreVersionRef(
            id = profile.id,
            releaseType = profile.releaseType,
            javaVersion = profile.javaMajorVersion,
            assetIndexId = profile.assetIndex?.id ?: profile.assetsVersion ?: "",
            assetIndexCount = assetIndexCount,
            clientDownloadUrl = client.url,
            clientDownloadSha1 = client.sha1,
            releaseTimeMillis = profile.releaseTimeMillis,
            inheritsFrom = profile.inheritsFrom,
            lastSeenManifestMillis = lastSeenManifestMillis,
            installed = installed,
        ).validate()
    }

    companion object {
        const val DEFAULT_JAVA_MAJOR = 8
        private val WHITESPACE = Regex("\\s+")
    }
}

private fun JsonObject.optBoolean(key: String): Boolean? {
    val element = get(key) ?: return null
    return if (element.isJsonPrimitive) element.asBoolean else null
}
