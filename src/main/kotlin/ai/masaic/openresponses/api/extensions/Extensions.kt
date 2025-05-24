package ai.masaic.openresponses.api.extensions

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText

/**
 * Extension function to copy properties from a ResponseCreateParams.Body to a ResponseCreateParams.Builder.
 * This helps simplify the process of creating request parameters from request bodies.
 *
 * @param body The request body to copy properties from
 * @return The builder with properties copied from the body
 */
suspend fun ResponseCreateParams.Builder.fromBody(
    body: ResponseCreateParams.Body,
    responseStore: ResponseStore,
    objectMapper: ObjectMapper,
): ResponseCreateParams.Builder {
    // Set required parameters
    if (body.previousResponseId().isPresent) {
        responseStore.getResponse(body.previousResponseId().get()) ?: throw ResponseNotFoundException("Previous response not found")
        val previousInputItems =
            responseStore
                .getInputItems(body.previousResponseId().get())
                .map {
                    objectMapper.convertValue(it, ResponseInputItem::class.java)
                }.toMutableList()
        val previousResponseOutputItems = responseStore.getOutputItems(body.previousResponseId().get())
        val currentInputItems =
            if (body.input().isResponse()) {
                body.input().asResponse().toMutableList()
            } else {
                mutableListOf(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .content(body.input().asText())
                            .role(
                                EasyInputMessage.Role.USER,
                            ).build(),
                    ),
                )
            }

        previousInputItems.addAll(previousResponseOutputItems.map { objectMapper.convertValue(it, ResponseInputItem::class.java) })
        previousInputItems.addAll(currentInputItems)
        input(ResponseCreateParams.Input.ofResponse(removeImageBody(previousInputItems)))
    } else {
        input(body.input())
    }

    // Extract model name from request
    val modelName =
        if (body.model().isChat()) {
            body
                .model()
                .chat()
                .get()
                .toString()
        } else {
            body.model().string().get()
        }

    // If model contains url@model format, update the model name to just the model part
    if (modelName.contains("@") == true) {
        val parts = modelName.split("@", limit = 2)
        if (parts.size == 2) {
            model(parts[1])
        }
    } else {
        model(modelName)
    }

    // Set optional parameters
    instructions(body.instructions())
    reasoning(body.reasoning())
    parallelToolCalls(body.parallelToolCalls())
    maxOutputTokens(body.maxOutputTokens())
    include(body.include())
    metadata(body.metadata())
    store(body.store())
    temperature(body.temperature())
    topP(body.topP())
    truncation(body.truncation())
    previousResponseId(body.previousResponseId())

    // Set additional properties
    additionalBodyProperties(body._additionalProperties())

    // Set optional parameters that use Optional
    body.text().ifPresent { text(it) }
    body.user().ifPresent { user(it) }
    body.toolChoice().ifPresent { toolChoice(it) }
    body.tools().ifPresent { tools(it) }

    return this
}

/**
 * Data class to hold image format information
 */
data class ImageInfo(
    val format: String,
    val isImage: Boolean,
)

/**
 * Function to detect if content is a valid image based on base64 pattern and image signatures
 */
fun isImageContent(content: String): ImageInfo {
    if (content.isBlank() || content.length <= 5000) {
        return ImageInfo("", false)
    }
    
    // Extract potential base64 content from various formats
    val base64Content = extractBase64Content(content.trim())
    
    if (base64Content.isBlank() || base64Content.length <= 5000) {
        return ImageInfo("", false)
    }
    
    // Validate that the extracted content is valid base64
    val base64Pattern = Regex("^[A-Za-z0-9+/]*={0,2}$")
    if (!base64Pattern.matches(base64Content)) {
        return ImageInfo("", false)
    }
    
    // Check image format signatures on the extracted base64 content
    return when {
        base64Content.startsWith("/9j/") || base64Content.startsWith("FFD8") || base64Content.startsWith("/9j") ->
            ImageInfo("JPEG", true)
        base64Content.startsWith("iVBORw0KGgo") || base64Content.startsWith("89504E47") || base64Content.startsWith("iVBORw") ->
            ImageInfo("PNG", true)
        base64Content.startsWith("UklGR") || base64Content.startsWith("UklGRg") ->
            ImageInfo("WebP", true)
        base64Content.startsWith("R0lGODlh") || base64Content.startsWith("R0lGODdh") || base64Content.startsWith("R0lGOD") ->
            ImageInfo("GIF", true)
        // Also check if signatures appear anywhere in the content (for malformed base64)
        base64Content.contains("/9j/") || base64Content.contains("FFD8") ->
            ImageInfo("JPEG", true)
        base64Content.contains("iVBORw0KGgo") || base64Content.contains("89504E47") || base64Content.contains("iVBORw") ->
            ImageInfo("PNG", true)
        base64Content.contains("UklGR") || base64Content.contains("UklGRg") ->
            ImageInfo("WebP", true)
        base64Content.contains("R0lGODlh") || base64Content.contains("R0lGODdh") || base64Content.contains("R0lGOD") ->
            ImageInfo("GIF", true)
        else -> ImageInfo("", false)
    }
}

