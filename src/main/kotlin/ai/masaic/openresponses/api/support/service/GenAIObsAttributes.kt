package ai.masaic.openresponses.api.support.service

/**
 * Constants for GenAI observation attributes used in metrics and monitoring.
 */
object GenAIObsAttributes {
    const val OPERATION_NAME = "gen_ai.operation.name"
    const val TOOL_NAME = "gen_ai.tool.name"
    const val TOOL_DESCRIPTION = "gen_ai.tool.description"
    const val SYSTEM = "gen_ai.system"
    const val REQUEST_MODEL = "gen_ai.request.model"
    const val REQUEST_TEMPERATURE = "gen_ai.request.temperature"
    const val REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens"
    const val REQUEST_TOP_P = "gen_ai.request.top_p"
    const val RESPONSE_ID = "gen_ai.response.id"
    const val RESPONSE_MODEL = "gen_ai.response.model"
    const val RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons"
    const val USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"
    const val USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
    const val OUTPUT_TYPE = "gen_ai.output.type"
    const val ERROR_TYPE = "error.type"
    const val SERVER_ADDRESS = "server.address"
    const val SERVER_PORT = "server.port"
    const val SPAN_KIND = "span.kind"
    const val OPERATION_DURATION = "gen_ai.client.operation.duration"
    const val TOOL_CALL_ID = "gen_ai.tool.call.id"
    const val USER_MESSAGE = "gen_ai.user.message"
    const val ASSISTANT_MESSAGE = "gen_ai.assistant.system"
    const val TOOL_MESSAGE = "gen_ai.tool.message"
    const val SYSTEM_MESSAGE = "gen_ai.system.message"
    const val CHOICE = "gen_ai.choice"
    const val CLIENT_TOKEN_USAGE = "gen_ai.client.token.usage"
}

object OpenResponsesObsAttributes {
    // File operations
    const val FILE_OPERATION = "open-responses.file.operation"
    const val FILE_ID = "open-responses.file.id"
    const val FILE_NAME = "open-responses.file.name"
    const val FILE_SIZE = "open-responses.file.size"
    const val FILE_OPERATION_DURATION = "open-responses.file.operation.duration"
    const val FILE_PURPOSE = "open-responses.file.purpose"

    // Vector store operations
    const val VECTOR_STORE_OPERATION = "open-responses.vector_store.operation"
    const val VECTOR_STORE_ID = "open-responses.vector_store.id"
    const val VECTOR_STORE_OPERATION_DURATION = "open-responses.vector_store.operation.duration"

    // Search operations
    const val SEARCH_OPERATION = "open-responses.search.operation"
    const val SEARCH_QUERY = "open-responses.search.query"
    const val SEARCH_RESULTS_COUNT = "open-responses.search.results.count"
    const val SEARCH_DURATION = "open-responses.search.duration"
    const val SEARCH_DOCUMENT_IDS = "open-responses.search.document_ids"
    const val SEARCH_CHUNK_IDS = "open-responses.search.chunk_ids"
    const val SEARCH_TOP_SCORE = "open-responses.search.top_score"
    const val SEARCH_AVG_SCORE = "open-responses.search.avg_score"
}
