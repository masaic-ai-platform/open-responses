#!/bin/bash

# Regression Test Script for OpenResponses API
# This script tests different flavors of the application by starting them with docker-compose
# and making curl requests to verify they're working correctly.

set -e  # Exit on error

# Text formatting
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Set the default environment file path
ENV_FILE_PATH=".env"

# Results tracking using simple arrays
test_names=()
test_statuses=()
total_tests=0
passed_tests=0
failed_tests=0

# Load environment variables from .env file
if [ -f "$ENV_FILE_PATH" ]; then
    echo "Loading API keys from $ENV_FILE_PATH file..."
    set -a  # Mark all variables for export
    source "$ENV_FILE_PATH"
    set +a  # Turn off auto-export
    echo -e "${GREEN}Successfully loaded environment variables from $ENV_FILE_PATH file${NC}"
else
    echo -e "${YELLOW}No $ENV_FILE_PATH file found. Make sure environment variables are set manually.${NC}"
fi

# Set empty values for variables we don't need to avoid warnings
export GITHUB_TOKEN=${GITHUB_TOKEN:-""}
export BRAVE_API_KEY=${BRAVE_API_KEY:-""}

# Set flag to indicate we're not in cleanup
IN_CLEANUP="false"

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
    
    echo "Checking if service is ready at http://localhost:8080/v1/models..."
    
    while [ $attempt -le $max_attempts ]; do
        echo "Attempt $attempt of $max_attempts..."
        
        # Try to connect to the API
        if curl --silent --fail --max-time 2 http://localhost:8080/v1/models > /dev/null; then
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
    local curl_command=$2
    local expected_status_code=${3:-200}

    echo -e "\n${BOLD}Running test: ${test_name}${NC}"
    
    # Increment total tests counter
    ((total_tests++))
    
    # Run the curl command and capture the response
    echo "Testing the API endpoint..."
    response=$(eval "$curl_command" 2>&1)
    status_code=$(echo "$response" | grep -oE 'HTTP/[0-9.]+ [0-9]+' | awk '{print $2}')
    
    # Check if the status code matches the expected one
    if [[ "$status_code" == "$expected_status_code" ]]; then
        echo -e "${GREEN}✓ Test passed! Status code: $status_code${NC}"
        echo "Response:"
        echo "$response" | grep -v "HTTP/" | head -n 20  # Show truncated response for readability
        store_test_result "$test_name" "PASSED"
        ((passed_tests++))
        return 0
    else
        echo -e "${RED}✗ Test failed! Expected status code $expected_status_code but got $status_code${NC}"
        echo "Response:"
        echo "$response"
        store_test_result "$test_name" "FAILED (Expected: $expected_status_code, Got: $status_code)"
        ((failed_tests++))
        return 1
    fi
}

# Function to print test results summary
print_results_summary() {
    echo -e "\n${BOLD}========== TEST RESULTS SUMMARY ==========${NC}"
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
    # Only run full cleanup if this is not an interrupt during normal test execution
    if [ "$1" != "INT" ] || [ "$IN_CLEANUP" = "true" ]; then
        echo -e "\n${BOLD}Cleaning up...${NC}"
        docker-compose down -v 2>/dev/null || true
        docker ps | grep ":8080" | awk '{print $1}' | xargs -r docker kill
    fi
    
    print_results_summary
    
    # If this is an interrupt, exit with a non-zero status
    if [ "$1" = "INT" ]; then
        exit 130
    fi
    
    exit
}

# Function to run a section of tests
run_section() {
    local section_name=$1
    
    echo -e "\n${BOLD}==================== RUNNING SECTION: $section_name ====================${NC}"
    IN_CLEANUP="true"  # Mark that we're in a controlled section to prevent premature cleanup
    
    # Execute the section function
    $section_name
    
    echo -e "\n${BOLD}==================== COMPLETED SECTION: $section_name ====================${NC}"
    IN_CLEANUP="false"  # Reset the flag
}

