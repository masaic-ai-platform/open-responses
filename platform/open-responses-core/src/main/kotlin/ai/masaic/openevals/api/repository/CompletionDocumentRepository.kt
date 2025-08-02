package ai.masaic.openevals.api.repository

import ai.masaic.openresponses.store.CompletionDocument
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CompletionDocumentRepository : ReactiveMongoRepository<CompletionDocument, String>
