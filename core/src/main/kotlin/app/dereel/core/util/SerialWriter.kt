package app.dereel.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs persistence work one task at a time, in submission order. In-memory state can then change
 * synchronously, for example from accessibility callbacks, while storage stays ordered.
 */
class SerialWriter(
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in queue) {
                try {
                    task()
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    fun submit(task: suspend () -> Unit) {
        queue.trySend(task)
    }

    /** Suspends until every task submitted before this call has finished. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }
}
