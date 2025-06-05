package ai.masaic.improved.controller

import ai.masaic.improved.AgentRunRepository
import ai.masaic.improved.QDRANTCOLLECTIONS
import ai.masaic.improved.model.*
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.improved.repository.MongoConversationRepository
import ai.masaic.openevals.api.service.ModelClientService
import ai.masaic.openresponses.api.controller.CompletionController
import ai.masaic.openresponses.api.controller.EmbeddingsController
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.CreateEmbeddingRequest
import ai.masaic.openresponses.api.model.EmbeddingData
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletion
import com.opencsv.CSVReader
import io.qdrant.client.ConditionFactory.matchKeyword
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.grpc.Points
import io.qdrant.client.grpc.Points.ScrollPoints
import io.qdrant.client.grpc.Points.SearchPoints
import io.qdrant.client.grpc.Points.WithPayloadSelector
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*

@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
@RestController
@RequestMapping("/v1")
class DataSetController(
    private val modelClientService: ModelClientService,
    private val conversationRepository: ConversationRepository,
    private val agentRunRepository: AgentRunRepository,
    private val embeddingsController: EmbeddingsController,
    private val completionController: CompletionController,
    private val qdrantClient: QdrantClient,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper()

    @Value("\${dataset.csv.path:/Users/jasbirsingh/Downloads/Shortlisted-DataSet.csv}")
    private lateinit var csvFilePath: String

    // Data class to deserialize the model response
    data class ConversationResponse(
        val messages: List<MessageResponse>,
    )

    data class MessageResponse(
        val role: String,
        val content: String,
    )

    // Data class to represent a row in the CSV
    data class DataSetEntry(
        val flags: String,
        val instruction: String,
        val category: String,
        val intent: String,
        val response: String,
    )

    @GetMapping("/classified/conversations", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getClassifiedConversations(
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = true) runId: String,
    ): List<Conversation> {
        try {
            // Get the agent run outcome which contains the conversation IDs
            val agentRunOutcome = agentRunRepository.getAgentRunOutcomeByRunId(runId)
            val conversationIds = agentRunOutcome.conversationIds
            
            if (conversationIds.isEmpty()) {
                return emptyList()
            }
            
            // Fetch all conversations
            val conversations =
                conversationIds.mapNotNull { conversationId ->
                    conversationRepository.getConversation(conversationId)
                }
            
            // Sort conversations by createdAt
            val sortedConversations =
                if (order.equals("asc", ignoreCase = true)) {
                    conversations.sortedBy { it.createdAt }
                } else {
                    conversations.sortedByDescending { it.createdAt }
                }
            
            // Apply pagination if 'after' parameter is provided
            val filteredConversations =
                if (after != null) {
                    val afterConversation = conversationRepository.getConversation(after)
                    if (afterConversation != null) {
                        if (order.equals("asc", ignoreCase = true)) {
                            sortedConversations.filter { it.createdAt > afterConversation.createdAt }
                        } else {
                            sortedConversations.filter { it.createdAt < afterConversation.createdAt }
                        }
                    } else {
                        sortedConversations
                    }
                } else {
                    sortedConversations
                }
            
            // Apply limit
            return filteredConversations.take(limit)
        } catch (e: NoSuchElementException) {
            logger.error(e) { "No AgentRunOutcome found for runId: $runId" }
            throw IllegalArgumentException("No classification run found for runId: $runId")
        } catch (e: Exception) {
            logger.error(e) { "Error getting classified conversations for runId: $runId" }
            throw IllegalStateException("Error retrieving classified conversations")
        }
    }

    @GetMapping("/conversations/{conversationId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getConversation(
        @PathVariable conversationId: String,
    ): Conversation = conversationRepository.getConversation(conversationId) ?: throw IllegalStateException("conversation not found")

    @GetMapping("/conversations/transcripts", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getTranscripts(
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = true) domainLabel: String,
    ): List<Transcript> {
        // Create params for conversation repository, including the label filter
        val params =
            ListConversationsParams(
                limit = limit,
                after = after,
                order = order, // Always desc on createdAt as specified in comments
                labels = listOf(domainLabel), // Filter by domainLabel at the database layer
            )

        // Get conversations already filtered by domainLabel from the repository
        val conversations = conversationRepository.listConversations(params)

        // Map conversations to transcripts, checking if each one has a generic label
        return conversations.map { conversation ->
            // Check if the conversation has any label that starts with "generic"
            val isGenericLabelAvailable =
                conversation.labels.any { label ->
                    label.path.startsWith("generic")
                }

            // Create and return a Transcript object
            Transcript(
                conversationId = conversation.id,
                messages = conversation.messages,
                isGenericLabelAvailable = isGenericLabelAvailable,
                createdAt = conversation.createdAt,
            )
        }
    }

    @PostMapping("/backfill/conversations/embeddings", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun backFillEmbeddings(
        @RequestHeader("Authorization") authHeader: String,
    ): ResponseEntity<HttpStatusCode> {
        val conversations = conversationRepository.listConversations()
        val messages = conversations.map { objectMapper.writeValueAsString(it.messages) }

        var globalOffset = 0
        messages.chunked(50).forEach { batchMessages ->
            val request =
                CreateEmbeddingRequest(
                    input = batchMessages,
                    model = "default",
                )

            val embedResp = embeddingsController.createEmbedding(request, authHeader)
            val embeddings = embedResp.body?.data as List<EmbeddingData>
            val points =
                embeddings.map { embedData ->
                    val conversation = conversations[globalOffset + embedData.index]
                    createConversationVectorPoint(embedData, globalOffset, conversation)
                }
            globalOffset += batchMessages.size
            qdrantClient
                .upsertAsync(QDRANTCOLLECTIONS.CONVERSATIONS, points)
        }
        return ResponseEntity.ok().build()
    }

    suspend fun createConversationVectorPoint(
        embeddingData: EmbeddingData,
        offset: Int,
        conversation: Conversation,
    ): Points.PointStruct {
        val floatArray =
            (embeddingData.embedding as List<*>)
                .map { (it as Number).toFloat() }
                .toFloatArray()

        return Points.PointStruct
            .newBuilder()
            // Use the embedding's index as the point ID (or UUID if you prefer)
            .setId(id(UUID.randomUUID()))
            // Attach the vector
            .setVectors(vectors(*floatArray))
            // Add any metadata you like; here, we store the original index and model name
            .putAllPayload(
                mapOf(
                    "conversationId" to value(conversation.id),
                    "labels" to value(objectMapper.writeValueAsString(conversation.labels.map { it.path })),
//                    "category" to value(conversation.meta["category"] as String),
//                    "intent" to value(conversation.meta["intent"] as String),
                    "messages" to value(objectMapper.writeValueAsString(conversation.messages)),
                    "createdAt" to value(conversation.createdAt.toString()),
                ),
            ).build()
    }

    @PostMapping("/backfill/conversations/domainlabels", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun assignDomainLabels(): ResponseEntity<List<String>> {
        val conversations = conversationRepository.listConversations()
        val conversationIds =
            conversations.map {
                assignDomainLabels(it.id).body?.id ?: "not_available for ${it.id}"
            }
        return ResponseEntity.ok().body(conversationIds)
    }

    @PostMapping("/backfill/conversations/domainlabels/{conversationId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun assignDomainLabels(
        @PathVariable conversationId: String,
    ): ResponseEntity<Conversation> {
        val filter =
            Points.Filter
                .newBuilder()
                .addMust(matchKeyword("conversationId", conversationId))
                .build()

        val scrollReq =
            ScrollPoints
                .newBuilder()
                .setCollectionName(QDRANTCOLLECTIONS.CONVERSATIONS)
                .setFilter(filter)
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(true))
                .setLimit(1)
                .build()

        val points = qdrantClient.scrollAsync(scrollReq).get()
        val messagesVector =
            points.resultList
                .first()
                .vectors.vector.dataList
                .toFloatArray()

        val ruleSearchPoint =
            SearchPoints
                .newBuilder()
                .setCollectionName(QDRANTCOLLECTIONS.LABEL_RULES)
                .addAllVector(messagesVector.map { it })
                .setLimit(1)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true))
                .build()

        val results: List<Points.ScoredPoint> =
            qdrantClient
                .searchAsync(ruleSearchPoint)
                .get()

        val labelPath =
            results.firstOrNull { it.score >= (it.getPayloadOrThrow("threshold").doubleValue) }.let { hit ->
                hit?.getPayloadOrDefault("label", value(""))?.stringValue
            } ?: ""

        var conversation =
            conversationRepository.getConversation(conversationId)
                ?: throw IllegalStateException("Conversation with id=$conversationId not found.")
        if (labelPath.isNotEmpty()) {
            val labels =
                conversation.labels +
                    Label(
                        path = labelPath,
                        source = LabelSource.AUTO_ALGO,
                        status = "final",
                        createdAt = Instant.now(),
                    )
            conversation = conversation.copy(labels = labels)
        } else {
            val metaMap = mutableMapOf<String, Any>()
            metaMap.putAll(conversation.meta)
            metaMap["domainLabel404"] = true
            conversation = conversation.copy(meta = metaMap)
        }
        conversation = conversationRepository.createConversation(conversation)
        return ResponseEntity.ok().body(conversation)
    }

    @GetMapping(
        "/data/export",
        produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
    )
    suspend fun exportDataSet(): ResponseEntity<ByteArray> {
        logger.info { "Exporting conversations to Excel file" }
        
        // Get data from repository
        val conversationRepository = this.conversationRepository as MongoConversationRepository
        val conversations = conversationRepository.aggregateDomainConversations()
        
        // Create a workbook
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Conversations")
        
        // Create header row
        val headerRow = sheet.createRow(0)
        val columnHeaders = arrayOf("conversation_id", "conversation", "bucket", "problem")
        columnHeaders.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }
        
        // Create data rows
        conversations.forEachIndexed { index, conversation ->
            val row = sheet.createRow(index + 1)
            
            // Set conversation ID
            row.createCell(0).setCellValue(conversation.conversationId)
            
            // Set conversation messages as JSON string
            val messagesJson = objectMapper.writeValueAsString(conversation.conversation)
            row.createCell(1).setCellValue(messagesJson)
            val labels = conversation.domainLabel.split("/")
            if (labels.size == 3) {
                row.createCell(2).setCellValue(labels[1])
                row.createCell(3).setCellValue(labels[2])
            }
        }
        
        // Adjust column widths
        columnHeaders.indices.forEach { i ->
            sheet.autoSizeColumn(i)
        }
        
        // Write workbook to byte array
        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        
        // Set headers for file download
        val headers = org.springframework.http.HttpHeaders()
        headers.add("Content-Disposition", "attachment; filename=conversations_export.xlsx")
        headers.contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        
        logger.info { "Successfully exported ${conversations.size} conversations" }
        
        return ResponseEntity
            .ok()
            .headers(headers)
            .body(outputStream.toByteArray())
    }

    @PostMapping(
        "/data/import",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.MULTIPART_MIXED_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun importDataSet(
        @RequestPart("file") file: FilePart,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        @RequestParam(required = false) labelConvWithDomain: Boolean = false,
    ): List<String> {
        logger.info { "Processing Excel file: ${file.filename()}" }

        // Create a temporary ByteArrayOutputStream to collect all the data
        val outputStream = ByteArrayOutputStream()

        // Use DataBufferUtils to collect and aggregate the data
        try {
            // Read all content to a single ByteArrayOutputStream
            val dataBuffers = file.content().collectList().awaitSingle()

            // Process all dataBuffers
            for (dataBuffer in dataBuffers) {
                val bytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bytes)
                outputStream.write(bytes)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to read Excel file data" }
            throw IllegalArgumentException("Failed to read Excel file data: ${e.message}")
        }

        // Convert the collected data to an InputStream
        val inputStream = ByteArrayInputStream(outputStream.toByteArray())

        // Create the workbook from the collected data
        val workbook =
            try {
                WorkbookFactory.create(inputStream)
            } catch (e: Exception) {
                logger.error(e) { "Failed to open Excel file" }
                throw IllegalArgumentException("Failed to open Excel file: ${e.message}")
            }

        val sheet = workbook.getSheetAt(0)
        val conversations = mutableListOf<Conversation>()
        val conversationMessages = mutableListOf<String>()
        val formatter =
            org.apache.poi.ss.usermodel
                .DataFormatter()

        // Iterate through rows (skip header row)
        for (rowIdx in 1 until sheet.physicalNumberOfRows) {
            val row = sheet.getRow(rowIdx) ?: continue

            // Get conversation_id from column 0 - handle both numeric and string cell types
            val cell0 = row.getCell(0) ?: continue
            val conversationId =
                when (cell0.cellType) {
                    org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell0.numericCellValue.toLong().toString()
                    org.apache.poi.ss.usermodel.CellType.STRING -> cell0.stringCellValue.trim()
                    else -> {
                        // Try to get any value as string using DataFormatter
                        formatter.formatCellValue(cell0).trim()
                    }
                }

            if (conversationId.isBlank()) continue

            // Get conversation JSON from column 1 - handle both string and other cell types
            val cell1 = row.getCell(1) ?: continue
            val conversationJson =
                when (cell1.cellType) {
                    org.apache.poi.ss.usermodel.CellType.STRING -> cell1.stringCellValue.trim()
                    else -> {
                        // Try to get any value as string using DataFormatter
                        formatter.formatCellValue(cell1).trim()
                    }
                }

            if (conversationJson.isBlank()) continue

            try {
                // Parse conversation JSON
//                val messages = parseConversationJson(conversationJson)

                val startDate = Instant.parse("2025-05-01T00:00:00Z")
                val endDate = Instant.parse("2025-05-20T23:59:59Z")
                val randomCreatedAt = generateRandomTimestamp(startDate, endDate)
                // Create a Conversation object
                val conversation =
                    Conversation(
                        id = conversationId,
                        createdAt = randomCreatedAt,
                        labels = emptyList(),
                        meta =
                            mapOf(
                                "source" to "excel_import",
                            ),
                        version = 1,
                    )

                // Save to the repository
                conversations.add(conversation)
                conversationMessages.add(conversationJson)
                logger.info { "Processed conversation ID: $conversationId" }
            } catch (e: Exception) {
                logger.error(e) { "Error processing row $rowIdx with conversation ID $conversationId: ${e.message}" }
                // Continue processing other rows even if one fails
            }
        }

        // Close resources
        workbook.close()
        inputStream.close()
        outputStream.close()

        logger.info { "Successfully imported ${conversations.size} conversations" }

        if (conversations.isEmpty()) {
            throw IllegalStateException("no conversations found for processing.")
        }

        val savedConvLog =
            conversations.mapIndexed { index, converstion ->
                val savedConversation =
                    processConversation(converstion, conversationMessages[index], headers, queryParams, labelConvWithDomain)
                "saved conversation: ${savedConversation.id}"
            }

        return savedConvLog
    }

    /**
     * Parse conversation JSON string into a list of Message objects
     * with improved handling for different JSON formats
     */
    private fun parseConversationJson(json: String): List<Message> {
        val objectMapper = jacksonObjectMapper()

        try {
            // Clean up the JSON string
            var cleanJson = json.trim()

            // Handle various JSON formats
            if (cleanJson.startsWith("[") && cleanJson.endsWith("]")) {
                // Convert single quotes to double quotes for JSON compatibility
                cleanJson = cleanJson.replace("'", "\"")

                // Try Jackson parser first - most efficient if it works
                try {
                    val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {}
                    val messagesList = objectMapper.readValue(cleanJson, typeRef)

                    return messagesList.map { messageMap ->
                        val role = messageMap["role"] ?: throw IllegalArgumentException("Role is missing in message")
                        val content =
                            messageMap["content"] ?: throw IllegalArgumentException("Content is missing in message")

                        Message(
                            role = Role.fromString(role),
                            text = content,
                        )
                    }
                } catch (e: Exception) {
                    logger.info { "Jackson parser failed, falling back to manual parsing: ${e.message}" }

                    // Jackson failed, try manual parsing
                    return parseConversationManually(cleanJson)
                }
            } else {
                throw IllegalArgumentException("JSON string must be an array")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error parsing conversation JSON" }
            throw e
        }
    }

    /**
     * Manual fallback parser for conversation JSON strings that don't parse with standard methods
     */
    private fun parseConversationManually(json: String): List<Message> {
        val messages = mutableListOf<Message>()

        try {
            // Remove the outer square brackets
            val content = json.trim().removeSurrounding("[", "]").trim()

            // Manual parsing of each message object
            var remainingContent = content
            var depth = 0
            var currentMessage = StringBuilder()

            for (char in remainingContent) {
                when (char) {
                    '{' -> {
                        depth++
                        currentMessage.append(char)
                    }

                    '}' -> {
                        depth--
                        currentMessage.append(char)

                        // If we've completed a message object
                        if (depth == 0) {
                            val messageStr = currentMessage.toString().trim()
                            parseMessageAndAddToList(messageStr, messages)
                            currentMessage = StringBuilder()
                        }
                    }

                    else -> {
                        // Only append the character if we're inside a message object
                        if (depth > 0 || char.isWhitespace().not()) {
                            currentMessage.append(char)
                        }
                    }
                }
            }

            // If we have any remaining content in the buffer
            if (currentMessage.isNotEmpty()) {
                val messageStr = currentMessage.toString().trim()
                if (messageStr.startsWith("{") && messageStr.endsWith("}")) {
                    parseMessageAndAddToList(messageStr, messages)
                }
            }

            return messages
        } catch (e: Exception) {
            logger.error(e) { "Error in manual conversation parsing" }
            throw IllegalArgumentException("Failed to manually parse conversation: ${e.message}")
        }
    }

    /**
     * Helper method to parse a single message string and add it to the messages list
     */
    private fun parseMessageAndAddToList(
        messageStr: String,
        messages: MutableList<Message>,
    ) {
        try {
            // Clean up the message string
            val cleanMessageStr = messageStr.replace("'", "\"").trim()

            // Try Jackson parser first
            try {
                val objectMapper = jacksonObjectMapper()
                val messageMap = objectMapper.readValue<Map<String, String>>(cleanMessageStr)

                val role = messageMap["role"] ?: throw IllegalArgumentException("Role is missing")
                val content = messageMap["content"] ?: throw IllegalArgumentException("Content is missing")

                messages.add(
                    Message(
                        role = Role.fromString(role),
                        text = content,
                    ),
                )
                return
            } catch (e: Exception) {
                // Jackson failed, try manual extraction
                logger.debug { "Jackson failed to parse message, trying manual extraction: ${e.message}" }
            }

            // Manual extraction of role and content
            var inRole = false
            var inContent = false
            var roleValue = StringBuilder()
            var contentValue = StringBuilder()
            var currentQuotes = 0

            // First, find the role key
            val roleKeyIndex = cleanMessageStr.indexOf("\"role\"")
            if (roleKeyIndex == -1) {
                logger.warn { "Could not find role key in message: $messageStr" }
                return
            }

            // Then find the content key
            val contentKeyIndex = cleanMessageStr.indexOf("\"content\"")
            if (contentKeyIndex == -1) {
                logger.warn { "Could not find content key in message: $messageStr" }
                return
            }

            // Extract role value
            var i = roleKeyIndex + 6 // "role" length
            while (i < cleanMessageStr.length) {
                val c = cleanMessageStr[i]
                if (c == ':') {
                    // Found the colon, now look for the opening quote
                    while (i < cleanMessageStr.length && cleanMessageStr[i] != '"') {
                        i++
                    }
                    // Skip the opening quote
                    i++
                    // Extract until the closing quote
                    while (i < cleanMessageStr.length && cleanMessageStr[i] != '"') {
                        roleValue.append(cleanMessageStr[i])
                        i++
                    }
                    break
                }
                i++
            }

            // Extract content value
            i = contentKeyIndex + 9 // "content" length
            while (i < cleanMessageStr.length) {
                val c = cleanMessageStr[i]
                if (c == ':') {
                    // Found the colon, now look for the opening quote
                    while (i < cleanMessageStr.length && cleanMessageStr[i] != '"') {
                        i++
                    }
                    // Skip the opening quote
                    i++
                    // Extract until the closing quote, handling escaped quotes
                    var escaped = false
                    while (i < cleanMessageStr.length) {
                        val c2 = cleanMessageStr[i]
                        if (c2 == '\\' && !escaped) {
                            escaped = true
                        } else if (c2 == '"' && !escaped) {
                            break
                        } else {
                            if (escaped && c2 == '"') {
                                // This is an escaped quote, add just the quote
                                contentValue.append('"')
                            } else {
                                contentValue.append(c2)
                            }
                            escaped = false
                        }
                        i++
                    }
                    break
                }
                i++
            }

            if (roleValue.isNotEmpty() && contentValue.isNotEmpty()) {
                messages.add(
                    Message(
                        role = Role.fromString(roleValue.toString()),
                        text = contentValue.toString(),
                    ),
                )
            } else {
                logger.warn { "Could not extract role or content values: role=$roleValue, content=$contentValue" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing message: $messageStr" }
        }
    }

    @PostMapping("/data/generation", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun createDataSet(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        @RequestParam(required = false) index: Int? = null,
        @RequestParam(required = false, defaultValue = "false") processAll: Boolean = false,
        @RequestBody(required = false) requestBody: ProcessRangeRequest,
    ): ResponseEntity<*> {
        val apiKey = headers["Authorization"]?.first() ?: throw IllegalStateException("apiKey is mandatory")
        try {
            // Load entries from CSV file
            val entries = loadEntriesFromCsv()
            if (entries.isEmpty()) {
                return ResponseEntity.badRequest().body("No entries found in CSV file")
            }

            // Process a range of entries, all entries, or a single entry based on the parameters
            if (requestBody != null && requestBody.startIndex != null && requestBody.endIndex != null) {
                // Process a range of entries
                val startIndex = requestBody.startIndex.coerceAtLeast(0)
                val endIndex = requestBody.endIndex.coerceAtMost(entries.size - 1)

                if (startIndex > endIndex) {
                    return ResponseEntity.badRequest().body("startIndex must be less than or equal to endIndex")
                }

                logger.info { "Processing entries from index $startIndex to $endIndex" }
                val results = mutableListOf<String>()

                // Process each entry in the specified range
                for (entryIndex in startIndex..endIndex) {
                    val entry = entries[entryIndex]
                    try {
                        logger.info { "Processing entry $entryIndex/${entries.size}: Category=${entry.category}, Intent=${entry.intent}" }

                        // Generate and save conversation for this entry
                        val savedConversation =
                            processConversation(entry, headers, queryParams, requestBody.labelConvWithDomain)
                        results.add("Successfully processed entry $entryIndex: ${savedConversation.id}")
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing entry $entryIndex: ${entry.instruction}" }
                        results.add("Failed to process entry $entryIndex: ${e.message}")
                    }
                }

                logger.info { "Completed processing entries from index $startIndex to $endIndex" }
                return ResponseEntity.ok().body(
                    mapOf(
                        "message" to "Processed entries from index $startIndex to $endIndex",
                        "totalProcessed" to (endIndex - startIndex + 1),
                        "results" to results,
                    ),
                )
            } else if (processAll) {
                logger.info { "Processing all ${entries.size} entries from dataset" }
                val results = mutableListOf<String>()

                // Process each entry in the dataset
                for ((entryIndex, entry) in entries.withIndex()) {
                    try {
                        logger.info { "Processing entry $entryIndex/${entries.size}: Category=${entry.category}, Intent=${entry.intent}" }

                        // Generate and save conversation for this entry
                        val savedConversation =
                            processConversation(entry, headers, queryParams, requestBody.labelConvWithDomain)

                        results.add("Successfully processed entry $entryIndex: ${savedConversation.id}")
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing entry $entryIndex: ${entry.instruction}" }
                        results.add("Failed to process entry $entryIndex: ${e.message}")
                    }
                }

                logger.info { "Completed processing all entries" }
                return ResponseEntity.ok().body(
                    mapOf(
                        "message" to "Processed ${entries.size} entries",
                        "results" to results,
                    ),
                )
            } else {
                // Process a single entry (either random or specified by index)
                val entry =
                    if (index != null && index < entries.size) {
                        entries[index]
                    } else {
                        entries.random()
                    }

                logger.info { "Selected entry - Category: ${entry.category}, Intent: ${entry.intent}" }

                // Generate and save conversation for this entry
                processConversation(entry, headers, queryParams, requestBody.labelConvWithDomain)

                return ResponseEntity.ok().body("processed")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating conversation from dataset" }
            return ResponseEntity.internalServerError().body("Error: ${e.message}")
        }
    }

    suspend fun processConversation(
        entry: DataSetEntry,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
        labelConvWithDomain: Boolean,
    ): Conversation {
        val apiKey = headers["Authorization"]?.first() ?: throw IllegalStateException("apiKey is mandatory")
        val conversation = generateConversationForEntry(entry, headers, queryParams)
        return processConversation(conversation, headers, queryParams, labelConvWithDomain)
    }

    suspend fun processConversation(
        conversation: Conversation,
        conversationMessages: String,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
        labelConvWithDomain: Boolean,
    ): Conversation {
        val apiKey = headers["Authorization"]?.first() ?: throw IllegalStateException("apiKey is mandatory")
        val labellingResponse = generateGenericLabel(conversationMessages, headers, queryParams)
        val messages =
            if (labellingResponse.messages.last().role == Role.ASSISTANT) {
                labellingResponse.messages.dropLast(1)
            } else {
                labellingResponse.messages
            }
        val updatedConversation =
            conversation.copy(
                labels = listOf(labellingResponse.toLabel()),
                summary = labellingResponse.summary,
                messages = messages,
            )

        val savedConversation = conversationRepository.createConversation(updatedConversation)
        saveConversationVector(savedConversation, apiKey)
        assignDomainLabels(savedConversation.id).body
            ?: throw IllegalStateException("Conversation not labelled with domain label..")
        return savedConversation
    }

    suspend fun processConversation(
        conversation: Conversation,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
        labelConvWithDomain: Boolean,
    ): Conversation {
        val apiKey = headers["Authorization"]?.first() ?: throw IllegalStateException("apiKey is mandatory")
        var savedConversation = conversationRepository.createConversation(conversation)
        saveConversationVector(savedConversation, apiKey)

        var labelledConversation: Conversation? = null
        var convNotLabelled = false
        if (labelConvWithDomain) {
            labelledConversation = assignDomainLabels(savedConversation.id).body
                ?: throw IllegalStateException("Conversation not labelled with domain label..")
            convNotLabelled = labelledConversation.meta.any { it.key == "domainLabel404" && it.value == true }
        }

        if (!labelConvWithDomain || convNotLabelled) {
            val labellingResponse = generateGenericLabel(savedConversation.messages, headers, queryParams)
            val updatedConversation =
                savedConversation.copy(
                    labels = listOf(labellingResponse.toLabel()),
                    summary = labellingResponse.summary,
                )
            savedConversation = conversationRepository.createConversation(updatedConversation)
        }
        return savedConversation
    }

    private fun loadEntriesFromCsv(): List<DataSetEntry> {
        val entries = mutableListOf<DataSetEntry>()

        try {
            logger.info { "Loading dataset from: $csvFilePath" }
            val reader = CSVReader(FileReader(csvFilePath, StandardCharsets.UTF_8))

            // Skip header
            val header = reader.readNext()
            if (header == null || header.size < 5) {
                logger.error { "Invalid CSV header" }
                return entries
            }

            var line: Array<String>?
            while (reader.readNext().also { line = it } != null) {
                if (line!!.size >= 5 && line!![2].isNotBlank() && line!![3].isNotBlank()) {
                    val entry =
                        DataSetEntry(
                            flags = line!![0],
                            instruction = line!![1],
                            category = line!![2],
                            intent = line!![3],
                            response = line!![4],
                        )
                    entries.add(entry)
                }
            }

            reader.close()
            logger.info { "Loaded ${entries.size} entries from CSV" }
        } catch (e: Exception) {
            logger.error(e) { "Error loading CSV file" }
        }

        return entries
    }

    /**
     * Generates a random timestamp between two Instant values.
     *
     * @param startInclusive The starting Instant (inclusive)
     * @param endInclusive The ending Instant (inclusive)
     * @return A random Instant between the two provided Instants
     */
    private fun generateRandomTimestamp(
        startInclusive: Instant,
        endInclusive: Instant,
    ): Instant {
        val startSeconds = startInclusive.epochSecond
        val endSeconds = endInclusive.epochSecond
        val randomSeconds = startSeconds + (Math.random() * (endSeconds - startSeconds)).toLong()

        // Add random nanoseconds for more randomness
        val randomNanos = (Math.random() * 999_999_999).toInt()

        return Instant.ofEpochSecond(randomSeconds, randomNanos.toLong())
    }

    /**
     * Generates a conversation for a specific dataset entry
     */
    private suspend fun generateConversationForEntry(
        entry: DataSetEntry,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): Conversation {
        val random = Math.random()
        val (numberOfTurns, userState) =
            if (random > 0.7) {
                Pair(5, "satisfied")
            } else if (random > 0.3 && random <= 0.6) {
                Pair(4, "unsatisfied")
            } else {
                Pair(3, "negative with sentiments")
            }

        var pointsToRemember =
            if (Math.random() > 0.4) {
                """
            1. most of the reply form user are abstract and few words rather than complete sentences.
            2. sometime reply from user is ambiguous and assistant can ask fro clarification. 
            3. End when the user is $userState.
            4. Some of the times user never explicitly state that he is not satisfied.
            """
            } else {
                """
            1. End when the user is $userState. 
            2. Some of the times user never explicitly state that he is not satisfied.                          
            """
            }

        if (userState != "satisfied") {
            pointsToRemember = """
               $pointsToRemember
           
           The conversation results into one of the following direction: 
           1. Gap between user's expectations and available capabilities with the assistant.
           2. User is not satisfied want to talk to human agent.
           3. Bot not able to resolve and offer human agent help.
           4. user keep on changing the context without providing information for clarification asked in previous turn.
           5. user seems in panic state.
           6. user was promised something but promiss not fulfilled.
            """
        }

        val question = entry.instruction
        val answer = entry.response
        val systemPrompt = """
        System: You are a CS bot designer. 
        Combine the following Q‑A into a realistic multi‑turn chat with at least $numberOfTurns turns. 
        Points to remember:
        $pointsToRemember

        User: $question
        Assistant: $answer
        """
        val responseFormat =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "conversationSchema",
                        "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(responseFormat),
                    ),
            )
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to systemPrompt)),
                model = "openai@gpt-4o-mini",
                response_format = responseFormat,
                stream = false,
                store = false,
            )

        val response = completionController.createCompletion(createCompletionRequest, headers, queryParams)
        // Deserialize response into List<Message>
        val objectMapper =
            jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val conversationResponse =
            objectMapper.readValue<ConversationResponse>(
                (response.body as ChatCompletion)
                    .choices()[0]
                    .message()
                    .content()
                    .get(),
            )

        // Instantiate Conversation without labels, nps
        val messages =
            conversationResponse.messages.map { messageResponse ->
                Message(
                    role = Role.fromString(messageResponse.role),
                    text = messageResponse.content,
                )
            }

        // Set resolved = true if userState = satisfied
        val resolved = userState == "satisfied"

        // Generate a random timestamp between May 1, 2025 and May 12, 2025
        val startDate = Instant.parse("2025-05-01T00:00:00Z")
        val endDate = Instant.parse("2025-05-20T23:59:59Z")
        val randomCreatedAt = generateRandomTimestamp(startDate, endDate)

        return Conversation(
            id = "conv_" + UUID.randomUUID().toString().replace("-", ""),
            createdAt = randomCreatedAt,
            messages = messages,
            labels = emptyList(),
            resolved = resolved,
            nps = null,
            meta =
                mapOf(
                    "userState" to userState,
                    "numberOfTurns" to numberOfTurns,
                    "category" to entry.category,
                    "intent" to entry.intent,
                    "flags" to entry.flags,
                ),
            version = 1,
        )
    }

    private suspend fun saveConversationVector(
        conversation: Conversation,
        apiKey: String,
    ) {
        val messages = objectMapper.writeValueAsString(conversation.messages)
        val request =
            CreateEmbeddingRequest(
                input = messages,
                model = "default",
            )

        val embedResp = embeddingsController.createEmbedding(request, apiKey)
        val embeddings = embedResp.body?.data as List<EmbeddingData>
        if (embeddings.size > 1) throw IllegalStateException("for once conversation embeddings cannot be more than 1")
        val vectorPoint = createConversationVectorPoint(embeddings.first(), 0, conversation)
        qdrantClient.upsertAsync(QDRANTCOLLECTIONS.CONVERSATIONS, listOf(vectorPoint)).get()
    }

    private suspend fun generateGenericLabel(
        messages: List<Message>,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): LabellingResponse {
        val messagesJson = jacksonObjectMapper().writeValueAsString(messages.toString())
        return generateGenericLabel(messagesJson, headers, queryParams)
    }

    private suspend fun generateGenericLabel(
        messagesJson: String,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): LabellingResponse {
        val systemPrompt = """
You are **HandoverJudgeGPT**, an expert in labeling chat transcripts with exactly one Level‑1 (L1) and one Level‑2 (L2) hand‑over code or query_resolved in case customer query is resolved.

Conversation:
$messagesJson

────────────────  TAXONOMY (v0.2)  ────────────────
1. nlu_low_confidence
      • ambiguous_intent  – Two intents nearly tied ("I can't access my account or card")
      • out_of_scope      – Request outside bot's domain ("What's your CEO's birthday?")

2. context_carryover_fail
      • missing_slot_phone_number – Phone slot never filled after reprompts
      • multi_topic_switch       – User changes topic before required slot filled

3. content_gap
      • no_kb_article           – kb lookup misses ⇒ bot says "sorry, i don't know."
      • policy_block            – bot refuses due to legal/policy
      • api_404                 – upstream api returned 404 "not found"
      • steps_exhausted         – bot walked user through all scripted steps but issue persists;
                                   user says it "still doesn't help" and chat is handed to human
      • bot_proactive_escalate  – bot itself admits inability and offers a human agent
      • broken_link_or_capability – bot's link or tool in its instructions fails / not available

4. user_escalation
      • explicit_escalate  – user types "human", "agent", etc.
      • negative_sentiment – strongly negative language or thumbs‑down
      • abusive_language   – profanity or slurs

5. system_error
      • tool_execution_failed - tool executed with error.
      • internal_service_call_failed - dependent service call error. 
      • RATE_LIMIT_HIT — Upstream 429.
      • internal_service_error — unknown technical error occurred.
      • api_404
        – Definition: Upstream says "not found".
        – Example: "Account ID 999 not found."

For hand‑over codes:
- Reason for label choice.
    1. Conclude crisp and clear reason for choice of a label in less than or equal to 200 words.

- Summary of conversation:
    1. Summarise the conversation 2 or 3 sentences.
    2. Summary should clearly indicate pain point of the user.

 ━━━━━━━━  GUIDELINES  ━━━━━━━━
 1. Think step‑by‑step internally …
 2. negative_sentiment outranks content_gap if both apply.
 3. Prefer missing_info_for_context over no_kb_article when bot asked for data.
 """

        val responseFormat =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "labellingSchema",
                        "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(labelling_response_format),
                    ),
            )
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to systemPrompt)),
                model = "o3-mini",
                response_format = responseFormat,
                stream = false,
                store = false,
            )

        val response = completionController.createCompletion(createCompletionRequest, headers, queryParams)
        // Deserialize response into List<Message>
        val objectMapper =
            jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        return objectMapper.readValue<LabellingResponse>(
            (response.body as ChatCompletion)
                .choices()[0]
                .message()
                .content()
                .get(),
        )
    }
}

