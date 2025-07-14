#!/bin/bash

# Vector Store Test Script for OpenResponses API
# This script tests vector store functionality in OpenResponses

set -e  # Exit on error

# Text formatting
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Results tracking using simple arrays
test_names=()
test_statuses=()
total_tests=0
passed_tests=0
failed_tests=0

# Declare variables for user input
MODEL_PROVIDER=""
API_KEY=""
MODEL_NAME=""

# Set empty values for other env variables we might not need, to avoid warnings
export GITHUB_TOKEN=${GITHUB_TOKEN:-""}
export BRAVE_API_KEY=${BRAVE_API_KEY:-""}

# Store test result
store_test_result() {
    local name="$1"
    local status="$2"
    
    test_names+=("$name")
    test_statuses+=("$status")
}

# Function to wait for the service to be ready
wait_for_service() {
    local max_attempts=30
    local attempt=1
    local delay=2
    
    echo "Checking if service is ready at http://localhost:6644/actuator/health..."
    
    while [ $attempt -le $max_attempts ]; do
        echo "Attempt $attempt of $max_attempts..."
        
        # Try to connect to the API
        if curl --silent --fail --max-time 2 http://localhost:6644/actuator/health > /dev/null; then
            echo -e "${GREEN}Service is ready!${NC}"
            return 0
        fi
        
        echo "Service not yet ready. Waiting ${delay}s before next attempt..."
        sleep $delay
        ((attempt++))
    done
    
    echo -e "${RED}Service did not become ready within the timeout period.${NC}"
    return 1
}

# Define a function to start containers
start_container() {
    local container_name=$1
    local docker_compose_command=$2
    
    echo -e "\n${BOLD}Starting container: ${container_name}${NC}"
    eval "$docker_compose_command"
    
    # Wait for the service to start
    echo "Waiting for service to start..."
    wait_for_service
    echo -e "${GREEN}Container started${NC}"
}

# Define a function to run tests
run_test() {
    local test_name=$1
    local curl_command_base=$2 # Command string *without* -v and output options
    local expected_status_code=${3:-200}

    echo -e "\n${BOLD}Running test: ${test_name}${NC}"

    # Increment total tests counter
    ((total_tests++))

    # Construct command to get status code
    # Ensure no literal '-v' exists in curl_command_base passed by caller
    local curl_status_cmd="$curl_command_base -w '%{http_code}' -o /dev/null -s" # -s for silent, -o /dev/null discards body, -w writes status code

    # Construct command to get full response for logging (if needed)
    # Note: removes potential -s if user added it, we add it back if needed.
    local curl_body_cmd="$(echo "$curl_command_base" | sed 's/ -s / /g') -s"

    echo "Testing the API endpoint..."
    # Get status code
    status_code=$(eval "$curl_status_cmd" 2>&1) # Eval needed if command has shell variables like $API_KEY
    
    # Check if status_code is a number
    if ! [[ "$status_code" =~ ^[0-9]+$ ]]; then
        echo -e "${RED}✗ Test failed! Could not get valid HTTP status code.${NC}"
        # Run the body command here to get more info for debugging
        echo "Running command to get response body: $curl_body_cmd"
        response_body=$(eval "$curl_body_cmd" 2>&1)
        echo "Full command output: $response_body"
        store_test_result "$test_name" "FAILED (Invalid Status: $status_code)"
        ((failed_tests++))
        return 1
    fi

    # Get response body only if needed (e.g., for logging failure/success)
    response_body=""

    # Check if the status code matches the expected one
    if [[ "$status_code" == "$expected_status_code" ]]; then
        echo -e "${GREEN}✓ Test passed! Status code: $status_code${NC}"
        # Get body for successful log
        # response_body=$(eval "$curl_body_cmd" 2>&1)
        # echo "Response (sample):"
        # echo "$response_body" | head -n 10 # Show truncated response body
        store_test_result "$test_name" "PASSED"
        ((passed_tests++))
        return 0
    else
        echo -e "${RED}✗ Test failed! Expected status code $expected_status_code but got $status_code${NC}"
        # Get body for failed log
        echo "Running command to get response body: $curl_body_cmd"
        response_body=$(eval "$curl_body_cmd" 2>&1)
        echo "Response:"
        echo "$response_body"
        store_test_result "$test_name" "FAILED (Expected: $expected_status_code, Got: $status_code)"
        ((failed_tests++))
        return 1
    fi
}

