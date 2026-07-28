package tk.zwander.common.util

import io.ktor.util.collections.ConcurrentSet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val eventManager: EventManager by lazy { EventManager.create() }

class EventManager private constructor() {
    companion object {
        @Volatile
        private var instance: EventManager? = null
        private val lock = CommonLock()

        fun create(): EventManager {
            return instance ?: lock.withLock {
                instance ?: EventManager().also { instance = it }
            }
        }
    }

    interface EventListener {
        suspend fun onEvent(event: Event)
    }

    private val listeners = ConcurrentSet<EventListener>()

    fun addListener(listener: EventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: EventListener) {
        listeners.remove(listener)
    }

    suspend fun sendEvent(event: Event) {
        coroutineScope {
            listeners.forEach { listener ->
                launch {
                    with(listener) {
                        onEvent(event)
                    }
                }
            }
        }
    }
}

sealed class Event {
    sealed class Download : Event() {
        data class Progress(
            val status: String,
            val current: Long,
            val max: Long,
        ) : Download()
        data object Start : Download()
        data object Finish : Download()
    }
    sealed class Decrypt : Event() {
        data class Progress(
            val status: String,
            val current: Long,
            val max: Long,
        ) : Decrypt()
        data object Start : Decrypt()
        data object Finish : Decrypt()
    }
}