# Quick Start Guide

Follow this guide to run the service in under 5 minutes. The setup flow is as follows:

1. Quick setup without any additional tool overhead.
2. Start Docker with built-in tools.
3. Start Docker with a custom MCP configuration.

---

## 1. Quick Setup

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

## 2. Start Docker with Built-In Tools

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

## 3. Start Docker with Custom MCP Configuration

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

---

This guide covers all three flows:

1. Quick setup without additional tool overhead.
2. Running with built-in MCP tools.
3. Running with a custom MCP configuration.

Happy coding!