# Masaic OpenAI API Compatibility Layer

This project provides a compatibility layer that implements OpenAI's API response structure, allowing other Language Model (LLM) providers to expose their functionality through the familiar OpenAI completion API interface.

## Overview

The OpenAI Compatibility Layer acts as a bridge between various LLM providers and applications that are built to work with OpenAI's completion API. By implementing the same API structure and response format as OpenAI's Responses API, this project enables seamless integration with tools and applications that expect OpenAI's Responses API without modifying their codebase.

## API Endpoints

The API implements the following OpenAI-compatible endpoints:

- `POST /v1/responses` - Create a new model response
- `GET /v1/responses/{responseId}` - Retrieve a specific response
- `DELETE /v1/responses/{responseId}` - Delete a response
- `GET /v1/responses/{responseId}/input_items` - List input items for a response

## Setup with Docker

You can run the application using Docker:

### Build the Docker Image

```bash
docker build -t openai-compatibility-layer .
```

### Run the Docker Container

```bash
docker run -p 8080:8080 openai-compatibility-layer
```

The API will be available at `http://localhost:8080`.

## API Examples

### Create a Response

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

### Create a Streaming Response

```bash
curl -X POST http://localhost:8080/v1/responses \
  -H "Content-Type: application/json" \
  -d '{
    "model": "your-model",
    "input": [
      {
        "role": "user",
        "content": "Write a short story about a robot."
      }
    ],
    "stream": true
  }'
```

### Retrieve a Response

```bash
curl -X GET http://localhost:8080/v1/responses/{responseId}
```

### Delete a Response

```bash
curl -X DELETE http://localhost:8080/v1/responses/{responseId}
```

## Configuration

The application supports the following environment variables:

- `MCP_SERVER_CONFIG_FILE_PATH`: Path to MCP server configuration
- `MASAIC_MAX_TOOL_CALLS`: Maximum number of allowed tool calls (default: 10)
- `MASAIC_MAX_STREAMING_TIMEOUT`: Maximum streaming timeout in milliseconds (default: 60000)

## MCP Servers Configured

The following MCP servers are configured by default:

1. Brave search - https://github.com/modelcontextprotocol/servers/tree/main/src/brave-search
2. Github - https://github.com/modelcontextprotocol/servers/tree/main/src/github
3. Browser-use - https://github.com/co-browser/browser-use-mcp-server

## Docker build steps
1. docker build --tag open-responses -f Dockerfile .
2. docker run
```
docker run \
-v /var/run/docker.sock:/var/run/docker.sock \                                  
-p 8080:8080 \
-e GITHUB_TOKEN=GITHUB_TOKEN_VALUE \
-e OPENAI_API_KEY=OPENAI_API_KEY_VALUE \
-e BRAVE_API_KEY=BRAVE_API_KEY_VALUE \
-e MCP_SERVER_CONFIG_FILE_PATH=/app/mcp-servers-config.json open-responses

```