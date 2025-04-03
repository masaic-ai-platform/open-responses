# Response Store Configuration

The Open Responses API provides a configurable storage mechanism for OpenAI API responses and their associated input items. This document explains the available storage options and how to configure them.

## Value Addition Compared to OpenAI Managed Service

Unlike OpenAI's managed service, our Response Store implementation offers:

1. **Complete Data Control**: Store all your conversation data on your own infrastructure, ensuring data sovereignty and compliance with your organization's data policies.
2. **Flexible Storage Options**: Choose between in-memory storage for development or MongoDB for persistent storage in production environments.
3. **Customizable Retention**: Define your own data retention policies without being constrained by OpenAI's limits.
4. **Integration Flexibility**: Easily integrate with your existing systems and data pipelines for analytics, auditing, or other business processes.
5. **Cost Management**: Potentially reduce costs by optimizing storage and retrieval according to your specific usage patterns.

## Controlling Response Storage with the `store` Attribute

The API specification includes a `store` boolean attribute that explicitly controls whether response history is stored:

- When `store=true` (explicitly set in the request), the response and its associated inputs are persisted in the configured store.
- When `store=false` or omitted, no data is stored, regardless of the configured store implementation.

**Important**: Response history is only stored when the `store` property is explicitly set to `true` in the API request. This gives you fine-grained control over which interactions should be preserved.

## Available Storage Implementations

The API supports two storage implementations:

1. **In-Memory Store** (default): Stores responses in memory using concurrent hash maps.
2. **MongoDB Store**: Persists responses in a MongoDB database.

## Configuration Options

### In-Memory Store (Default)

The in-memory store is enabled by default and requires no additional configuration. It's suitable for development and testing environments, but data will be lost when the application restarts.

To explicitly configure the in-memory store:

```properties
open-responses.response-store.type=in-memory
```

### MongoDB Store

For production environments where persistence is required, the MongoDB store provides durable storage of response data.

To enable the MongoDB store:

1. Add the following properties to your configuration:

```properties
open-responses.response-store.type=mongodb
open-response.mongodb.uri=mongodb://localhost:27017
open-response.mongodb.database=openresponses
```

2. Ensure you have MongoDB installed and running, or provide the connection string to your MongoDB instance.

## Using Environment Variables

You can also configure the response store using environment variables:

```bash
# Set the response store type
export OPEN_RESPONSES_RESPONSE_STORE_TYPE=mongodb

# Configure MongoDB (when using mongodb store type)
export OPEN_RESPONSES_MONGODB_URI=mongodb://localhost:27017
export OPEN_RESPONSES_MONGODB_DATABASE=openresponses
```

Spring Boot automatically converts environment variables by:
1. Converting to uppercase
2. Replacing dots (.) with underscores (_)
3. Converting from kebab-case to snake_case

## Data Structure

Both storage implementations manage:
- Response objects from the OpenAI API
- Input message items associated with each response
- Automatic serialization/deserialization of response data

## API Examples

### Storing Responses

The following example demonstrates storing a response by setting `store=true` in the request:

```bash
curl <open-responses-base-url>/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4o",
    "input": "Tell me a three sentence bedtime story about a unicorn.",
    "store": true
  }'
```

### Using Stored Responses

To reference a previously stored response with `previous_response_id` (note that the original response must have been stored with `store=true`):

```bash
curl <open-responses-base-url>/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4o",
    "previous_response_id": "resp_abc123",
    "input": [{"role": "user", "content": "Make the story longer."}],
    "store": true
  }'
```

### Example Without Storage

If you don't need to store a response (for ephemeral interactions), omit the `store` attribute or set it to `false`:

```bash
curl <open-responses-base-url>/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4o",
    "input": "What's the capital of France?",
    "store": false
  }'
```

## Usage Example

Python OpenAI SDK example can be found here [example](https://github.com/masaic-ai-platform/openai-agents-python/blob/main/examples/open_responses/conversation_state.py)

## Considerations

- **In-Memory Store**: Fast but non-persistent. Data is lost on application restart.
- **MongoDB Store**: Provides persistence but requires additional infrastructure.
- The MongoDB implementation stores responses as JSON strings to accommodate potential changes in the OpenAI API response structure. 
