package studio.xycore.xcore.graphics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.runtime.XCoreDeviceFacts

/**
 * Pure-JVM tests for the graphics decisions: version parsing, the requirement tables, and the
 * validate/refuse matrix. Nothing here touches EGL, so a machine with no GPU runs them the same way
 * the phone does.
 */
class XCoreGraphicsCapabilitiesTest {

    private fun device(abi: String = "arm64-v8a", apiLevel: Int = 34): XCoreDeviceFacts =
        XCoreDeviceFacts(
            abi = abi,
            allSupportedAbis = listOf(abi),
            apiLevel = apiLevel,
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            nativeLibraryDir = "/data/app/lib",
            osVersionRelease = "14",
            deviceModel = "test-device",
        )

    private fun facts(
        glVersionString: String = "OpenGL ES 3.2",
        extensions: Set<String> = setOf("GL_EXT_texture_filter_anisotropic"),
        maxTextureSize: Int = 8192,
        samples: Int = 4,
        clientVersion: Int = 3,
    ) = XCoreGlContextFacts(
        eglVersion = "1.5 Android",
        eglClientApis = "opengl_es2 opengl_es3",
        glVersionString = glVersionString,
        glVendor = "Test Vendor",
        glRenderer = "Test GPU",
        glslVersionString = "OpenGL ES GLSL ES 3.20",
        extensions = extensions,
        maxTextureSize = maxTextureSize,
        maxViewportWidth = 8192,
        maxViewportHeight = 8192,
        advertisedSamples = samples,
        contextClientVersion = clientVersion,
        surfaceKind = XCoreSurfaceKind.PBUFFER,
    )

    private fun capabilities(
        gl: XCoreGlContextFacts?,
        abi: String = "arm64-v8a",
        lowRam: Boolean = false,
        failure: XCoreError? = gl?.let { null } ?: XCoreError.Native(detail = "eglCreateContext failed"),
    ) = XCoreDeviceCapabilities(
        device = device(abi = abi),
        gl = gl,
        probeFailure = failure,
        lowRamDevice = lowRam,
        declaredGlFeatures = listOf("android.hardware.opengles.aep"),
    )

    @Test
    fun parsesTheVersionStringsDriversActuallyReturn() {
        assertEquals(XCoreGlVersion(3, 2), XCoreGlVersion.parse("OpenGL ES 3.2"))
        assertEquals(XCoreGlVersion(3, 0), XCoreGlVersion.parse("OpenGL ES-3.0"))
        assertEquals(XCoreGlVersion(4, 6), XCoreGlVersion.parse("4.6.0 NVIDIA 550.54"))
        assertNull(XCoreGlVersion.parse("Adreno (TM) 660"))
        assertNull(XCoreGlVersion.parse(null))
        assertNull(XCoreGlVersion.parse(""))
    }

    @Test
    fun versionOrderingComparesMinorBeforeMajorIsIgnored() {
        assertTrue(XCoreGlVersion(3, 2) > XCoreGlVersion(3, 1))
        assertTrue(XCoreGlVersion(4, 0) > XCoreGlVersion(3, 9))
        assertFalse(XCoreGlVersion(3, 0) >= XCoreGlVersion(3, 1))
        assertEquals(0, XCoreGlVersion(3, 2).compareTo(XCoreGlVersion(3, 2)))
    }

    @Test
    fun aShimBackedConfigurationIsRefusedWithoutEvenLookingAtTheGpu() {
        val config = XCoreRendererConfig(
            backend = XCoreRenderBackend.LWJGL_GLFW_SHIM,
            minimumGlVersion = XCoreGlVersion(3, 2),
            requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
            requirementEvidence = "shaders said 320 es",
            eglClientVersion = 3,
            samples = 0,
            framebufferWidth = 1920,
            framebufferHeight = 1080,
            versionId = "1.21.4",
        )
        val verdict = config.validateAgainst(capabilities(facts()))
        assertFalse(verdict.usable)
        assertTrue(
            "the refusal must name the missing shim, not the GPU: ${verdict.firstReason()}",
            verdict.firstReason().contains(XCoreRenderBackend.LWJGL_GLFW_SHIM.blocker, ignoreCase = true) ||
                verdict.firstReason().contains("GLFW", ignoreCase = true),
        )
    }

