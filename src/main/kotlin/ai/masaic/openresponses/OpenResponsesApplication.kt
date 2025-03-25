package ai.masaic.openresponses

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Main application class for the OpenResponses Spring Boot application.
 *
 * This class serves as the entry point for the application and is annotated with
 * [SpringBootApplication] to enable Spring Boot's auto-configuration.
 */
@SpringBootApplication
class OpenResponsesApplication

/**
 * Application entry point.
 *
 * @param args Command line arguments passed to the application
 */
fun main(args: Array<String>) {
    runApplication<OpenResponsesApplication>(*args)
}
