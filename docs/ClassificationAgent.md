# Classification Agent Documentation

> 📊 **Visual Workflows**: For comprehensive workflow diagrams and visual representations of the agent's architecture, see [ClassificationAgent-Workflow.md](./ClassificationAgent-Workflow.md)

## Overview

The `ClassificationAgent` is an advanced AI-powered system for classifying conversations as "RESOLVED" or "UNRESOLVED" using a state machine pattern with human-in-the-loop capabilities and checkpoint persistence.

## Current Architecture (Updated Implementation)

### Key Features

1. **Optimized Human-in-the-Loop (HITL) Control**
   - **Fetch Approval**: Users approve actual fetched data with plan context (no blind plan approval)
   - **Batch Approval**: Users approve classification results before saving
   - **Autonomous Recovery**: Agent handles failures automatically without user interruption
   - **Exit-and-resume pattern** for better UX

2. **Checkpoint Persistence**
   - Automatic state saving to MongoDB after each transition
   - Resume capability from any state
   - Direct `AgentContext` serialization for type safety
   - Clean separation of concerns with `CheckpointRepository`

3. **Streaming Protocol**
   - Text streaming with started/delta/done events
   - Structured data events for batch and final results
   - Real-time progress updates

4. **Enhanced Architecture**
   - Unified execution engine for run/resume operations
   - Eliminated code duplication between run and resume flows
   - Dedicated DB operations layer with `CheckpointRepository`
   - Proper separation of HITL command handling

### Architecture Components

#### Core Classes

1. **`ClassificationAgent`** - Main orchestrator
   - `run()` - Start new classification run
   - `resumeFromCheckpoint()` - Resume from saved state
   - `handleCommand()` - Process all HITL commands (fetch/batch approval, stop)

2. **`CheckpointRepository`** - Database operations
   - `saveCheckpoint()` - Persist agent context
   - `loadCheckpoint()` - Restore agent context

3. **`ClassificationAgentController`** - REST API layer
   - `POST /agents/classificationAgent/runs` - Start new run
   - `POST /agents/classificationAgent/runs/{runId}/resume` - Resume run
   - `POST /agents/classificationAgent/runs/{runId}/command` - Send commands
   - `GET /agents/classificationAgent/runs/{runId}` - Get status

### State Machine States

```kotlin
sealed interface AgentState {
    data object Planning                    // LLM creates classification plan
    data object Fetching                    // Retrieve conversations from database  
    data class AwaitingFetchApproval        // Wait for human approval of fetched data + plan
    data object Classifying                 // AI classification of conversations
    data class AwaitingBatchApproval        // Wait for batch approval
    data object Saving                      // Persist approved classifications
    data object Summarizing                 // Generate run summary
    data object Completed                   // Successfully finished
    data object Stopped                     // Stopped (by user or limits)
    data class Error(message)               // Error state with details
}
```

### Updated HITL Workflow

#### **1. Fetch Approval Flow (NEW):**
```
Planning → Fetching (auto-retry on failures) → AwaitingFetchApproval → [User Decision]

User sees:
- Plan details (strategy, target size, MongoDB query)  
- Fetched data (conversation count, IDs, sample data)
- Options: ApproveFetch / RejectFetch(feedback) / Stop

Actions:
- ApproveFetch → Continue to Classifying
- RejectFetch → Return to Planning with feedback
- Stop → End execution
```

#### **2. Batch Approval Flow (EXISTING):**
```
Classifying → AwaitingBatchApproval → [User Decision]

User sees:
- Classification results preview (first 5 classifications)
- Progress percentage
- Options: ApproveBatch / RejectBatch(feedback) / Stop

Actions:
- ApproveBatch → Save and continue/complete
- RejectBatch → Return to Planning with feedback  
- Stop → End execution
```

### State Transitions

```
Planning → Fetching → AwaitingFetchApproval → Classifying → AwaitingBatchApproval → Saving
    ↑         ↑                ↓                                        ↓
    ←---------←--------[RejectFetch]                              [RejectBatch]
    ↑                                                                   ↓
    ←-------------------------------------------------------------------←

Failure Recovery (Autonomous):
Planning → Fetching (fails) → Planning (auto-retry)
Planning → Fetching (empty) → Planning (auto-retry)

Terminal States:
Any State → Stopped (Stop command)
Any State → Error → Stopped
Saving → Summarizing → Completed
```

## API Reference

### Base URL
```
POST /agents/classificationAgent
```

### Endpoints

