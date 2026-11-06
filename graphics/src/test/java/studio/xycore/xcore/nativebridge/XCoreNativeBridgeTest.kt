package studio.xycore.xcore.nativebridge

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreMemoryLogger
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.graphics.XCoreRenderLifecycle
import studio.xycore.xcore.graphics.XCoreRenderState
import studio.xycore.xcore.graphics.XCoreRenderTarget
import studio.xycore.xcore.graphics.XCoreSurfaceState
import studio.xycore.xcore.graphics.XCoreFrameTicker
import studio.xycore.xcore.graphics.XCoreViewportMapping

/**
 * The parts of the bridge that can be decided without a GPU: the lifecycle's state table, how a
 * framebuffer is scaled, what the JNI contract actually says, and whether the ELF reader finds real
 * symbols in a synthetic object.
 *
 * The ELF fixture matters more than it looks: the whole honesty of [XCoreLibraryLoader] rests on
 * reading exports from bytes rather than trusting a filename, so a reader that silently finds nothing
 * has to be caught here rather than on a phone.
 */
class XCoreNativeBridgeTest {

    private class FakeTicker : XCoreFrameTicker {
        var started = 0
        var stopped = 0
        var produce: ((Long) -> Boolean)? = null

        override fun start(produce: (Long) -> Boolean) {
            started += 1
            this.produce = produce
        }

        override fun stop() {
            stopped += 1
            produce = null
        }
    }

    private class FakeTarget(private val binds: Boolean = true) : XCoreRenderTarget {
        var readyCalls = 0
        var lostGenerations = listOf<Int>()
        var frames = 0
        var released = 0

        override fun onSurfaceReady(state: XCoreSurfaceState): XCoreResult<Unit> {
            readyCalls += 1
            return if (binds) {
                Unit.asXCoreSuccess()
            } else {
                XCoreError.Native(detail = "eglMakeCurrent refused", retryable = false).asXCoreFailure()
            }
        }

        override fun onSurfaceLost(generation: Int) {
            lostGenerations = lostGenerations + generation
        }

        override fun onFrame(frameTimeNanos: Long): Boolean {
            frames += 1
            return true
        }

        override fun onRelease() {
            released += 1
        }
    }

    private fun surfaceState(generation: Int = 1) = XCoreSurfaceState(
        generation = generation,
        width = 2400,
        height = 1080,
        format = 1,
        valid = true,
        attachedToView = true,
    )

    @Test
    fun aFreshLifecycleRefusesToRenderAndExplainsWhy() {
        val logger = XCoreMemoryLogger(minimumLevel = XCoreLogLevel.DEBUG)
        val lifecycle = XCoreRenderLifecycle(logger, FakeTicker()) { 0L }
        val refusal = lifecycle.startRendering()
        assertTrue(refusal is XCoreResult.Failure)
        val error = (refusal as XCoreResult.Failure).error
        assertTrue(error.detail.contains("context must be bound", ignoreCase = true))
        assertEquals(XCoreRenderState.NO_SURFACE, lifecycle.currentState)
        assertTrue(
            "the refusal has to reach XCoreLogger, not only the caller",
            logger.snapshot().any { record -> record.tag == XCoreLogTags.NATIVE },
        )
    }

    @Test
    fun aSurfaceWithoutARenderTargetReachesSurfaceReadyAndStopsThere() {
        val lifecycle = XCoreRenderLifecycle(null, FakeTicker()) { 0L }
        val outcome = lifecycle.onSurfaceAvailable(surfaceState())
        assertTrue(outcome is XCoreResult.Failure)
        assertEquals(XCoreRenderState.SURFACE_READY, lifecycle.currentState)
        assertFalse(lifecycle.startRendering() is XCoreResult.Success)
    }