/**
 * Extracts base64 content from various formats:
 * - data:image/jpeg;base64,<base64>
 * - https://example.com/path?data=<base64>
 * - base64:<base64>
 * - raw <base64>
 */
private fun extractBase64Content(content: String): String {
    return when {
        // Handle data URI scheme: data:image/jpeg;base64,<base64>
        content.startsWith("data:") -> {
            val commaIndex = content.indexOf(',')
            if (commaIndex != -1 && commaIndex < content.length - 1) {
                content.substring(commaIndex + 1)
            } else {
                content
            }
        }
        
        // Handle explicit base64 prefix: base64:<base64>
        content.startsWith("base64:", ignoreCase = true) -> {
            content.substring(7)
        }
        
        // Handle URL with base64 parameter: https://...?data=<base64> or similar
        content.startsWith("http") -> {
            // Look for common parameter names that might contain base64
            val patterns = listOf("data=", "image=", "content=", "base64=")
            for (pattern in patterns) {
                val index = content.indexOf(pattern, ignoreCase = true)
                if (index != -1) {
                    val start = index + pattern.length
                    val end = content.indexOf('&', start).let { if (it == -1) content.length else it }
                    val extracted = content.substring(start, end)
                    // If the extracted content looks like base64, return it
                    if (extracted.length > 100 && extracted.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))) {
                        return extracted
                    }
                }
            }
            // If no parameters found, check if the path itself might be base64
            val pathStart = content.indexOf("://")
            if (pathStart != -1) {
                val pathContent = content.substring(pathStart + 3)
                val slashIndex = pathContent.indexOf('/')
                if (slashIndex != -1) {
                    val afterDomain = pathContent.substring(slashIndex + 1)
                    // Remove query parameters if any
                    val queryIndex = afterDomain.indexOf('?')
                    val pathOnly = if (queryIndex != -1) afterDomain.substring(0, queryIndex) else afterDomain
                    if (pathOnly.length > 100 && pathOnly.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))) {
                        return pathOnly
                    }
                }
            }
            ""
        }
        
        // Handle other prefixes by looking for the longest valid base64 substring
        else -> {
            // Try to find the start of base64 content
            val possibleStarts = listOf(":", "=", ",", " ", "\t", "\n")
            for (delimiter in possibleStarts) {
                val index = content.indexOf(delimiter)
                if (index != -1 && index < content.length - 100) {
                    val candidate = content.substring(index + 1).trim()
                    if (candidate.length > 100 && candidate.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))) {
                        return candidate
                    }
                }
            }
            // If no delimiters work, try the content as-is
            content
        }
    }
}

