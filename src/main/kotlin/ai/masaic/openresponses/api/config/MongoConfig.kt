package ai.masaic.openresponses.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
class MongoConfig {
    /**
     * Configures a validator to validate MongoDB documents before saving.
     */
    @Bean
    fun validatingMongoEventListener(validator: LocalValidatorFactoryBean): ValidatingMongoEventListener = ValidatingMongoEventListener(validator)

    /**
     * Configures a ReactiveMongoTemplate with custom type mapping.
     *
     * This removes the _class field from MongoDB documents.
     */
    @Bean
    fun reactiveMongoTemplate(
        factory: ReactiveMongoDatabaseFactory,
        converter: MappingMongoConverter,
    ): ReactiveMongoTemplate = ReactiveMongoTemplate(factory, converter)

    /**
     * Configures a transaction manager for MongoDB.
     */
    @Bean
    fun transactionManager(factory: ReactiveMongoDatabaseFactory): ReactiveMongoTransactionManager = ReactiveMongoTransactionManager(factory)
}
