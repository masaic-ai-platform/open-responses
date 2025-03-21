package com.masaic.openai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Main application class for the OpenAI Spring Boot application.
 * 
 * This class serves as the entry point for the application and is annotated with
 * [SpringBootApplication] to enable Spring Boot's auto-configuration.
 */
@SpringBootApplication
class OpenaiApplication

/**
 * Application entry point.
 * 
 * @param args Command line arguments passed to the application
 */
fun main(args: Array<String>) {
    runApplication<OpenaiApplication>(*args)
}
