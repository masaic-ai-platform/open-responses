package com.masaic.openai.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.masaic.openai.api.model.CreateResponseRequest
import com.masaic.openai.api.utils.EventUtils
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.responses.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.stereotype.Service

@Service
class ResponseService {

    val client = OpenAIOkHttpClient.fromEnv()
    val objectMapper = ObjectMapper()

    suspend fun createResponse(request: CreateResponseRequest): Response {

        val createParamsBuilder = ResponseCreateParams.builder()
            .model(request.model)
            .input(objectMapper.writeValueAsString(request.input))

        if(request.instructions != null) {
            createParamsBuilder.instructions(request.instructions)
        }

        request.tools?.forEach {
            when(it.type) {
                "web_search_preview" -> {
                    val additionalProps = it.getAdditionalProperties()
                    createParamsBuilder.addTool(WebSearchTool.builder().type(WebSearchTool.Type.of("web_search_preview")).putAllAdditionalProperties(additionalProps).build())
                }

                "web_search_preview_2025_03_11" -> {
                    val additionalProps = it.getAdditionalProperties()
                    createParamsBuilder.addTool(WebSearchTool.builder().type(WebSearchTool.Type.of("web_search_preview_2025_03_11")).putAllAdditionalProperties(additionalProps).build())
                }

                "file_search" -> {
                    val additionalProps = it.getAdditionalProperties()
                    createParamsBuilder.addTool(FileSearchTool.builder().type(JsonValue.from("file_search")).putAllAdditionalProperties(additionalProps).build())
                }

                "function" -> {
                    val additionalProps = it.getAdditionalProperties()
                    createParamsBuilder.addTool(FunctionTool.builder().type(JsonValue.from("function")).putAllAdditionalProperties(additionalProps).build())
                }

                "computer_use_preview" -> {
                    val additionalProps = it.getAdditionalProperties()
                    createParamsBuilder.addTool(ComputerTool.builder().type(JsonValue.from("computer_use_preview")).putAllAdditionalProperties(additionalProps).build())
                }

                else -> throw IllegalArgumentException("Unknown tool type: ${it.type}")
            }
        }

        return client.responses().create(createParamsBuilder.build())
    }

    fun createStreamingResponse(request: CreateResponseRequest): Flow<String> = flow {

        val createParams = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.instructions)
            .input(objectMapper.writeValueAsString(request.input))
            .build()

        client.responses().createStreaming(createParams).stream().consumeAsFlow().collect {
            emit(EventUtils.convertEvent(it))
        }
    }

    fun getResponse(responseId: String): Response{
        return client.responses().retrieve(ResponseRetrieveParams.builder().responseId(responseId).build())
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message) 