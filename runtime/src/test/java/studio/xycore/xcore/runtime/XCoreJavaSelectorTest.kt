package studio.xycore.xcore.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreRuntimeFailureReason
import studio.xycore.xcore.core.XCoreRuntimeState
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull
import studio.xycore.xcore.data.XCoreReleaseType
import studio.xycore.xcore.data.XCoreRuntimeComponent
import studio.xycore.xcore.data.XCoreSha1
import studio.xycore.xcore.data.XCoreVersionRef

/**
 * Which runtime satisfies which requirement.
 *
 * [XCoreJavaSelector.select] is pure, so these tests can state the whole policy: the exact major
 * wins, a newer major only when the caller asks for a substitution, a different ABI never, and a
 * broken tree reported rather than silently skipped.
 */
class XCoreJavaSelectorTest {

    private val device = XCoreJavaSelector.deviceFor("arm64-v8a", apiLevel = 34, totalRamBytes = 6L * 1024 * 1024 * 1024)
    private val selector = XCoreJavaSelector(device)

    private val elf = XCoreElfFacts(
        path = "runtimes/java/bin/java",
        is64Bit = true,
        isLittleEndian = true,
        machine = XCoreElfFacts.EM_AARCH64,
        interpreter = "/apex/com.android.runtime/bin/linker64",
        neededLibraries = listOf("libc.so"),
        dynamicSectionUnreadable = false,
    )

    private fun candidate(
        id: String,
        javaMajor: Int?,
        fullVersion: String? = javaMajor?.let { major -> "$major.0.1" },
        abi: String = "arm64-v8a",
        state: XCoreRuntimeState = XCoreRuntimeState.READY,
        problems: List<String> = emptyList(),
    ): XCoreRuntimeInfo = XCoreRuntimeInfo(
        id = id,
        displayName = id,
        abi = abi,
        archiveRelativePath = "runtimes/$id/$id.deb",
        homeRelativePath = "runtimes/$id/jdk",
        sizeBytes = 1_000_000L,
        sha1 = null,
        state = state,
        provenance = XCoreRuntimeProvenance.DISCOVERED_ON_DISK,
        release = javaMajor?.let { major ->
            XCoreJavaRelease(major, fullVersion.orEmpty(), "test", major >= 9, emptyMap())
        },
        launcherElf = elf,
        problems = problems,
    )

    private fun requirement(
        javaMajor: Int,
        abi: String = "arm64-v8a",
        versionId: String = "1.21.4",
    ): XCoreJavaRequirement = XCoreJavaRequirement(
        javaMajor = javaMajor,
        abi = abi,
        source = XCoreRequirementSource.VERSION_METADATA,
        versionId = versionId,
        detail = "test requirement",
    )

    @Test
    fun theExactMajorWinsAndTheNewestPatchOfItWinsWithinIt() {
        val selection = selector.select(
            requirement(17),
            listOf(
                candidate("java-17-old", 17, fullVersion = "17.0.2"),
                candidate("java-17-new", 17, fullVersion = "17.0.9"),
                candidate("java-21", 21, fullVersion = "21.0.1"),
            ),
        )
        val selected = selection as XCoreRuntimeSelection.Selected
        assertEquals("java-17-new", selected.info.id)
        assertEquals(listOf("java-17-old"), selected.alternatives.map { it.id })
    }

    @Test
    fun aNewerMajorIsOnlyOfferedWhenTheCallerAsksForSubstitution() {
        val candidates = listOf(candidate("java-21", 21))

        val refused = selector.select(requirement(17), candidates)
        assertTrue("Java 21 must not replace 17 unasked", refused is XCoreRuntimeSelection.NoRuntime)

        val selected = selector.select(requirement(17), candidates, allowNewerMajor = true) as XCoreRuntimeSelection.Selected
        assertEquals("java-21", selected.info.id)
        assertEquals(17, selected.requirement.javaMajor)
    }

    @Test
    fun anAbiMismatchIsReportedAsAnAbiMismatch() {
        val selection = selector.select(requirement(17), listOf(candidate("java-17-intel", 17, abi = "x86_64")))
        val incompatible = selection as XCoreRuntimeSelection.Incompatible
        assertTrue(incompatible.considered.single().contains("x86_64"))
        val error = incompatible.errorOrNull()
        assertNotNull(error)
        assertEquals(XCoreRuntimeFailureReason.INCOMPATIBLE_ABI, error?.runtimeReason())
        assertEquals(XCoreErrorKind.RUNTIME, error?.kind)
    }

    @Test
    fun aBrokenTreeIsReportedRatherThanSkippedSilently() {
        val selection = selector.select(
            requirement(21),
            listOf(
                candidate(
                    "java-21-torn",
                    21,
                    state = XCoreRuntimeState.READY,
                    problems = listOf("libjava.so is not a readable ELF object"),
                ),
            ),
        )
        val unverified = selection as XCoreRuntimeSelection.Unverified
        assertEquals("java-21-torn", unverified.info.id)
        val error = unverified.errorOrNull()!!
        assertEquals(XCoreRuntimeFailureReason.UNVERIFIED, error.runtimeReason())
        assertTrue(error.detail.contains("libjava.so"))
    }

    @Test
    fun anEmptyTableAndAnUnmeasuredTreeBothSayMissing() {
        val none = selector.select(requirement(8), emptyList()) as XCoreRuntimeSelection.NoRuntime
        assertTrue(none.reason().contains("the runtime table is empty"))

        val unreadable = selector.select(requirement(8), listOf(candidate("mystery", null))) as XCoreRuntimeSelection.NoRuntime
        assertTrue(unreadable.considered.single().contains("?"))
    }

