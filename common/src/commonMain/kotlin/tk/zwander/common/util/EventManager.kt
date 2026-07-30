package tk.zwander.common.util

import io.ktor.util.collections.ConcurrentSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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
        supervisorScope {
            listeners.forEach { listener ->
                launch {
                    try {
                        with(listener) {
                            onEvent(event)
                        }
                    } catch (e: Exception) {
                        BifrostLogger.general.warn("Event listener failed for $event", e)
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