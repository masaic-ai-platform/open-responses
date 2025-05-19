package ai.masaic.improved

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
                    .setSize(1536)
                    .build(),
            ).get()
    }
}

object QDRANTCOLLECTIONS {
    const val CONVERSATIONS = "conversations"
    const val LABEL_RULES = "label_rules"
}
