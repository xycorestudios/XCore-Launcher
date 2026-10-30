package studio.xycore.xcore.services

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.reflect.KClass
import studio.xycore.xcore.core.XCoreError
import studio.xycore.xcore.core.XCoreErrorKind
import studio.xycore.xcore.core.XCoreResult
import studio.xycore.xcore.core.XCoreServiceCatalog
import studio.xycore.xcore.core.XCoreServiceLocator
import studio.xycore.xcore.core.asXCoreFailure
import studio.xycore.xcore.core.asXCoreSuccess
import studio.xycore.xcore.core.map

/** Bookkeeping for one registered service, safe to expose to the UI and diagnostics. */
data class XCoreServiceBinding(
    val id: String,
    val type: KClass<*>,
    val priority: Int,
    val registeredAtMillis: Long,
) {
    val simpleName: String get() = type.simpleName ?: type.toString()
}

/**
 * Typed, thread-safe service directory.
 *
 * Lookups return [XCoreResult] instead of throwing, so a missing capability travels through the
 * same channel as every other failure. There are no default or placeholder entries: an absent
 * service stays absent, and that is reported as [XCoreError.Service].
 */
class XCoreServiceRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) : XCoreServiceCatalog {

    private class Entry(val binding: XCoreServiceBinding, val instance: Any)

    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, Entry>()
    private val typeIndex = HashMap<KClass<*>, MutableList<String>>()

    /**
     * @param id stable identifier used by capability readiness checks; defaults to the type name.
     * @param priority lower values win when several implementations share a [type].
     */
    fun <T : Any> register(
        instance: T,
        type: KClass<out T>,
        id: String = type.simpleName ?: type.toString(),
        priority: Int = 0,
        replace: Boolean = false,
    ): XCoreResult<XCoreServiceBinding> = lock.write {
        val existing = entries[id]
        if (existing != null && !replace) {
            return@write XCoreError.Service(
                detail = "service '$id' is already bound to ${existing.binding.simpleName}; " +
                    "pass replace = true to override it",
                serviceId = id,
                duplicate = true,
            ).asXCoreFailure()
        }
        if (existing != null) {
            typeIndex[existing.binding.type]?.remove(id)
        }
        val binding = XCoreServiceBinding(
            id = id,
            type = type,
            priority = priority,
            registeredAtMillis = clock(),
        )
        entries[id] = Entry(binding, instance)
        val forType = typeIndex.getOrPut(type) { ArrayList() }
        forType.remove(id)
        forType += id
        forType.sortBy { entries.getValue(it).binding.priority }
        binding.asXCoreSuccess()
    }

    /** Registers under the instance's own class. Use the [KClass] overload for interfaces. */
    fun <T : Any> register(
        instance: T,
        id: String = instance::class.simpleName ?: instance::class.toString(),
        priority: Int = 0,
        replace: Boolean = false,
    ): XCoreResult<XCoreServiceBinding> = register(
        instance = instance,
        type = instance::class,
        id = id,
        priority = priority,
        replace = replace,
    )

    override fun <T : Any> find(type: KClass<T>): T? = lock.read {
        val ids = typeIndex[type] ?: return@read null
        val entry = ids.firstNotNullOfOrNull { entries[it] } ?: return@read null
        @Suppress("UNCHECKED_CAST")
        entry.instance as T
    }

    override fun <T : Any> require(type: KClass<T>): XCoreResult<T> {
        val found = find(type)
        return if (found == null) {
            XCoreError.Service(
                detail = "no service registered for ${type.simpleName ?: type}; " +
                    "registered ids: ${identifiers().joinToString().ifBlank { "none" }}",
                serviceId = type.simpleName ?: type.toString(),
            ).asXCoreFailure()
        } else {
            found.asXCoreSuccess()
        }
    }

    override fun contains(id: String): Boolean = lock.read { entries.containsKey(id) }

    override fun contribute(
        id: String,
        instance: Any,
        priority: Int,
        replace: Boolean,
    ): XCoreResult<Unit> = register(
        instance = instance,
        type = instance::class,
        id = id,
        priority = priority,
        replace = replace,
    ).map { }

    override fun withdraw(id: String): XCoreResult<Unit> = unregister(id)

    override fun identifiers(): Set<String> = lock.read { entries.keys.toSet() }

    /** All bindings ordered by priority, then registration time. */
    fun bindings(): List<XCoreServiceBinding> = lock.read {
        entries.values.map { it.binding }.sortedWith(compareBy({ it.priority }, { it.registeredAtMillis }))
    }

    fun binding(id: String): XCoreServiceBinding? = lock.read { entries[id]?.binding }

    fun unregister(id: String): XCoreResult<Unit> = lock.write {
        val entry = entries.remove(id)
            ?: return@write XCoreError.Service(
                detail = "cannot unregister unknown service '$id'",
                serviceId = id,
            ).asXCoreFailure()
        typeIndex[entry.binding.type]?.remove(id)
        Unit.asXCoreSuccess()
    }

    /**
     * Closes every `AutoCloseable` service in reverse priority order. A close that throws is
     * collected, not hidden, and reported through the result.
     */
    fun closeAll(): XCoreResult<Unit> {
        val ordered = lock.write {
            val snapshot = entries.values.sortedByDescending { it.binding.priority }.toList()
            entries.clear()
            typeIndex.clear()
            snapshot
        }
        val failures = ArrayList<XCoreError>()
        for (entry in ordered) {
            val instance = entry.instance
            if (instance is AutoCloseable) {
                runCatching { instance.close() }
                    .onFailure {
                        failures += XCoreError.fromThrowable(
                            it,
                            XCoreErrorKind.SERVICE,
                            "close of '${entry.binding.id}' failed",
                        )
                    }
            }
        }
        return if (failures.isEmpty()) {
            Unit.asXCoreSuccess()
        } else {
            XCoreError.Unexpected(
                detail = "${failures.size} service(s) failed to close: ${failures.joinToString(" | ") { it.format() }}",
                cause = failures.first().cause,
            ).asXCoreFailure()
        }
    }

    inline fun <reified T : Any> findService(): T? = find(T::class)

    inline fun <reified T : Any> requireBound(): XCoreResult<T> = require(T::class)

    companion object {
        /** Builds a registry and registers each pair, returning the first failure if any. */
        fun of(vararg services: Pair<String, Any>): XCoreResult<XCoreServiceRegistry> {
            val registry = XCoreServiceRegistry()
            for ((id, instance) in services) {
                val outcome = registry.register(instance, id = id)
                if (outcome is XCoreResult.Failure) return outcome
            }
            return registry.asXCoreSuccess()
        }
    }
}