    @Test
    fun anUnmeasuredFloorIsRefusedEvenOnADeviceThatWouldSatisfyIt() {
        val config = XCoreRendererConfig(
            backend = XCoreRenderBackend.EGL_DIRECT,
            minimumGlVersion = null,
            requirementSource = XCoreRequirementSource.UNKNOWN,
            requirementEvidence = "no client jar was readable",
            eglClientVersion = 3,
            samples = 0,
            framebufferWidth = 1280,
            framebufferHeight = 720,
        )
        val verdict = config.validateAgainst(capabilities(facts()))
        assertFalse(verdict.usable)
        assertTrue(verdict.firstReason().contains("no GL floor", ignoreCase = true))
    }

    @Test
    fun aFloorAboveTheDeviceIsRefusedWithBothNumbers() {
        val config = XCoreRendererConfig(
            backend = XCoreRenderBackend.EGL_DIRECT,
            minimumGlVersion = XCoreGlVersion(3, 1),
            requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
            requirementEvidence = "GLSL 310 es",
            eglClientVersion = 3,
            samples = 0,
            framebufferWidth = 1280,
            framebufferHeight = 720,
        )
        val verdict = config.validateAgainst(capabilities(facts(glVersionString = "OpenGL ES 3.0")))
        assertFalse(verdict.usable)
        assertTrue(verdict.reasons.any { reason -> reason.contains("3.1") && reason.contains("3.0") })
    }

    @Test
    fun aMeasuredBaselineIsAcceptedOnTheDeviceItWasMeasuredOn() {
        val measured = capabilities(facts())
        val baseline = XCoreRendererConfig.measuredBaseline(measured, 2400, 1080)
        assertNotNull(baseline)
        val verdict = baseline!!.validateAgainst(measured)
        assertTrue("baseline must be usable on its own device: ${verdict.reasons}", verdict.usable)
        assertEquals(XCoreRequirementSource.MEASURED_ON_DEVICE, baseline.requirementSource)
        assertEquals("OpenGL ES 3.2", baseline.requirementEvidence)
    }

    @Test
    fun noBaselineExistsWithoutAContext() {
        assertNull(XCoreRendererConfig.measuredBaseline(capabilities(null), 100, 100))
    }

    @Test
    fun aContextlessDeviceRefusesWithTheProbeFailureAsReason() {
        val config = XCoreRendererConfig(
            backend = XCoreRenderBackend.EGL_DIRECT,
            minimumGlVersion = XCoreGlVersion(3, 0),
            requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
            requirementEvidence = "GLSL 300 es",
            eglClientVersion = 3,
            samples = 0,
            framebufferWidth = 800,
            framebufferHeight = 600,
        )
        val verdict = config.validateAgainst(capabilities(null))
        assertFalse(verdict.usable)
        assertTrue(verdict.firstReason().contains("no GL context", ignoreCase = true))
    }

    @Test
    fun msaaTextureSizeAndExtensionsAreAllCheckedAgainstTheDriver() {
        val measured = capabilities(
            facts(extensions = emptySet(), maxTextureSize = 4096, samples = 0),
        )
        val config = XCoreRendererConfig(
            backend = XCoreRenderBackend.EGL_DIRECT,
            minimumGlVersion = XCoreGlVersion(3, 0),
            requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
            requirementEvidence = "measured",
            eglClientVersion = 3,
            samples = 4,
            framebufferWidth = 8192,
            framebufferHeight = 600,
            requiredExtensions = listOf("GL_KHR_blend_equation_advanced"),
        )
        val reasons = measured.incompatibilitiesFor(config)
        assertTrue(reasons.toString(), reasons.any { it.contains("multisampling") })
        assertTrue(reasons.toString(), reasons.any { it.contains("GL_MAX_TEXTURE_SIZE") })
        assertTrue(reasons.toString(), reasons.any { it.contains("blend_equation") })
    }