const val responseFormat = """
{
        "type": "object",
        "properties": {
          "messages": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string"
                },
                "role": {
                  "type": "string",
                  "enum": [
                    "assistant",
                    "user"
                  ]
                }
              },
              "required": [
                "content",
                "role"
              ],
              "additionalProperties": false
            }
          }
        },
        "required": [
          "messages"
        ],
        "additionalProperties": false
      }
"""

const val labelling_response_format = """
 {
  "title": "ConversationAnnotation",
  "type": "object",
  "properties": {
    "level1": {
      "type": "string",
      "description": "Top-level reason for agent handover"
    },
    "level2": {
      "type": "string",
      "description": "More specific sub-reason for handover"
    },
    "reason": {
      "type": "string",
      "description": "Explanation for why these labels were selected"
    },
    "summary": {
      "type": "string",
      "description": "Summarization of the conversation."
    },
    "messages": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "text": {
            "type": "string"
          },
          "role": {
            "type": "string",
            "enum": [
              "ASSISTANT",
              "USER"
            ]
          }
        },
        "required": [
          "text",
          "role"
        ],
        "additionalProperties": false
      }
    }
  },
  "required": [
    "level1",
    "level2",
    "reason",
    "summary",
    "messages"
  ],
  "additionalProperties": false
}
"""

/**
 * Request body for processing a range of entries
 */
data class ProcessRangeRequest(
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val labelConvWithDomain: Boolean = false,
)

data class LabellingResponse(
    val level1: String,
    val level2: String,
    val reason: String,
    val summary: String,
    val messages: List<Message> = emptyList(),
) {
    fun toLabel(): Label =
        Label(
            path = "generic/$level1/$level2",
            source = LabelSource.AUTO,
            status = "final",
            reason = reason,
            createdAt = Instant.now(),
        )
}

data class Transcript(
    val conversationId: String,
    val messages: List<Message>,
    val isGenericLabelAvailable: Boolean,
    val createdAt: Instant,
)
