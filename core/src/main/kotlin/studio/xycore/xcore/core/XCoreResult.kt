package studio.xycore.xcore.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Facts about how an operation completed, kept separate from the value it produced. */
data class XCoreOperationMeta(
    val elapsed: Duration = Duration.ZERO,
    val attempts: Int = 1,
    val origin: String? = null,
) {
    val elapsedMillis: Long get() = elapsed.inWholeMilliseconds
}

/**
 * The single outcome type used across X-Core layers.
 *
 * [Failure] and [Cancelled] are `Nothing`-typed so a failure can flow into any expected result
 * type, and so `when (result)` stays exhaustive at call sites.
 */
sealed interface XCoreResult<out T> {

    data class Success<T>(
        val value: T,
        val meta: XCoreOperationMeta = XCoreOperationMeta(),
    ) : XCoreResult<T>

    data class Failure(
        val error: XCoreError,
        val meta: XCoreOperationMeta = XCoreOperationMeta(),
    ) : XCoreResult<Nothing>

    /**
     * Produced only when a scope genuinely observed cancellation of the work item. It is never
     * fabricated from an operation that actually succeeded.
     */
    data class Cancelled(
        val operation: String,
        val reason: String? = null,
    ) : XCoreResult<Nothing>
}

/**
 * Throws [error] out of a block that is being wrapped by [xCoreRun], without it being
 * reclassified as an unknown throwable.
 */
class XCoreFailureException(
    val error: XCoreError,
    override val message: String = error.format(),
) : RuntimeException(message)

fun <T> T.asXCoreSuccess(meta: XCoreOperationMeta = XCoreOperationMeta()): XCoreResult<T> =
    XCoreResult.Success(this, meta)

fun XCoreError.asXCoreFailure(meta: XCoreOperationMeta = XCoreOperationMeta()): XCoreResult<Nothing> =
    XCoreResult.Failure(this, meta)

fun xCoreCancelled(operation: String, reason: String? = null): XCoreResult<Nothing> =
    XCoreResult.Cancelled(operation, reason)

val XCoreResult<*>.isSuccess: Boolean get() = this is XCoreResult.Success

val XCoreResult<*>.isFailure: Boolean get() = this is XCoreResult.Failure

val XCoreResult<*>.isCancelled: Boolean get() = this is XCoreResult.Cancelled

fun <T> XCoreResult<T>.valueOrNull(): T? = (this as? XCoreResult.Success)?.value

fun <T> XCoreResult<T>.errorOrNull(): XCoreError? = (this as? XCoreResult.Failure)?.error

/**
 * Suspends until [block] finishes and classifies the outcome.
 *
 * [CancellationException] is deliberately rethrown rather than converted: swallowing it would
 * break structured concurrency for the caller's own scope. Code that owns a separate job
 * (see `XCoreTaskManager`) is the place where cancellation becomes [XCoreResult.Cancelled].
 */
suspend fun <T> xCoreRun(
    origin: String? = null,
    errorKind: XCoreErrorKind = XCoreErrorKind.UNKNOWN,
    block: suspend () -> T,
): XCoreResult<T> {
    val start: TimeMark = TimeSource.Monotonic.markNow()
    return try {
        val value = block()
        XCoreResult.Success(value, XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin))
    } catch (failure: XCoreFailureException) {
        XCoreResult.Failure(failure.error, XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        XCoreResult.Failure(
            XCoreError.fromThrowable(throwable, errorKind),
            XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin),
        )
    }
}

/** Non-suspending counterpart for CPU-bound and IO-lite work. */
inline fun <T> xCoreRunCatching(
    origin: String? = null,
    errorKind: XCoreErrorKind = XCoreErrorKind.UNKNOWN,
    block: () -> T,
): XCoreResult<T> {
    val start: TimeMark = TimeSource.Monotonic.markNow()
    return try {
        val value = block()
        XCoreResult.Success(value, XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin))
    } catch (failure: XCoreFailureException) {
        XCoreResult.Failure(failure.error, XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        XCoreResult.Failure(
            XCoreError.fromThrowable(throwable, errorKind),
            XCoreOperationMeta(elapsed = start.elapsedNow(), origin = origin),
        )
    }
}

inline fun <T, R> XCoreResult<T>.map(transform: (T) -> R): XCoreResult<R> = when (this) {
    is XCoreResult.Success -> XCoreResult.Success(transform(value), meta)
    is XCoreResult.Failure -> this
    is XCoreResult.Cancelled -> this
}

inline fun <T> XCoreResult<T>.mapError(transform: (XCoreError) -> XCoreError): XCoreResult<T> = when (this) {
    is XCoreResult.Success -> this
    is XCoreResult.Failure -> XCoreResult.Failure(transform(error), meta)
    is XCoreResult.Cancelled -> this
}

inline fun <T, R> XCoreResult<T>.flatMap(transform: (T) -> XCoreResult<R>): XCoreResult<R> = when (this) {
    is XCoreResult.Success -> transform(value)
    is XCoreResult.Failure -> this
    is XCoreResult.Cancelled -> this
}

inline fun <T> XCoreResult<T>.onSuccess(action: (T, XCoreOperationMeta) -> Unit): XCoreResult<T> = apply {
    if (this is XCoreResult.Success) action(value, meta)
}

inline fun <T> XCoreResult<T>.onFailure(action: (XCoreError) -> Unit): XCoreResult<T> = apply {
    if (this is XCoreResult.Failure) action(error)
}

inline fun <T> XCoreResult<T>.onCancelled(action: (String) -> Unit): XCoreResult<T> = apply {
    if (this is XCoreResult.Cancelled) action(operation)
}

inline fun <T, R> XCoreResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (XCoreError) -> R,
    onCancelled: (String) -> R,
): R = when (this) {
    is XCoreResult.Success -> onSuccess(value)
    is XCoreResult.Failure -> onFailure(error)
    is XCoreResult.Cancelled -> onCancelled(operation)
}

/** Recovers from a typed failure by producing a substitute value. Use sparingly. */
inline fun <T> XCoreResult<T>.recover(transform: (XCoreError) -> T): XCoreResult<T> = when (this) {
    is XCoreResult.Failure -> XCoreResult.Success(transform(error), meta)
    else -> this
}

fun <T> XCoreResult<T>.getOrThrow(): T = when (this) {
    is XCoreResult.Success -> value
    is XCoreResult.Failure -> throw XCoreFailureException(error)
    is XCoreResult.Cancelled -> throw XCoreFailureException(
        XCoreError.Cancelled("operation cancelled", operation),
    )
}

/** Short-circuits the enclosing [xCoreRun] / [xCoreRunCatching] with a typed error. */
fun failXCore(error: XCoreError): Nothing = throw XCoreFailureException(error)

/** All values, or the first failure/innermost cancellation. */
fun <T> List<XCoreResult<T>>.combine(): XCoreResult<List<T>> {
    val values = ArrayList<T>(size)
    for (result in this) {
        when (result) {
            is XCoreResult.Success -> values += result.value
            is XCoreResult.Failure -> return result
            is XCoreResult.Cancelled -> return result
        }
    }
    return XCoreResult.Success(values)
}

fun <T> Result<T>.toXCoreResult(origin: String? = null): XCoreResult<T> = fold(
    onSuccess = { XCoreResult.Success(it, XCoreOperationMeta(origin = origin)) },
    onFailure = {
        val typed = (it as? XCoreFailureException)?.error ?: XCoreError.fromThrowable(it)
        XCoreResult.Failure(typed, XCoreOperationMeta(origin = origin))
    },
)
