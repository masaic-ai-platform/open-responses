package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.RunDataSource

/**
 * Interface for processing different types of data sources for evaluation runs.
 */
interface DataSourceProcessor {
    /**
     * Checks if this processor can handle the given data source type.
     *
     * @param dataSource The data source to check
     * @return True if this processor can handle the data source
     */
    fun canProcess(dataSource: RunDataSource): Boolean

    /**
     * Process the data source and prepare the appropriate data structure based on the source type.
     * Different implementations will return different subclasses of DataSourceProcessingResult.
     *
     * @param dataSource The data source to process
     * @return Result of processing the data source
     */
    suspend fun processDataSource(dataSource: RunDataSource): DataSourceProcessingResult

    /**
     * Get the raw data lines from the data source.
     * 
     * @param dataSource The data source to get lines from
     * @return List of raw data lines as strings
     */
    suspend fun getRawDataLines(dataSource: RunDataSource): List<String>
} 
