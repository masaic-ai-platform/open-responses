package ai.masaic.platform.api.controller

import ai.masaic.openresponses.api.model.MasaicManagedTool
import ai.masaic.openresponses.api.model.Tool
import ai.masaic.platform.api.tools.*
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Profile("platform")
@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
class AgentsController {
    @GetMapping("/agents/{agentName}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAgent(
        @PathVariable agentName: String,
    ): ResponseEntity<PlatformAgentMeta> {
        val agentMeta =
            when (agentName) {
                "Masaic-Mocky" -> {
                    PlatformAgentMeta(
                        name = "Masaic-Mocky",
                        description = "Mocky: Expert in making mock MCP servers quickly",
                        greetingMessage = "Hi, this is Mocky from Masaic. Let me know the quick mock functions you would like to create.",
                        systemPrompt = mockyPrompt,
                        tools = mockyTools,
                    )
                }

                "ModelTestAgent" -> {
                    PlatformAgentMeta(
                        name = "ModelTestAgent",
                        description = "This agent tests compatibility of model with platform",
                        greetingMessage = "Hi, let me test Model with query: \"Tell me the weather of new delhi\"",
                        systemPrompt = modelTestPrompt,
                        userMessage = "Tell me the weather of new delhi",
                        tools = modelTestTools,
                    )
                }
                else -> throw UnsupportedOperationException("Agent: $agentName is not supported.")
            }
        return ResponseEntity.ok(agentMeta)
    }

    private val mockyPrompt =
        """
Function Requirement Gathering, Definition Generation and Mock Creation Workflow  
- Accept the initial user input describing the function they want to define.  
- Pass the user input and any gathered details to fun_req_gathering_tool; analyze its feedback for missing requirements.  
- Prompt the user for any missing details as indicated by fun_req_gathering_tool, incorporating each user response back into fun_req_gathering_tool. If the missing information is available in the context then use the same with more clear context call to tool fun_req_gathering_tool
- Repeat the gathering and prompting cycle until fun_req_gathering_tool indicates that the requirements gathering is complete.  
- Once complete, pass the full set of collected requirements to fun_def_generation_tool to generate the function definition.  
- Present  the final generated function definition to the user.
- Once user approves the function definition then save the same using mock_fun_save_tool. If mock_fun_save_tool fails then retry to recover from error. 
- Once function is saved, offer user for mock requests and response generation. If user is interested then accept the initial user input describing the type of mocks they want.
- Pass the user input and any gathered details to mocks_generation_tool; analyze its feedback for missing requirements.  
- Prompt the user for any missing details as indicated by mocks_generation_tool, incorporating each user response back into mocks_generation_tool.
- Repeat the gathering and prompting cycle until mocks_generation_tool indicates that the requirements gathering is complete and mocks are generated.
- Once user approves the proposed mocks then save the same using mocks_save_tool.

Output format: 
- Intermediate prompts to user should be bullet points.
- Present the final function definition with one or two sentences brief about function and enclose the function definition returned by fun_def_generation_tool in ```json```.
- Present the final proposed mocks with one or two sentences brief about mocks and enclose the mocks returned by mocks_generation_tool in ```json```.
- Return unique identifiers of saved function definition and saved mocks to user for reference.

**Reminder:** 
- Keep the user’s original requirements intact without adding any assumptions. Continue requirement gathering until fun_req_gathering_tool explicitly indicates completion, then generate and return the function definition.
- If mock_fun_save_tool is executed then success of the tool is mandatory to proceed to next step.
- If mocks_save_tool is executed then success of  the tool is mandatory for the workflow completion.
        """.trimIndent()

    private val mockyTools =
        listOf(
            MasaicManagedTool(PlatformToolsNames.FUN_DEF_GEN_TOOL),
            MasaicManagedTool(PlatformToolsNames.FUN_REQ_GATH_TOOL),
            MasaicManagedTool(PlatformToolsNames.MOCK_FUN_SAVE_TOOL),
            MasaicManagedTool(PlatformToolsNames.MOCK_GEN_TOOL),
            MasaicManagedTool(PlatformToolsNames.MOCK_SAVE_TOOL),
        )

    val modelTestPrompt = """
    # Weather Information Provider

* Accept a city name from the user.
* Call get_weather_by_city with the provided city name.
* Return the weather information for the requested location.

Output format:
Provide only the weather data with no additional commentary.

Examples:
Input: What's the weather in Tokyo?
Output: [Weather data for Tokyo]

Input: Weather for New York
Output: [Weather data for New York]

**Reminder: Keep responses concise and focused only on the weather data.**
    """
    private val modelTestTools = listOf(MasaicManagedTool(PlatformToolsNames.MODEL_TEST_TOOL))
}

data class PlatformAgentMeta(
    val name: String,
    val description: String,
    val greetingMessage: String,
    val systemPrompt: String,
    val userMessage: String? = null,
    val tools: List<Tool>,
)
