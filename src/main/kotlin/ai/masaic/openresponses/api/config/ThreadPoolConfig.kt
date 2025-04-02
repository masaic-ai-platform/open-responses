package ai.masaic.openresponses.api.config

import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Configuration for thread pools and connection pools in the application.
 * This ensures resources are managed efficiently under load.
 */
@Configuration
@EnableScheduling
class ThreadPoolConfig : WebFluxConfigurer {
    @Value("\${server.port:8080}")
    private val serverPort: Int = 8080

    @Value("\${open-responses.thread-pool.core-size:10}")
    private val corePoolSize: Int = 10

    @Value("\${open-responses.thread-pool.max-size:50}")
    private val maxPoolSize: Int = 50

    @Value("\${open-responses.thread-pool.queue-capacity:100}")
    private val queueCapacity: Int = 100

    @Value("\${open-responses.connection-pool.max-connections:500}")
    private val maxConnections: Int = 500

    @Value("\${open-responses.connection-pool.max-idle-time:30}")
    private val maxIdleTimeSeconds: Long = 30

    @Value("\${open-responses.connection-pool.max-life-time:60}")
    private val maxLifeTimeSeconds: Long = 60

    @Value("\${open-responses.http-client.connect-timeout:5}")
    private val connectTimeoutSeconds: Int = 5

    @Value("\${open-responses.http-client.read-timeout:30}")
    private val readTimeoutSeconds: Int = 30

    @Value("\${open-responses.http-client.write-timeout:30}")
    private val writeTimeoutSeconds: Int = 30

    @Value("\${open-responses.http-client.enable-tcp-no-delay:true}")
    private val enableTcpNoDelay = true

    @Value("\${open-responses.http-client.enable-keep-alive:true}")
    private val enableKeepAlive = false

    @Value("\${open-responses.http-client.enable-reuse-address:true}")
    private val enableReuseAddress = true

    /**
     * Creates a task executor for background tasks.
     * This ensures that background operations don't interfere with request handling.
     */
    @Bean
    fun taskExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = corePoolSize
        executor.maxPoolSize = maxPoolSize
        executor.queueCapacity = queueCapacity
        executor.setThreadNamePrefix("app-task-")
        executor.setRejectedExecutionHandler(
            RejectedExecutionHandler { r: Runnable, e: ThreadPoolExecutor ->
                logger.warn { "Task rejected from executor: ${e.javaClass.simpleName}" }
                throw IllegalStateException("Server too busy, please try again later")
            },
        )
        return executor
    }

    /**
     * Creates a Netty server factory with configured event loop groups
     * to handle incoming requests efficiently.
     */
    @Bean
    fun reactiveWebServerFactory(): ReactiveWebServerFactory {
        val factory = NettyReactiveWebServerFactory(serverPort)
        // Create an inline implementation of the NettyServerCustomizer interface
        val serverCustomizer =
            object : NettyServerCustomizer {
                override fun apply(httpServer: HttpServer): HttpServer =
                    httpServer
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutSeconds * 1000)
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .option(ChannelOption.TCP_NODELAY, enableTcpNoDelay)
                        .childOption(ChannelOption.SO_KEEPALIVE, enableKeepAlive)
                        .option(ChannelOption.SO_REUSEADDR, enableReuseAddress)
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            }
        factory.addServerCustomizers(serverCustomizer)
        return factory
    }

    /**
     * Creates a connection provider for WebClient with configured connection pool limits.
     */
    @Bean
    fun connectionProvider(): ConnectionProvider =
        ConnectionProvider
            .builder("http-client-pool")
            .maxConnections(maxConnections)
            .maxIdleTime(Duration.ofSeconds(maxIdleTimeSeconds))
            .maxLifeTime(Duration.ofSeconds(maxLifeTimeSeconds))
            .pendingAcquireTimeout(Duration.ofSeconds(connectTimeoutSeconds.toLong()))
            .evictInBackground(Duration.ofSeconds(30))
            .metrics(true)
            .build()

    /**
     * Creates a WebClient with configured timeouts and connection pool.
     */
    @Bean
    fun webClient(connectionProvider: ConnectionProvider): WebClient {
        val httpClient =
            HttpClient
                .create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutSeconds * 1000)
                .doOnConnected { conn ->
                    conn
                        .addHandlerLast(ReadTimeoutHandler(readTimeoutSeconds.toLong(), TimeUnit.SECONDS))
                        .addHandlerLast(WriteTimeoutHandler(writeTimeoutSeconds.toLong(), TimeUnit.SECONDS))
                }.responseTimeout(Duration.ofSeconds(readTimeoutSeconds.toLong()))

        return WebClient
            .builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) // 2MB buffer size
            }.build()
    }
}
