package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ChatMessage
import com.fasterxml.jackson.databind.JsonNode

/**
 * Sealed class representing the result of processing a data source.
 * Different types of data sources will return different subclasses.
 */
sealed class DataSourceProcessingResult

/**
 * Result for completion-based data sources that produce chat messages.
 * 
 * @property messages Map of chat messages by index
 */
class CompletionMessagesResult(
    val messages: Map<Int, List<ChatMessage>>,
) : DataSourceProcessingResult()

/**
 * Result for JSON data sources that produce structured data.
 * 
 * @property items Map of JSON nodes by index
 */
class JsonlDataResult(
    val items: Map<Int, JsonNode>,
) : DataSourceProcessingResult()

/**
 * Result for when no data could be processed or an unsupported data source was used.
 * 
 * @property reason Explanation of why no data was produced
 */
class EmptyProcessingResult(
    val reason: String,
) : DataSourceProcessingResult() 
