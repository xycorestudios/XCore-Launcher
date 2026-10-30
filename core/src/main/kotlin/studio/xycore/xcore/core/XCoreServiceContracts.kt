package studio.xycore.xcore.core

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a unit of background work owned by the task controller. */
enum class XCoreTaskState(val terminal: Boolean) {
    QUEUED(false),
    RUNNING(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true),
    ;
}

/** Provisioning state of an external Java runtime component. */
enum class XCoreRuntimeState {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    READY,
    BROKEN,
    ;
}

/**
 * Typed lookup of live services. Implemented by `XCoreServiceRegistry` in the `:services` module;
 * declared here so `:core` never has to depend on an implementation.
 */
interface XCoreServiceLocator {

    fun <T : Any> find(type: KClass<T>): T?

    fun <T : Any> require(type: KClass<T>): XCoreResult<T>

    fun contains(id: String): Boolean

    fun identifiers(): Set<String>
}

inline fun <reified T : Any> XCoreServiceLocator.find(): T? = find(T::class)

inline fun <reified T : Any> XCoreServiceLocator.requireService(): XCoreResult<T> = require(T::class)

/**
 * The write side of the service directory, used by modules that have just prepared a capability.
 *
 * Keeping it separate from [XCoreServiceLocator] means a module can be handed a read-only view when
 * it should not be able to publish anything, and a registration is made at the moment the thing it
 * describes genuinely exists rather than at wiring time.
 */
interface XCoreServiceCatalog : XCoreServiceLocator {

    fun contribute(
        id: String,
        instance: Any,
        priority: Int = 0,
        replace: Boolean = false,
    ): XCoreResult<Unit>

    fun withdraw(id: String): XCoreResult<Unit>
}

/**
 * Canonical service ids for the launcher capabilities. Keeping them here means the engine's
 * readiness check and the modules that provide them cannot drift apart silently.
 */
object XCoreServiceIds {
    const val STORAGE_LAYOUT = "storage.layout"
    const val VERSION_INDEX = "version.index"
    const val DOWNLOAD_ENGINE = "download.engine"
    const val AUTHENTICATION = "authentication"
    const val RUNTIME_PROVISIONER = "runtime.provisioner"
    const val NATIVE_BRIDGE = "native.bridge"
    const val GRAPHICS_SURFACE = "graphics.surface"
    const val LAUNCH_PIPELINE = "launch.pipeline"
}

/**
 * Handle for a submitted task. Reads reflect the live state; [await] suspends until the task
 * reaches a terminal state and reproduces its outcome faithfully, including cancellation.
 */
interface XCoreTaskHandle<out T> {

    val id: String

    /** Caller-supplied key used for de-duplication and lookup; defaults to [name]. */
    val key: String

    val name: String

    val group: String?

    val state: XCoreTaskState

    /** `null` while the total is unknown, otherwise a `0f..1f` fraction. */
    val progress: Float?

    val error: XCoreError?

    val submittedAtMillis: Long

    fun cancel(reason: String? = null): Boolean

    suspend fun await(): XCoreResult<T>
}

/** Reporting surface handed to a task body. */
interface XCoreTaskScope {

    val id: String

    val name: String

    fun reportProgress(fraction: Float, note: String? = null)

    fun reportIndeterminate(note: String? = null)
}

/**
 * Description of work to run. The body is expected to be a suspend function that either returns a
 * value or throws; the controller classifies the outcome and never invents a result for a body
 * that did not produce one.
 */
data class XCoreTaskSpec<T>(
    val name: String,
    val key: String = name,
    val group: String? = null,
    val originErrorKind: XCoreErrorKind = XCoreErrorKind.UNKNOWN,
    val block: suspend XCoreTaskScope.() -> T,
)

/**
 * Owns the coroutine infrastructure for background launcher work (downloads, verification,
 * runtime provisioning). Implemented by `XCoreTaskManager` in the `:services` module.
 */
interface XCoreTaskController {

    /** Live and recently finished tasks, newest submissions last. */
    val tasks: Flow<List<XCoreTaskHandle<*>>>

    val activeCount: Int

    /** Rejects a submission whose [XCoreTaskSpec.key] is already queued or running. */
    fun <T> submit(spec: XCoreTaskSpec<T>): XCoreResult<XCoreTaskHandle<T>>

    fun find(key: String): XCoreTaskHandle<*>?

    fun cancelGroup(group: String, reason: String? = null): Int

    suspend fun shutdown(graceMillis: Long = 5_000L): XCoreResult<Unit>
}

/** Everything a module is allowed to reach without knowing the engine's internals. */
interface XCoreModuleContext {

    val config: XCoreConfig

    val services: XCoreServiceCatalog

    val tasks: XCoreTaskController

    val events: XCoreEventBus

    val logger: XCoreLogger
}
