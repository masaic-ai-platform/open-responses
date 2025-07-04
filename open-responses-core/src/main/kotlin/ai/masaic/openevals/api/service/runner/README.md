# EvalRunner Refactoring

This directory contains a refactored implementation of the `EvalRunner` with a more modular architecture that separates concerns and makes the code more maintainable and extensible.

## Architecture Overview

The refactoring breaks down the monolithic `EvalRunner` class into multiple components with clear responsibilities:

1. **EvalRunner**: Orchestrates the overall evaluation process, delegating specific tasks to specialized components.

2. **DataSourceProcessor**: Handles different types of data sources.
   - `FileDataSourceProcessor`: Processes file-based data sources.
   - Uses a flexible `DataSourceProcessingResult` design that supports different output formats:
     - `CompletionMessagesResult`: For chat completions
     - `JsonlDataResult`: For structured JSON data
     - `EmptyProcessingResult`: When no data can be processed
   - Future implementations can handle other data source types without modifying existing code.

3. **GenerationService**: Manages completion generation from different APIs.
   - `ChatCompletionsGenerationService`: Handles OpenAI chat completions.
   - Future implementations can be added for different APIs (like Responses API).

4. **CriterionEvaluator**: Evaluates specific types of testing criteria.
   - `StringCheckEvaluator`: For string comparison tests.
   - `TextSimilarityEvaluator`: For text similarity tests.
   - Future evaluators can be added for other test types.

5. **CriterionEvaluatorFactory**: Manages different criterion evaluators and routes evaluation to the appropriate one.

6. **ResultProcessor**: Handles calculation of result metrics and statistics.

## Integration Steps

To transition from the old monolithic `EvalRunner` to this new architecture:

1. Move the original `EvalRunner` class out of the `ai.masaic.openevals.api.service` package.
2. Place these new classes in the `ai.masaic.openevals.api.service.runner` package.
3. Update any service that was calling the original `EvalRunner` to call the new one instead.
4. If needed, add a thin adapter layer to maintain backward compatibility during the transition.

## Extension Points

This refactored architecture makes it easy to extend functionality:

1. **New Data Sources**: Implement a new `DataSourceProcessor` for any new data source type.
2. **New Result Types**: Add new subclasses of `DataSourceProcessingResult` for different data formats.
3. **New Generation Services**: Implement a new `GenerationService` for any new completion API.
4. **New Testing Criteria**: Implement a new `CriterionEvaluator` for any new evaluation criteria.

These extensions can be made without modifying existing code, adhering to the Open-Closed Principle. 