    @Test
    fun aBoundContextCanRenderAndDeliversFramesThroughTheTicker() {
        val ticker = FakeTicker()
        val target = FakeTarget()
        val lifecycle = XCoreRenderLifecycle(null, ticker) { 0L }
        lifecycle.attachTarget(target)
        assertTrue(lifecycle.onSurfaceAvailable(surfaceState()) is XCoreResult.Success)
        assertEquals(XCoreRenderState.CONTEXT_BOUND, lifecycle.currentState)
        assertTrue(lifecycle.startRendering() is XCoreResult.Success)
        assertEquals(XCoreRenderState.RENDERING, lifecycle.currentState)
        val produce = ticker.produce ?: throw AssertionError("the ticker was never handed a frame producer")
        assertTrue(produce(1_000_000L))
        assertTrue(produce(2_000_000L))
        assertEquals(2, target.frames)
        assertTrue(lifecycle.summarize().contains("frames delivered 2"))
    }

    @Test
    fun pauseStopsThePumpAndResumeRestoresItWithoutLosingTheSurface() {
        val ticker = FakeTicker()
        val lifecycle = XCoreRenderLifecycle(null, ticker) { 0L }
        lifecycle.attachTarget(FakeTarget())
        lifecycle.onSurfaceAvailable(surfaceState())
        lifecycle.startRendering()
        assertTrue(lifecycle.pause("the activity stopped") is XCoreResult.Success)
        assertEquals(XCoreRenderState.PAUSED, lifecycle.currentState)
        assertTrue(ticker.stopped >= 1)
        assertFalse(lifecycle.currentState.mayDraw())
        assertTrue(lifecycle.resume("the activity restarted") is XCoreResult.Success)
        assertEquals(XCoreRenderState.CONTEXT_BOUND, lifecycle.currentState)
        assertFalse("resume must not start frames by itself", lifecycle.currentState == XCoreRenderState.RENDERING)
    }

    @Test
    fun aLostSurfaceIsRecoverableButReleasedIsTerminal() {
        val target = FakeTarget()
        val lifecycle = XCoreRenderLifecycle(null, FakeTicker()) { 0L }
        lifecycle.attachTarget(target)
        lifecycle.onSurfaceAvailable(surfaceState(generation = 3))
        lifecycle.startRendering()
        assertTrue(lifecycle.onSurfaceLost(generation = 3) is XCoreResult.Success)
        assertEquals(listOf(3), target.lostGenerations)
        assertEquals(XCoreRenderState.SURFACE_LOST, lifecycle.currentState)
        assertTrue(lifecycle.onSurfaceRecovered(surfaceState(generation = 4)) is XCoreResult.Success)
        assertEquals(XCoreRenderState.CONTEXT_BOUND, lifecycle.currentState)
        assertTrue(lifecycle.release() is XCoreResult.Success)
        assertEquals(1, target.released)
        assertFalse(lifecycle.transition(XCoreRenderState.SURFACE_READY, "after release") is XCoreResult.Success)
    }

    @Test
    fun illegalMovesAreReportedInsteadOfBeingSilentlyIgnored() {
        val lifecycle = XCoreRenderLifecycle(null, FakeTicker()) { 0L }
        val illegal = lifecycle.transition(XCoreRenderState.RENDERING, "a frame was requested")
        assertTrue(illegal is XCoreResult.Failure)
        val error = (illegal as XCoreResult.Failure).error
        assertTrue(error is XCoreError.Validation)
        assertEquals("renderLifecycle", (error as XCoreError.Validation).field)
        assertEquals(XCoreRenderState.NO_SURFACE, lifecycle.currentState)
    }

    @Test
    fun windowFocusNeverChangesTheRenderStateButIsRecorded() {
        val lifecycle = XCoreRenderLifecycle(null, FakeTicker()) { 0L }
        lifecycle.onWindowFocusChanged(true, "the dialog closed")
        assertTrue(lifecycle.windowFocused)
        assertEquals(XCoreRenderState.NO_SURFACE, lifecycle.currentState)
    }

    @Test
    fun anIdentityMappingKeepsCoordinatesAndALetterboxedOneShiftsThem() {
        val identity = XCoreViewportMapping.identity(1080, 2400)
        val point = identity.toFramebuffer(540f, 1200f)
        assertNotNull(point)
        assertEquals(540f, point!!.x, 0.001f)
        assertEquals(1200f, point.y, 0.001f)

        val scaled = XCoreViewportMapping(
            viewWidth = 100,
            viewHeight = 100,
            framebufferWidth = 200,
            framebufferHeight = 400,
        )
        val mapped = scaled.toFramebuffer(50f, 25f)
        assertEquals(100f, mapped!!.x, 0.001f)
        assertEquals(100f, mapped.y, 0.001f)

        val offset = scaled.copy(offsetX = 10, offsetY = 10)
        assertEquals(0f, offset.toFramebuffer(0f, 0f)!!.x, 0.001f)

        assertEquals(null, scaled.copy(viewWidth = 0).toFramebuffer(1f, 1f))
        assertEquals(null, scaled.copy(framebufferHeight = -1).toFramebuffer(1f, 1f))
    }

