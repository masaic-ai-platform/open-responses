# OpenResponses API

![OpenResponses - All-in-One AI Toolkit](static/About.png)

> **Unlock enterprise-grade AI capabilities through a single, powerful API — simplify development, accelerate deployment, and maintain complete data control**

[![Discord](https://img.shields.io/static/v1?label=Discord&message=Join%20Us&color=5865F2&logo=discord&logoColor=white)](https://discord.com/channels/1335132819260702723/1354795442004820068)
[![Discussions](https://img.shields.io/static/v1?label=Discussions&message=Community&color=3FB950&logo=github&logoColor=white)](https://github.com/orgs/masaic-ai-platform/discussions)

## Overview

OpenResponses revolutionizes how developers build AI applications by providing a comprehensive, production-ready toolkit with essential enterprise features—all through an elegantly simplified API interface. Stop cobbling together disparate tools and start building what matters.

## 🚀 Getting Started

Run OpenResponses locally to access an OpenAI-compatible API that works seamlessly with multiple model providers and supports unlimited tool integrations. Deploy a complete AI infrastructure on your own hardware with full data sovereignty.

### Run with Docker

```bash
docker run -p 8080:8080 masaicai/open-responses:latest
```

### Using with OpenAI SDK

```python
openai_client = OpenAI(base_url="http://localhost:8080/v1", api_key=os.getenv("OPENAI_API_KEY"), default_headers={'x-model-provider': 'openai'})

response = openai_client.responses.create(
    model="gpt-4o-mini",
    input="Write a poem on Masaic"
)
```

### Using with OpenAI Agent SDK

```python
client = AsyncOpenAI(base_url="http://localhost:8080/v1", api_key=os.getenv("OPENAI_API_KEY"), default_headers={'x-model-provider': 'openai'})
agent = Agent(
    name="Assistant",
    instructions="You are a humorous poet who can write funny poems of 4 lines.",
    model=OpenAIResponsesModel(model="gpt-4o-mini", openai_client=client)
)
```

### Using with cURL

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
For detailed implementation instructions, see our [Quick Start Guide](https://openresponses.masaic.ai/quickstart).

## Core Capabilities

| Feature | Description | Benefit |
|---------|-------------|---------|
| **Automated Tracing** | Comprehensive request and response monitoring | Track performance and usage without additional code |
| **Integrated RAG** | Contextual information retrieval | Enhance responses with relevant external data automatically |
| **Pre-built Tool Integrations** | Web search, GitHub access, and more | Deploy advanced capabilities instantly |
| **Self-Hosted Architecture** | Full control of deployment infrastructure | Maintain complete data sovereignty |
| **OpenAI-Compatible Interface** | Drop-in replacement for existing OpenAI implementations | Minimal code changes for migration |

## API Reference

The API implements the following OpenAI-compatible endpoints:

| Endpoint | Description |
|----------|-------------|
| `POST /v1/responses` | Create a new model response | 
| `GET /v1/responses/{responseId}` | Retrieve a specific response | 
| `DELETE /v1/responses/{responseId}` | Delete a response | 
| `GET /v1/responses/{responseId}/input_items` | List input items for a response | 

## 📚 Documentation

Explore our comprehensive [documentation](https://openresponses.masaic.ai/) to learn more about OpenResponses features and setup.

## ⚠️ Production Use

>  **Alpha Release Disclaimer**: This project is currently in alpha stage. The API and features are subject to breaking changes as we continue to evolve and improve the platform. While we strive to maintain stability, please be aware that updates may require modifications to your integration code.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

Before submitting a PR, please:

1. Run the regression test suite to ensure your changes don't break existing functionality:
   ```bash
   # Run the common regression tests
   ./regression_common.sh
   
   # Run the vector store regression tests
   ./regression_vector.sh
   ```

2. Make sure all tests pass successfully before creating your pull request.

> **"Alone we can do so little; together we can do so much."** — Helen Keller

## 📄 License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

---

<p align="center">
  Made with ❤️ by the Masaic AI Team
</p>
