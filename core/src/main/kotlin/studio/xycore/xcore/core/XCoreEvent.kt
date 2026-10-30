package studio.xycore.xcore.core

import java.util.ArrayDeque
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

/**
 * Launcher-level state changes worth surfacing to the UI or to a bug report. Events describe what
 * already happened; they are never a substitute for a result from the operation that caused them.
 */
sealed interface XCoreEvent {

    val timestampMillis: Long

    data class EngineStateChanged(
        override val timestampMillis: Long,
        val previous: XCoreEngineState,
        val current: XCoreEngineState,
        val note: String? = null,
    ) : XCoreEvent

    data class ModuleRegistered(
        override val timestampMillis: Long,
        val moduleId: String,
        val requires: Set<String>,
    ) : XCoreEvent

    data class ModuleStateChanged(
        override val timestampMillis: Long,
        val moduleId: String,
        val previous: XCoreModuleState,
        val current: XCoreModuleState,
        val error: XCoreError? = null,
    ) : XCoreEvent

    data class ConfigReloaded(
        override val timestampMillis: Long,
        val source: String,
        val changedKeys: Set<String>,
    ) : XCoreEvent

    data class StorageProbed(
        override val timestampMillis: Long,
        val rootPath: String,
        val usableBytes: Long,
        val fellBackToInternal: Boolean,
    ) : XCoreEvent

    data class DatabasePrepared(
        override val timestampMillis: Long,
        val version: Int,
        val migratedFrom: Int?,
    ) : XCoreEvent

    data class TaskStateChanged(
        override val timestampMillis: Long,
        val taskId: String,
        val taskName: String,
        val state: XCoreTaskState,
        val progress: Float? = null,
        val error: XCoreError? = null,
    ) : XCoreEvent

    data class InstanceChanged(
        override val timestampMillis: Long,
        val instanceId: String,
        val change: XCoreInstanceChange,
    ) : XCoreEvent

    data class RuntimeComponentChanged(
        override val timestampMillis: Long,
        val componentId: String,
        val previous: XCoreRuntimeState,
        val current: XCoreRuntimeState,
    ) : XCoreEvent

    data class ErrorRaised(
        override val timestampMillis: Long,
        val error: XCoreError,
        val origin: String,
    ) : XCoreEvent
}

enum class XCoreInstanceChange { CREATED, UPDATED, DELETED, SELECTED }

/**
 * Non-blocking publish/subscribe channel.
 *
 * [publish] is fire-and-forget for call sites that must not suspend (engine internals,
 * cancellation paths); [publishSuspended] applies backpressure for bulk producers.
 */
interface XCoreEventBus {

    val events: SharedFlow<XCoreEvent>

    /** Recent events for the diagnostics screen, newest last. */
    fun recent(limit: Int = 100): List<XCoreEvent>

    /** @return false when the buffer was full and the oldest event had to be dropped. */
    fun publish(event: XCoreEvent): Boolean

    suspend fun publishSuspended(event: XCoreEvent)

    /** Backpressure-free view filtered to [type]; collect [events] directly if drops matter. */
    fun <E : XCoreEvent> of(type: KClass<E>): Flow<E>

    fun clear()
}

class XCoreFlowEventBus(
    private val historyCapacity: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) : XCoreEventBus {

    private val historyLock = Any()
    private val history = ArrayDeque<XCoreEvent>(historyCapacity.coerceAtLeast(16))

    private val flow = MutableSharedFlow<XCoreEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<XCoreEvent> = flow.asSharedFlow()

    override fun recent(limit: Int): List<XCoreEvent> = synchronized(historyLock) {
        history.toList().takeLast(limit.coerceAtLeast(1))
    }

    override fun publish(event: XCoreEvent): Boolean {
        record(event)
        return flow.tryEmit(event)
    }

    override suspend fun publishSuspended(event: XCoreEvent) {
        record(event)
        flow.emit(event)
    }

    override fun <E : XCoreEvent> of(type: KClass<E>): Flow<E> =
        events.filter { type.isInstance(it) }.map { type.java.cast(it) }

    override fun clear() {
        synchronized(historyLock) { history.clear() }
    }

    private fun record(event: XCoreEvent) {
        synchronized(historyLock) {
            if (history.size >= historyCapacity) history.pollFirst()
            history.addLast(event)
        }
    }

    companion object {
        /** Stamp used by producers that do not compute their own timestamp. */
        fun nowMillis(): Long = System.currentTimeMillis()
    }
}