    @Test
    fun theJniContractNamesSymbolsThatFollowTheRealManglingRules() {
        val entries = XCoreNativeEntryPoint.entries
        assertEquals(7, entries.size)
        assertEquals(6, entries.count { it.required })
        entries.forEach { entry ->
            assertTrue("${entry.symbol} is not a JNI name", entry.symbol.startsWith("Java_"))
            assertEquals(
                "the contract may only name methods of one owner class",
                "XCoreNativeBridgeState",
                XCoreElfSymbolReader.classNameOf(entry.symbol).substringAfterLast('.'),
            )
        }
        val initialize = XCoreNativeEntryPoint.INITIALIZE
        assertEquals(
            "studio.xycore.xcore.nativebridge.XCoreNativeBridgeState",
            XCoreElfSymbolReader.classNameOf(initialize.symbol),
        )
        assertTrue(initialize.symbol.endsWith("_nativeInitialize"))
        assertEquals("nativeInitialize", XCoreElfSymbolReader.methodNameOf(initialize.symbol))
    }

    @Test
    fun theLicenseLedgerKeepsProprietaryContentUnbundledAndUnreadLicensesFlagged() {
        val ledger = XCoreNativeComponent.entries
        assertTrue(ledger.any { it == XCoreNativeComponent.GLFW })
        assertTrue(ledger.any { it == XCoreNativeComponent.LWJGL_CORE })
        val minecraft = XCoreNativeComponent.MINECRAFT_CLIENT
        assertFalse("game content may never be bundled", minecraft.mayBeBundled)
        assertFalse(minecraft.licenseVerified)
        assertTrue(
            "every component that may be bundled must have a licence this build actually read",
            ledger.filter { it.mayBeBundled }.all { it.licenseVerified },
        )
        assertTrue(ledger.any { !it.licenseVerified })
        assertTrue(XCoreNativeComponent.OPENAL_SOFT.note.isNotBlank())
    }

    @Test
    fun anAuditWithNoLibrariesIsNotReadyAndSaysHowManyEntryPointsAreMissing() {
        val report = XCoreNativeAuditReport(
            searchedDirectories = listOf("/data/app/xcore~lib/arm64"),
            candidates = emptyList(),
            satisfiedEntryPoints = emptyList(),
            missingEntryPoints = XCoreNativeEntryPoint.entries.toList(),
            components = XCoreNativeComponent.entries.toList(),
        )
        assertFalse(report.isBridgeReady)
        assertEquals(0, report.librariesMatchingDeviceAbi)
        assertTrue(report.summarize().contains("0 of 7"))
        assertTrue(report.summarize().contains("not usable"))
    }

    @Test
    fun aPartialAuditIsStillNotReady() {
        val report = XCoreNativeAuditReport(
            searchedDirectories = listOf("/a"),
            candidates = listOf(
                XCoreLibraryCandidate(
                    file = File("/a/libxcore_glfw.so"),
                    abi = "arm64-v8a",
                    elf = null,
                    neededLibraries = emptyList(),
                    jniSymbols = emptyList(),
                    unreadableReason = "synthetic: not parsed in this test",
                ),
            ),
            satisfiedEntryPoints = listOf(XCoreNativeEntryPoint.INITIALIZE),
            missingEntryPoints = XCoreNativeEntryPoint.entries.drop(1),
            components = emptyList(),
        )
        assertFalse(report.isBridgeReady)
        assertTrue(report.summarize().contains("1 of 7"))
    }

