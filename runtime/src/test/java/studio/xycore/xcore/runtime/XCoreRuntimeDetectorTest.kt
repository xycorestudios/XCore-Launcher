package studio.xycore.xcore.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.XCoreStorageRoot
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.isSuccess
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreGameDirectory
import studio.xycore.xcore.data.XCoreStorageLayout
import studio.xycore.xcore.data.XCoreStoragePlan

/**
 * Runtime discovery: what the launcher is allowed to claim about a directory it found.
 *
 * Every version number and every platform decision in these tests comes out of the bytes of the
 * files themselves, which is the rule the detector exists to keep. A test that let the detector
 * conclude "Java 17" from a directory named `java-17` would be testing nothing.
 */
class XCoreRuntimeDetectorTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val arm64Device = XCoreJavaSelector.deviceFor(
        abi = "arm64-v8a",
        apiLevel = 34,
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        nativeLibraryDir = "/data/app/~~abc==/studio.xycore.xcore-N1bh==/lib/arm64",
    )

    private fun detector() = XCoreRuntimeDetector(arm64Device)

    private fun layout(): XCoreStorageLayout {
        val root = temp.newFolder("game")
        return XCoreStorageLayout(
            plan = XCoreStoragePlan(
                root = root,
                requested = XCoreStorageRoot.INTERNAL,
                usedFallback = false,
                usableBytes = 10L * 1024 * 1024 * 1024,
                reason = null,
            ),
            directories = XCoreGameDirectory.REQUIRED.associateWith { directory -> File(root, directory.relativePath) },
        )
    }

    private fun XCoreStorageLayout.dirOrThrow(kind: XCoreGameDirectory): File =
        requireNotNull(dir(kind)) { "the layout has no ${kind.relativePath} directory" }

    /** Builds `runtimes/<name>/{bin/java,release}` with the given bytes; returns the home directory. */
    private fun runtimeTree(
        storage: XCoreStorageLayout,
        name: String,
        javaBytes: ByteArray,
        releaseText: String?,
        modular: Boolean = true,
    ): File {
        val home = File(storage.dirOrThrow(XCoreGameDirectory.RUNTIMES), name)
        val binDirectory = if (modular) File(home, "bin") else File(home, "jre/bin")
        binDirectory.mkdirs()
        File(binDirectory, "java").writeBytes(javaBytes)
        if (releaseText != null) {
            File(home, if (modular) "release" else "jre/release").writeText(releaseText)
        }
        File(home, "lib").mkdirs()
        File(home, "lib/timestamp").writeText("placeholder")
        return home
    }

    private fun androidBinary(needed: List<String> = listOf("libc.so", "libz.so")) = buildElf64(
        machine = XCoreElfFacts.EM_AARCH64,
        interpreter = "/apex/com.android.runtime/bin/linker64",
        needed = needed,
    )

    @Test
    fun measuresARuntimeFromItsOwnBinaryAndReleaseFile() {
        val storage = layout()
        val home = runtimeTree(storage, "java-17-openjdk", androidBinary(), "JAVA_VERSION=\"17.0.2\"\nJAVA_VENDOR=\"Termux\"\n")

        val outcome = detector().inspectTree(storage, home, archive = null)
        assertNull("a complete tree should measure successfully: ${outcome.errorOrNull()?.detail}", outcome.errorOrNull())
        val read = outcome.valueOrNull()!!
        assertEquals(17, read.javaMajor)
        assertEquals("17.0.2", read.release?.fullVersion)
        assertEquals("arm64-v8a", read.abi)
        assertEquals(XCoreRuntimeState.READY, read.state)
        assertTrue("a clean tree must report no problems, saw ${read.problems}", read.problems.isEmpty())
        assertTrue(read.isComplete)
        assertTrue(read.launcherElf?.linksBionic == true)
        assertTrue(read.launcherElf?.usesAndroidLinker == true)
        assertNull(read.launcherElf?.incompatibility())
        assertTrue(read.archiveRelativePath.endsWith("/${XCoreRuntimeDetector.MANIFEST_FILE_NAME}"))
    }

    @Test
    fun reportsADesktopLinuxJdkAsADesktopLinuxJdk() {
        val storage = layout()
        val home = runtimeTree(
            storage,
            "java-17-openjdk-amd64",
            buildElf64(
                machine = XCoreElfFacts.EM_X86_64,
                interpreter = "/lib64/ld-linux-x86-64.so.2",
                needed = listOf("libc.so.6", "libpthread.so.0"),
            ),
            "JAVA_VERSION=\"17.0.2\"\n",
        )

        val info = detector().inspectTree(storage, home, archive = null).valueOrNull()!!
        assertEquals("x86_64", info.abi)
        assertEquals(XCoreRuntimeState.BROKEN, info.state)
        assertFalse(info.isComplete)
        assertTrue("the report must name the C library, got ${info.problems}", info.problems.any { it.contains("bionic") })
        assertTrue(info.problems.any { it.contains("x86_64") })
        assertFalse(info.launcherElf!!.linksBionic)
        assertFalse(info.launcherElf!!.usesAndroidLinker)
    }

    @Test
    fun refusesToInventAVersionWhenTheReleaseFileIsMissingOrUnreadable() {
        val storage = layout()

        val withoutRelease = runtimeTree(storage, "mystery", androidBinary(), releaseText = null)
        val mystery = detector().inspectTree(storage, withoutRelease, archive = null).valueOrNull()!!
        assertNull(mystery.javaMajor)
        assertEquals(XCoreRuntimeState.NOT_INSTALLED, mystery.state)
        assertTrue(mystery.notes.any { it.contains("no release file") })
        assertFalse("an unmeasured tree must not be storable as a runtime row", mystery.toComponent().isSuccess)

        val unreadable = runtimeTree(storage, "broken", androidBinary(), "JAVA_VERSION=\"\"\n")
        val broken = detector().inspectTree(storage, unreadable, archive = null).valueOrNull()!!
        assertNull(broken.javaMajor)
        assertEquals(XCoreRuntimeState.BROKEN, broken.state)
        assertTrue(broken.problems.any { it.contains("declares no readable JAVA_VERSION") })
    }

    @Test
    fun findsTheLauncherInBothTheModularAndTheJreLayout() {
        val storage = layout()
        val legacy = runtimeTree(storage, "java-8-openjdk", androidBinary(), "JAVA_VERSION=\"1.8.0_382\"\n", modular = false)
        val info = detector().inspectTree(storage, legacy, archive = null).valueOrNull()!!
        assertEquals(8, info.javaMajor)
        assertEquals(File(legacy, "jre/bin/java").absolutePath, detector().findJavaBinary(legacy)?.absolutePath)
        assertEquals(File(legacy, "jre/release").absolutePath, detector().findReleaseFile(legacy)?.absolutePath)

        val empty = File(storage.dirOrThrow(XCoreGameDirectory.RUNTIMES), "not-a-runtime").also { it.mkdirs() }
        assertNull(detector().findJavaBinary(empty))
        val reported = detector().inspectTree(storage, empty, archive = null).valueOrNull()!!
        assertTrue(reported.problems.any { it.contains("no bin/java") })
    }

    @Test
    fun scanWalksEveryDirectoryUnderRuntimesAndIgnoresStrayFiles() = runBlocking {
        val storage = layout()
        runtimeTree(storage, "java-17", androidBinary(), "JAVA_VERSION=\"17.0.9\"\n")
        runtimeTree(storage, "java-21", androidBinary(), "JAVA_VERSION=\"21.0.1\"\n")
        File(storage.dirOrThrow(XCoreGameDirectory.RUNTIMES), "stray.txt").writeText("not a directory")

        val found = detector().scan(storage).valueOrNull()!!
        assertEquals(2, found.size)
        assertEquals(setOf(17, 21), found.mapNotNull { info -> info.javaMajor }.toSet())
        assertTrue(found.all { info -> info.provenance == XCoreRuntimeProvenance.DISCOVERED_ON_DISK })
    }

    @Test
    fun writesAManifestSoADiscoveredTreeHasSomethingToHash() {
        val storage = layout()
        val binary = androidBinary()
        val home = runtimeTree(storage, "java-17", binary, "JAVA_VERSION=\"17.0.2\"\n")

        val manifest = detector()
            .writeManifest(home, File(home, "bin/java"), XCoreJavaRelease.parse("JAVA_VERSION=\"17.0.2\""))
            .valueOrNull()
        assertNotNull(manifest)
        assertTrue(manifest!!.isFile)
        val text = manifest.readText()
        assertTrue(text.contains("javaBinary=bin/java"))
        assertTrue(text.contains("javaVersion=17.0.2"))
        assertTrue(text.contains("abi=arm64-v8a"))
        assertEquals(40, Regex("javaBinarySha1=([0-9a-f]{40})").find(text)?.groupValues?.get(1)?.length)
    }

    @Test
    fun readsTheVersionSpellingsOpenjdkBuildsShip() {
        assertEquals(8, XCoreJavaRelease.majorVersionOf("1.8.0_382"))
        assertEquals(17, XCoreJavaRelease.majorVersionOf("17.0.2"))
        assertEquals(21, XCoreJavaRelease.majorVersionOf("21-internal"))
        assertEquals(21, XCoreJavaRelease.majorVersionOf("21+35"))
        assertNull(XCoreJavaRelease.majorVersionOf("openjdk-17"))
        assertNull(XCoreJavaRelease.majorVersionOf(""))

        val release = XCoreJavaRelease.parse(
            """
            JAVA_VERSION="21.0.13"
            JAVA_VENDOR="Termux"
            IMPLEMENTOR_SOURCE="git:deadbeef"
            """.trimIndent(),
        )
        assertEquals(21, release?.majorVersion)
        assertEquals("Termux", release?.vendor)
        assertTrue("Java 9+ is modular", release?.modules == true)
        assertNull(XCoreJavaRelease.parse("OS_ARCH=\"aarch64\""))
    }

    @Test
    fun choosesTheAbiTheSameWayTheActivityDoes() {
        assertEquals("arm64-v8a", XCoreRuntimeDetector.chooseAbi(listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals("x86_64", XCoreRuntimeDetector.chooseAbi(listOf("x86_64")))
        assertEquals("armeabi-v7a", XCoreRuntimeDetector.chooseAbi(listOf("armeabi-v7a")))
        assertEquals("arm64-v8a", XCoreRuntimeDetector.chooseAbi(emptyList()))
    }

    @Test
    fun execPolicyStatesThePlatformRuleRatherThanWorkingAroundIt() {
        val twentyNine = XCoreJavaSelector.deviceFor("arm64-v8a", apiLevel = 29)
        assertFalse(twentyNine.execPolicy.canExecStagedRuntime)
        assertTrue(twentyNine.execPolicy.explain().contains("execve()"))
        val twentyEight = XCoreJavaSelector.deviceFor("arm64-v8a", apiLevel = 28)
        assertTrue(twentyEight.execPolicy.canExecStagedRuntime)
    }
}
