<p align="center">
  <img src="https://img.shields.io/badge/status-active-success.svg" alt="Status">
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

<p align="center">
  <em>"One API to rule them all, one API to find them, one API to bring them all, and in the cloud bind them."</em>
</p>

## 🌟 Overview

OpenResponses API empowers developers to leverage the incredible capabilities of various LLM providers through a familiar interface - the OpenAI Responses API structure. This API layer bridges the gap between different LLM providers and applications built on OpenAI's completion API by following the [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses) specifications.

> **"Simplicity is the ultimate sophistication."** — Leonardo da Vinci

With OpenResponses API, you can:
- 🔄 Use the same code to work with multiple LLM providers
- 🛠️ Easily swap between models without changing your application code
- 🚀 Leverage the full power of each provider's unique features
- 🧩 Build with a standardized API that works across the AI ecosystem

## 🛠️ Advanced Features

### Built-in Tools Support

OpenResponses API comes with support for various tools including:

- 🔍 **Brave Web Search**: Integrate real-time search capabilities
- 📂 **GitHub Repositories Search**: Access GitHub data directly
- 🧠 **Claude Think Tool**: Enable more thoughtful responses

## 🤔 Why OpenResponses?

### One Interface, Multiple Providers
Stop maintaining different codebases for each LLM provider. OpenResponses provides a way to access to multiple models through a standardized interface.

### Cost Optimization
Easily switch between models to optimize for cost, performance, or features - with just a parameter change.

### Risk Mitigation
Eliminate vendor lock-in by having the flexibility to switch providers when needed without code changes.

### Extended Capabilities
Access unique features like streaming responses, built-in tools(currently supported with MCP servers - tool discovery and execution), function calling.

## ✨ API Endpoints

The API implements the following OpenAI-compatible endpoints:

| Endpoint | Description |
|----------|-------------|
| `POST /v1/responses` | Create a new model response | 
| `GET /v1/responses/{responseId}` | Retrieve a specific response | 
| `DELETE /v1/responses/{responseId}` | Delete a response | 
| `GET /v1/responses/{responseId}/input_items` | List input items for a response | 

## 🚀 Quick Start

- Please follow our step-by-step guide here : [Quick Start Guide](docs/Quickstart.md)

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

## 📊 API Comparison

| Feature                     | OpenAI Responses API      | OpenResponses API                       | 
|-----------------------------|---------------------------|-----------------------------------------|
| Streaming                   | ✅                         | ✅                                       |
| Function Calling            | ✅                         | ✅                                       |
| Max Context                 | 200k                      | Model dependent                         |
| Models supported            | Only OpenAI models        | Any Model compliant with completion API | 
| Hosted Tools                | Limited                   | MCP, BYOT, In-built etc.                | 
| Inbuilt Tracing             | Limited                   | Comprehensive coming soon               |
| Data privacy                | ❌                         | ✅                                       |
| OpenAI Agent SDK Compliance | ✅ with only OpenAI models | ✅ with multiple Model providers         |

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

## 🔮 Coming Soon

We're continuously working to enhance OpenResponses API with powerful new features.

## ❓ Frequently Asked Questions

### Can I use my existing provider API keys?
Yes! OpenResponses acts as a pass-through to the provider APIs using your own keys.

### Is there any performance penalty?
Our benchmarks show minimal overhead (30-50ms) compared to direct API calls.

### How do I handle errors?
OpenResponses standardizes error responses across providers:
```json
{
  "type": "rate_limit_exceeded",
  "message": "Rate limit exceeded. Please try again in 30 seconds.",
  "param": null,
  "code": "rate_limit"
}
```

## ⚙️ Configuration

The application supports the following environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_SERVER_CONFIG_FILE_PATH` | Path to MCP server configuration | - |
| `MASAIC_MAX_TOOL_CALLS` | Maximum number of allowed tool calls | 10 |
| `MASAIC_MAX_STREAMING_TIMEOUT` | Maximum streaming timeout in ms | 60000 |


## 📚 Documentation

For more detailed information about using OpenResponses API via Completion API, check out our documentation:

- [OpenAI Compatibility Guide](docs/OpenAICompatibility.md)

## 💻 Local Development

Follow these instructions to set up the project locally for development:

### Prerequisites

- Java JDK 21+
- [Gradle](https://gradle.org/) (optional, as project includes Gradle Wrapper)
- [Docker](https://www.docker.com/) (optional, for containerized setup)

### Steps

1. **Clone the repository**

```bash
git clone https://github.com/masaic-ai-platform/open-responses.git
cd open-responses
```

2. **Build the project**

Use the Gradle Wrapper included in the project:

```bash
./gradlew build
```

3. **Configure Environment Variables**

Create or update the `application.properties` file with necessary configuration under `src/main/resources`:

```properties
server.port: 8080
```

Set any additional configuration required by your project.

4. **Run the server**

To start the server in development mode:

```bash
./gradlew bootRun
```

### Docker Setup (Optional)

Build and run the application using Docker:

```bash
./gradlew build
docker build -t openresponses .
docker run -p 8080:8080 -d openresponses
```

### Testing

Run the tests with:

```bash
./gradlew test
```

## ⚠️ Production Use

>  **Alpha Release Disclaimer**: This project is currently in alpha stage. The API and features are subject to breaking changes as we continue to evolve and improve the platform. While we strive to maintain stability, please be aware that updates may require modifications to your integration code.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

> **"Alone we can do so little; together we can do so much."** — Helen Keller

## 📄 License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

---

<p align="center">
  Made with ❤️ by the Masaic AI Team
</p>