    @Test
    fun a32BitDeviceIsRefusedForReasonsThatHaveNothingToDoWithTheGpu() {
        val reasons = capabilities(facts(), abi = "armeabi-v7a").incompatibilitiesFor(
            XCoreRendererConfig(
                backend = XCoreRenderBackend.EGL_DIRECT,
                minimumGlVersion = XCoreGlVersion(3, 0),
                requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
                requirementEvidence = "measured",
                eglClientVersion = 3,
                samples = 0,
                framebufferWidth = 800,
                framebufferHeight = 600,
            ),
        )
        assertTrue(reasons.toString(), reasons.any { it.contains("32-bit") })
    }

    @Test
    fun aLowRamDeviceIsRefusedEvenWhenEveryGlNumberPasses() {
        val reasons = capabilities(facts(), lowRam = true).incompatibilitiesFor(
            XCoreRendererConfig(
                backend = XCoreRenderBackend.EGL_DIRECT,
                minimumGlVersion = XCoreGlVersion(3, 0),
                requirementSource = XCoreRequirementSource.MEASURED_FROM_SHADERS,
                requirementEvidence = "measured",
                eglClientVersion = 3,
                samples = 0,
                framebufferWidth = 800,
                framebufferHeight = 600,
            ),
        )
        assertTrue(reasons.toString(), reasons.any { it.contains("low-RAM") })
    }

    @Test
    fun extensionMatchingToleratesThePrefixesAndSuffixesDriversAdd() {
        val withSuffix = facts(extensions = setOf("GL_KHR_no_error"))
        assertTrue(withSuffix.hasExtension("KHR_no_error"))
        assertTrue(withSuffix.hasExtension("khr_no_error"))
        assertFalse(withSuffix.hasExtension("KHR_robustness"))
    }

    @Test
    fun shaderGlslNumbersMapToTheProfilesKhronosPublishes() {
        assertEquals(XCoreGlVersion(3, 2), XCoreShaderFloor.GLSL_TO_GL[150])
        assertEquals(XCoreGlVersion(3, 3), XCoreShaderFloor.GLSL_TO_GL[330])
        assertEquals(XCoreGlVersion(4, 6), XCoreShaderFloor.GLSL_TO_GL[460])
        assertEquals(XCoreGlVersion(3, 0), XCoreShaderFloor.GLSL_ES_TO_GL[300])
        assertEquals(XCoreGlVersion(3, 2), XCoreShaderFloor.GLSL_ES_TO_GL[320])
        assertNull(XCoreShaderFloor.GLSL_ES_TO_GL[330])
        assertNull(
            XCoreShaderFloor(glslVersion = 999, isOpenGlEs = false, shadersRead = 1, sourceJar = "x.jar")
                .asGlVersion(),
        )
    }

    @Test
    fun theShaderReaderTakesTheHighestVersionDirectiveInTheJar() {
        val jar = zipOf(
            "shaders/core/rename.vsh" to "#version 150\nvoid main() {}\n",
            "shaders/core/rename.fsh" to "#version 150 #define X\nvoid main() {}\n",
            "shaders/include/util.fsh" to "#version 120\n",
            "assets/notashader.txt" to "#version 460\n",
            "shaders/core/silent.vsh" to "void main() {}\n",
        )
        val outcome = XCoreShaderFloorReader().read(jar)
        val floor = (outcome as XCoreResult.Success).value
        assertEquals(150, floor.glslVersion)
        assertFalse(floor.isOpenGlEs)
        assertEquals(4, floor.shadersRead)
        assertEquals(XCoreGlVersion(3, 2), floor.asGlVersion())
        jar.delete()
    }

