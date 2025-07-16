package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.tool.mcp.McpClientFactory
import ai.masaic.openresponses.tool.mcp.SimpleMcpClientFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class McpClientConfig {

    @Bean
    @ConditionalOnMissingBean(McpClientFactory::class)
    fun mcpClientFactory() = SimpleMcpClientFactory()
}