#### 1. Start New Run
```http
POST /agents/classificationAgent/runs
Content-Type: application/json
Authorization: Bearer <openai-api-key>

{
  "instruction": "Classify customer service conversations from the last week"
}

Response: 200 OK (Server-Sent Events stream)
```

#### 2. Resume From Checkpoint
```http
POST /agents/classificationAgent/runs/{runId}/resume

Response: 200 OK (Server-Sent Events stream)
```

#### 3. Send Command (HITL)
```http
POST /agents/classificationAgent/runs/{runId}/command
Content-Type: application/json

// Fetch Approval Commands
{ "type": "ApproveFetch" }
{ "type": "RejectFetch", "feedback": "Use broader date range" }

// Batch Approval Commands  
{ "type": "ApproveBatch" }
{ "type": "RejectBatch", "feedback": "Classifications look incorrect" }

// Control Commands
{ "type": "Stop" }

Response: 200 OK (Server-Sent Events stream)
```

#### 4. Get Run Status
```http
GET /agents/classificationAgent/runs/{runId}

Response: 200 OK (AgentContext JSON)
{
  "runId": "abc123",
  "state": "AwaitingFetchApproval", 
  "totalConversationsClassified": 15,
  "targetSampleSize": 50,
  "plansCount": 2,
  // ... other context fields
}
```

## Server-Sent Events (SSE) Protocol

### Event Types for Frontend Integration

#### Lifecycle Events
```javascript
// Run started/resumed
{ type: "agent.run.started", logMessage: "Classification agent started successfully" }
{ type: "agent.run.resumed", logMessage: "Agent resumed from checkpoint" }

// Run completed/stopped
{ type: "agent.run.completed", logMessage: "Classification completed! 25 conversations classified." }
{ type: "agent.run.stopped", logMessage: "Agent execution stopped" }
{ type: "agent.run.error", logMessage: "Error occurred: ..." }
```

#### State Transition Events
```javascript
// Planning
{ type: "agent.run.planning.started", logMessage: "Creating classification plan..." }
{ type: "agent.run.planning.completed", logMessage: "Plan created successfully" }

// Fetching  
{ type: "agent.run.fetching.started", logMessage: "Fetching up to 10 conversations..." }
{ type: "agent.run.fetching.completed", logMessage: "Fetched 8 conversations successfully" }
{ type: "agent.run.replanning", logMessage: "Fetch failed - creating new plan automatically" }

// Classification
{ type: "agent.run.classifying.started", logMessage: "Classifying 8 conversations..." }
{ type: "agent.run.classifying.completed", logMessage: "Successfully classified 8 conversations (65% complete)" }

// Saving
{ type: "agent.run.saving.started", logMessage: "Saving classification results..." }
{ type: "agent.run.saving.completed", logMessage: "Saved 8 classifications. Total progress: 23/35 (66%)" }
```

#### HITL Approval Events  
```javascript
// Fetch Approval Required
{
  type: "agent.run.awaiting_fetch_approval",
  logMessage: "Waiting for approval of fetched data (8 conversations)",
  data: {
    plan: {
      targetSampleSize: 35,
      planDetails: "## Classification Plan\n...",
      additionalInstructions: "Focus on recent tickets"
    },
    fetchedData: {
      conversationCount: 8,
      conversationIds: ["conv_123", "conv_456", ...],
      sampleConversation: {
        id: "conv_123", 
        messageCount: 4,
        summary: "Customer requesting refund"
      }
    }
  }
}

// Batch Approval Required
{
  type: "agent.run.awaiting_batch_approval", 
  logMessage: "Waiting for batch approval",
  data: [
    { id: "conv_123", label: "RESOLVED" },
    { id: "conv_456", label: "UNRESOLVED" },
    // ... up to 5 preview classifications
  ]
}
```

#### Streaming Text Events
```javascript
// Plan details streaming
{ type: "agent.run.plan_summary.started", logMessage: "Generating plan summary..." }
{ type: "agent.run.plan_summary.delta", data: "## Classification Plan\n\nTarget: 50 conversations..." }
{ type: "agent.run.plan_summary.done", logMessage: "Plan summary generation completed" }

// Batch summary streaming  
{ type: "agent.run.batch_summary.started", logMessage: "Generating batch summary..." }
{ type: "agent.run.batch_summary.delta", data: "Successfully processed 8 conversations..." }
{ type: "agent.run.batch_summary.done", logMessage: "Batch summary generation completed" }

// Final summary streaming
{ type: "agent.run.summary.started", logMessage: "Generating run summary..." }
{ type: "agent.run.summary.delta", data: "## Classification Run Summary\n\n• Successfully..." }
{ type: "agent.run.summary.done", logMessage: "Summary generation completed" }
```

