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
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory
import org.springframework.data.mongodb.ReactiveMongoTransactionManager
import org.springframework.data.mongodb.config.EnableReactiveMongoAuditing
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.mapping.event.ValidatingMongoEventListener
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

/**
 * MongoDB configuration class.
 *
 * This class configures the MongoDB connection and related beans.
 */
@Configuration
@EnableReactiveMongoRepositories(basePackages = ["ai.masaic.openresponses.api.repository"])
@EnableReactiveMongoAuditing
@EnableTransactionManagement
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
    fun validatingMongoEventListener(validator: LocalValidatorFactoryBean): ValidatingMongoEventListener = ValidatingMongoEventListener(validator)

    @Bean
    fun mongoTemplate(): MongoTemplate {
        logger.debug { "Creating MongoTemplate bean" }
        return MongoTemplate(mongoClient(), databaseName)
    }

    /**
     * Configures a ReactiveMongoTemplate with custom type mapping.
     *
     * This removes the _class field from MongoDB documents.
     */
    fun reactiveMongoTemplate(
        factory: ReactiveMongoDatabaseFactory,
        converter: MappingMongoConverter,
    ): ReactiveMongoTemplate = ReactiveMongoTemplate(factory, converter)


    @Bean
    fun mongoResponseStore(
        mongoTemplate: MongoTemplate,
        objectMapper: ObjectMapper,
    ): MongoResponseStore {
        logger.debug { "Creating MongoResponseStore bean" }
        return MongoResponseStore(mongoTemplate, objectMapper)
    }

    /**
     * Configures a transaction manager for MongoDB.
     */
    fun transactionManager(factory: ReactiveMongoDatabaseFactory): ReactiveMongoTransactionManager = ReactiveMongoTransactionManager(factory)
}
