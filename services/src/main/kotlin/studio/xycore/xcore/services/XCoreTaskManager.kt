package studio.xycore.xcore.services

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreEvent
import studio.xycore.xcore.core.XCoreEventBus
import studio.xycore.xcore.core.XCoreFailureException
import studio.xycore.xcore.core.XCoreLogTags
import studio.xycore.xcore.core.XCoreLogLevel
import studio.xycore.xcore.core.XCoreLogger
import studio.xycore.xcore.core.XCoreOperationMeta
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreTaskController
import studio.xycore.xcore.core.XCoreTaskHandle
import studio.xycore.xcore.core.XCoreTaskScope
import studio.xycore.xcore.core.XCoreTaskSpec
import studio.xycore.xcore.core.XCoreTaskState
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess

/**
 * Coroutine-backed launcher work queue: downloads, verification, runtime provisioning.
 *
 * A child failure never tears down its siblings (`SupervisorJob`), and an outcome is only ever
 * what the submitted body actually produced. [XCoreTaskState.CANCELLED] is reached by real
 * cancellation, never by a timeout that completed successfully.
 */
class XCoreTaskManager(
    private val events: XCoreEventBus? = null,
    private val logger: XCoreLogger? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val historyCapacity: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) : XCoreTaskController {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val sequence = AtomicLong(0L)
    private val stateLock = Any()
    private val records = LinkedHashMap<String, TaskRecord<*>>()
    private val snapshotFlow = MutableStateFlow<List<XCoreTaskHandle<*>>>(emptyList())

    @Volatile
    private var released = false

    override val tasks: Flow<List<XCoreTaskHandle<*>>> = snapshotFlow.asStateFlow()

    override val activeCount: Int
        get() = synchronized(stateLock) { records.values.count { !it.state.terminal } }

    override fun <T> submit(spec: XCoreTaskSpec<T>): XCoreResult<XCoreTaskHandle<T>> {
        if (spec.name.isBlank()) {
            return XCoreError.Validation(
                detail = "task name must not be blank",
                field = "taskSpec.name",
            ).asXCoreFailure()
        }
        val record: TaskRecord<T> = synchronized(stateLock) {
            if (released) {
                return XCoreError.Configuration("task manager has been shut down").asXCoreFailure()
            }
            val duplicate = records.values.firstOrNull { it.key == spec.key && !it.state.terminal }
            if (duplicate != null) {
                return XCoreError.Service(
                    detail = "task '${spec.key}' is already ${duplicate.state} (id ${duplicate.id})",
                    serviceId = spec.key,
                    duplicate = true,
                ).asXCoreFailure()
            }
            val created = TaskRecord<T>(
                id = "task-${sequence.incrementAndGet()}",
                key = spec.key,
                name = spec.name,
                group = spec.group,
                originErrorKind = spec.originErrorKind,
            )
            records[created.id] = created
            created
        }
        publish(record, snapshot = copySnapshot())
        record.start(spec.block)
        return record.asXCoreSuccess()
    }

    override fun find(key: String): XCoreTaskHandle<*>? =
        synchronized(stateLock) { records.values.firstOrNull { it.key == key } }

    override fun cancelGroup(group: String, reason: String?): Int {
        val targets = synchronized(stateLock) { records.values.filter { it.group == group && !it.state.terminal } }
        var cancelled = 0
        for (record in targets) {
            if (record.cancel(reason ?: "group '$group' cancelled")) cancelled += 1
        }
        return cancelled
    }

    /**
     * Cancels live tasks and waits up to [graceMillis] for their jobs to settle. Bodies that do not
     * reach a suspension point inside the grace window are reported as a failure, not assumed done.
     */
    override suspend fun shutdown(graceMillis: Long): XCoreResult<Unit> {
        synchronized(stateLock) { released = true }
        val live = synchronized(stateLock) { records.values.filter { !it.state.terminal } }
        for (record in live) {
            record.cancel("engine shutdown")
        }
        supervisor.cancelChildren()
        val children = supervisor.children.toList()
        val drained = withTimeoutOrNull(graceMillis) {
            children.joinAll()
            true
        } ?: false
        val stubborn = synchronized(stateLock) { records.values.count { !it.state.terminal } }
        return if (drained && stubborn == 0) {
            Unit.asXCoreSuccess(XCoreOperationMeta(origin = "taskManager.shutdown"))
        } else {
            XCoreError.Configuration(
                "shutdown left $stubborn of ${live.size} task(s) unfinished after ${graceMillis}ms",
            ).asXCoreFailure()
        }
    }

    /** Immediate snapshot for the diagnostics screen; newest submissions last. */
    fun snapshot(): List<XCoreTaskHandle<*>> = snapshotFlow.value

    fun history(limit: Int = 50): List<XCoreTaskHandle<*>> = synchronized(stateLock) {
        records.values.filter { it.state.terminal }.toList().takeLast(limit.coerceAtLeast(1))
    }

    /** Cancels and forgets terminal records; live work is untouched. */
    fun clearFinished(): Int {
        val removed = synchronized(stateLock) {
            val finished = records.values.filter { it.state.terminal }.map { it.id }
            finished.forEach { records.remove(it) }
            finished.size
        }
        if (removed > 0) publish(snapshot = synchronized(stateLock) { copySnapshotLocked() })
        return removed
    }

    private fun copySnapshot(): List<XCoreTaskHandle<*>> =
        synchronized(stateLock) { copySnapshotLocked() }

    private fun copySnapshotLocked(): List<XCoreTaskHandle<*>> {
        if (records.size > historyCapacity) {
            val finished = records.values.filter { it.state.terminal }.map { it.id }
            val excess = records.size - historyCapacity
            finished.take(excess).forEach { records.remove(it) }
        }
        return records.values.toList()
    }

    private fun publish(
        changed: TaskRecord<*>? = null,
        snapshot: List<XCoreTaskHandle<*>>,
    ) {
        snapshotFlow.value = snapshot
        if (changed != null) {
            events?.publish(
                XCoreEvent.TaskStateChanged(
                    timestampMillis = clock(),
                    taskId = changed.id,
                    taskName = changed.name,
                    state = changed.state,
                    progress = changed.progress,
                    error = changed.error,
                ),
            )
            val level = when (changed.state) {
                XCoreTaskState.FAILED -> XCoreLogLevel.ERROR
                XCoreTaskState.CANCELLED -> XCoreLogLevel.WARN
                XCoreTaskState.COMPLETED -> XCoreLogLevel.DEBUG
                else -> XCoreLogLevel.VERBOSE
            }
            logger?.log(
                level,
                XCoreLogTags.TASKS,
                "${changed.name} (${changed.id}) -> ${changed.state}" +
                    (changed.progress?.let { " at ${(it * 100).toInt()}%" } ?: "") +
                    (changed.error?.let { " error=${it.format()}" } ?: ""),
                changed.error?.cause,
            )
        }
    }

    /**
     * One queued-or-running task. State transitions are terminal-once: after a task reaches
     * COMPLETED, FAILED or CANCELLED it cannot be re-decided by a late body return.
     */
    private inner class TaskRecord<T>(
        override val id: String,
        override val key: String,
        override val name: String,
        override val group: String?,
        private val originErrorKind: XCoreErrorKind,
    ) : XCoreTaskHandle<T>, XCoreTaskScope {

        private val completion = CompletableDeferred<XCoreResult<T>>()

        @Volatile
        private var stateValue: XCoreTaskState = XCoreTaskState.QUEUED

        @Volatile
        private var progressValue: Float? = null

        @Volatile
        private var errorValue: XCoreError? = null

        @Volatile
        private var noteValue: String? = null

        @Volatile
        private var handle: Job? = null

        override val submittedAtMillis: Long = clock()

        override val state: XCoreTaskState get() = stateValue

        override val progress: Float? get() = progressValue

        override val error: XCoreError? get() = errorValue

        val note: String? get() = noteValue

        override fun reportProgress(fraction: Float, note: String?) {
            progressValue = fraction.coerceIn(0f, 1f)
            noteValue = note
            publish(this, snapshot = synchronized(stateLock) { copySnapshotLocked() })
        }

        override fun reportIndeterminate(note: String?) {
            progressValue = null
            noteValue = note
            publish(this, snapshot = synchronized(stateLock) { copySnapshotLocked() })
        }

        override fun cancel(reason: String?): Boolean {
            if (stateValue.terminal) return false
            finish(XCoreResult.Cancelled(name, reason ?: "cancelled by caller"))
            handle?.cancel()
            return true
        }

        override suspend fun await(): XCoreResult<T> = completion.await()

        fun start(block: suspend XCoreTaskScope.() -> T) {
            handle = scope.launch {
                transition(XCoreTaskState.RUNNING)
                val outcome: XCoreResult<T> = try {
                    XCoreResult.Success(block(this@TaskRecord))
                } catch (cancelled: CancellationException) {
                    XCoreResult.Cancelled(name, cancelled.message ?: "task was cancelled")
                } catch (failure: XCoreFailureException) {
                    XCoreResult.Failure(failure.error)
                } catch (throwable: Throwable) {
                    XCoreResult.Failure(
                        XCoreError.fromThrowable(
                            throwable,
                            originErrorKind,
                            "task '$name' failed",
                        ),
                    )
                }
                finish(outcome)
            }
        }

        private fun transition(next: XCoreTaskState) {
            stateValue = next
            publish(this, snapshot = synchronized(stateLock) { copySnapshotLocked() })
        }

        private fun finish(outcome: XCoreResult<T>) {
            val decided = synchronized(stateLock) {
                if (stateValue.terminal) {
                    false
                } else {
                    when (outcome) {
                        is XCoreResult.Success -> stateValue = XCoreTaskState.COMPLETED
                        is XCoreResult.Failure -> {
                            stateValue = XCoreTaskState.FAILED
                            errorValue = outcome.error
                        }
                        is XCoreResult.Cancelled -> {
                            stateValue = XCoreTaskState.CANCELLED
                            errorValue = XCoreError.Cancelled(
                                detail = outcome.reason ?: "cancelled",
                                operation = outcome.operation,
                            )
                        }
                    }
                    true
                }
            }
            if (!decided) {
                logger?.log(
                    XCoreLogLevel.DEBUG,
                    XCoreLogTags.TASKS,
                    "ignored late outcome for '${name}' already in $stateValue",
                )
                return
            }
            completion.complete(outcome)
            publish(this, snapshot = synchronized(stateLock) { copySnapshotLocked() })
        }
    }
}

private fun Job.cancelChildren() {
    children.forEach { it.cancel() }
}
