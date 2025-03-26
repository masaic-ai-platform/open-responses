# Quick Start Guide

Follow this guide to run the service in under 5 minutes using one of the following flows:

1. Getting Started (Clone repo and quick setup without additional tool overhead)
2. Starting Docker with Built-In Tools
3. Starting Docker with a Custom MCP Configuration
4. Running example scripts with the openai-agent-python SDK
5. Running Agent Examples Built with OpenAI Agent SDK To Use Open Responses API Built In Tools

---

## 1. Getting Started

### Clone the Repository and Navigate to the Project Directory

Begin by cloning the repository and entering its directory:

```bash
git clone https://github.com/masaic-ai-platform/open-responses.git
cd open-responses
```
---

## 2. Quick Setup

### Pre-requisites

- Ensure port **8080** is available.
- Docker daemon must be running on your local machine.

### Run the Service

Start the service using Docker Compose with the default configuration:

```bash
docker-compose up open-responses
```

### Example API Calls

Replace the placeholder API keys with your own values.

#### Groq Example (Streaming Enabled)

Open your [Groq key](https://console.groq.com/keys) to create a key if you haven't done so already.

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer GROQ_API_KEY' \
--data '{
    "model": "llama-3.2-3b-preview",
    "stream": true,
    "input": [
        {
            "role": "user",
            "content": "Write a poem on Masaic"
        }
    ]
}'
```

#### OpenAI Example (Get your [OpenAI key](https://platform.openai.com/settings))

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer OPENAI_API_KEY' \
--header 'x-model-provider: openai' \
--data '{
    "model": "gpt-4o",
    "stream": false,
    "input": [
        {
            "role": "user",
            "content": "Write a poem on Masaic"
        }
    ]
}'
```

#### Claude Example (Get your [Anthropic key](https://console.anthropic.com/dashboard))

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer ANTHROPIC_API_KEY' \
--header 'x-model-provider: claude' \
--data '{
    "model": "claude-3-5-sonnet-20241022",
    "stream": false,
    "input": [
        {
            "role": "user",
            "content": "Write a poem on Masaic"
        }
    ]
}'
```

---

## 3. Start Docker with Built-In Tools

### Update Prerequisites for Built-In Tools

Before using the built-in tools, complete the following:

- Generate a GitHub Personal Access Token (PAT) from [GitHub Tokens](https://github.com/settings/personal-access-tokens)
- Get your Brave Search API key from [Brave API Dashboard](https://api-dashboard.search.brave.com/app/keys)

### Update the .env File

Add the following lines to your `.env` file:

```
GITHUB_TOKEN=your_token_value
BRAVE_API_KEY=your_brave_key_value
```

### Run the Service with MCP Tools Enabled
Start the service with the built-in MCP tools using:

<sub>NOTE: Stop previously running docker-compose (if any) before running this command.</sub>

For macOS:
```bash
docker-compose --profile mcp up open-responses-mcp
```
For Windows:
```bash
docker-compose --profile mcp up open-responses-mcp-windows
````

### Example API Calls with Built-In Tools

#### Groq Example with Brave Web Search Tool

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer GROQ_API_KEY' \
--data '{
    "model": "qwen-2.5-32b",
    "stream": false,
    "tools": [
        {
            "type": "brave_web_search"
        }
    ],
    "input": [
        {
            "role": "user",
            "content": "Where did NVIDIA GTC happened in 2025 and what were the major announcements?"
        }
    ]
}'
```

#### OpenAI Example with GitHub Repositories Search Tool (Get your [OpenAI key](https://platform.openai.com/settings))

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer OPENAI_API_KEY' \
--header 'x-model-provider: openai' \
--data '{
    "model": "gpt-4o",
    "stream": false,
    "tools": [
        {
            "type": "search_repositories"
        }
    ],
    "input": [
        {
            "role": "user",
            "content": "Give me details of all repositories in github org masaic-ai-platform"
        }
    ]
}'
```

#### Claude Example with Claude Think Tool (Get your [Anthropic key](https://console.anthropic.com/dashboard))

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer ANTHROPIC_API_KEY' \
--header 'x-model-provider: claude' \
--data '{
    "model": "claude-3-7-sonnet-20250219",
    "stream": false,
    "tools": [
       {"type": "think"}
    ],
    "input": [
        {
            "role": "system",
            "content": "You are an experienced system design architect. Use the think tool to cross confirm thoughts before preparing the final answer."
        },
        {
            "role": "user",
            "content": "Give me the guidelines on designing a multi-agent distributed system with the following constraints in mind: 1. compute costs minimal, 2. the system should be horizontally scalable, 3. the behavior should be deterministic."
        }
    ]
}'
```

---

## 4. Start Docker with Custom MCP Configuration

If you have your own MCP servers configuration, follow these steps:

### Update the .env File

Add or update the following property in your `.env` file:

```
MCP_CONFIG_FILE_PATH=path_to_mcp_config_file
```

### Run the Service with the Custom MCP Configuration

Start the service using:

<sub>NOTE: Stop previously running docker-compose (if any) before running this command.</sub>

For macOS:
```bash
docker-compose --profile mcp up open-responses-custom-mcp
```
For Windows:
```bash
docker-compose --profile mcp up open-responses-custom-mcp-windows
```

## 5. Running Example Scripts with the openai-agent-python SDK

You can run examples provided by the openai-agent-python SDK using your locally deployed open-responses API.

### Steps to Run openai-agent-python Examples

1. Start the service using:

<sub>NOTE: Stop previously running docker-compose (if any) before running this command.</sub>
```bash
docker-compose up open-responses-with-openai
```

2. Clone the Forked Repository:

   Start by cloning the repository from the forked version available at [this link](https://github.com/masaic-ai-platform/openai-agents-python/tree/main). Once the repository is cloned, switch to the project's directory using:

```bash
   git clone https://github.com/masaic-ai-platform/openai-agents-python.git
   cd openai-agents-python
```

2. Configure the SDK in Your Python Script:

   To set up the connection details for the SDK, follow these steps:

   • Define the environment variable OPENAI_API_KEY with your OpenAI API key. You can set this in your system environment or directly in [config.py]("https://github.com/masaic-ai-platform/openai-agents-python/blob/main/examples/config.py").

   • Define the environment variable OPEN_RESPONSES_URL to specify the URL for your local open-responses API. If this variable is not set, it will default to "http://localhost:8080/v1".

   • Ensure that these environment variables are properly recognized by your script so that the SDK can initialize the default OpenAI client.

3. Run the Examples:

   • Follow [Get started](https://github.com/masaic-ai-platform/openai-agents-python/tree/main?tab=readme-ov-file#get-started) instructions to install the SDK.

   • Head over to the [examples directory](https://github.com/masaic-ai-platform/openai-agents-python/tree/main/examples) within the repository. Select and run any example script of your choice. Please note that all examples should work as expected except for the [research_bot example](https://github.com/masaic-ai-platform/openai-agents-python/tree/main/examples/research_bot) because agent uses OpenAI's proprietary WebSearchTool.

## 6. Running Agent Examples Built with OpenAI Agent SDK To Use Open Responses API Built In Tools

Before running any example, ensure you are in the openai-agents-python folder.

This section demonstrates how to run examples that create Agents using the OpenAI Agent SDK. These examples showcase a variety of model flavors and custom tools powered by the Open Responses API.

1. Run the Service  
   Start the service with the MCP profile by executing:

   <sub>NOTE: Stop previously running docker-compose (if any) before running this command.</sub>
   1. For macOS:
      ```bash
      docker-compose --profile mcp up open-responses-mcp
      ```  
   2. For Windows:
        ```bash
        docker-compose --profile mcp up open-responses-mcp-windows
        ```
   For more details, please refer to the [Start Docker with Built-In Tools](#3-start-docker-with-built-in-tools) section.

   Note:  
   • If you require SDK traces, ensure that you set the environment variable OPENAI_API_KEY.  
   • Otherwise, you may see a warning "OPENAI_API_KEY is not set, skipping trace export". To disable tracing explicitly, add the statement:
   ```python
   set_tracing_disabled(disabled=False)
   ```

2. Setup the SDK  
   Follow the steps outlined in [Clone the Forked Repository](#5-running-example-scripts-with-the-openai-agent-python-sdk) to set up your environment.  
   • Ensure that you have defined the environment variables:
   - GROK_API_KEY
   - OPEN_RESPONSES_URL  
     These can be set in your environment or configured within a file.

   You can review example scripts under the [open_responses examples](https://github.com/masaic-ai-platform/openai-agents-python/tree/main/examples/open_responses) repository.

3. Run the agent_hands_off.py Example  
   From within the openai-agents-python folder, execute the following command:
   ```bash
   python -m examples.open_responses.agent_hands_off
   ```

4. Run the brave_search_agent_with_groq.py Example  
   From within the openai-agents-python folder, execute:
   ```bash
   python -m examples.open_responses.brave_search_agent_with_groq
   ```

5. Run the brave_search_agent_with_groq_stream Example  
   From within the openai-agents-python folder, execute:
   ```bash
   python -m examples.open_responses.brave_search_agent_with_groq_stream
   ```
   Note: This example requires the CLAUDE_API_KEY along with the OPEN_RESPONSES_URL to be set appropriately. (Refer to step [6](#6-running-agent-examples-with-openai-agent-sdk) for additional details if needed.)

6. Run the think_tool_agent_with_claude.py Example  
   From within the openai-agents-python folder, execute:
   ```bash
   python -m examples.open_responses.think_tool_agent_with_claude
   ```
   Note: This example requires the CLAUDE_API_KEY along with the OPEN_RESPONSES_URL to be set appropriately.

---

Happy coding!
