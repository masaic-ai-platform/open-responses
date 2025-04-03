package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.client.MongoResponseStore
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
@ConditionalOnProperty(name = ["open-responses.response-store.type"], havingValue = "mongodb", matchIfMissing = false)
class MongoConfig(
    @Value("\${open-responses.mongodb.uri}")
    private val mongoURI: String,
    @Value("\${open-responses.mongodb.database}")
    private val databaseName: String,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun mongoClient(): MongoClient {
        logger.debug { "Creating MongoClient bean" }
        return MongoClients.create(mongoURI)
    }

    @Bean
    fun mongoTemplate(): MongoTemplate {
        logger.debug { "Creating MongoTemplate bean" }
        return MongoTemplate(mongoClient(), databaseName)
    }

    @Bean
    fun mongoResponseStore(
        mongoTemplate: MongoTemplate,
        objectMapper: ObjectMapper,
    ): MongoResponseStore {
        logger.debug { "Creating MongoResponseStore bean" }
        return MongoResponseStore(mongoTemplate, objectMapper)
    }
}
