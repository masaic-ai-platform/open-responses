package ai.masaic.openresponses.api.client

/**
 * Constants for GenAI observation attributes used in metrics and monitoring.
 */
object GernAIObsAttributes {
    const val OPERATION_NAME = "gen_ai.operation.name"
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
} 