# Basic tests section
run_basic_tests() {
    # Start the basic container once for all basic tests
    start_container "Basic OpenResponses" "docker-compose up -d open-responses"

    # Test 1: Basic Setup - Chat Completion
    echo -e "\n${BOLD}Test 1: Basic Setup - Chat Completion${NC}"
    run_test "Basic Setup - Chat Completion" \
        "curl --location 'http://localhost:8080/v1/responses' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' \
        --data '{
            \"model\": \"qwen-qwq-32b\",
            \"stream\": false,
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"Hello, how are you?\"
                }
            ]
        }' -v"

    # Test 2: Streaming Response
    echo -e "\n${BOLD}Test 2: Streaming Response${NC}"
    run_test "Streaming Response" \
        "curl --location 'http://localhost:8080/v1/responses' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' \
        --data '{
            \"model\": \"qwen-qwq-32b\",
            \"stream\": true,
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"Tell me a short story about a programmer\"
                }
            ]
        }' -v"

    # Test 7: Get Available Models
    echo -e "\n${BOLD}Test 7: Get Available Models${NC}"
    run_test "Get Available Models" \
        "curl --location 'http://localhost:8080/v1/models' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' -v"
}

# File operations tests section
run_file_operations_tests() {
    # Use the same container for file operations
    echo -e "\n${BOLD}STARTING FILE OPERATIONS TESTS${NC}"

    # Test 10: File Upload - Create a sample text file
    echo "This is a sample text file for testing file uploads." > sample_file.txt
    echo -e "\n${BOLD}Test 10: File Upload${NC}"
    run_test "File Upload" \
        "curl --location 'http://localhost:8080/v1/files' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --form 'file=@\"sample_file.txt\"' \
        --form 'purpose=\"user_data\"' -v"

    # Get the file ID from the response
    file_data=$(curl --silent --location 'http://localhost:8080/v1/files' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --form 'file=@"sample_file.txt"' \
        --form 'purpose="user_data"')

    file_id=$(echo $file_data | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

    if [[ -z "$file_id" ]]; then
        echo -e "${YELLOW}Failed to extract file ID, using dummy value for subsequent tests${NC}"
        file_id="file_dummy_id"
        store_test_result "File Upload - ID Extraction" "FAILED (Could not extract file ID)"
        ((failed_tests++))
    else
        echo "Uploaded file with ID: $file_id"
        store_test_result "File Upload - ID Extraction" "PASSED"
        ((passed_tests++))
    fi

    # Test 11: List Files
    echo -e "\n${BOLD}Test 11: List Files${NC}"
    run_test "List Files" \
        "curl --location 'http://localhost:8080/v1/files?limit=10&order=desc' \
        --header 'Authorization: Bearer $GROQ_API_KEY' -v"

    # Test 12: Get File
    echo -e "\n${BOLD}Test 12: Get File${NC}"
    run_test "Get File" \
        "curl --location 'http://localhost:8080/v1/files/$file_id' \
        --header 'Authorization: Bearer $GROQ_API_KEY' -v"

    # Test 13: Get File Content
    echo -e "\n${BOLD}Test 13: Get File Content${NC}"
    run_test "Get File Content" \
        "curl --location 'http://localhost:8080/v1/files/$file_id/content' \
        --header 'Authorization: Bearer $GROQ_API_KEY' -v"

    # Test 14: Delete File
    echo -e "\n${BOLD}Test 14: Delete File${NC}"
    run_test "Delete File" \
        "curl --location --request DELETE 'http://localhost:8080/v1/files/$file_id' \
        --header 'Authorization: Bearer $GROQ_API_KEY' -v"

    # Clean up the sample file
    rm -f sample_file.txt
}

# MongoDB tests section
run_mongodb_tests() {
    # Stop the basic container before switching to MongoDB profile
    echo "Stopping basic container before switching to MongoDB..."
    docker-compose down -v 2>/dev/null || true
    # Kill any existing containers that might be using port 8080
    docker ps | grep ":8080" | awk '{print $1}' | xargs -r docker kill
    # Wait for containers to fully shut down
    sleep 5

    # Start MongoDB container once for all MongoDB tests
    echo -e "\n${BOLD}STARTING MONGODB TESTS${NC}"
    start_container "MongoDB OpenResponses" "docker-compose --profile mongodb up -d"

    # Test 3: MongoDB Persistence
    echo -e "\n${BOLD}Test 3: MongoDB Persistence${NC}"
    run_test "MongoDB Persistence" \
        "curl --location 'http://localhost:8080/v1/responses' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' \
        --data '{
            \"model\": \"qwen-qwq-32b\",
            \"store\": true,
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"Write a short poem about testing\"
                }
            ]
        }' -v"

    # Create a response and extract the ID for subsequent tests
    echo "Creating a stored response for subsequent tests..."
    response_data=$(curl --silent --location 'http://localhost:8080/v1/responses' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Bearer '$GROQ_API_KEY \
        --header 'x-model-provider: groq' \
        --data '{
            "model": "qwen-qwq-32b",
            "store": true,
            "input": [
                {
                    "role": "user",
                    "content": "Write a short poem about software testing"
                }
            ]
        }')

    response_id=$(echo $response_data | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

    if [[ -z "$response_id" ]]; then
        echo -e "${YELLOW}Failed to extract response ID, using dummy value for subsequent tests${NC}"
        response_id="resp_dummy_id"
        store_test_result "Response ID Extraction" "FAILED (Could not extract response ID)"
        ((failed_tests++))
    else
        echo "Created response with ID: $response_id"
        store_test_result "Response ID Extraction" "PASSED"
        ((passed_tests++))
    fi

    # Test 4: Get Response by ID
    echo -e "\n${BOLD}Test 4: Get Response by ID${NC}"
    run_test "Get Response by ID" \
        "curl --location 'http://localhost:8080/v1/responses/$response_id' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' -v"

    # Test 6: Get Input Items for Response
    echo -e "\n${BOLD}Test 6: Get Input Items for Response${NC}"
    run_test "Get Input Items for Response" \
        "curl --location 'http://localhost:8080/v1/responses/$response_id/input_items' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' -v"

    # Test 8: Multi-Turn Conversation
    echo -e "\n${BOLD}Test 8: Multi-Turn Conversation${NC}"
    run_test "Multi-Turn Conversation" \
        "curl --location 'http://localhost:8080/v1/responses' \
        --header 'Content-Type: application/json' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' \
        --data '{
            \"model\": \"qwen-qwq-32b\",
            \"store\": true,
            \"previous_response_id\": \"$response_id\",
            \"input\": [
                {
                    \"role\": \"user\",
                    \"content\": \"Can you explain more about regression testing specifically?\"
                }
            ]
        }' -v"

    # Test 5: Delete Response
    echo -e "\n${BOLD}Test 5: Delete Response${NC}"
    run_test "Delete Response" \
        "curl --location --request DELETE 'http://localhost:8080/v1/responses/$response_id' \
        --header 'Authorization: Bearer $GROQ_API_KEY' \
        --header 'x-model-provider: groq' -v"
}

