package studio.xycore.xcore.core

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class XCoreLogLevel(val priority: Int, val label: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E"),
    NONE(7, "-"),
    ;

    /** Whether a record at [candidate] should be emitted for a logger filtering at this level. */
    fun accepts(candidate: XCoreLogLevel): Boolean = candidate.priority >= priority
}

/** Stable tag names so log lines can be filtered per subsystem. */
object XCoreLogTags {
    const val ENGINE = "XCore/Engine"
    const val MODULES = "XCore/Modules"
    const val TASKS = "XCore/Tasks"
    const val REGISTRY = "XCore/Registry"
    const val CONFIG = "XCore/Config"
    const val PATHS = "XCore/Paths"
    const val DATABASE = "XCore/Database"
    const val PREFERENCES = "XCore/Preferences"
    const val REPOSITORY = "XCore/Repository"
    const val USE_CASE = "XCore/UseCase"
    const val EVENTS = "XCore/Events"
    const val UI = "XCore/UI"
}

data class XCoreLogRecord(
    val timestampMillis: Long,
    val level: XCoreLogLevel,
    val tag: String,
    val message: String,
    val error: Throwable? = null,
) {
    fun formatLine(): String = buildString {
        append(timestampMillis)
        append(' ')
        append(level.label)
        append('/')
        append(tag)
        append(": ")
        append(message)
        error?.let { append(" [").append(it.javaClass.name).append(": ").append(it.message).append(']') }
    }
}

/**
 * Platform adapter boundary. Android's `Log` is reached from an Android module, which keeps the
 * core layer testable on the JVM without an `android.jar` stub hanging every call.
 */
fun interface XCoreLogSink {
    fun write(record: XCoreLogRecord)
}

interface XCoreLogger {

    val minimumLevel: XCoreLogLevel

    fun log(
        level: XCoreLogLevel,
        tag: String,
        message: String,
        error: Throwable? = null,
    )

    /** Returns a logger that stamps records with [tag], sharing this logger's storage and sinks. */
    fun withTag(tag: String): XCoreLogger
}

fun XCoreLogger.verbose(tag: String, message: String) = log(XCoreLogLevel.VERBOSE, tag, message)

fun XCoreLogger.debug(tag: String, message: String) = log(XCoreLogLevel.DEBUG, tag, message)

fun XCoreLogger.info(tag: String, message: String) = log(XCoreLogLevel.INFO, tag, message)

fun XCoreLogger.warn(tag: String, message: String, error: Throwable? = null) =
    log(XCoreLogLevel.WARN, tag, message, error)

fun XCoreLogger.error(tag: String, message: String, error: Throwable? = null) =
    log(XCoreLogLevel.ERROR, tag, message, error)

fun XCoreLogger.error(error: XCoreError, detail: String? = null) =
    log(XCoreLogLevel.ERROR, error.kind.label, detail ?: error.detail, error.cause)

/**
 * Logger that retains a bounded in-process history and fans records out to platform sinks.
 *
 * The retained history is what the diagnostics screen and bug-report export read; it is a real
 * buffer, oldest entries are dropped once [capacity] is reached.
 */
class XCoreMemoryLogger(
    override val minimumLevel: XCoreLogLevel = XCoreLogLevel.INFO,
    val capacity: Int = 2_000,
    private val sinks: List<XCoreLogSink> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : XCoreLogger {

    private val lock = Any()
    private val buffer = ArrayDeque<XCoreLogRecord>(capacity.coerceAtLeast(16))
    private val emitted = MutableSharedFlow<XCoreLogRecord>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var droppedRecords: Long = 0L

    val recordsFlow: SharedFlow<XCoreLogRecord> = emitted.asSharedFlow()

    val droppedCount: Long
        get() = synchronized(lock) { droppedRecords }

    fun snapshot(): List<XCoreLogRecord> = synchronized(lock) { buffer.toList() }

    fun exportText(limit: Int = 500): String = synchronized(lock) {
        buffer.toList()
            .takeLast(limit.coerceAtLeast(1))
            .joinToString(separator = "\n") { it.formatLine() }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            droppedRecords = 0L
        }
    }

    override fun log(
        level: XCoreLogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        if (!minimumLevel.accepts(level)) return
        val record = XCoreLogRecord(
            timestampMillis = clock(),
            level = level,
            tag = tag,
            message = message,
            error = error,
        )
        synchronized(lock) {
            if (buffer.size >= capacity) {
                buffer.pollFirst()
                droppedRecords += 1
            }
            buffer.addLast(record)
        }
        emitted.tryEmit(record)
        for (sink in sinks) {
            runCatching { sink.write(record) }
        }
    }

    override fun withTag(tag: String): XCoreLogger = XCoreTaggedLogger(this, tag)

    companion object {
        /** Convenience for a logger that keeps history and forwards to one sink. */
        fun of(
            sink: XCoreLogSink,
            minimumLevel: XCoreLogLevel = XCoreLogLevel.INFO,
            capacity: Int = 2_000,
        ): XCoreMemoryLogger = XCoreMemoryLogger(
            minimumLevel = minimumLevel,
            capacity = capacity,
            sinks = listOf(sink),
        )
    }
}

internal class XCoreTaggedLogger(
    private val delegate: XCoreLogger,
    private val tag: String,
) : XCoreLogger {

    override val minimumLevel: XCoreLogLevel get() = delegate.minimumLevel

    override fun log(
        level: XCoreLogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) = delegate.log(level, "${this.tag}/$tag", message, error)

    override fun withTag(tag: String): XCoreLogger = XCoreTaggedLogger(delegate, "${this.tag}/$tag")
}

/** Sends every record to each member logger, honouring each member's own level filter. */
class XCoreFanOutLogger(
    private val members: List<XCoreLogger>,
    override val minimumLevel: XCoreLogLevel = XCoreLogLevel.VERBOSE,
) : XCoreLogger {

    override fun log(
        level: XCoreLogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        for (member in members) {
            member.log(level, tag, message, error)
        }
    }

    override fun withTag(tag: String): XCoreLogger =
        XCoreFanOutLogger(members.map { it.withTag(tag) }, minimumLevel)
}