    @Test
    fun theReaderFindsDefinedJniExportsInASyntheticElf64Object() {
        val file = writeElf(
            machine = 183,
            symbols = listOf(
                "Java_studio_xycore_xcore_nativebridge_XCoreNativeBridgeState_nativeInitialize" to true,
                "Java_studio_xycore_xcore_nativebridge_XCoreNativeBridgeState_nativeSwapBuffers" to true,
                "Java_studio_xycore_xcore_nativebridge_XCoreNativeBridgeState_nativeMissingHere" to false,
                "memcpy" to true,
            ),
        )
        val outcome = XCoreElfSymbolReader.readJniSymbols(file)
        val symbols = (outcome as XCoreResult.Success).value
        assertEquals(3, symbols.size)
        assertEquals(2, symbols.count { it.defined })
        val initialize = symbols.first { it.rawName.endsWith("nativeInitialize") }
        assertEquals("studio.xycore.xcore.nativebridge.XCoreNativeBridgeState", initialize.className)
        assertEquals("nativeInitialize", initialize.methodName)
        assertEquals("XCoreNativeBridgeState.nativeInitialize", initialize.readable())
        assertTrue(symbols.first { it.rawName.endsWith("nativeMissingHere") }.let { !it.defined })

        val defined = (XCoreElfSymbolReader.readDefinedJniSymbols(file) as XCoreResult.Success).value
        assertEquals(2, defined.size)
        assertEquals("arm64-v8a", (XCoreElfSymbolReader.readHeader(file) as XCoreResult.Success).value.abi)
        file.delete()
    }

    @Test
    fun aDefinedSymbolThatIsNotAFunctionIsNotCountedAsAnEntryPoint() {
        val file = writeElf(
            machine = 183,
            symbols = listOf("Java_studio_xycore_xcore_nativebridge_XCoreNativeBridgeState_nativeData" to true),
            functionSymbols = false,
        )
        val symbols = (XCoreElfSymbolReader.readJniSymbols(file) as XCoreResult.Success).value
        assertEquals(1, symbols.size)
        assertFalse(symbols.first().defined)
        assertEquals(
            0,
            (XCoreElfSymbolReader.readDefinedJniSymbols(file) as XCoreResult.Success).value.size,
        )
        file.delete()
    }

    @Test
    fun a32BitObjectIsRefusedInsteadOfSurveyed() {
        val file = writeElf(machine = 40, symbols = listOf("Java_a_b_c" to true), classOf = 1)
        val outcome = XCoreElfSymbolReader.readJniSymbols(file)
        assertTrue(outcome is XCoreResult.Failure)
        assertTrue((outcome as XCoreResult.Failure).error.detail.contains("32-bit"))
        file.delete()
    }

    @Test
    fun aFileThatIsNotElfReportsATypedFailure() {
        val file = File.createTempFile("not-elf", ".so")
        file.writeBytes(ByteArray(128) { 0x41 })
        val outcome = XCoreElfSymbolReader.readJniSymbols(file)
        assertTrue(outcome is XCoreResult.Failure)
        file.delete()
    }

    @Test
    fun jniNamesDecodeUnderscoreEscapesTheWayTheSpecSays() {
        assertEquals(
            "com.example.My_Class",
            XCoreElfSymbolReader.classNameOf("Java_com_example_My_1Class_doThing"),
        )
        assertEquals("doThing", XCoreElfSymbolReader.methodNameOf("Java_com_example_My_1Class_doThing"))
        assertEquals("run", XCoreElfSymbolReader.methodNameOf("Java_com_example_C_run__Ljava_lang_String_2"))
        assertEquals("notAJavaName", XCoreElfSymbolReader.classNameOf("notAJavaName"))
    }

    @Test
    fun theShimNameTheLoaderLooksForIsTheOneTheDocsName() {
        assertEquals("libxcore_glfw.so", XCoreLibraryLoader.SHIM_LIBRARY_NAME)
    }

