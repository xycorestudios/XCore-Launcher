package studio.xycore.xcore.installer

import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.flatMap
import studio.xycore.xcore.core.failXCore
import studio.xycore.xcore.core.getOrThrow
import studio.xycore.xcore.core.map
import studio.xycore.xcore.core.xCoreRunCatching
import studio.xycore.xcore.data.XCoreSha1

/** A file that was read end to end and compared against what the metadata claimed. */
data class XCoreVerifiedFile(
    val file: File,
    val sha1: XCoreSha1?,
    val sizeBytes: Long,
)

/**
 * Streaming SHA-1 checks for downloaded content.
 *
 * Every published Mojang file carries a SHA-1, and this is the only place a digest is compared. The
 * digest proves the bytes match what the metadata said and nothing more: an attacker who can swap
 * both the file and the metadata can also satisfy this check, which is why the host allowlist in
 * `:versions` is what actually bounds where content comes from.
 */
class XCoreHashVerifier(private val chunkBytes: Int = 64 * 1024) {

    /** SHA-1 of a file, or a typed failure if it cannot be read. */
    fun sha1Of(file: File): XCoreResult<XCoreSha1> = xCoreRunCatching(
        origin = "hash.sha1(${file.name})",
    ) {
        if (!file.isFile) {
            failXCore(
                XCoreError.Storage(
                    detail = "no file to hash at ${file.absolutePath}",
                    path = file.absolutePath,
                    retryable = true,
                ),
            )
        }
        val digest = newSha1()
        file.inputStream().buffered(chunkBytes).use { stream ->
            val chunk = ByteArray(chunkBytes)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                digest.update(chunk, 0, read)
            }
        }
        XCoreSha1.parse(toHex(digest.digest())).getOrThrow()
    }

    /** False, not an error, when the file simply does not match. */
    fun matches(
        file: File,
        expected: XCoreSha1,
    ): XCoreResult<Boolean> = sha1Of(file).map { actual -> actual == expected }

    /**
     * SHA-1 of a document already in memory, used to confirm an asset index against the digest the
     * version profile published for it.
     */
    fun sha1OfText(text: String): XCoreResult<XCoreSha1> = xCoreRunCatching(origin = "hash.text") {
        val digest = newSha1().digest(text.toByteArray(Charsets.UTF_8))
        XCoreSha1.parse(toHex(digest)).getOrThrow()
    }

    /**
     * Checks length first, then digest.
     *
     * The cheap test runs first because a truncated transfer is by far the most common failure and
     * hashing a 40 MB file to discover it is 12 MB short wastes the user's battery.
     */
    fun verify(
        file: File,
        expectedSha1: XCoreSha1?,
        expectedBytes: Long = -1L,
    ): XCoreResult<XCoreVerifiedFile> {
        if (!file.isFile) {
            return XCoreError.Storage(
                detail = "expected a file at ${file.absolutePath} but none is present",
                path = file.absolutePath,
                retryable = true,
            ).asXCoreFailure()
        }
        val size = file.length()
        if (expectedBytes in 1L until size) {
            return XCoreError.Storage(
                detail = "${file.absolutePath} is $size bytes but the metadata declares $expectedBytes",
                path = file.absolutePath,
                retryable = true,
            ).asXCoreFailure()
        }
        if (expectedSha1 == null) {
            return XCoreVerifiedFile(file, sha1 = null, sizeBytes = size).asXCoreSuccess()
        }
        return sha1Of(file).flatMap { actual ->
            if (actual == expectedSha1) {
                XCoreVerifiedFile(file, actual, size).asXCoreSuccess()
            } else {
                XCoreError.Storage(
                    detail = "${file.name} does not match its published digest: expected " +
                        "${expectedSha1.value} but the file hashes to ${actual.value}",
                    path = file.absolutePath,
                    retryable = true,
                ).asXCoreFailure()
            }
        }
    }

    /** Hex of a SHA-1 digest, lowercase, the same shape [XCoreSha1] accepts. */
    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value < 0x10) out.append('0')
            out.append(value.toString(16))
        }
        return out.toString()
    }

    private fun newSha1(): MessageDigest = try {
        MessageDigest.getInstance("SHA-1")
    } catch (absent: NoSuchAlgorithmException) {
        failXCore(
            XCoreError.Unexpected(
                detail = "this runtime offers no SHA-1 message digest, which every published " +
                    "Minecraft digest depends on",
                cause = absent,
            ),
        )
    }
}
