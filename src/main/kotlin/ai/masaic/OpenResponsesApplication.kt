package ai.masaic

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration
import org.springframework.boot.autoconfigure.data.mongo.MongoReactiveRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import reactor.core.publisher.Hooks

/**
 * Main application class for the OpenResponses Spring Boot application.
 *
 * This class serves as the entry point for the application and is annotated with
 * [SpringBootApplication] to enable Spring Boot's autoconfiguration.
 * We explicitly exclude both MongoDB auto-configurations to prevent Spring from automatically
 * connecting to MongoDB when it's not explicitly enabled via properties.
 */
@SpringBootApplication(
    exclude = [
        MongoReactiveAutoConfiguration::class,
        MongoReactiveDataAutoConfiguration::class,
        MongoReactiveRepositoriesAutoConfiguration::class,
    ],
)
@ConfigurationPropertiesScan
@EnableScheduling
class OpenResponsesApplication

/**
 * Application entry point.
 *
 * @param args Command line arguments passed to the application
 */
fun main(args: Array<String>) {
    Hooks.enableAutomaticContextPropagation()
    runApplication<OpenResponsesApplication>(*args)
}