# Register different traps for different signals
trap "cleanup EXIT" EXIT
trap "cleanup INT" INT
trap "cleanup TERM" TERM

# Main test execution
main() {
    # Initialize environment variables
    echo -e "${YELLOW}Checking GROQ_API_KEY for tests${NC}"
    if [[ -n "$GROQ_API_KEY" ]]; then
        echo -e "${YELLOW}Current value: ${GROQ_API_KEY:0:5}...${GROQ_API_KEY: -5}${NC}"
    else
        echo -e "${YELLOW}GROQ_API_KEY is not set${NC}"
    fi

    # Verify GROQ_API_KEY is set
    if [[ -z "$GROQ_API_KEY" ]]; then
        echo -e "${RED}Error: GROQ_API_KEY is not set. Please set it in your .env file or export it.${NC}"
        exit 1
    fi

    # Stop any running containers at the start
    echo "Stopping any running containers..."
    docker-compose down -v 2>/dev/null || true
    # Kill any existing containers that might be using port 8080
    docker ps | grep ":8080" | awk '{print $1}' | xargs -r docker kill
    sleep 5

    # Run test sections
    echo -e "\n${BOLD}STARTING REGRESSION TESTS${NC}"
    
    # BASIC TESTS
    echo -e "\n${BOLD}SECTION 1: BASIC SERVICE TESTS${NC}"
    run_section run_basic_tests
    
    # FILE OPERATIONS TESTS 
    echo -e "\n${BOLD}SECTION 2: FILE OPERATIONS TESTS${NC}"
    run_section run_file_operations_tests
    
    # MONGODB TESTS
    echo -e "\n${BOLD}SECTION 3: MONGODB TESTS${NC}"
    run_section run_mongodb_tests
    
    echo -e "\n${BOLD}ALL TESTS COMPLETED${NC}"
}

# Call the main function
main 