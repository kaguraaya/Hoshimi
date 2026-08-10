package com.acgcompass.data.sync

import com.acgcompass.core.common.ApplicationScope
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PublicCacheFillCoordinator @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val activeJob = AtomicReference<Job?>(null)
    private val activeDeferred = AtomicReference<Deferred<Any?>?>(null)

    suspend fun <T> run(block: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        val deferred = mutex.withLock {
            activeDeferred.get() ?: scope.async(start = CoroutineStart.LAZY) {
                activeJob.set(coroutineContext[Job])
                try {
                    block()
                } finally {
                    activeJob.set(null)
                    activeDeferred.set(null)
                }
            }.also { created -> activeDeferred.set(created as Deferred<Any?>) }
        } as Deferred<T>
        return deferred.await()
    }

    fun cancel() {
        activeJob.getAndSet(null)?.cancel(CancellationException("Cache fill stopped by user"))
    }
}
