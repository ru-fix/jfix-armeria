package ru.fix.armeria.limiter.concurrency

import MetricTag
import Metrics
import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.SimpleDecoratingClient
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.Response
import com.linecorp.armeria.server.RequestTimeoutException
import io.netty.util.concurrent.ScheduledFuture
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.ProfiledCall
import ru.fix.aggregating.profiler.Profiler
import ru.fix.dynamic.property.api.DynamicProperty
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction


/**
 * Profiled [Client] decorator able to limit number of active requests of type [RequestT].
 *
 * Based on standard armeria [com.linecorp.armeria.client.limit.ConcurrencyLimitingClient]
 * with [Profiler] and dynamic change of settings through [DynamicProperty] support added.
 */
abstract class ProfiledDynamicConcurrencyLimitingClient<RequestT : Request, ResponseT : Response>(
    delegate: Client<RequestT, ResponseT>,
    private val profiler: Profiler,
    private val maxConcurrencyProperty: DynamicProperty<Int>,
    private val timeoutMsProperty: DynamicProperty<Long>
) : SimpleDecoratingClient<RequestT, ResponseT>(delegate) {

    private val numActiveRequests = AtomicInteger()
    private val pendingRequests: Queue<PendingTask> = ConcurrentLinkedQueue()

    init {
        profiler.attachIndicator(Metrics.ACTIVE_REQUESTS) { numActiveRequests.get().toLong() }
        profiler.attachIndicator(Metrics.PENDING_REQUESTS) { pendingRequests.size.toLong() }
    }

    @Throws(Exception::class)
    override fun execute(ctx: ClientRequestContext, req: RequestT) =
        if (maxConcurrencyProperty.get() <= 0) {
            unlimitedExecute(ctx, req)
        } else {
            limitedExecute(ctx, req)
        }

    abstract fun newDeferredResponse(ctx: ClientRequestContext, resultFuture: CompletionStage<ResponseT>): ResponseT
    abstract fun getRequestMetricTags(ctx: ClientRequestContext, req: RequestT): Map<String, String>

    @Throws(Exception::class)
    private fun limitedExecute(ctx: ClientRequestContext, req: RequestT): ResponseT {
        val resultFuture = CompletableFuture<ResponseT>()
        val deferred = newDeferredResponse(ctx, resultFuture)
        val queueWaitProfiler = profiler.profiledCall(
            Identity(Metrics.WAIT_LATENCY, getRequestMetricTags(ctx, req).toMap())
        )
        val currentTask = PendingTask(ctx, req, resultFuture, queueWaitProfiler)
        queueWaitProfiler.start()
        pendingRequests.add(currentTask)
        drain()
        val timeoutMs = timeoutMsProperty.get()
        if (timeoutMs > 0
            && currentTask.state.compareAndSet(PendingTaskState.NOT_STARTED, PendingTaskState.TIMEOUT_SCHEDULED)
        ) {
            // Current request was not delegated. Schedule a timeout.
            currentTask.timeoutFuture = ctx.eventLoop().schedule(
                {
                    if (currentTask.state.compareAndSet(
                            PendingTaskState.TIMEOUT_SCHEDULED,
                            PendingTaskState.TIMEOUT_STARTED
                        )
                    ) {
                        resultFuture.completeExceptionally(UnprocessedRequestException(RequestTimeoutException.get()))
                        profiler.profiledCall(
                            Identity(Metrics.WAIT_TIMEOUT, getRequestMetricTags(ctx, req).toMap())
                        ).call()
                    }
                },
                timeoutMs, TimeUnit.MILLISECONDS
            )
        }
        return deferred
    }

    @Throws(Exception::class)
    private fun unlimitedExecute(ctx: ClientRequestContext, req: RequestT): ResponseT {
        numActiveRequests.incrementAndGet()
        var success = false
        return try {
            val res = delegate<Client<RequestT, ResponseT>>().execute(ctx, req)
            res.whenComplete().handle<Unit> { _, _ ->
                numActiveRequests.decrementAndGet()
            }
            success = true
            res
        } finally {
            if (!success) {
                numActiveRequests.decrementAndGet()
            }
        }
    }

    private fun drain() {
        while (!pendingRequests.isEmpty()) {
            val currentActiveRequests = numActiveRequests.get()
            val maxConcurrency = possiblyChangedMaxConcurrency
            if (currentActiveRequests >= maxConcurrency) {
                break
            }
            if (numActiveRequests.compareAndSet(currentActiveRequests, currentActiveRequests + 1)) {
                val task = pendingRequests.poll()
                task?.run() ?: numActiveRequests.decrementAndGet()
            }
        }
    }

    //max concurrency has changed since request is queued
    private val possiblyChangedMaxConcurrency: Int
        get() {
            val maxConcurrency = maxConcurrencyProperty.get()
            return if (maxConcurrency <= 0) {
                //max concurrency has changed since request is queued
                Int.MAX_VALUE
            } else maxConcurrency
        }

    private inner class PendingTask(
        val ctx: ClientRequestContext,
        val req: RequestT,
        val resultFuture: CompletableFuture<ResponseT>,
        val queueWaitProfiler: ProfiledCall
    ) : Runnable {

        val state = AtomicReference(PendingTaskState.NOT_STARTED)

        @Volatile
        var timeoutFuture: ScheduledFuture<*>? = null

        override fun run() {
            val previousState = state.getAndSet(PendingTaskState.STARTED)
            if (previousState == PendingTaskState.TIMEOUT_SCHEDULED) {
                val currentTimeoutFuture = timeoutFuture
                if (currentTimeoutFuture != null && !currentTimeoutFuture.cancel(false)) {
                    cancelDueToTimeout()
                    return
                }
            } else if (previousState == PendingTaskState.TIMEOUT_STARTED) {
                cancelDueToTimeout()
                return
            }
            queueWaitProfiler.stop()
            ctx.replace().use {
                try {
                    val actualRes = delegate<Client<RequestT, ResponseT>>().execute(ctx, req)
                    actualRes.whenComplete().handleAsync<Unit>(BiFunction { _, _ ->
                        numActiveRequests.decrementAndGet()
                        drain()
                    }, ctx.eventLoop())
                    resultFuture.complete(actualRes)
                } catch (e: Exception) {
                    numActiveRequests.decrementAndGet()
                    resultFuture.completeExceptionally(e)
                }
            }
        }

        private fun cancelDueToTimeout() {
            // Timeout task ran already or is determined to run.
            numActiveRequests.decrementAndGet()
            queueWaitProfiler.close()
        }

    }

    private enum class PendingTaskState {
        NOT_STARTED, TIMEOUT_SCHEDULED, TIMEOUT_STARTED, STARTED
    }

}