fun ResponseCreateParams.Builder.removeImageBody(items: List<ResponseInputItem>): List<ResponseInputItem> {
    // Take all function and function output items
    val imageFunctionIds = items.filter { it.isFunctionCall() && it.asFunctionCall().name() == "image_generation" }.map { it.asFunctionCall().callId() }.toSet()

    val newItems = mutableListOf<ResponseInputItem>()

    items.forEachIndexed { index, it ->
        if (it.isFunctionCallOutput() && imageFunctionIds.contains(it.asFunctionCallOutput().callId())) {
            // Check if the output content is an image
            val outputContent = it.asFunctionCallOutput().output()
            val imageInfo = isImageContent(outputContent)
            val builder = it.asFunctionCallOutput().toBuilder()
            
            if (imageInfo.isImage) {
                builder.output("<${imageInfo.format}>...")
            } else {
                builder.output("<image>...")
            }
            newItems.add(ResponseInputItem.ofFunctionCallOutput(builder.build()))
        } else if (it.isEasyInputMessage() &&
            it.asEasyInputMessage().content().isResponseInputMessageContentList()
        ) {
            // Check if any content in the list is an image and replace only those items
            val contentList = it.asEasyInputMessage().content().asResponseInputMessageContentList()
            val newContentList = mutableListOf<ResponseInputContent>()
            var hasAnyReplacement = false
            
            for (contentItem in contentList) {
                if (contentItem.isInputText()) {
                    val textContent = contentItem.asInputText().text()
                    val imageInfo = isImageContent(textContent)
                    if (imageInfo.isImage) {
                        // Replace this specific image content
                        newContentList.add(
                            ResponseInputContent.ofInputText(
                                contentItem
                                    .asInputText()
                                    .toBuilder()
                                    .text("<${imageInfo.format}>...")
                                    .build(),
                            ),
                        )
                        hasAnyReplacement = true
                    } else if (contentItem.asInputText()._additionalProperties()["type"].toString() == "output_image") {
                        // Also handle the old detection method for backward compatibility
                        newContentList.add(
                            ResponseInputContent.ofInputText(
                                contentItem
                                    .asInputText()
                                    .toBuilder()
                                    .text("<image>...")
                                    .build(),
                            ),
                        )
                        hasAnyReplacement = true
                    } else {
                        // Keep the original content item
                        newContentList.add(contentItem)
                    }
                } else {
                    // Keep non-text content items as they are
                    newContentList.add(contentItem)
                }
            }
            
            if (hasAnyReplacement) {
                val builder = it.asEasyInputMessage().toBuilder()
                builder.content(EasyInputMessage.Content.ofResponseInputMessageContentList(newContentList))
                newItems.add(ResponseInputItem.ofEasyInputMessage(builder.build()))
            } else {
                newItems.add(it)
            }
        } else if (it.isResponseOutputMessage()) {
            // Check if any content is an image and replace only those items
            val contentList = it.asResponseOutputMessage().content()
            val newContentList = mutableListOf<ResponseOutputMessage.Content>()
            var hasAnyReplacement = false
            
            for (contentItem in contentList) {
                val jsonContent = contentItem._json().get().toString()
                
                // Check for old detection patterns
                if (jsonContent.contains("output_format=b64_json") || jsonContent.contains("type=output_image")) {
                    newContentList.add(
                        ResponseOutputMessage.Content.ofOutputText(
                            ResponseOutputText
                                .builder()
                                .text("<image>...")
                                .annotations(listOf())
                                .build(),
                        ),
                    )
                    hasAnyReplacement = true
                } else if (contentItem.isOutputText()) {
                    // Try to check actual content for image signatures
                    val textContent = contentItem.asOutputText().text()
                    val imageInfo = isImageContent(textContent)
                    if (imageInfo.isImage) {
                        newContentList.add(
                            ResponseOutputMessage.Content.ofOutputText(
                                ResponseOutputText
                                    .builder()
                                    .text("<${imageInfo.format}>...")
                                    .annotations(contentItem.asOutputText().annotations())
                                    .build(),
                            ),
                        )
                        hasAnyReplacement = true
                    } else {
                        // Keep the original content item
                        newContentList.add(contentItem)
                    }
                } else {
                    // Keep non-text content items as they are
                    newContentList.add(contentItem)
                }
            }
            
            if (hasAnyReplacement) {
                val builder = it.asResponseOutputMessage().toBuilder()
                builder.content(newContentList)
                newItems.add(ResponseInputItem.ofResponseOutputMessage(builder.build()))
            } else {
                newItems.add(it)
            }
        } else {
            newItems.add(it)
        }
    }

    return newItems
}

fun ChatCompletionMessage.toChatCompletionMessageParam(objectMapper: ObjectMapper): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam
            .builder()
            .role(this._role())
            .content(objectMapper.convertValue(this.content(), ChatCompletionAssistantMessageParam.Content::class.java))
            .toolCalls(this._toolCalls())
            .build(),
    )
