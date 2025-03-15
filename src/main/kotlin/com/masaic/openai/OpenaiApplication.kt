package com.masaic.openai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OpenaiApplication

fun main(args: Array<String>) {
	runApplication<OpenaiApplication>(*args)
}
