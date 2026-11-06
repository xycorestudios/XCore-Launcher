package studio.xycore.xcore.runtime

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Builders for the two binary formats the runtime module reads: an ELF object and a tar archive.
 *
 * These are written by hand rather than taken from a real JDK because the point of the tests is the
 * parser, not the publisher - and a 60 MB `bin/java` in version control would only let the tests
 * check that a known-good file parses, not that a hostile or unusual one is handled.
 */

/** One member of a tar archive; [type] is the typeflag byte the extractor switches on. */
internal data class TarEntry(
    val name: String,
    val bytes: ByteArray = ByteArray(0),
    val mode: Int = DEFAULT_MODE,
    val type: Char = TYPE_FILE,
    val linkName: String = "",
) {
    companion object {
        const val TYPE_FILE = '0'
        const val TYPE_LINK = '2'
        const val TYPE_DIRECTORY = '5'
        const val TYPE_GNU_LONG_NAME = 'L'
        const val TYPE_PAX_GLOBAL = 'g'
        const val DEFAULT_MODE = 0b110_100_100
        const val EXECUTABLE_MODE = 0b111_101_101
    }
}

internal fun buildTar(entries: List<TarEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    entries.forEach { entry ->
        out.write(tarHeader(entry))
        out.write(entry.bytes)
        val padding = (512 - entry.bytes.size % 512) % 512
        repeat(padding) { out.write(0) }
    }
    // Two zero blocks terminate a tar stream.
    repeat(1024) { out.write(0) }
    return out.toByteArray()
}

internal fun buildGzTar(entries: List<TarEntry>): ByteArray {
    val raw = buildTar(entries)
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { gzip -> gzip.write(raw) }
    return out.toByteArray()
}

private const val NAME_FIELD = 0
private const val MODE_FIELD = 100
private const val SIZE_FIELD = 124
private const val CHECKSUM_FIELD = 148
private const val TYPE_FLAG_FIELD = 156
private const val LINK_NAME_FIELD = 157
private const val MAGIC_FIELD = 257
private const val PREFIX_FIELD = 345

private fun tarHeader(entry: TarEntry): ByteArray {
    val header = ByteArray(512)
    // The checksum field must read as spaces while the sum is being computed.
    for (index in CHECKSUM_FIELD until CHECKSUM_FIELD + 8) header[index] = ' '.code.toByte()
    writeAscii(header, NAME_FIELD, 100, entry.name.take(99))
    writeOctal(header, MODE_FIELD, 8, entry.mode.toLong())
    writeOctal(header, SIZE_FIELD, 12, entry.bytes.size.toLong())
    header[TYPE_FLAG_FIELD] = entry.type.code.toByte()
    writeAscii(header, LINK_NAME_FIELD, 100, entry.linkName.take(99))
    writeAscii(header, MAGIC_FIELD, 6, "ustar")
    val checksum = header.sumOf { byte -> byte.toInt() and 0xff }
    writeOctal(header, CHECKSUM_FIELD, 8, checksum.toLong())
    return header
}

private fun writeAscii(
    target: ByteArray,
    offset: Int,
    length: Int,
    value: String,
) {
    val bytes = value.toByteArray(Charsets.ISO_8859_1)
    System.arraycopy(bytes, 0, target, offset, minOf(bytes.size, length))
}

/** tar's octal ASCII field: [length] - 1 digits plus a terminator. */
private fun writeOctal(
    target: ByteArray,
    offset: Int,
    length: Int,
    value: Long,
) {
    if (length <= 0) return
    val digits = value.toString(8).padStart(length - 1, '0').take(length - 1)
    writeAscii(target, offset, length - 1, digits)
    target[offset + length - 1] = 0
}

private const val ELF_HEADER_BYTES = 64
private const val ELF64_PROGRAM_HEADER_BYTES = 56
private const val PT_LOAD = 1
private const val PT_DYNAMIC = 2
private const val PT_INTERP = 3
private const val DT_NULL = 0L
private const val DT_NEEDED = 1L
private const val DT_STRTAB = 5L
private const val DT_STRSZ = 10L

/**
 * A minimal but honestly structured ELF64 object: header, three program headers, an interpreter
 * string, a dynamic section naming its dependencies, and a load-mapped string table.
 *
 * Everything the detector reads is here; everything it does not read is absent, which is why the
 * file is a few hundred bytes rather than a real binary.
 */
