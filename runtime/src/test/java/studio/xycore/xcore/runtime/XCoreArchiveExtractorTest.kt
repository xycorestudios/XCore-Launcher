package studio.xycore.xcore.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreRuntimeFailureReason
import studio.xycore.xcore.core.errorOrNull
import studio.xycore.xcore.core.valueOrNull

/**
 * Unpacking the containers the runtime catalogue names.
 *
 * The archive is the only part of the install pipeline that takes instructions from a file somebody
 * else wrote, so these tests are mostly about instructions that must not be obeyed: a member that
 * points outside the destination, a link that would send a write through to `/etc`, and a container
 * that expands forever.
 */
class XCoreArchiveExtractorTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val containers: File by lazy { File(temp.root, "containers").also { it.mkdirs() } }

    private fun extractor() = XCoreArchiveExtractor()

    private fun destination(name: String = "unpacked"): File = temp.newFolder(name)

    private fun write(name: String, bytes: ByteArray): File =
        File(containers, name).also { file -> file.writeBytes(bytes) }

    private fun XCoreResult<XCoreExtractionReport>.orExtracted(context: String): XCoreExtractionReport =
        valueOrNull() ?: throw AssertionError(
            "$context should unpack: " + errorOrNull()?.let { failure -> "${failure.kind}: ${failure.detail}" }.orEmpty(),
        )

    private fun jdkEntries() = listOf(
        TarEntry("opt/java/bin/", type = TarEntry.TYPE_DIRECTORY),
        TarEntry("opt/java/bin/java", "shell stub".toByteArray(), TarEntry.EXECUTABLE_MODE),
        TarEntry("opt/java/release", "JAVA_VERSION=\"17.0.2\"\n".toByteArray()),
    )

    @Test
    fun unpacksFilesDirectoriesAndTheExecutableBitFromATarStream() {
        val container = write("tree.tar", buildTar(jdkEntries()))
        val target = destination()

        val report = extractor().extract(container, target, XCoreArchiveFormat.TAR).orExtracted("a plain tar")
        assertEquals(XCoreArchiveFormat.TAR, report.format)
        assertEquals(2, report.filesWritten)
        assertEquals(1, report.directoriesCreated)
        assertEquals("shell stub".toByteArray().size + "JAVA_VERSION=\"17.0.2\"\n".toByteArray().size, report.bytesWritten.toInt())
        assertEquals("JAVA_VERSION=\"17.0.2\"\n", File(target, "opt/java/release").readText())
        assertTrue("the ustar mode 0755 must survive onto the file", File(target, "opt/java/bin/java").canExecute())
        assertTrue(report.summarize().startsWith("2 files"))
    }

    @Test
    fun readsAGnuLongNameAndAppliesItToTheEntryThatFollows() {
        val deep = "opt/java/lib/modules/" + "x".repeat(120) + "/belt.txt"
        val container = write(
            "long.tar",
            buildTar(
                listOf(
                    TarEntry(
                        name = "././@LongLink",
                        bytes = (deep + "\n").toByteArray(),
                        type = TarEntry.TYPE_GNU_LONG_NAME,
                    ),
                    TarEntry(deep.take(99), "carried by the long name".toByteArray()),
                ),
            ),
        )
        val target = destination()

        val report = extractor().extract(container, target, XCoreArchiveFormat.TAR).orExtracted("a gnu long name archive")
        assertEquals(1, report.filesWritten)
        assertEquals(
            "carried by the long name",
            File(target, deep.replace('/', File.separatorChar)).readText(),
        )
    }

    @Test
    fun recordsALinkButNeverCreatesOneOrWritesThroughIt() {
        val outside = File(temp.root, "passwd-victim")
        outside.writeText("original")
        val container = write(
            "links.tar",
            buildTar(
                listOf(
                    TarEntry("opt/java/bin/java", type = TarEntry.TYPE_LINK, linkName = outside.absolutePath),
                    TarEntry("opt/java/release", "JAVA_VERSION=\"21.0.1\"\n".toByteArray()),
                ),
            ),
        )
        val target = destination()

        val report = extractor().extract(container, target, XCoreArchiveFormat.TAR).orExtracted("an archive holding a link")
        assertEquals(listOf("opt/java/bin/java -> " + outside.absolutePath), report.symlinksSkipped)
        assertEquals(1, report.filesWritten)
        assertFalse("a tar member must not be able to place a file where it points", File(target, "opt/java/bin/java").exists())
        assertEquals("original", outside.readText())
    }

    @Test
    fun refusesMembersThatPointOutsideTheDestination() {
        val container = write(
            "hostile.tar",
            buildTar(
                listOf(
                    TarEntry("../../escaped.txt", "outside".toByteArray()),
                    TarEntry("/absolute/escaped.txt", "outside".toByteArray()),
                    TarEntry("windows\\..\\escaped.txt", "outside".toByteArray()),
                    TarEntry("opt/java/release", "JAVA_VERSION=\"17.0.2\"\n".toByteArray()),
                ),
            ),
        )
        val target = destination()

        val report = extractor().extract(container, target, XCoreArchiveFormat.TAR).orExtracted("an archive with hostile members")
        assertEquals(
            listOf("../../escaped.txt", "/absolute/escaped.txt", "windows\\..\\escaped.txt"),
            report.rejectedPaths,
        )
        assertEquals("the one safe member must still be written", 1, report.filesWritten)
        val twoLevelsUp = target.absoluteFile.parentFile.parentFile
        assertFalse("nothing may be written above the destination", File(twoLevelsUp, "escaped.txt").exists())
        assertEquals(1, target.walkTopDown().filter { file -> file.isFile }.count())
    }

    @Test
    fun failsRatherThanSucceedingWhenTheArchiveHoldsNoFiles() {
        val empty = write("empty.tar", buildTar(emptyList()))
        val directoriesOnly = write(
            "dirs.tar",
            buildTar(listOf(TarEntry("opt/java/bin/", type = TarEntry.TYPE_DIRECTORY))),
        )
        val notAnArchive = write("junk.tar", ByteArray(600) { index -> (index % 7 + 1).toByte() })

        listOf(
            extractor().extract(empty, destination("empty-dest"), XCoreArchiveFormat.TAR),
            extractor().extract(directoriesOnly, destination("dirs-dest"), XCoreArchiveFormat.TAR),
            extractor().extract(notAnArchive, destination("junk-dest"), XCoreArchiveFormat.TAR),
        ).forEach { outcome ->
            val error = outcome.errorOrNull()
            assertNotNull("an archive with nothing to write must not report success", error)
            assertTrue(
                "the failure must name the archive as corrupt, saw ${error?.detail}",
                (error as? XCoreError.Runtime)?.reason == XCoreRuntimeFailureReason.CORRUPT_ARCHIVE,
            )
        }
        assertTrue(
            extractor().extract(empty, destination("again"), XCoreArchiveFormat.TAR).errorOrNull()!!
                .detail.contains("no files at all"),
        )
    }

    @Test
    fun detectsEveryContainerFromItsOwnMagic() {
        val tar = write("detect.tar", buildTar(jdkEntries()))
        val gz = write("detect.tar.gz", buildGzTar(jdkEntries()))
        val xz = write("detect.tar.xz", buildXzTar(jdkEntries()))
        val deb = write("detect.deb", buildAr(listOf("data.tar.xz" to buildXzTar(jdkEntries()))))
        val zip = write("detect.zip", buildZip(listOf("opt/java/release" to "JAVA_VERSION=\"17.0.2\"\n")))
        write("detect.bin", ByteArray(64) { index -> index.toByte() })

        assertEquals(XCoreArchiveFormat.TAR, extractor().detectFormat(tar))
        assertEquals(XCoreArchiveFormat.TAR_GZ, extractor().detectFormat(gz))
        assertEquals(XCoreArchiveFormat.TAR_XZ, extractor().detectFormat(xz))
        assertEquals(XCoreArchiveFormat.DEB_AR, extractor().detectFormat(deb))
        assertEquals(XCoreArchiveFormat.ZIP, extractor().detectFormat(zip))
        assertEquals(
            "a missing container is unknown, not tar",
            XCoreArchiveFormat.UNKNOWN,
            extractor().detectFormat(File(temp.root, "absent.deb")),
        )
    }

    @Test
    fun unpacksGzipAndXzPayloadsTheWayItUnpacksAPlainTar() {
        val compressed = listOf(
            XCoreArchiveFormat.TAR_GZ to write("payload.tar.gz", buildGzTar(jdkEntries())),
            XCoreArchiveFormat.TAR_XZ to write("payload.tar.xz", buildXzTar(jdkEntries())),
        )
        compressed.forEach { (format, container) ->
            val target = destination(format.name.lowercase())
            val report = extractor().extract(container, target, format).orExtracted(format.name)
            assertEquals(2, report.filesWritten)
            assertEquals(format.name, "JAVA_VERSION=\"17.0.2\"\n", File(target, "opt/java/release").readText())
        }
    }

    @Test
    fun followsTheDebArMemberToTheTarPayloadItHides() {
        val container = write(
            "openjdk.deb",
            buildAr(
                listOf(
                    "debian-binary" to "2.0\n".toByteArray(),
                    "control.tar.xz" to buildXzTar(listOf(TarEntry("./md5sums", "abc\n".toByteArray()))),
                    "data.tar.xz" to buildXzTar(jdkEntries()),
                ),
            ),
        )
        val target = destination("deb-tree")

        val report = extractor()
            .extract(container, target, XCoreArchiveFormat.DEB_AR, member = "data.tar.xz")
            .orExtracted("the deb's data member")
        assertEquals(XCoreArchiveFormat.DEB_AR, report.format)
        assertEquals("only the data member may be unpacked", 2, report.filesWritten)
        assertEquals("JAVA_VERSION=\"17.0.2\"\n", File(target, "opt/java/release").readText())
        assertFalse("the control member must not land in the runtime tree", File(target, "md5sums").exists())
        assertFalse(
            "the temporary payload must be cleaned up",
            File(target.absoluteFile.parentFile, target.name + XCoreArchiveExtractor.TMP_SUFFIX).exists(),
        )
    }

    @Test
    fun refusesAnArContainerWhoseMemberIsMissingOrUnnamed() {
        val container = write("no-data.deb", buildAr(listOf("debian-binary" to "2.0\n".toByteArray())))
        val unnamed = write("unnamed.deb", buildAr(listOf("data.tar.xz" to buildXzTar(jdkEntries()))))

        val noMember = extractor().extract(container, destination("no-member"), XCoreArchiveFormat.DEB_AR, member = "data.tar.xz")
        assertTrue(noMember.errorOrNull()!!.detail.contains("has no 'data.tar.xz' member"))

        val noName = extractor().extract(unnamed, destination("no-name"), XCoreArchiveFormat.DEB_AR, member = null)
        assertTrue(noName.errorOrNull()!!.detail.contains("needs the name of the member"))
    }

    @Test
    fun unpacksAZipAndRefusesItsTraversalEntry() {
        val container = write(
            "runtime.zip",
            buildZip(
                listOf(
                    "release" to "JAVA_VERSION=\"17.0.2\"\n",
                    "../escaped.txt" to "outside",
                    "/absolute.txt" to "outside",
                ),
            ),
        )
        val target = destination("zip-tree")

        val report = extractor().extract(container, target, XCoreArchiveFormat.ZIP).orExtracted("a zip archive")
        assertEquals(1, report.filesWritten)
        assertEquals(listOf("../escaped.txt", "/absolute.txt"), report.rejectedPaths)
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    private fun buildZip(texts: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            texts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildXzTar(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { xz -> xz.write(buildTar(entries)) }
        return out.toByteArray()
    }
}
