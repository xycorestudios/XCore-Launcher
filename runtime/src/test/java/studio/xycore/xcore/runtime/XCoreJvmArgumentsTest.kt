package studio.xycore.xcore.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.versions.XCoreRuntimeEnvironment
import studio.xycore.xcore.versions.XCoreVersionParser

/**
 * Building the command, and keeping the JVM's options separated from the game's.
 *
 * The profile is parsed here rather than hand-rolled, so these tests exercise the real argument
 * clauses - rule-gated OS and feature entries included - the way the installer will hand them over.
 */
class XCoreJvmArgumentsTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val arm64 = XCoreJavaSelector.deviceFor(
        abi = "arm64-v8a",
        apiLevel = 34,
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        nativeLibraryDir = "/data/app/lib/arm64",
    )

    private val profileJson = """
        {
          "id": "1.21.4",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
          "libraries": [],
          "arguments": {
            "game": [
              "--username", "${'$'}{auth_player_name}",
              "--version", "${'$'}{version_name}",
              "--gameDir", "${'$'}{game_directory}",
              "--accessToken", "${'$'}{auth_access_token}",
              {"rules": [{"action": "allow", "features": {"has_custom_resolution": {"required": true}}}], "value": ["--width", "${'$'}{width}"]},
              "--mystery", "${'$'}{not_a_real_token}"
            ],
            "jvm": [
              {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
              "-Djava.library.path=${'$'}{natives_directory}",
              "-cp", "${'$'}{classpath}"
            ]
          }
        }
    """.trimIndent()

    private fun profile(): studio.xycore.xcore.versions.XCoreVersionProfile =
        XCoreVersionParser().parse(profileJson).let { outcome ->
            assertNull("the profile must parse: ${outcome.errorOrNull()?.detail}", outcome.errorOrNull())
            outcome.valueOrNull()!!
        }

    private val runtimeInfo = XCoreRuntimeInfo(
        id = "java-17",
        displayName = "OpenJDK 17",
        abi = "arm64-v8a",
        archiveRelativePath = "runtimes/java-17/java-17.deb",
        homeRelativePath = "runtimes/java-17/jdk",
        sizeBytes = 96_344_020L,
        sha1 = null,
        state = XCoreRuntimeState.READY,
        provenance = XCoreRuntimeProvenance.INSTALLED_FROM_SOURCE,
        release = XCoreJavaRelease(17, "17.0.21", "Temurin-era Termux build", true, emptyMap()),
        launcherElf = null,
        problems = emptyList(),
    )

    private fun tokens(
        classpath: List<File> = listOf(File(temp.root, "versions/1.21.4/1.21.4.jar")),
        resolution: Pair<Int, Int>? = null,
    ): XCoreLaunchTokens {
        val gameDir = temp.root.newFolderIfMissing("game")
        return XCoreLaunchTokens(
            playerName = "badlion",
            uuid = "8ff-uuid",
            accessToken = "SECRET-ACCESS-TOKEN",
            versionName = "1.21.4",
            gameDirectory = gameDir,
            assetsDirectory = temp.root.newFolderIfMissing("assets"),
            assetsIndexName = "16",
            nativesDirectory = temp.root.newFolderIfMissing("natives"),
            classpath = classpath,
            resolutionWidth = resolution?.first,
            resolutionHeight = resolution?.second,
        )
    }

    private fun File.newFolderIfMissing(name: String): File = File(this, name).also { it.mkdirs() }

    private fun request(
        profile: studio.xycore.xcore.versions.XCoreVersionProfile? = this.profile(),
        environment: XCoreRuntimeEnvironment = arm64.runtimeEnvironment(),
        tokens: XCoreLaunchTokens = this.tokens(),
        memoryMb: Int = 1024,
        extraJvmArguments: List<String> = emptyList(),
        extraGameArguments: List<String> = emptyList(),
    ): XCoreJvmRequest = XCoreJvmRequest(
        runtime = runtimeInfo,
        javaExecutable = File(temp.root, "runtimes/java-17/jdk/bin/java"),
        runtimeHome = temp.root.newFolderIfMissing("runtimes/java-17/jdk"),
        profile = profile,
        environment = environment,
        tokens = tokens,
        memoryMb = memoryMb,
        extraJvmArguments = extraJvmArguments,
        extraGameArguments = extraGameArguments,
        execPolicy = arm64.execPolicy,
    )

    private fun arguments() = XCoreJvmArguments(arm64)

    private fun build(request: XCoreJvmRequest): XCoreCommandPlan =
        arguments().build(request).let { outcome ->
            assertNull("the plan should build: ${outcome.errorOrNull()?.detail}", outcome.errorOrNull())
            outcome.valueOrNull()!!
        }

    @Test
    fun heapIsCappedAtHalfTheDevicesRamAndTheClampIsStated() {
        val granted = arguments().decideHeap(1024)
        assertFalse(granted.clamped)
        assertEquals(1024, granted.grantedMb)
        assertEquals(4096L, granted.deviceRamMb)
        assertNull(granted.reason)

        val tooBig = arguments().decideHeap(4096)
        assertTrue(tooBig.clamped)
        assertEquals(2048, tooBig.grantedMb)
        assertTrue(tooBig.reason.orEmpty().contains("half of 4096M device RAM"))

        val tooSmall = arguments().decideHeap(64)
        assertTrue(tooSmall.clamped)
        assertEquals(XCoreJvmArguments.MIN_HEAP_MB.toInt(), tooSmall.grantedMb)
        assertTrue(tooSmall.reason.orEmpty().contains("below the 256M floor"))

        val unknownRam = XCoreJvmArguments(XCoreJavaSelector.deviceFor("arm64-v8a", apiLevel = 30)).decideHeap(8192)
        assertEquals(XCoreJvmArguments.DEFAULT_HEAP_CEILING_MB.toInt(), unknownRam.grantedMb)
    }

    @Test
    fun expandsPlaceholdersAndKeepsTheOneItCannotFill() {
        val unresolved = LinkedHashSet<String>()
        val launch = tokens()
        val expanded = arguments().substitute("--dir=${'$'}{game_directory} --token=${'$'}{auth_access_token}", launch.tokenMap(), unresolved)
        assertTrue("expanded was '$expanded'", expanded.contains(launch.gameDirectory.absolutePath))
        assertTrue("expanded was '$expanded'", expanded.contains("SECRET-ACCESS-TOKEN"))
        assertTrue("unresolved was $unresolved", unresolved.isEmpty())

        val stillMissing = LinkedHashSet<String>()
        val kept = arguments().substitute("--width=${'$'}{width}", tokens().tokenMap(), stillMissing)
        assertEquals("--width=${'$'}{width}", kept)
        assertEquals(setOf("width"), stillMissing)
        assertEquals("no placeholder at all", "--plain", arguments().substitute("--plain", emptyMap(), LinkedHashSet()))
        val unterminated = LinkedHashSet<String>()
        assertEquals("--x=${'$'}{oops", arguments().substitute("--x=${'$'}{oops", emptyMap(), unterminated))
        assertTrue("an unterminated placeholder is literal text, not a missing token", unterminated.isEmpty())
    }

    @Test
    fun theCredentialIsNeverInTheMapUsedForDisplay() {
        val redacted = tokens().redactedTokenMap()
        val plain = tokens().tokenMap()
        assertEquals("SECRET-ACCESS-TOKEN", plain["auth_access_token"])
        assertEquals(XCoreLaunchTokens.REDACTED, redacted["auth_access_token"])
        assertEquals("badlion", redacted["auth_player_name"])
        assertEquals(plain.size, redacted.size)
        val withSession = tokens().copy(clientToken = "session-123").redactedTokenMap()
        assertEquals(XCoreLaunchTokens.REDACTED, withSession["auth_session"])
    }

    @Test
    fun legacyArgumentsSplitOnWhitespaceButRespectQuoting() {
        val split = arguments().tokenizeLegacy("--username ${'$'}{auth_player_name} --gameDir \"C:\\Program Files\\Minecraft\" --versionType \"release\"")
        assertEquals(6, split.size)
        assertEquals("C:\\Program Files\\Minecraft", split[3])
        assertEquals("release", split[5])
        assertTrue(arguments().tokenizeLegacy("   ").isEmpty())
    }

    @Test
    fun jvmOptionsAndGameArgumentsStayOnTheirOwnSidesOfTheMainClass() {
        val plan = build(request())
        val argv = plan.asArgv()
        val mainClassAt = argv.indexOf("net.minecraft.client.main.Main")

        assertTrue(mainClassAt > 0)
        listOf("-Xmx1024M", "-Dfile.encoding=UTF-8").forEach { option ->
            assertTrue("$option must sit before the main class", argv.indexOf(option) in 0 until mainClassAt)
        }
        assertTrue(plan.jvmArguments.any { it == "-cp" })
        assertTrue(plan.gameArguments.containsAll(listOf("--username", "badlion", "--version", "1.21.4")))
        listOf("--username", "--accessToken", "--gameDir").forEach { flag ->
            assertFalse("$flag belongs to the game", plan.jvmArguments.contains(flag))
        }
        listOf("-Xmx1024M", "-cp").forEach { option ->
            assertFalse("$option belongs to the JVM", plan.gameArguments.contains(option))
        }
        assertEquals(argv.take(mainClassAt), listOf(argv.first()) + plan.jvmArguments)
        assertEquals(argv.drop(mainClassAt + 1), plan.gameArguments)
    }

    @Test
    fun clausesTheDeviceDoesNotMatchAreLeftOutAndTheOnesItDoesAreFilled() {
        val plain = build(request())
        assertFalse("an osx clause must not reach a linux device", plain.jvmArguments.contains("-XstartOnFirstThread"))
        assertFalse("a feature the launcher did not enable must not be added", plain.gameArguments.contains("--width"))
        assertFalse(plain.unresolvedTokens.contains("width"))

        val withResolution = build(
            request(
                environment = arm64.runtimeEnvironment().withFeature("has_custom_resolution"),
                tokens = tokens(resolution = 1280 to 720),
            ),
        )
        assertTrue(withResolution.gameArguments.contains("--width"))
        assertEquals("1280", withResolution.gameArguments[withResolution.gameArguments.indexOf("--width") + 1])
    }

    @Test
    fun theCredentialFlagIsRedactedPositionallyForDisplay() {
        val plan = build(request())
        val tokenAt = plan.gameArguments.indexOf("--accessToken") + 1
        assertEquals("SECRET-ACCESS-TOKEN", plan.gameArguments[tokenAt])

        val shown = plan.describeForDisplay()
        assertFalse("the account token must never reach a log", shown.contains("SECRET-ACCESS-TOKEN"))
        assertTrue(shown.contains(XCoreLaunchTokens.REDACTED))
        assertTrue("the flag itself stays visible so the user can read the command", shown.contains("--accessToken"))

        val inline = plan.copy(gameArguments = listOf("--accessToken=SECRET-ACCESS-TOKEN"))
        assertFalse(inline.describeForDisplay().contains("SECRET-ACCESS-TOKEN"))
        assertTrue(inline.describeForDisplay().contains("--accessToken=<redacted>"))
    }

    @Test
    fun anUnfilledPlaceholderRefusesThePlanInsteadOfStartingADamagedCommand() {
        val plan = build(request())
        assertFalse(plan.isStartable)
        assertTrue(plan.unresolvedTokens.contains("not_a_real_token"))
        assertTrue(plan.gameArguments.contains("${'$'}{not_a_real_token}"))
        val error = plan.validate().errorOrNull()
        assertNotNull(error)
        assertEquals(XCoreErrorKind.LAUNCH, error!!.kind)
        assertTrue(error.detail.contains("unresolved placeholder"))
    }

    @Test
    fun aMissingProfileOrClasspathIsRefusedWithTheReasonNamed() {
        val noProfile = build(request(profile = null, environment = arm64.runtimeEnvironment()))
        assertTrue(noProfile.notes.any { it.contains("no version profile") })
        assertEquals(XCoreErrorKind.LAUNCH, noProfile.validate().errorOrNull()?.kind)

        val emptyClasspath = arguments().build(request(tokens = tokens(classpath = emptyList())))
        assertEquals(XCoreErrorKind.LAUNCH, emptyClasspath.errorOrNull()?.kind)
        assertTrue(emptyClasspath.errorOrNull()!!.detail.contains("no classpath entries"))
    }

    @Test
    fun theClasspathIsWrittenOnceEvenWhenTheProfileAlreadySuppliesIt() {
        val plan = build(request())
        assertEquals(1, plan.jvmArguments.count { it == "-cp" || it == "-classpath" })
        assertEquals(
            tokens().classpath.joinToString(File.pathSeparator) { it.absolutePath },
            plan.jvmArguments[plan.jvmArguments.indexOf("-cp") + 1],
        )

        val overridden = build(request(extraJvmArguments = listOf("-classpath", "other.jar")))
        assertEquals(2, overridden.jvmArguments.count { it == "-cp" || it == "-classpath" })
        assertTrue(
            "the classpath the instance asked for must come last, because that is the one HotSpot reads",
            overridden.jvmArguments.indexOf("-classpath") > overridden.jvmArguments.indexOf("-cp"),
        )
    }

    @Test
    fun anArgumentThatCannotSurviveASingleLineIsDroppedOrRefused() {
        val dropped = build(request(extraJvmArguments = listOf("-Dok=1", "-Dbroken=two\nlines")))
        assertTrue(dropped.jvmArguments.contains("-Dok=1"))
        assertFalse("a newline in an argument would split it on reload", dropped.jvmArguments.any { it.contains('\n') })
        assertTrue(dropped.notes.any { it.contains("line break") })

        val withNul = build(request(extraGameArguments = listOf("--host" + 0.toChar() + "example.com")))
        assertTrue(withNul.gameArguments.any { argument -> argument.any { character -> character.code == 0 } })
        assertEquals(XCoreErrorKind.LAUNCH, withNul.validate().errorOrNull()?.kind)
        assertTrue(withNul.validate().errorOrNull()!!.detail.contains("NUL byte"))
    }

    @Test
    fun theEnvironmentBlockCarriesWhatAJvmNeedsAndNoCredential() {
        val home = File(temp.root, "runtimes/java-17/jdk")
        File(home, "lib/server").mkdirs()
        val plan = build(request())
        val environment = plan.environment
        XCoreProcessEnvironment.REQUIRED_KEYS.forEach { key ->
            assertTrue("$key must be set", environment.entries[key]?.isNotBlank() == true)
        }
        assertEquals(home.absolutePath, environment.entries["JAVA_HOME"])
        assertTrue(environment.entries.none { (_, value) -> value.contains("SECRET-ACCESS-TOKEN") })
        assertTrue(environment.describe().contains("blocked-by-W^X"))
        assertEquals(environment.missingRequired().toString(), 0, environment.missingRequired().size)
        assertTrue(environment.librarySearchPath.any { it.name == "server" })
    }

    @Test
    fun theRuntimeOwnProblemsReachThePlanAsNotesRatherThanSilence() {
        val broken = runtimeInfo.copy(state = XCoreRuntimeState.BROKEN, problems = listOf("libjvm.so is missing"))
        val plan = build(request().copy(runtime = broken))
        assertTrue(plan.notes.contains("runtime: libjvm.so is missing"))
    }
}
