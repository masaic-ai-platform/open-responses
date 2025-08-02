package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.tool.NativeToolRegistry
import ai.masaic.openresponses.tool.mcp.McpClientFactory
import ai.masaic.openresponses.tool.mcp.SimpleMcpClientFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class McpClientConfig {
    @Bean
    @Profile("!platform")
    fun mcpClientFactory(): McpClientFactory = SimpleMcpClientFactory()

    @Bean
    @Profile("!platform")
    fun nativeToolRegistry(
        objectMapper: ObjectMapper,
        responseStore: ResponseStore,
    ) = NativeToolRegistry(objectMapper, responseStore)
}