internal fun buildElf64(
    machine: Int,
    interpreter: String,
    needed: List<String>,
): ByteArray {
    val interpreterBytes = interpreter.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
    val stringTable = buildStringTable(needed)
    val programHeaderOffset = ELF_HEADER_BYTES
    val interpreterOffset = programHeaderOffset + ELF64_PROGRAM_HEADER_BYTES * 3
    val dynamicOffset = interpreterOffset + interpreterBytes.size
    val dynamicEntries = 2 + needed.size + 1
    val dynamicSize = dynamicEntries * 16L
    val stringTableOffset = dynamicOffset + dynamicSize.toInt()
    val stringTableAddress = 0x1000L

    val total = stringTableOffset + stringTable.size
    val file = ByteArray(total)
    file[0] = 0x7f
    file[1] = 'E'.code.toByte()
    file[2] = 'L'.code.toByte()
    file[3] = 'F'.code.toByte()
    file[4] = 2 // ELFCLASS64
    file[5] = 1 // ELFDATA2LSB
    file[6] = 1 // current version
    putLe16(file, 18, machine)
    putLe64(file, 32, programHeaderOffset.toLong())
    putLe16(file, 54, ELF64_PROGRAM_HEADER_BYTES)
    putLe16(file, 56, 3)

    writeProgramHeader(file, programHeaderOffset, PT_INTERP, interpreterOffset, 0L, interpreterBytes.size.toLong())
    writeProgramHeader(file, programHeaderOffset + ELF64_PROGRAM_HEADER_BYTES, PT_LOAD, stringTableOffset, stringTableAddress, stringTable.size.toLong())
    writeProgramHeader(
        file,
        programHeaderOffset + ELF64_PROGRAM_HEADER_BYTES * 2,
        PT_DYNAMIC,
        dynamicOffset,
        0L,
        dynamicSize,
    )

    System.arraycopy(interpreterBytes, 0, file, interpreterOffset, interpreterBytes.size)

    var cursor = dynamicOffset
    putLe64(file, cursor, DT_STRTAB); putLe64(file, cursor + 8, stringTableAddress); cursor += 16
    putLe64(file, cursor, DT_STRSZ); putLe64(file, cursor + 8, stringTable.size.toLong()); cursor += 16
    var offset = 1
    needed.forEach { name ->
        putLe64(file, cursor, DT_NEEDED); putLe64(file, cursor + 8, offset.toLong()); cursor += 16
        offset += name.length + 1
    }
    putLe64(file, cursor, DT_NULL); putLe64(file, cursor + 8, 0L)

    System.arraycopy(stringTable, 0, file, stringTableOffset, stringTable.size)
    return file
}

/** The `.dynstr` layout: a leading NUL, then each name NUL-terminated. */
private fun buildStringTable(names: List<String>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0)
    names.forEach { name ->
        out.write(name.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
    }
    return out.toByteArray()
}

private fun writeProgramHeader(
    file: ByteArray,
    offset: Int,
    type: Int,
    fileOffset: Int,
    virtualAddress: Long,
    size: Long,
) {
    putLe32(file, offset, type)
    putLe32(file, offset + 4, 6)
    putLe64(file, offset + 8, fileOffset.toLong())
    putLe64(file, offset + 16, virtualAddress)
    putLe64(file, offset + 24, virtualAddress)
    putLe64(file, offset + 32, size)
    putLe64(file, offset + 40, size)
    putLe64(file, offset + 48, 0x1000L)
}

private fun putLe16(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    target[offset] = (value and 0xff).toByte()
    target[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private fun putLe32(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    putLe16(target, offset, value and 0xffff)
    putLe16(target, offset + 2, (value shr 16) and 0xffff)
}

private fun putLe64(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    putLe32(target, offset, (value and 0xffffffffL).toInt())
    putLe32(target, offset + 4, ((value shr 32) and 0xffffffffL).toInt())
}

/** An ar (`*.deb`) container holding exactly the named members, in the order Debian writes them. */
internal fun buildAr(members: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("!<arch>\n".toByteArray(Charsets.ISO_8859_1))
    members.forEach { (name, bytes) ->
        // 16 name + 12 mtime + 6 uid + 6 gid + 8 mode + 10 size + 2 magic = the 60-byte header.
        val header = name.padEnd(16) +
            "0".padEnd(12) +
            "0".padEnd(6) +
            "0".padEnd(6) +
            "100644".padEnd(8) +
            bytes.size.toString().padEnd(10) +
            "`\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        if (bytes.size % 2 != 0) out.write(0)
    }
    return out.toByteArray()
}