#### Structured Data Events
```javascript
// Batch data after approval
{
  type: "agent.run.batch_data",
  data: {
    conversationIds: ["conv_123", "conv_456"],
    classifications: [
      { id: "conv_123", label: "RESOLVED" },
      { id: "conv_456", label: "UNRESOLVED" }
    ],
    progress: 65
  }
}

// Final run data
{
  type: "agent.run.final_data", 
  data: {
    allConversationIds: ["conv_123", "conv_456", ...],
    totalClassified: 50,
    runId: "abc123"
  }
}
```

## Configuration

### AgentConfig Properties

Configure the agent via `application.yml`:

```yaml
classification-agent:
  maxModelCalls: 10      # Maximum LLM calls per run
  maxPlans: 5            # Maximum plans the agent can create  
  maxBatch: 10           # Maximum conversations per batch
  checkIntervalMs: 1000  # State transition check interval
  chunkSize: 500         # Text streaming chunk size
```

## Usage Examples

### JavaScript Frontend Integration

```javascript
// Start new classification run
function startClassification(apiKey, instructions) {
  const eventSource = new EventSource('/agents/classificationAgent/runs', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ instruction: instructions })
  });

  eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    handleAgentEvent(data);
  };
  
  return eventSource;
}

// Send fetch approval command
async function approveFetch(runId) {
  const response = await fetch(`/agents/classificationAgent/runs/${runId}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'ApproveFetch' })
  });
  
  // Handle SSE response for command processing
  return response.body.getReader();
}

// Send fetch rejection with feedback
async function rejectFetch(runId, feedback) {
  return fetch(`/agents/classificationAgent/runs/${runId}/command`, {
    method: 'POST', 
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ 
      type: 'RejectFetch', 
      feedback: feedback 
    })
  });
}

// Get current run status
async function getRunStatus(runId) {
  const response = await fetch(`/agents/classificationAgent/runs/${runId}`);
  return response.json(); // AgentContext object
}
```

### Kotlin Backend Integration

```kotlin
@Service
class MyClassificationService(
    private val classificationAgent: ClassificationAgent
) {
    suspend fun runClassification() {
        val apiKey = "your-openai-api-key"
        val instructions = "Classify customer service conversations from the last week"
        val runId = UUID.randomUUID().toString()
        
        classificationAgent.run(runId, apiKey, instructions)
            .collect { event ->
                // Handle SSE events
                println("Event: ${event.event()}")
                println("Data: ${event.data()}")
            }
    }
}
```

## UI/UX Design Specifications

### Fetch Approval Interface

**When**: `agent.run.awaiting_fetch_approval` event received

**Display**:
1. **Plan Section**
   - Target sample size
   - Plan details (markdown formatted)
   - Additional instructions

2. **Fetched Data Section**  
   - Conversation count: "Found 8 conversations"
   - Sample conversation preview (ID, message count, summary)
   - First 10 conversation IDs list

3. **Action Buttons**
   - ✅ **"Approve & Continue"** → Send `ApproveFetch` command
   - ❌ **"Reject & Revise"** → Show feedback textarea → Send `RejectFetch` 
   - 🛑 **"Stop"** → Send `Stop` command

**UX Notes**:
- Show loading state during fetch attempts
- Display auto-retry messages clearly  
- Highlight what changed if this is a replanning attempt

### Batch Approval Interface  

**When**: `agent.run.awaiting_batch_approval` event received

**Display**:
1. **Classification Results Preview**
   - Table showing first 5 classifications (ID, Label)
   - Total batch size: "8 conversations classified"
   - Progress bar showing overall completion

2. **Action Buttons**
   - ✅ **"Approve Batch"** → Send `ApproveBatch` command
   - ❌ **"Reject Batch"** → Show feedback textarea → Send `RejectBatch`
   - 🛑 **"Stop"** → Send `Stop` command

### Progress Interface

**Real-time Updates**:
- Progress bar with percentage (from structured data events)
- Current state indicator: "Planning → Fetching → Approving → ..."
- Live activity log showing event log messages
- Streaming text display for plans/summaries (typewriter effect)

### Error Handling

**Auto-retry Display**:
- Show "Attempting alternative approach..." during auto-retry
- Display retry attempt count: "Attempt 2 of 5"
- Log failures but don't interrupt user

**Error States**:
- Show clear error messages for terminal failures
- Provide option to restart or resume from checkpoint
- Display troubleshooting suggestions

This architecture provides a robust, user-friendly solution for AI-driven conversation classification with optimal human oversight and control. 