    /**
     * Assembles a minimal ELF64 object: header, one symbol table, one string table and the section
     * name table, then the section headers that point at them.
     */
    private fun writeElf(
        machine: Int,
        symbols: List<Pair<String, Boolean>>,
        functionSymbols: Boolean = true,
        classOf: Int = 2,
    ): File {
        val names = ByteArrayOutputStream()
        val nameOffsets = HashMap<String, Int>()
        fun addName(value: String) {
            if (value.isEmpty()) return
            if (value in nameOffsets) return
            nameOffsets[value] = names.size()
            names.write(value.toByteArray())
            names.write(0)
        }
        listOf("", ".symtab", ".strtab", ".shstrtab").forEach(::addName)
        symbols.forEach { (name, _) -> addName(name) }
        val strtab = names.toByteArray()

        val symbolBytes = ByteArrayOutputStream()
        fun quad(stream: ByteArrayOutputStream, value: Long) {
            var remaining = value
            repeat(8) {
                stream.write((remaining and 0xFF).toInt())
                remaining = remaining shr 8
            }
        }
        fun word(stream: ByteArrayOutputStream, value: Int) {
            var remaining = value
            repeat(4) {
                stream.write((remaining and 0xFF))
                remaining = remaining shr 8
            }
        }
        fun half(stream: ByteArrayOutputStream, value: Int) {
            stream.write(value and 0xFF)
            stream.write((value shr 8) and 0xFF)
        }
        // the required null symbol, then one entry per name; each entry is 24 bytes
        symbolBytes.write(ByteArray(24))
        symbols.forEach { (name, defined) ->
            word(symbolBytes, nameOffsets[name] ?: 0)
            symbolBytes.write(if (functionSymbols) 0x12 else 0x11) // global, function or object
            symbolBytes.write(0)
            half(symbolBytes, if (defined) 4 else 0)
            quad(symbolBytes, 0x1000L)
            quad(symbolBytes, 16L)
        }
        val symtab = symbolBytes.toByteArray()

        val shstr = ByteArrayOutputStream()
        val shNameOffsets = HashMap<String, Int>()
        listOf("", ".symtab", ".strtab", ".shstrtab").forEach { name ->
            shNameOffsets[name] = shstr.size()
            if (name.isNotEmpty()) {
                shstr.write(name.toByteArray())
                shstr.write(0)
            }
        }
        val shstrtab = shstr.toByteArray()

        val symtabOffset = 64L
        val strtabOffset = symtabOffset + symtab.size
        val shstrtabOffset = strtabOffset + strtab.size
        val sectionOffset = shstrtabOffset + shstrtab.size

        val out = ByteArrayOutputStream()
        fun leQuad(value: Long) = quad(out, value)
        fun leWord(value: Int) = word(out, value)
        fun leHalf(value: Int) = half(out, value)

        out.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        out.write(classOf) // EI_CLASS: 1 = 32-bit, 2 = 64-bit
        out.write(1) // EI_DATA: little endian
        out.write(1) // EI_VERSION
        out.write(0) // EI_OSABI
        repeat(8) { out.write(0) }
        leHalf(3) // e_type: ET_DYN
        leHalf(machine) // e_machine
        leWord(1) // e_version
        leQuad(0L) // e_entry
        leQuad(0L) // e_phoff
        leQuad(sectionOffset) // e_shoff
        leWord(0) // e_flags
        leHalf(64) // e_ehsize
        leHalf(0) // e_phentsize
        leHalf(0) // e_phnum
        leHalf(64) // e_shentsize
        leHalf(4) // e_shnum
        leHalf(3) // e_shstrndx

        out.write(symtab)
        out.write(strtab)
        out.write(shstrtab)

        fun sectionHeader(
            name: String,
            type: Int,
            offset: Long,
            size: Long,
            link: Int,
            info: Int,
            entrySize: Long,
        ) {
            leWord(shNameOffsets[name] ?: 0)
            leWord(type)
            leQuad(0L) // sh_flags
            leQuad(0L) // sh_addr
            leQuad(offset)
            leQuad(size)
            leWord(link)
            leWord(info)
            leQuad(8L) // sh_addralign
            leQuad(entrySize)
        }

        sectionHeader("", 0, 0L, 0L, 0, 0, 0L)
        sectionHeader(".symtab", 2, symtabOffset, symtab.size.toLong(), 2, 1, 24L)
        sectionHeader(".strtab", 3, strtabOffset, strtab.size.toLong(), 0, 0, 0L)
        sectionHeader(".shstrtab", 3, shstrtabOffset, shstrtab.size.toLong(), 0, 0, 0L)

        val file = File.createTempFile("synthetic-lib", ".so")
        file.writeBytes(out.toByteArray())
        return file
    }
}
