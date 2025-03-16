package com.masaic.openai.api.extensions

import com.openai.models.responses.ResponseCreateParams

fun ResponseCreateParams.Builder.fromBody(body: ResponseCreateParams.Body): ResponseCreateParams.Builder {
    this.input(body.input())
    this.model(body.model())
    this.instructions(body.instructions())
    this.reasoning(body.reasoning())
    this.additionalBodyProperties(body._additionalProperties())
    this.parallelToolCalls(body.parallelToolCalls())
    this.maxOutputTokens(body.maxOutputTokens())
    this.include(body.include())
    this.metadata(body.metadata())
    this.store(body.store())
    this.temperature(body.temperature())
    this.topP(body.topP())
    this.truncation(body.truncation())
    body.text().ifPresent { this.text(it) }
    body.user().ifPresent { this.user(it) }
    body.toolChoice().ifPresent { this.toolChoice(it) }
    body.tools().ifPresent { this.tools(it) }
    return this
}