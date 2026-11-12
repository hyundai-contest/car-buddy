package ai.carbuddy.signal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

fun interface SignalSink { suspend fun emit(e: SignalEvent) }

/**
 * Merges every adapter into one stream (SPEC 4.3): replay 0, buffer 256.
 * Persistence to signal_log is done by the single consumer (TripPipeline) so ordering is kept.
 */
@Singleton
class SignalHub @Inject constructor() : SignalSink {
    private val flow = MutableSharedFlow<SignalEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<SignalEvent> = flow.asSharedFlow()

    override suspend fun emit(e: SignalEvent) = flow.emit(e)

    /** Non-suspending emit for SDK callbacks; returns false only if the buffer is full. */
    fun tryEmit(e: SignalEvent): Boolean = flow.tryEmit(e)
}
