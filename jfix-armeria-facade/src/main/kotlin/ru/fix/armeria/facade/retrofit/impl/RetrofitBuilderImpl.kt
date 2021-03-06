package ru.fix.armeria.facade.retrofit.impl

import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofitBuilder
import org.apache.logging.log4j.kotlin.Logging
import retrofit2.Converter
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.facade.Either
import ru.fix.armeria.facade.retrofit.CloseableRetrofit
import ru.fix.armeria.facade.retrofit.RetrofitHttpClientBuilder
import ru.fix.armeria.facade.webclient.impl.BaseHttpClientBuilderImpl
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.concurrency.threads.NamedExecutors
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

internal data class BlockingResponseReadingExecutorState(
    val shutdownTimeout: Either<Duration, DynamicProperty<Duration>>,
    val lazyExecutor: Lazy<ExecutorService>
)

internal data class RetrofitHttpClientBuilderState(
    val converterFactories: List<Converter.Factory> = emptyList(),
    val blockingResponseReadingExecutorState: BlockingResponseReadingExecutorState? = null,
    val armeriaRetrofitBuilderCustomizer: ArmeriaRetrofitBuilder.() -> ArmeriaRetrofitBuilder = { this }
)

internal class RetrofitHttpClientBuilderImpl(
    private val baseHttpClientBuilder: BaseHttpClientBuilderImpl<*>,
    private val builderState: RetrofitHttpClientBuilderState = RetrofitHttpClientBuilderState()
) : RetrofitHttpClientBuilder {

    companion object : Logging

    override fun addConverterFactory(factory: Converter.Factory): RetrofitHttpClientBuilder =
        RetrofitHttpClientBuilderImpl(
            baseHttpClientBuilder,
            builderState.copy(converterFactories = builderState.converterFactories + factory)
        )

    override fun enableNamedBlockingResponseReadingExecutor(
        maxPoolSizeProp: DynamicProperty<Int>,
        profiler: Profiler,
        shutdownTimeoutProp: DynamicProperty<Duration>
    ): RetrofitHttpClientBuilder = RetrofitHttpClientBuilderImpl(
        baseHttpClientBuilder,
        builderState.copy(
            blockingResponseReadingExecutorState = BlockingResponseReadingExecutorState(
                Either.Right(shutdownTimeoutProp),
                lazy {
                    NamedExecutors.newDynamicPool(
                        "${baseHttpClientBuilder.lazyClientName()}-retrofit-reading-pool",
                        maxPoolSizeProp,
                        profiler
                    )
                }
            )
        )
    )

    override fun setBlockingResponseReadingExecutor(
        retrofitCallbackExecutor: ExecutorService,
        shutdownTimeout: Duration
    ): RetrofitHttpClientBuilder = RetrofitHttpClientBuilderImpl(
        baseHttpClientBuilder,
        builderState.copy(
            blockingResponseReadingExecutorState = BlockingResponseReadingExecutorState(
                Either.Left(shutdownTimeout),
                lazyOf(retrofitCallbackExecutor)
            )
        )
    )

    override fun setBlockingResponseReadingExecutor(
        retrofitCallbackExecutor: ExecutorService,
        shutdownTimeoutProp: DynamicProperty<Duration>
    ): RetrofitHttpClientBuilder = RetrofitHttpClientBuilderImpl(
        baseHttpClientBuilder,
        builderState.copy(
            blockingResponseReadingExecutorState = BlockingResponseReadingExecutorState(
                Either.Right(shutdownTimeoutProp),
                lazyOf(retrofitCallbackExecutor)
            )
        )
    )

    override fun buildRetrofit(): CloseableRetrofit {
        val webClient = baseHttpClientBuilder.buildArmeriaWebClient()
        var armeriaRetrofitBuilder = ArmeriaRetrofit.builder(webClient)
            .streaming(true)

        for (converterFactory in builderState.converterFactories) {
            armeriaRetrofitBuilder = armeriaRetrofitBuilder.addConverterFactory(converterFactory)
        }

        builderState.blockingResponseReadingExecutorState?.let {
            armeriaRetrofitBuilder = armeriaRetrofitBuilder.callbackExecutor(it.lazyExecutor.value)
        }

        val armeriaRetrofitBuilderCustomizer = builderState.armeriaRetrofitBuilderCustomizer
        return CloseableRetrofit(
            armeriaRetrofitBuilder
                .armeriaRetrofitBuilderCustomizer()
                .build(),
            AutoCloseable {
                val baseHttpClientBuilderStr = """"$baseHttpClientBuilder""""
                val logPrefix = """Retrofit of $baseHttpClientBuilderStr:"""
                logger.info { """Closing Armeria Retrofit of builder $baseHttpClientBuilderStr...""" }
                val closingTimeMillis = measureTimeMillis {
                    webClient.close()
                    builderState.blockingResponseReadingExecutorState?.let {
                        logger.info {
                            "$logPrefix closing blocking response reading executor..."
                        }
                        it.lazyExecutor.value.shutdown()
                        val shutdownTimeout = when (val timeout = it.shutdownTimeout) {
                            is Either.Left -> timeout.value
                            is Either.Right -> timeout.value.get()
                        }
                        if (!it.lazyExecutor.value.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                            logger.error {
                                "$logPrefix failed to await termination of blocking response reading executor " +
                                        "for $shutdownTimeout ms. Forcing shutdown..."
                            }
                            it.lazyExecutor.value.shutdownNow()
                        }
                    }
                }
                logger.info {
                    """Armeria Retrofit of builder "$baseHttpClientBuilderStr" closed in $closingTimeMillis ms."""
                }
            }
        )
    }

    override fun customizeArmeriaRetrofitBuilder(customizer: ArmeriaRetrofitBuilder.() -> ArmeriaRetrofitBuilder) =
        RetrofitHttpClientBuilderImpl(
            baseHttpClientBuilder,
            builderState.copy(armeriaRetrofitBuilderCustomizer = customizer)
        )
}