# Function to print test results summary
print_results_summary() {
    echo -e "\n${BOLD}========== VECTOR STORE TEST RESULTS SUMMARY ==========${NC}"
    echo -e "Total tests run: $total_tests"
    echo -e "${GREEN}Passed: $passed_tests${NC}"
    echo -e "${RED}Failed: $failed_tests${NC}"
    
    echo -e "\n${BOLD}Detailed Test Results:${NC}"
    local i
    for ((i=0; i<${#test_names[@]}; i++)); do
        if [[ "${test_statuses[$i]}" == PASSED* ]]; then
            echo -e "${GREEN}✓ ${test_names[$i]}: ${test_statuses[$i]}${NC}"
        else
            echo -e "${RED}✗ ${test_names[$i]}: ${test_statuses[$i]}${NC}"
        fi
    done
}

# Function to cleanup containers on exit
cleanup() {
    echo -e "\n${BOLD}Cleaning up...${NC}"
    # Stop and remove containers
    docker-compose down -v 2>/dev/null || true
    docker ps | grep ":6644" | awk '{print $1}' | xargs -r docker kill 2>/dev/null || true
    
    # Clean up all temporary files
    echo -e "\n${BOLD}Cleaning up temporary files...${NC}"
    rm -f vector_store_file.txt chunking_test.txt
    
    # Print test results
    print_results_summary
}

# Register trap for cleanup on script exit
trap cleanup EXIT INT TERM

# Main vector store tests function
run_vector_tests() {
    # Prompt user for model provider and API key
    echo -e "${BOLD}Enter the model provider to use (e.g., groq, openai):${NC}"
    read -p "> " MODEL_PROVIDER
    
    echo -e "${BOLD}Enter the API key for $MODEL_PROVIDER:${NC}"
    read -p "> " API_KEY
    
    echo -e "${BOLD}Enter the model name to use (e.g., llama3-70b-8192, gpt-4o):${NC}"
    read -p "> " MODEL_NAME
    
    # Verify input
    if [[ -z "$MODEL_PROVIDER" ]]; then
        echo -e "${RED}Error: Model provider cannot be empty.${NC}"
        exit 1
    fi
    if [[ -z "$MODEL_NAME" ]]; then
        echo -e "${RED}Error: Model name cannot be empty.${NC}"
        exit 1
    fi
    if [[ -z "$API_KEY" ]]; then
        echo -e "${RED}Error: API key cannot be empty.${NC}"
        exit 1
    fi
    
    # Combine provider and model into provider@model format
    FULL_MODEL_NAME="${MODEL_PROVIDER}@${MODEL_NAME}"
    
    echo -e "${GREEN}Using model provider: $MODEL_PROVIDER${NC}"
    echo -e "${GREEN}Using model name: $MODEL_NAME${NC}"
    echo -e "${GREEN}Full model format: $FULL_MODEL_NAME${NC}"
    echo -e "${GREEN}API Key: ****${API_KEY: -4}${NC}" # Show only last 4 chars for confirmation

    # Stop any running containers
    echo "Stopping any running containers..."
    docker-compose down -v 2>/dev/null || true
    # Kill any existing containers that might be using port 8080
    docker ps | grep ":6644" | awk '{print $1}' | xargs -r docker kill 2>/dev/null || true
    sleep 5

    # Start Qdrant container for vector store tests
    echo -e "\n${BOLD}STARTING VECTOR STORE TESTS${NC}"
    start_container "Qdrant Vector Store" "docker-compose --profile qdrant up -d"

    # Test 9: Qdrant Vector Search
    echo -e "\n${BOLD}Test 1: Qdrant Vector Search${NC}"
    run_test "Qdrant Vector Search" \
        "curl --location 'http://localhost:6644/v1/responses' \
        --header 'Content-Type: application/json' \
        --header \"Authorization: Bearer $API_KEY\" \
        --header \"x-model-provider: $MODEL_PROVIDER\" \
        --data '{
            \"model\": \"$FULL_MODEL_NAME\",
            \"stream\": false,
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"What is vector search?\"
                }
            ]
        }'"

    # Test 2: Create Vector Store
    echo -e "\n${BOLD}Test 2: Create Vector Store${NC}"
    run_test "Create Vector Store" \
        "curl --location 'http://localhost:6644/v1/vector_stores' \
        --header 'Content-Type: application/json' \
        --header \"Authorization: Bearer $API_KEY\" \
        --header \"x-model-provider: $MODEL_PROVIDER\" \
        --data '{
            \"name\": \"regression_test_store\",
            \"description\": \"Test vector store for regression testing\"
        }'"

    # Get the vector store ID
    vector_store_data=$(curl --silent --location 'http://localhost:6644/v1/vector_stores' \
        --header 'Content-Type: application/json' \
        --header "Authorization: Bearer $API_KEY" \
        --data '{
            "name": "another_test_store",
            "description": "Another test vector store for regression testing"
        }')

    vector_store_id=$(echo $vector_store_data | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -z "$vector_store_id" ]]; then
        echo -e "${YELLOW}Failed to extract vector store ID, using dummy value for subsequent tests${NC}"
        vector_store_id="vs_dummy_id"
        store_test_result "Vector Store ID Extraction" "FAILED (Could not extract vector store ID)"
        ((failed_tests++))
    else
        echo "Created vector store with ID: $vector_store_id"
        store_test_result "Vector Store ID Extraction" "PASSED"
        ((passed_tests++))
    fi

    # Test 3: List Vector Stores
    echo -e "\n${BOLD}Test 3: List Vector Stores${NC}"
    run_test "List Vector Stores" \
        "curl --location 'http://localhost:6644/v1/vector_stores' \
        --header \"Authorization: Bearer $API_KEY\""

    # Test 4: Get Vector Store
    echo -e "\n${BOLD}Test 4: Get Vector Store${NC}"
    run_test "Get Vector Store" \
        "curl --location 'http://localhost:6644/v1/vector_stores/$vector_store_id' \
        --header \"Authorization: Bearer $API_KEY\""

    # Create a file for vector store upload
    echo "This is some test content for vector store file." > vector_store_file.txt

    # Upload a file first to get ID
    echo "Uploading a file for the vector store test..."
    file_data=$(curl --silent --location 'http://localhost:6644/v1/files' \
        --header "Authorization: Bearer $API_KEY" \
        --form 'file=@"vector_store_file.txt"' \
        --form 'purpose="user_data"')

    echo "Response from file upload: $file_data"
    file_id=$(echo $file_data | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

    if [[ -z "$file_id" ]]; then
        echo -e "${YELLOW}Warning: Failed to upload file for vector store test. Using dummy file ID.${NC}"
        file_id="file_dummy_id"
        store_test_result "Vector Store File Upload" "FAILED (Could not extract file ID)"
        ((failed_tests++))
    else
        echo "Uploaded file with ID: $file_id"
        store_test_result "Vector Store File Upload" "PASSED"
        ((passed_tests++))
        
        # Add a small delay after file upload to ensure it's processed
        echo "Waiting 2 seconds for file to be processed..."
        sleep 2

        # First create a vector store with the files
        echo -e "\n${BOLD}Test 5: Create Vector Store with File${NC}"
        echo "Creating vector store with file ID: $file_id"
        
        vs_with_file_data=$(curl --silent --location 'http://localhost:6644/v1/vector_stores' \
            --header 'Content-Type: application/json' \
            --header "Authorization: Bearer $API_KEY" \
            --data "{
                \"name\": \"test_vector_store_with_file\",
                \"file_ids\": [\"$file_id\"]
            }")
        
        echo "Vector store with file creation response: $vs_with_file_data"
        vs_with_file_id=$(echo $vs_with_file_data | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        
        if [[ -z "$vs_with_file_id" ]]; then
            echo -e "${RED}Failed to create vector store with file${NC}"
            store_test_result "Create Vector Store with File" "FAILED (Could not extract vector store ID)"
            ((failed_tests++))
        else
            echo -e "${GREEN}Successfully created vector store with file, ID: $vs_with_file_id${NC}"
            store_test_result "Create Vector Store with File" "PASSED"
            ((passed_tests++))
            
            # Wait for the vector store to fully process the file
            echo "Waiting for vector store to process the file..."
            max_wait_attempts=10
            wait_attempt=1
            vs_ready=false
            
            while [ $wait_attempt -le $max_wait_attempts ]; do
                echo "Check attempt $wait_attempt of $max_wait_attempts..."
                
                vs_status=$(curl --silent "http://localhost:6644/v1/vector_stores/$vs_with_file_id" \
                    --header "Authorization: Bearer $API_KEY")
                
                file_counts=$(echo "$vs_status" | grep -o '"file_counts":{[^}]*}' || echo "")
                status=$(echo "$vs_status" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "")
                completed=$(echo "$file_counts" | grep -o '"completed":[0-9]*' | cut -d':' -f2 || echo "0")
                total=$(echo "$file_counts" | grep -o '"total":[0-9]*' | cut -d':' -f2 || echo "0")
                
                echo "Vector store status: $status, Files processed: $completed/$total"
                
                if [[ "$status" == "ready" ]] || [[ "$completed" == "$total" && "$total" != "0" ]]; then
                    echo -e "${GREEN}Vector store is ready for querying!${NC}"
                    vs_ready=true
                    break
                fi
                
                echo "Vector store not ready yet. Waiting 5 seconds before next check..."
                sleep 5
                ((wait_attempt++))
            done
            
            if [[ "$vs_ready" == false ]]; then
                echo -e "${YELLOW}Warning: Vector store might not be fully ready after maximum wait time.${NC}"
                echo "Proceeding with tests anyway, but they might fail if files aren't processed."
            fi
            
            # Test 6: List Files in Vector Store
            echo -e "\n${BOLD}Test 6: List Files in Vector Store${NC}"
            run_test "List Files in Vector Store" \
                "curl --location 'http://localhost:6644/v1/vector_stores/$vs_with_file_id/files' \
                --header \"Authorization: Bearer $API_KEY\""
            
            # Test 7: Vector Store Query with file
            echo -e "\n${BOLD}Test 7: Vector Store Query${NC}"
            
            # Get the vector store status before querying
            vs_status=$(curl --silent "http://localhost:6644/v1/vector_stores/$vs_with_file_id" \
                --header "Authorization: Bearer $API_KEY")
            echo "Current vector store status before query:"
            echo "$vs_status" | grep -o '"status":"[^"]*"' || echo "Status not found"
            
            query_endpoint="http://localhost:6644/v1/vector_stores/$vs_with_file_id/search"
            echo "Search endpoint: $query_endpoint"
            
            run_test "Vector Store Query" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3
                }'" \
                "200"
                
            # Test 7a: Vector Store Query with Comparison Filter
            echo -e "\n${BOLD}Test 7a: Vector Store Query with Comparison Filter${NC}"
            filter_test_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data '{
                    "query": "test content",
                    "max_num_results": 10,
                    "filters": {
                        "type": "eq",
                        "key": "filename",
                        "value": "vector_store_file.txt"
                    }
                }')
            
            # Validate filter response contains results and filename matches
            result_count=$(echo "$filter_test_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $result_count -gt 0 ]]; then
                filenames=$(echo "$filter_test_response" | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | sort -u)
                echo "Filter results count: $result_count, Filenames: $filenames"
                
                # Check that all filenames match the filter
                if echo "$filenames" | grep -q "vector_store_file.txt"; then
                    echo -e "${GREEN}✓ Filename filter working correctly${NC}"
                    store_test_result "Filename Filter Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ Filename filter not working - results don't match filter${NC}"
                    store_test_result "Filename Filter Validation" "FAILED (Results don't match filter)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for filename filter test${NC}"
                store_test_result "Filename Filter Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with Comparison Filter" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"filters\": {
                        \"type\": \"eq\",
                        \"key\": \"filename\",
                        \"value\": \"vector_store_file.txt\"
                    }
                }'" \
                "200"
                
            # Test 7b: Vector Store Query with Compound Filter (AND)
            echo -e "\n${BOLD}Test 7b: Vector Store Query with Compound Filter (AND)${NC}"
            and_filter_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data "{
                    \"query\": \"test content\",
                    \"max_num_results\": 10,
                    \"filters\": {
                        \"type\": \"and\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"filename\",
                                \"value\": \"vector_store_file.txt\"
                            },
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            }
                        ]
                    }
                }")
            
            # Validate AND filter response
            and_result_count=$(echo "$and_filter_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $and_result_count -gt 0 ]]; then
                # Check file_id and filename
                and_filenames=$(echo "$and_filter_response" | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | sort -u)
                and_file_ids=$(echo "$and_filter_response" | grep -o '"file_id":"[^"]*"' | cut -d'"' -f4 | sort -u)
                echo "AND filter results count: $and_result_count"
                echo "Filenames: $and_filenames"
                echo "File IDs: $and_file_ids"
                
                # Check that all results match both filter conditions
                if echo "$and_filenames" | grep -q "vector_store_file.txt" && \
                   echo "$and_file_ids" | grep -q "$file_id"; then
                    echo -e "${GREEN}✓ AND filter working correctly${NC}"
                    store_test_result "AND Filter Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ AND filter not working - results don't match both conditions${NC}"
                    store_test_result "AND Filter Validation" "FAILED (Results don't match both conditions)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for AND filter test${NC}"
                store_test_result "AND Filter Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with Compound Filter (AND)" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"filters\": {
                        \"type\": \"and\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"filename\",
                                \"value\": \"vector_store_file.txt\"
                            },
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            }
                        ]
                    }
                }'" \
                "200"
                
            # Test 7c: Vector Store Query with Compound Filter (OR)
            echo -e "\n${BOLD}Test 7c: Vector Store Query with Compound Filter (OR)${NC}"
            or_filter_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data "{
                    \"query\": \"test content\",
                    \"max_num_results\": 10,
                    \"filters\": {
                        \"type\": \"or\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"filename\",
                                \"value\": \"non_existent_file.txt\"
                            },
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            }
                        ]
                    }
                }")
            
            # Validate OR filter response
            or_result_count=$(echo "$or_filter_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $or_result_count -gt 0 ]]; then
                # Check file_id - we expect this to match but not the non-existent filename
                or_file_ids=$(echo "$or_filter_response" | grep -o '"file_id":"[^"]*"' | cut -d'"' -f4 | sort -u)
                echo "OR filter results count: $or_result_count"
                echo "File IDs: $or_file_ids"
                
                # Check that file_id matches in at least one result (the OR condition)
                if echo "$or_file_ids" | grep -q "$file_id"; then
                    echo -e "${GREEN}✓ OR filter working correctly${NC}"
                    store_test_result "OR Filter Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ OR filter not working - results don't match any condition${NC}"
                    store_test_result "OR Filter Validation" "FAILED (Results don't match any condition)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for OR filter test${NC}"
                store_test_result "OR Filter Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with Compound Filter (OR)" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"filters\": {
                        \"type\": \"or\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"filename\",
                                \"value\": \"non_existent_file.txt\"
                            },
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            }
                        ]
                    }
                }'" \
                "200"
                
            # Test 7d: Vector Store Query with Numeric Comparison Filter
            echo -e "\n${BOLD}Test 7d: Vector Store Query with Numeric Comparison Filter${NC}"
            numeric_filter_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data '{
                    "query": "test content",
                    "max_num_results": 10,
                    "filters": {
                        "type": "gte",
                        "key": "chunk_index",
                        "value": 0
                    }
                }')
            
            # Validate numeric filter response
            numeric_result_count=$(echo "$numeric_filter_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $numeric_result_count -gt 0 ]]; then
                # Extract chunk indices to verify they're all >= 0
                chunk_indices=$(echo "$numeric_filter_response" | grep -o '"chunk_index":[0-9]*' | cut -d':' -f2)
                echo "Numeric filter results count: $numeric_result_count"
                echo "Chunk indices: $chunk_indices"
                
                # Check if any chunk indices are negative (should be none)
                invalid_indices=$(echo "$chunk_indices" | grep -c '^-')
                if [[ $invalid_indices -eq 0 ]]; then
                    echo -e "${GREEN}✓ Numeric comparison filter working correctly${NC}"
                    store_test_result "Numeric Filter Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ Numeric comparison filter not working - found negative indices${NC}"
                    store_test_result "Numeric Filter Validation" "FAILED (Invalid values found)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for numeric filter test${NC}"
                store_test_result "Numeric Filter Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with Numeric Comparison Filter" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"filters\": {
                        \"type\": \"gte\",
                        \"key\": \"chunk_index\",
                        \"value\": 0
                    }
                }'" \
                "200"
            
            # Test 7e: Vector Store Query with Nested Compound Filter
            echo -e "\n${BOLD}Test 7e: Vector Store Query with Nested Compound Filter${NC}"
            nested_filter_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data "{
                    \"query\": \"test content\",
                    \"max_num_results\": 10,
                    \"filters\": {
                        \"type\": \"and\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            },
                            {
                                \"type\": \"or\",
                                \"filters\": [
                                    {
                                        \"type\": \"eq\",
                                        \"key\": \"filename\",
                                        \"value\": \"vector_store_file.txt\"
                                    },
                                    {
                                        \"type\": \"eq\",
                                        \"key\": \"chunk_index\",
                                        \"value\": 0
                                    }
                                ]
                            }
                        ]
                    }
                }")
            
            # Validate nested filter response
            nested_result_count=$(echo "$nested_filter_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $nested_result_count -gt 0 ]]; then
                # Check conditions
                nested_file_ids=$(echo "$nested_filter_response" | grep -o '"file_id":"[^"]*"' | cut -d'"' -f4 | sort -u)
                nested_filenames=$(echo "$nested_filter_response" | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | sort -u)
                nested_chunks=$(echo "$nested_filter_response" | grep -o '"chunk_index":[0-9]*' | cut -d':' -f2 | sort -u)
                
                echo "Nested filter results count: $nested_result_count"
                echo "File IDs: $nested_file_ids"
                echo "Filenames: $nested_filenames"
                echo "Chunk indices: $nested_chunks"
                
                # Check that all file_ids match (AND part) and at least one OR condition is true
                if echo "$nested_file_ids" | grep -q "$file_id" && \
                   (echo "$nested_filenames" | grep -q "vector_store_file.txt" || echo "$nested_chunks" | grep -q "^0$"); then
                    echo -e "${GREEN}✓ Nested compound filter working correctly${NC}"
                    store_test_result "Nested Filter Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ Nested compound filter not working correctly${NC}"
                    store_test_result "Nested Filter Validation" "FAILED (Condition not met)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for nested filter test${NC}"
                store_test_result "Nested Filter Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with Nested Compound Filter" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"filters\": {
                        \"type\": \"and\",
                        \"filters\": [
                            {
                                \"type\": \"eq\",
                                \"key\": \"file_id\",
                                \"value\": \"$file_id\"
                            },
                            {
                                \"type\": \"or\",
                                \"filters\": [
                                    {
                                        \"type\": \"eq\",
                                        \"key\": \"filename\",
                                        \"value\": \"vector_store_file.txt\"
                                    },
                                    {
                                        \"type\": \"eq\",
                                        \"key\": \"chunk_index\",
                                        \"value\": 0
                                    }
                                ]
                            }
                        ]
                    }
                }'" \
                "200"
            
            # Test 7f: Vector Store Query with RankingOptions
            echo -e "\n${BOLD}Test 7f: Vector Store Query with RankingOptions${NC}"
            ranking_filter_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data '{
                    "query": "test content",
                    "max_num_results": 10,
                    "ranking_options": {
                        "ranker": "auto",
                        "score_threshold": 0.1
                    }
                }')
            
            # Validate ranking response
            ranking_result_count=$(echo "$ranking_filter_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            if [[ $ranking_result_count -gt 0 ]]; then
                # Extract scores to verify they're all >= threshold
                scores=$(echo "$ranking_filter_response" | grep -o '"score":[0-9.]*' | cut -d':' -f2)
                echo "Ranking results count: $ranking_result_count"
                echo "Scores: $scores"
                
                # Check if any scores are below threshold (should be none)
                below_threshold=$(echo "$scores" | awk '{ if ($1 < 0.1) print $1 }' | wc -l)
                if [[ $below_threshold -eq 0 ]]; then
                    echo -e "${GREEN}✓ Ranking options working correctly - all scores above threshold${NC}"
                    store_test_result "Ranking Options Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${RED}✗ Ranking options not working - found scores below threshold${NC}"
                    store_test_result "Ranking Options Validation" "FAILED (Scores below threshold)"
                    ((failed_tests++))
                fi
            else
                echo -e "${YELLOW}Warning: No results returned for ranking options test${NC}"
                store_test_result "Ranking Options Validation" "FAILED (No results)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Vector Store Query with RankingOptions" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"test content\",
                    \"max_num_results\": 3,
                    \"ranking_options\": {
                        \"ranker\": \"auto\",
                        \"score_threshold\": 0.1
                    }
                }'" \
                "200"
            
            # Test 7g: Create Vector Store with Static ChunkingStrategy
            echo -e "\n${BOLD}Test 7g: Create Vector Store with Static ChunkingStrategy${NC}"
            
            # Create another sample file for chunking strategy test
            echo "This is a larger document with multiple paragraphs for testing chunking strategies.
            
            Chunking strategies are important for ensuring that documents are indexed properly
            in vector stores. Different strategies may be appropriate for different types of content.
            
            Static chunking uses fixed sizes with overlap, which works well for most content types.
            This test verifies that custom chunking settings can be applied during indexing." > chunking_test.txt
            
            # Upload the file first
            echo "Uploading a file for the chunking strategy test..."
            chunking_file_data=$(curl --silent --location 'http://localhost:6644/v1/files' \
                --header "Authorization: Bearer $API_KEY" \
                --form 'file=@"chunking_test.txt"' \
                --form 'purpose="user_data"')
            
            chunking_file_id=$(echo $chunking_file_data | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
            
            if [[ -z "$chunking_file_id" ]]; then
                echo -e "${YELLOW}Warning: Failed to upload file for chunking test. Using dummy file ID.${NC}"
                chunking_file_id="chunking_dummy_id"
                store_test_result "Chunking Test File Upload" "FAILED (Could not extract file ID)"
                ((failed_tests++))
                
                # Clean up the chunking test file even when upload fails
                rm -f chunking_test.txt
            else
                echo "Uploaded chunking test file with ID: $chunking_file_id"
                store_test_result "Chunking Test File Upload" "PASSED"
                ((passed_tests++))
                
                # Now create vector store with chunking strategy
                echo "Creating vector store with chunking strategy..."
                run_test "Create Vector Store with Static ChunkingStrategy" \
                    "curl --location 'http://localhost:6644/v1/vector_stores' \
                    --header 'Content-Type: application/json' \
                    --header \"Authorization: Bearer $API_KEY\" \
                    --data '{
                        \"name\": \"chunking_test_store\",
                        \"file_ids\": [\"$chunking_file_id\"],
                        \"chunking_strategy\": {
                            \"type\": \"static\",
                            \"static\": {
                                \"max_chunk_size_tokens\": 100,
                                \"chunk_overlap_tokens\": 20
                            }
                        }
                    }'" \
                    "200"
                
                # Clean up the chunking test file
                rm -f chunking_test.txt
            fi
            
            # Test 7h: Modify Vector Store File Attributes
            echo -e "\n${BOLD}Test 7h: Modify Vector Store File Attributes${NC}"
            attributes_update_response=$(curl --silent --location 'http://localhost:6644/v1/vector_stores/$vs_with_file_id/files/$file_id' \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --data '{
                    "attributes": {
                        "category": "test",
                        "priority": 1,
                        "is_important": true
                    }
                }')
                
            # Validate that the attributes update works
            updated_attributes=$(echo "$attributes_update_response" | grep -o '"attributes":{[^}]*}' | cut -d'{' -f2 | cut -d'}' -f1)
            echo "Updated attributes: $updated_attributes"
            
            if echo "$updated_attributes" | grep -q "category" && echo "$updated_attributes" | grep -q "priority" && echo "$updated_attributes" | grep -q "is_important"; then
                echo -e "${GREEN}✓ Vector store file attributes updated successfully${NC}"
                store_test_result "Vector Store File Attributes Update" "PASSED"
                ((passed_tests++))
            else
                echo -e "${RED}✗ Vector store file attributes update failed${NC}"
                store_test_result "Vector Store File Attributes Update" "FAILED"
                ((failed_tests++))
            fi
            
            # Now wait a moment for attributes to apply to vector store before testing query
            echo "Waiting 5 seconds for attributes to be applied in the vector database..."
            sleep 5
            
            # Perform a query that uses the new attributes
            echo "Testing query with the new attributes..."
            attributes_query_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data '{
                    "query": "test content",
                    "max_num_results": 3,
                    "filters": {
                        "type": "eq",
                        "key": "category",
                        "value": "test"
                    }
                }')
                
            # Validate that the query returns results with our updated attributes
            attributes_query_results=$(echo "$attributes_query_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            
            if [[ $attributes_query_results -gt 0 ]]; then
                echo -e "${GREEN}✓ Query with new attributes returned $attributes_query_results results${NC}"
                store_test_result "Query with New Attributes" "PASSED"
                ((passed_tests++))
            else
                echo -e "${RED}✗ Query with new attributes failed to return results${NC}"
                store_test_result "Query with New Attributes" "FAILED"
                ((failed_tests++))
            fi
            
            # Also run the regular API call test
            run_test "Modify Vector Store File Attributes" \
                "curl --location 'http://localhost:6644/v1/vector_stores/$vs_with_file_id/files/$file_id' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --data '{
                    \"attributes\": {
                        \"category\": \"test\",
                        \"priority\": 1,
                        \"is_important\": true
                    }
                }'" \
                "200"
            
            # Test 7i: Query with Rewriting Enabled
            echo -e "\n${BOLD}Test 7i: Query with Rewriting Enabled${NC}"
            rewrite_query_response=$(curl --silent --location "$query_endpoint" \
                --header 'Content-Type: application/json' \
                --header "Authorization: Bearer $API_KEY" \
                --header "x-model-provider: $MODEL_PROVIDER" \
                --data '{
                    "query": "what is in the file?",
                    "max_num_results": 10,
                    "rewrite_query": true
                }')
            
            # Validate rewrite query response
            rewrite_result_count=$(echo "$rewrite_query_response" | grep -o '"data":\[.*\]' | grep -o '\[.*\]' | grep -o '{' | wc -l)
            has_rewritten_query=$(echo "$rewrite_query_response" | grep -o '"rewritten_query":"[^"]*"' | wc -l)
            
            echo "Rewrite query results count: $rewrite_result_count"
            echo "Has rewritten query: $has_rewritten_query"
            
            if [[ $rewrite_result_count -gt 0 && $has_rewritten_query -gt 0 ]]; then
                # Extract the rewritten query for inspection
                rewritten_query=$(echo "$rewrite_query_response" | grep -o '"rewritten_query":"[^"]*"' | cut -d'"' -f4)
                echo "Original query: \"what is in the file?\""
                echo "Rewritten query: \"$rewritten_query\""
                
                # Check if rewritten query is different from original
                if [[ "$rewritten_query" != "what is in the file?" && ! -z "$rewritten_query" ]]; then
                    echo -e "${GREEN}✓ Query rewriting working correctly${NC}"
                    store_test_result "Query Rewriting Validation" "PASSED"
                    ((passed_tests++))
                else
                    echo -e "${YELLOW}Warning: Query rewriting didn't modify the query or returned empty rewrite${NC}"
                    store_test_result "Query Rewriting Validation" "WARNING (Minimal change)"
                fi
            else
                echo -e "${RED}✗ Query rewriting not working - no results or no rewritten query${NC}"
                store_test_result "Query Rewriting Validation" "FAILED (No rewritten query)"
                ((failed_tests++))
            fi
            
            # Also run the regular status check
            run_test "Query with Rewriting Enabled" \
                "curl --location '$query_endpoint' \
                --header 'Content-Type: application/json' \
                --header \"Authorization: Bearer $API_KEY\" \
                --header \"x-model-provider: $MODEL_PROVIDER\" \
                --data '{
                    \"query\": \"what is in the file?\",
                    \"max_num_results\": 3,
                    \"rewrite_query\": true
                }'" \
                "200"
            
            # Test 7j: Get Vector Store File Content
            echo -e "\n${BOLD}Test 7j: Get Vector Store File Content${NC}"
            run_test "Get Vector Store File Content" \
                "curl --location 'http://localhost:6644/v1/vector_stores/$vs_with_file_id/files/$file_id/content' \
                --header \"Authorization: Bearer $API_KEY\"" \
                "200"
            
            # Test 8: Delete Vector Store with file
            echo -e "\n${BOLD}Test 8: Delete Vector Store with File${NC}"
            run_test "Delete Vector Store with File" \
                "curl --location --request DELETE 'http://localhost:6644/v1/vector_stores/$vs_with_file_id' \
                --header \"Authorization: Bearer $API_KEY\""
        fi
    fi

    # Clean up all temporary files
    echo -e "\n${BOLD}Cleaning up temporary files...${NC}"
    rm -f vector_store_file.txt chunking_test.txt
    
    # Test 9: Test Chat API with Vector Store and correct filters format
    echo -e "\n${BOLD}Test 9: Chat API with Vector Store and Correct Filters Format${NC}"
    run_test "Chat API with Vector Store and Correct Filters Format" \
        "curl --location 'http://localhost:6644/v1/responses' \
        --header 'Content-Type: application/json' \
        --header \"Authorization: Bearer $API_KEY\" \
        --header \"x-model-provider: $MODEL_PROVIDER\" \
        --data '{
            \"model\": \"$FULL_MODEL_NAME\",
            \"stream\": false,
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"What is in the vector store?\"
                }
            ],
            \"tools\": [
                {
                    \"type\": \"file_search\",
                    \"vector_store_ids\": [\"$vector_store_id\"],
                    \"max_num_results\": 5,
                    \"filters\": {
                        \"type\": \"eq\",
                        \"key\": \"file_id\",
                        \"value\": \"$file_id\"
                    }
                }
            ]
        }'"
    
    # Test 10: Delete Original Vector Store
    echo -e "\n${BOLD}Test 10: Delete Original Vector Store${NC}"
    run_test "Delete Original Vector Store" \
        "curl --location --request DELETE 'http://localhost:6644/v1/vector_stores/$vector_store_id' \
        --header \"Authorization: Bearer $API_KEY\""

    echo -e "\n${BOLD}VECTOR STORE TESTS COMPLETED${NC}"
}

# Run the vector store tests
run_vector_tests
