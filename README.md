# Masaic OpenAI API Compatibility Layer

This project provides a compatibility layer that implements OpenAI's API response structure, allowing other Language Model (LLM) providers to expose their functionality through the familiar OpenAI completion API interface.

## Overview

The OpenAI Compatibility Layer acts as a bridge between various LLM providers and applications that are built to work with OpenAI's completion API. By implementing the same API structure and response format as OpenAI's Responses API, this project enables seamless integration with tools and applications that expect OpenAI's Responses API without modifying their codebase.

## Features

- **OpenAI API Compatibility**: Implements OpenAI's API endpoints and response structure
- **Tool Integration**: Supports various tools including web search and file search
- **Streaming Responses**: Supports both streaming and non-streaming response formats
- **Input/Output Management**: Handles a variety of input formats and generates appropriate outputs
- **Flexible Configuration**: Supports various parameters for customizing model responses

## API Endpoints

The API implements the following OpenAI-compatible endpoints:

- `POST /v1/responses` - Create a new model response
- `GET /v1/responses/{responseId}` - Retrieve a specific response
- `DELETE /v1/responses/{responseId}` - Delete a response
- `GET /v1/responses/{responseId}/input_items` - List input items for a response

## Getting Started

### Prerequisites

- JDK 21 or higher
- Gradle

### Building the Project

```bash
./gradlew build
```

### Running the Application

```bash
./gradlew bootRun
```

The API will be available at `http://localhost:8080`.

### Docker Support

You can also run the application using Docker:

#### Build the Docker Image

```bash
docker build -t openai-compatibility-layer .
```

#### Run the Docker Container

```bash
docker run -p 8080:8080 openai-compatibility-layer
```

### Example Usage

Create a response using the API:

```bash
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "your-model",
    "input": [
      {
        "role": "user",
        "content": "Hello, how can you help me today?"
      }
    ],
    "stream": false
  }'
```

## Project Structure

- `/api/controller` - API endpoint definitions
- `/api/model` - Data models and structures
- `/api/service` - Business logic implementation
- `/api/client` - Client implementations for various LLM providers
- `/tool` - Implementation of tool functionalities

## Dependencies

- Spring Boot Webflux
- Kotlin Coroutines
- Jackson for JSON processing
- OpenAI Java SDK
- LangChain4j

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## MCP Servers Configured:
1. Brave search - https://github.com/modelcontextprotocol/servers/tree/main/src/brave-search
2. Github - https://github.com/modelcontextprotocol/servers/tree/main/src/github
3. Browser-use - https://github.com/co-browser/browser-use-mcp-server