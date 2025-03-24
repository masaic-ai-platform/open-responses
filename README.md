# 🚀 OpenResponses API

<p align="center">
  <img src="https://img.shields.io/badge/status-active-success.svg" alt="Status">
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

<p align="center">
  <em>"One API to rule them all, one API to find them, one API to bring them all, and in the cloud bind them."</em>
</p>

## 🌟 Overview

OpenResponses API empowers developers to leverage the incredible capabilities of various LLM providers through a familiar interface - the OpenAI API structure. This compatibility layer bridges the gap between different LLM providers and applications built for OpenAI's completion API.

> **"Simplicity is the ultimate sophistication."** — Leonardo da Vinci

With OpenResponses API, you can:
- 🔄 Use the same code to work with multiple LLM providers
- 🛠️ Easily swap between models without changing your application code
- 🚀 Leverage the full power of each provider's unique features
- 🧩 Build with a standardized API that works across the AI ecosystem

## ✨ API Endpoints

The API implements the following OpenAI-compatible endpoints:

| Endpoint | Description |
|----------|-------------|
| `POST /v1/responses` | Create a new model response |
| `GET /v1/responses/{responseId}` | Retrieve a specific response |
| `DELETE /v1/responses/{responseId}` | Delete a response |
| `GET /v1/responses/{responseId}/input_items` | List input items for a response |

## 🚀 Quick Start

### Using Docker

```bash
# Clone the repository
git clone https://github.com/masaic-ai-platform/api-draft.git
cd api-draft

# Start the service with Docker Compose
docker-compose up
```

### Example API Calls

Replace the placeholder API keys with your own values.

#### OpenAI Example
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
            "content": "Write a poem on OpenResponses"
        }
    ]
}'
```

#### Groq Example (with Streaming)
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
            "content": "Write a poem on OpenResponses"
        }
    ]
}'
```

#### Claude Example
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
            "content": "Write a poem on OpenResponses"
        }
    ]
}'
```

## 🛠️ Advanced Features

### Built-in Tools Support

OpenResponses API comes with support for various tools including:

- 🔍 **Brave Web Search**: Integrate real-time search capabilities
- 📂 **GitHub Repositories Search**: Access GitHub data directly
- 🧠 **Claude Think Tool**: Enable more thoughtful responses

### Using Tools in API Calls

```bash
curl --location 'http://localhost:8080/v1/responses' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer YOUR_API_KEY' \
--data '{
    "model": "your-model",
    "stream": false,
    "tools": [
        {
            "type": "brave_web_search"
        }
    ],
    "input": [
        {
            "role": "user",
            "content": "What are the latest developments in AI?"
        }
    ]
}'
```

## ⚙️ Configuration

The application supports the following environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_SERVER_CONFIG_FILE_PATH` | Path to MCP server configuration | - |
| `MASAIC_MAX_TOOL_CALLS` | Maximum number of allowed tool calls | 10 |
| `MASAIC_MAX_STREAMING_TIMEOUT` | Maximum streaming timeout in ms | 60000 |
| `GITHUB_TOKEN` | GitHub Personal Access Token | - |
| `BRAVE_API_KEY` | Brave Search API Key | - |

## 📚 Documentation

For more detailed information about using OpenResponses API, check out our documentation:

- [OpenAI Compatibility Guide](docs/OpenAICompatibility.md)
- [Quick Start Guide](docs/Quickstart.md)

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

> **"Alone we can do so little; together we can do so much."** — Helen Keller

## 📄 License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

---

<p align="center">
  Made with ❤️ by the Masaic AI Team
</p>