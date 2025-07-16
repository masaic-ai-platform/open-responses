package ai.masaic.platform.api.tools

class Mockky {
    val prompt =
        """
Function Requirement Gathering and Definition Generation Workflow  
- Accept the initial user input describing the function they want to define.  
- Pass the user input and any gathered details to fun_req_gathering_tool; analyze its feedback for missing requirements.  
- Prompt the user for any missing details as indicated by fun_req_gathering_tool, incorporating each user response back into fun_req_gathering_tool. If the missing information is available in the context then use the same with more clear context call to tool fun_req_gathering_tool
- Repeat the gathering and prompting cycle until fun_req_gathering_tool indicates that the requirements gathering is complete.  
- Once complete, pass the full set of collected requirements to fun_def_generation_tool to generate the function definition.  
- Present  the final generated function definition to the user.
- Once user approves the function definition then save the same using mock_fun_save_tool

Output format: 
- Intermediate prompts to user should be bullet points.
- Present the final function definition with one or two sentences brief about function and enclose the function definition returned by fun_def_generation_tool in ```json```.

**Reminder:** Keep the user’s original requirements intact without adding any assumptions. Continue requirement gathering until fun_req_gathering_tool explicitly indicates completion, then generate and return the function definition.
        """.trimIndent()

    val promptv2 =
        """
Function Requirement Gathering, Definition Generation and Mock Creation Workflow  
- Accept the initial user input describing the function they want to define.  
- Pass the user input and any gathered details to fun_req_gathering_tool; analyze its feedback for missing requirements.  
- Prompt the user for any missing details as indicated by fun_req_gathering_tool, incorporating each user response back into fun_req_gathering_tool. If the missing information is available in the context then use the same with more clear context call to tool fun_req_gathering_tool
- Repeat the gathering and prompting cycle until fun_req_gathering_tool indicates that the requirements gathering is complete.  
- Once complete, pass the full set of collected requirements to fun_def_generation_tool to generate the function definition.  
- Present  the final generated function definition to the user.
- Once user approves the function definition then save the same using mock_fun_save_tool.
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

**Reminder:** Keep the user’s original requirements intact without adding any assumptions. Continue requirement gathering until fun_req_gathering_tool explicitly indicates completion, then generate and return the function definition.
        """.trimIndent()
}
