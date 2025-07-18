package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.tool.mcp.McpClientFactory
import ai.masaic.openresponses.tool.mcp.SimpleMcpClientFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class McpClientConfig {
    @Bean
    @Profile("!platform")
    fun mcpClientFactory(): McpClientFactory = SimpleMcpClientFactory()
}
