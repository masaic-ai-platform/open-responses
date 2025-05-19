package ai.masaic.improved.controller

import ai.masaic.improved.repository.MongoConversationRepository
import org.bson.Document
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
@RestController
@RequestMapping("/v1")
class DashboardController(
    private val conversationRepository: MongoConversationRepository
) {
    @PostMapping(
        "/dashboard/domains/aggregation",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    suspend fun aggregateDomainStats(): ResponseEntity<PathSummaryResult> {
        // 1) pull the flat (path,day,count) buckets
        val buckets: List<Document> = conversationRepository.aggregateLabels()

        // 2) grand total across every bucket
        val grandTotal = buckets.sumOf { it.getInteger("count") }

        // 3) group by the second segment in "domain/<seg>/<label>"
        val byDomain: Map<String, List<Document>> = buckets.groupBy { doc ->
            doc.getString("path").split("/").getOrElse(1) { "" }
        }

        // 4) build one DomainSummary per domain
        val domains = byDomain.map { (domain, docsForDomain) ->
            // 4a) for each specific label under this domain:
            val bucketsList = docsForDomain
                .groupBy { doc ->
                    // last segment is the fine‐grained label
                    doc.getString("path").substringAfterLast("/")
                }
                .map { (labelName, docsForLabel) ->
                    // build dateWise for that label
                    val dateWise = docsForLabel
                        .map {
                            DateCount(
                                day   = it.getString("day"),
                                count = it.getInteger("count")
                            )
                        }
                        .sortedBy { it.day }
                    val totalForLabel = dateWise.sumOf { it.count }
                    PathSummary(
                        path     = labelName,
                        count    = totalForLabel,
                        dateWise = dateWise
                    )
                }

            // 4b) build the domain‐level dateWise (sum of all its buckets per day)
            val domainDateWise = docsForDomain
                .groupBy { it.getString("day") }
                .map { (day, docsOnDay) ->
                    DateCount(
                        day   = day,
                        count = docsOnDay.sumOf { it.getInteger("count") }
                    )
                }
                .sortedBy { it.day }

            // 4c) sum of all buckets under this domain
            val domainTotal = docsForDomain.sumOf { it.getInteger("count") }

            DomainSummary(
                path     = domain,
                count    = domainTotal,
                buckets  = bucketsList,
                dateWise = domainDateWise
            )
        }

        // 5) return with top‐level count and all domain summaries
        return ResponseEntity.ok(
            PathSummaryResult(
                count   = grandTotal,
                domains = domains
            )
        )
    }
}


// ——— DTOs —————————————

data class DateCount(
    val day: String,
    val count: Int
)

data class PathSummary(
    val path: String,
    val count: Int,
    val dateWise: List<DateCount>
)

data class DomainSummary(
    val path: String,               // e.g. "self_service"
    val count: Int,                 // sum of all its buckets
    val buckets: List<PathSummary>, // per‐label summaries
    val dateWise: List<DateCount>   // per‐day totals for the domain
)

data class PathSummaryResult(
    val count: Int,                // grand total across all domains
    val domains: List<DomainSummary>
)
