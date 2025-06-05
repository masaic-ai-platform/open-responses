package ai.masaic.improved

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.annotation.Value
import io.grpc.ManagedChannelBuilder
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ImprovementConfig {

    @Bean
    fun qdrantClient(): QdrantClient {
        // 1. Build the low-level gRPC channel (adjust host/port as needed)
        val channel = ManagedChannelBuilder
            .forAddress("localhost", 6334)
            .usePlaintext()
            .build()

        // 2. Create the high-level Qdrant client
        val qdrantClient = QdrantClient(
            QdrantGrpcClient
                .newBuilder(channel)
                .build()
        )

        val collections = qdrantClient.listCollectionsAsync().get()
        if (collections.none { it == QDRANTCOLLECTIONS.CONVERSATIONS }) {
            createCollection(qdrantClient, QDRANTCOLLECTIONS.CONVERSATIONS)
        }

        if (collections.none { it == QDRANTCOLLECTIONS.LABEL_RULES }) {
            createCollection(qdrantClient, QDRANTCOLLECTIONS.LABEL_RULES)
        }
        return qdrantClient
    }

    private fun createCollection(qdrantClient: QdrantClient, collectionName: String) {
        qdrantClient
            .createCollectionAsync(
                collectionName,
                Collections.VectorParams
                    .newBuilder()
                    .setDistance(Collections.Distance.Cosine)
                    .setSize(384)
                    .build(),
            ).get()
    }

    @Configuration
    class MemgraphConfig(
        @Value("\${memgraph.uri}") private val uri: String,
        @Value("\${memgraph.username}") private val username: String,
        @Value("\${memgraph.password}") private val password: String,
    ) {
        @Bean
        fun memgraphDriver(): Driver =
            GraphDatabase.driver(uri, AuthTokens.basic(username, password))
    }

}

object QDRANTCOLLECTIONS {
    const val CONVERSATIONS = "conversations"
    const val LABEL_RULES = "label_rules"
}
