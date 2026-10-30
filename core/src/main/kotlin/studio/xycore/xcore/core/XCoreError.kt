package studio.xycore.xcore.core

import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Stable, machine-readable classification of failures. The numeric code is part of the
 * diagnostics contract: it appears in exported logs and must not be renumbered.
 */
enum class XCoreErrorKind(val numericCode: Int, val label: String) {
    CONFIGURATION(100, "configuration"),
    STORAGE(110, "storage"),
    DATABASE(120, "database"),
    PREFERENCES(130, "preferences"),
    NETWORK(140, "network"),
    AUTHENTICATION(150, "authentication"),
    RUNTIME(160, "java-runtime"),
    LAUNCH(170, "launch"),
    NATIVE(180, "native-bridge"),
    LOADER(190, "loader"),
    PERMISSION(200, "permission"),
    VALIDATION(210, "validation"),
    SERVICE(220, "service-registry"),
    CANCELLED(230, "cancelled"),
    NOT_IMPLEMENTED(240, "not-implemented"),
    UNKNOWN(250, "unknown"),
    ;

    val tag: String get() = "XCORE-$numericCode"
}

/** Where in the Microsoft/Mojang pipeline a credential failure happened. */
enum class XCoreAuthStage {
    DISCOVERY,
    USER_CODE,
    TOKEN_EXCHANGE,
    XBOX_LIVE,
    XSTS,
    MINECRAFT_SERVICES,
    PROFILE,
    ENTITLEMENT,
    REFRESH,
    TOKEN_STORAGE,
}

/** Why no usable Java runtime exists for a version. */
enum class XCoreRuntimeFailureReason {
    MISSING,
    VERSION_TOO_LOW,
    INCOMPATIBLE_ABI,
    CHECKSUM_MISMATCH,
    CORRUPT_ARCHIVE,
    UNVERIFIED,
}

/**
 * Exhaustively matchable launcher failure. Nothing here carries a "pretend it worked"
 * value: an operation that cannot complete returns one of these instead of a default.
 */
sealed class XCoreError {

    abstract val kind: XCoreErrorKind
    abstract val detail: String
    open val cause: Throwable? = null

    /** Whether retrying the exact same operation has a realistic chance of succeeding. */
    open val retryable: Boolean = false

    fun format(): String = buildString {
        append(kind.tag)
        append(" (")
        append(kind.label)
        append("): ")
        append(detail)
        cause?.let { append(" <- ").append(it.javaClass.simpleName).append(": ").append(it.message) }
    }

    override fun toString(): String = format()

    data class Configuration(
        override val detail: String,
        override val cause: Throwable? = null,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.CONFIGURATION
    }

    data class Storage(
        override val detail: String,
        val path: String? = null,
        val usableBytes: Long? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.STORAGE
    }

    data class Database(
        override val detail: String,
        val sqlState: Int? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.DATABASE
    }

    data class Preferences(
        override val detail: String,
        val key: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.PREFERENCES
    }

    data class Network(
        override val detail: String,
        val url: String? = null,
        val httpStatus: Int? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = true,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.NETWORK
    }

    data class Authentication(
        override val detail: String,
        val stage: XCoreAuthStage,
        val httpStatus: Int? = null,
        val requiresInteractiveLogin: Boolean = false,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.AUTHENTICATION
    }

    data class Runtime(
        override val detail: String,
        val reason: XCoreRuntimeFailureReason,
        val requiredJavaVersion: Int? = null,
        val channel: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.RUNTIME
    }

    data class Launch(
        override val detail: String,
        val exitCode: Int? = null,
        val logTail: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.LAUNCH
    }

    data class Native(
        override val detail: String,
        val library: String? = null,
        val symbol: String? = null,
        val abi: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.NATIVE
    }

    data class Loader(
        override val detail: String,
        val loader: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.LOADER
    }

    data class Permission(
        override val detail: String,
        val permission: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.PERMISSION
    }

    data class Validation(
        override val detail: String,
        val field: String,
        val rejected: String? = null,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.VALIDATION
    }

    data class Service(
        override val detail: String,
        val serviceId: String,
        val duplicate: Boolean = false,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.SERVICE
    }

    data class Cancelled(
        override val detail: String,
        val operation: String,
        val byUser: Boolean = true,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.CANCELLED
        override val retryable: Boolean get() = true
    }

    /**
     * A capability that is genuinely not built yet. Returning this is always preferred over
     * inventing a plausible-looking success: callers can branch on it and the UI can label it.
     */
    data class NotImplemented(
        override val detail: String,
        val capability: String,
        val plannedPhase: String? = null,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.NOT_IMPLEMENTED
    }

    data class Unexpected(
        override val detail: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false,
    ) : XCoreError() {
        override val kind get() = XCoreErrorKind.UNKNOWN
    }

    companion object {

        /**
         * Maps an arbitrary throwable onto a typed error. The default [kind] keeps the call site's
         * intent: pass the subsystem you are in so an unclassifiable exception does not silently
         * become a generic UNKNOWN storage failure.
         */
        fun fromThrowable(
            error: Throwable,
            kind: XCoreErrorKind = XCoreErrorKind.UNKNOWN,
            detail: String? = null,
        ): XCoreError {
            val message = detail ?: error.message ?: error.javaClass.simpleName
            return when (error) {
                is FileNotFoundException -> Storage(message, cause = error)
                is SocketTimeoutException -> Network(message, cause = error)
                is UnknownHostException -> Network(message, cause = error)
                is SSLException -> Network(message, cause = error, retryable = false)
                is SecurityException -> Permission(message, cause = error)
                is java.io.IOException -> when (kind) {
                    XCoreErrorKind.NETWORK -> Network(message, cause = error)
                    XCoreErrorKind.DATABASE -> Database(message, cause = error)
                    else -> Storage(message, cause = error)
                }
                else -> Unexpected(message, cause = error)
            }
        }
    }
}

/** Convenience for `catch` blocks that already know which subsystem failed. */
fun Throwable.asXCoreError(
    kind: XCoreErrorKind = XCoreErrorKind.UNKNOWN,
    detail: String? = null,
): XCoreError = XCoreError.fromThrowable(this, kind, detail)
