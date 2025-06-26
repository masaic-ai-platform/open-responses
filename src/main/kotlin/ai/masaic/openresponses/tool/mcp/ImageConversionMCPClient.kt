package ai.masaic.openresponses.tool.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking


/**
 * Test client for the ImageConversionMCPServer
 *
 * This client demonstrates how to:
 * 1. Connect to an MCP server using HttpClientSseClientTransport
 * 2. Add authentication headers (Bearer token example)
 * 3. List available tools
 * 4. Call tools with proper JSON parameters
 *
 * Authentication Examples (when server requires authentication):
 * - For servers that require authentication, you can customize the underlying HttpClient
 * - The exact API depends on the MCP SDK version and HttpClient implementation
 * - Common patterns include adding Authorization headers or custom authentication
 */
object ImageConversionMCPClient {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking{
        // Replace with your actual Bearer token
        val bearerToken = "cnpwX3Rlc3RfYWpucmNBMmNpeDlaY206OUtScEdtN090SEFoOURtbDRwbWFhckFtCg=="

        // Create authenticated transport with Bearer token
        val transport = HeadersEnabledHttpSseClientTransport.Builder("https://mcp.razorpay.com/sse")
//        val transport = AuthenticatedHttpClientSseTransport.Builder("http://localhost:8081/sse")
            .objectMapper(jacksonObjectMapper())
            .addHeaders(mutableMapOf("Authorization" to "Bearer cnpwX3Rlc3RfYWpucmNBMmNpeDlaY206OUtScEdtN090SEFoOURtbDRwbWFhckFtCg=="))
            .build()
        val client = McpClient.async(transport)
//            .requestTimeout(Duration.ofSeconds(10))
            .capabilities(
                McpSchema.ClientCapabilities.builder()
                    .roots(true) // Enable roots capability
//                    .sampling() // Enable sampling capability
                    .build()
            )
            .build()

// Initialize connection
        client.initialize().awaitSingle().capabilities
        val tools = client.listTools().awaitSingle().tools
        println("$tools")
        val text = client.callTool(McpSchema.CallToolRequest("create_payment_link", "{\"amount\":20000,\"currency\":\"INR\",\"customer_contact\":\"\",\"customer_email\":\"\",\"customer_name\":\"\",\"description\":\"Blemish Toner Pads200mL (120 pads)\",\"notify_email\":false,\"notify_sms\":false,\"reference_id\":\"abcsJas1234\",\"reminder_enable\":false}\"}"))
//        val text = client.callTool(McpSchema.CallToolRequest("imageToBase64", """{"url": "https://cdn.shopify.com/s/files/1/0674/2215/9099/files/Pitch_Blemish_Pad_Hero_Photo_2.png?v=1725970100"}"""))
        println("$text")
    }
}

