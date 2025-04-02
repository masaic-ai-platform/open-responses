# Response Store Configuration

The Open Responses API provides a configurable storage mechanism for OpenAI API responses and their associated input items. This document explains the available storage options and how to configure them.

## Available Storage Implementations

The API supports two storage implementations:

1. **In-Memory Store** (default): Stores responses in memory using concurrent hash maps.
2. **MongoDB Store**: Persists responses in a MongoDB database.

## Configuration Options

### In-Memory Store (Default)

The in-memory store is enabled by default and requires no additional configuration. It's suitable for development and testing environments, but data will be lost when the application restarts.

To explicitly configure the in-memory store:

```properties
app.response-store.type=in-memory
```

### MongoDB Store

For production environments where persistence is required, the MongoDB store provides durable storage of response data.

To enable the MongoDB store:

1. Add the following properties to your configuration:

```properties
app.response-store.type=mongodb
spring.data.mongodb.uri=mongodb://localhost:27017
spring.data.mongodb.database=openresponses
```

2. Ensure you have MongoDB installed and running, or provide the connection string to your MongoDB instance.

## Using Environment Variables

You can also configure the response store using environment variables:

```bash
# Set the response store type
export APP_RESPONSE_STORE_TYPE=mongodb

# Configure MongoDB (when using mongodb store type)
export SPRING_DATA_MONGODB_URI=mongodb://localhost:27017
export SPRING_DATA_MONGODB_DATABASE=openresponses
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

To store a response (setting `store=true` in the request):

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

To reference a previously stored response with `previous_response_id`:

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

## Usage Example

Python OpenAI SDK example can be found here [example](https://github.com/masaic-ai-platform/openai-agents-python/blob/main/examples/open_responses/conversation_state.py)

The application automatically selects the appropriate store implementation based on your configuration. The `ResponseStore` interface is injected where needed, allowing the application to work with either implementation transparently.

## Considerations

- **In-Memory Store**: Fast but non-persistent. Data is lost on application restart.
- **MongoDB Store**: Provides persistence but requires additional infrastructure.
- The MongoDB implementation stores responses as JSON strings to accommodate potential changes in the OpenAI API response structure. 
