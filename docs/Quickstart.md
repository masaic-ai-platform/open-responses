# Quick Start Guide

Follow this guide to run the service in under 5 minutes using one of the following flows:

1. Getting Started (Clone repo and quick setup without additional tool overhead)
2. Starting Docker with Built-In Tools
3. Starting Docker with a Custom MCP Configuration
4. Starting Docker with Custom MCP Configuration
5. Running example scripts with the openai-agent-python SDK

---

## 1. Getting Started

### Clone the Repository and Navigate to the Project Directory

Begin by cloning the repository and entering its directory:

```bash
git clone https://github.com/masaic-ai-platform/api-draft.git
cd api-draft
```
---

## 2. Quick Setup

### Pre-requisites

- Ensure port **8080** is available.
- Docker daemon must be running on your local machine.

### Run the Service

Start the service using Docker Compose with the default configuration:

```bash
docker-compose up
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

```bash
docker-compose --profile mcp up open-responses-mcp
```

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

#### Groq Example with Claude Think Tool (Get your [Anthropic key](https://console.anthropic.com/dashboard))

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

```bash
docker-compose --profile mcp up open-responses-custom-mcp
```


## 5. Running Example Scripts with the openai-agent-python SDK

You can run examples provided by the openai-agent-python SDK using your locally deployed open-responses API.

### Steps to Run openai-agent-python Examples

1. Start the service using:
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

   Head over to the [examples directory](https://github.com/masaic-ai-platform/openai-agents-python/tree/main/examples) within the repository. Select and run any example script of your choice. Please note that all examples should work as expected except for the [research_bot example](https://github.com/masaic-ai-platform/openai-agents-python/tree/main/examples/research_bot)
because agent uses OpenAI's proprietary WebSearchTool.

---

Happy coding!