    @Test
    fun mapsReleasesToMojangsPublishedBoundaries() {
        assertEquals(8, XCoreJavaSelector.knownMajorFor("1.16.5"))
        assertEquals(8, XCoreJavaSelector.knownMajorFor("1.9"))
        assertEquals(17, XCoreJavaSelector.knownMajorFor("1.17"))
        assertEquals(17, XCoreJavaSelector.knownMajorFor("1.20.4"))
        assertEquals(21, XCoreJavaSelector.knownMajorFor("1.20.5"))
        assertEquals(21, XCoreJavaSelector.knownMajorFor("1.21.4"))
        assertNull("a snapshot id is not a release string", XCoreJavaSelector.knownMajorFor("24w14a"))
        assertNull(XCoreJavaSelector.knownMajorFor("1"))

        assertTrue(XCoreJavaSelector.atOrAbove("1.20.5", XCoreJavaSelector.MIN_VERSION_FOR_JAVA_21))
        assertFalse(XCoreJavaSelector.atOrAbove("1.20.4", XCoreJavaSelector.MIN_VERSION_FOR_JAVA_21))
        assertTrue("1.10 is newer than 1.9, not older", XCoreJavaSelector.atOrAbove("1.10", "1.9"))
    }

    @Test
    fun fallbackTableRequirementNamesItsOwnSource() {
        val requirement = selector.requirementForVersionId("1.21.4")!!
        assertEquals(21, requirement.javaMajor)
        assertEquals(XCoreRequirementSource.FALLBACK_TABLE, requirement.source)
        assertNull(selector.requirementForVersionId("24w14a"))
    }

    @Test
    fun aProfileThatAsksForAnUnsupportedMajorIsRefusedWithTheReason() {
        val failure = selector.requirementFor(versionRef("1.21.4", javaVersion = 11)).errorOrNull()!!
        assertEquals(XCoreErrorKind.VALIDATION, failure.kind)
        assertTrue(failure.detail.contains("Java 11"))

        val override = selector.requirementFor(versionRef("1.21.4", javaVersion = 17), explicitMajor = 21).valueOrNull()!!
        assertEquals(21, override.javaMajor)
        assertEquals(XCoreRequirementSource.EXPLICIT_REQUEST, override.source)
        assertTrue(override.detail.contains("user override"))
    }

    @Test
    fun explainUsesOnlyFactsFromTheSelection() {
        val selected = selector.select(requirement(17), listOf(candidate("java-17", 17)))
        val text = selector.explain(selected)
        assertTrue(text.contains("arm64-v8a"))
        assertTrue(text.contains("Java 17"))
        assertTrue(text.contains("matched java-17"))
        assertTrue(text.contains("version_metadata"))

        val missing = selector.select(requirement(21), listOf(candidate("java-17", 17)))
        assertTrue(selector.explain(missing).contains("no recorded runtime satisfies Java 21"))
    }

    @Test
    fun missingRuntimeErrorSaysVerificationIsTheGapWhenItIs() {
        val withVerification = selector.missingRuntimeError(requirement(17), requireVerified = true)
        assertTrue(withVerification.detail.contains("no signature verifier"))
        assertEquals(XCoreRuntimeFailureReason.MISSING, withVerification.runtimeReason())

        val without = selector.missingRuntimeError(requirement(17), requireVerified = false, problems = listOf("tree deleted"))
        assertTrue(without.detail.contains("tree deleted"))
    }

    @Test
    fun aRecordedRowIsDescribedAsNotReMeasured() {
        val component = XCoreRuntimeComponent(
            id = "rt-arm64-v8a-17-abcdef123456",
            javaVersion = 17,
            abi = "arm64-v8a",
            relativePath = "runtimes/termux-openjdk-17-aarch64/termux-openjdk-17-aarch64.deb",
            sizeBytes = 96_344_020L,
            sha1 = XCoreSha1.parse("e3dfe77166484834f5dbe53a4b6f0ce160861357").valueOrNull(),
            state = XCoreRuntimeState.READY,
            sourceUrl = "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-17/openjdk-17_17.0.21-1_aarch64.deb",
            signatureVerified = false,
        )
        val info = selector.describeRow(component)
        assertEquals(17, info.javaMajor)
        assertEquals(XCoreRuntimeProvenance.DATABASE_ROW, info.provenance)
        assertTrue(info.notes.any { note -> note.contains("not re-measured") })
        assertEquals(component.state, info.state)
        assertFalse(info.isComplete)

        val broken = selector.describeRow(component.copy(state = XCoreRuntimeState.BROKEN, lastErrorCode = 5))
        assertTrue(broken.problems.any { problem -> problem.contains("last error 5") })
    }

    @Test
    fun supportedMajorsAreTheThreeMojangActuallyAsksFor() {
        assertEquals(listOf(8, 17, 21), XCoreJavaSelector.supportedMajors())
        assertEquals("Java 8, 17, 21", XCoreJavaMajors.describe())
        assertFalse(XCoreJavaMajors.isSupported(11))
    }

    private fun versionRef(
        id: String,
        javaVersion: Int,
    ): XCoreVersionRef = XCoreVersionRef(
        id = id,
        releaseType = XCoreReleaseType.RELEASE,
        javaVersion = javaVersion,
        assetIndexId = "17",
        assetIndexCount = 1,
        clientDownloadUrl = "https://piston-data.mojang.com/v1/objects/${"a".repeat(40)}/client.jar",
        clientDownloadSha1 = XCoreSha1.parse("a".repeat(40)).valueOrNull()!!,
        releaseTimeMillis = 1_700_000_000_000L,
        installed = true,
    )

    /** Reads the reason back off a runtime error without repeating the sealed-class switch everywhere. */
    private fun XCoreError.runtimeReason(): XCoreRuntimeFailureReason? =
        (this as? XCoreError.Runtime)?.reason
}