    @Test
    fun anEsDirectiveIsReportedAsEsAndNotAsTheSameNumberOnDesktop() {
        val jar = zipOf("shaders/core/blocks.fsh" to "#version 300 es\nprecision mediump float;\n")
        val floor = (XCoreShaderFloorReader().read(jar) as XCoreResult.Success).value
        assertTrue(floor.isOpenGlEs)
        assertEquals(XCoreGlVersion(3, 0), floor.asGlVersion())
        jar.delete()
    }

    @Test
    fun aJarWithoutShadersFailsTypedInsteadOfDefaultingToTheOldestProfile() {
        val jar = zipOf("pack.mcmeta" to "{}")
        val outcome = XCoreShaderFloorReader().read(jar)
        assertTrue(outcome is XCoreResult.Failure)
        assertTrue((outcome as XCoreResult.Failure).error.detail.contains("no shader sources", ignoreCase = true))
        jar.delete()
    }

    @Test
    fun aMissingJarIsReportedAsAConfigurationFailure() {
        val outcome = XCoreShaderFloorReader().read(File(absolutePathOf("absent-client")))
        assertTrue(outcome is XCoreResult.Failure)
        assertTrue((outcome as XCoreResult.Failure).error.detail.contains("no client jar"))
    }

    private fun absolutePathOf(prefix: String): String =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}.jar").absolutePath

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = jarNamed("synthetic-client")
        ZipOutputStream(file.outputStream()).use { archive ->
            entries.forEach { (name, text) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(text.toByteArray())
                archive.closeEntry()
            }
        }
        return file
    }

    private fun jarNamed(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}.jar")

    @Test
    fun theReadinessReportNamesEveryMissingThingAndOnlyThose() {
        val measured = capabilities(facts())
        val baseline = XCoreRendererConfig.measuredBaseline(measured, 2400, 1080)!!
        val report = XCoreGraphicsReadinessReport(
            generatedAtMillis = 1L,
            capabilities = measured,
            baseline = baseline,
            baselineVerdict = baseline.validateAgainst(measured),
            windowSurface = null,
            surfaceFailure = null,
            target = null,
            shaderFloor = null,
            shaderReadFailure = null,
            versionConfig = null,
            versionVerdict = null,
            nativeAudit = null,
            nativeFailure = XCoreError.Native(detail = "no libraries"),
        )
        assertFalse(report.canPresentFrames)
        assertFalse(report.canRunMinecraft)
        assertFalse(report.bridgeReady)
        assertTrue(report.blockers.toString(), report.blockers.any { it.contains("native audit did not run") })
        assertTrue(report.blockers.toString(), report.blockers.any { it.contains("no Minecraft version was assessed") })
        assertTrue(report.blockers.toString(), report.blockers.any { it.contains("window-surface path was not exercised") })
        assertEquals(report.detailRows().size, report.detailRows().map { it.first }.distinct().size)
    }

    @Test
    fun theGraphicsTargetDescribesAnAbsentJarWithoutInventingOne() {
        val target = XCoreGraphicsTarget(versionId = "1.21.4", clientJar = null, lwjglCoordinate = null)
        assertTrue(target.describe().contains("no client jar on disk"))
        assertTrue(target.describe().contains("not recorded by this build"))
        assertTrue(target.missingClientJarError().detail.contains("1.21.4"))
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        assertNull(XCoreGraphicsTarget.jarFor(tmp, "no-such-version"))
        assertEquals("org.lwjgl:lwjgl:3.3.4", XCoreGraphicsTarget.acceptableCoordinate(" org.lwjgl:lwjgl:3.3.4 "))
        assertNull(XCoreGraphicsTarget.acceptableCoordinate("com.mojang:patchy:2.2.10"))
        assertFalse(capabilities(null).hasGlContext)
    }
}
