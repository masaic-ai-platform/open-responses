# ClassificationAgent Workflow Diagrams

This document provides visual representations of the ClassificationAgent's workflow, state transitions, API interactions, and checkpoint persistence mechanisms.

## 1. Overall System Architecture

```mermaid
graph TB
    subgraph "Frontend/Client"
        FE[Web Application]
        CLI[CLI Tool]
        API_CLIENT[API Client]
    end

    subgraph "HTTP Layer"
        CONTROLLER[ClassificationAgentController]
        SSE[Server-Sent Events]
    end

    subgraph "Core Agent"
        AGENT[ClassificationAgent]
        STATE_MACHINE[State Machine Engine]
        COMMANDS[Command Handlers]
    end

    subgraph "Persistence Layer"
        CHECKPOINT_REPO[CheckpointRepository]
        CONVERSATION_REPO[ConversationRepository]
    end

    subgraph "External Services"
        LLM[OpenAI/LLM Service]
        MONGO[(MongoDB)]
    end

    FE --> CONTROLLER
    CLI --> CONTROLLER
    API_CLIENT --> CONTROLLER
    
    CONTROLLER --> AGENT
    CONTROLLER --> CHECKPOINT_REPO
    
    AGENT --> STATE_MACHINE
    AGENT --> COMMANDS
    AGENT --> LLM
    AGENT --> CONVERSATION_REPO
    
    CHECKPOINT_REPO --> MONGO
    CONVERSATION_REPO --> MONGO
    
    CONTROLLER --> SSE
    SSE --> FE
    SSE --> CLI
    SSE --> API_CLIENT
```

## 2. State Machine Flow

```mermaid
stateDiagram-v2
    [*] --> Planning
    
    Planning --> AwaitingPlanApproval : Plan Created
    AwaitingPlanApproval --> Planning : Plan Rejected
    AwaitingPlanApproval --> Fetching : Plan Approved
    AwaitingPlanApproval --> Stopped : Stop Command
    
    Fetching --> Planning : No Data Found / Fetch Failed + Can Replan
    Fetching --> Classifying : Data Retrieved
    Fetching --> Summarizing : Target Reached
    Fetching --> Error : Fetch Failed + Max Plans Reached
    
    Classifying --> AwaitingBatchApproval : Classifications Ready
    Classifying --> Stopped : Max Model Calls Reached
    
    AwaitingBatchApproval --> Planning : Batch Rejected
    AwaitingBatchApproval --> Fetching : Batch Approved + More Needed
    AwaitingBatchApproval --> Summarizing : Batch Approved + Target Reached
    AwaitingBatchApproval --> Planning : Batch Approved + Should Replan
    AwaitingBatchApproval --> Stopped : Stop Command
    
    Summarizing --> Completed : Summary Generated
    
    Planning --> Error : Max Plans Reached
    Classifying --> Error : Classification Failed
    Summarizing --> Error : Summary Failed
    
    Error --> Stopped
    Completed --> [*]
    Stopped --> [*]
    
    note right of AwaitingPlanApproval : Human-in-the-Loop\nAgent exits flow\nWaits for command
    
    note right of AwaitingBatchApproval : Human-in-the-Loop\nAgent exits flow\nWaits for command
```

## 3. API Interaction Flow

### 3.1 Start New Run

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant Agent
    participant CheckpointRepo
    participant MongoDB
    participant LLM

    Client->>Controller: POST /agents/classification/runs
    Note over Client,Controller: {instruction: "...", runId: "..."}
    
    Controller->>Controller: Extract API Key
    Controller->>Controller: Generate RunId (if needed)
    Controller->>Agent: run(runId, apiKey, instruction)
    
    Agent->>Agent: Create AgentContext
    Agent->>CheckpointRepo: saveCheckpoint(context)
    CheckpointRepo->>MongoDB: Save initial state
    
    Agent->>Controller: SSE: agent.run.started
    Controller->>Client: SSE Stream Starts
    
    loop State Machine Execution
        Agent->>Agent: Execute Current State
        Agent->>CheckpointRepo: saveCheckpoint(context)
        CheckpointRepo->>MongoDB: Update checkpoint
        Agent->>Controller: SSE: Progress Events
        Controller->>Client: Stream Events
        
        alt Planning State
            Agent->>LLM: Create Classification Plan
            LLM->>Agent: Plan Response
            Agent->>Agent: State = AwaitingPlanApproval
            Agent->>Controller: SSE: awaiting_plan_approval
            Controller->>Client: Plan for Review
            Note over Agent: Agent exits flow, waits for command
        end
    end
```

### 3.2 Human-in-the-Loop Command Flow

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant Agent
    participant CheckpointRepo
    participant MongoDB

    Client->>Controller: POST /agents/classification/runs/{runId}/command
    Note over Client,Controller: {type: "ApprovePlan", feedback: null}
    
    Controller->>Controller: Validate Command
    Controller->>CheckpointRepo: loadCheckpoint(runId)
    CheckpointRepo->>MongoDB: Fetch Context
    MongoDB->>CheckpointRepo: AgentContext
    CheckpointRepo->>Controller: context
    
    Controller->>Agent: handleCommand(runId, command)
    
    alt Plan Approval
        Agent->>Agent: handlePlanApproval()
        Agent->>Agent: State = Fetching
        Agent->>Controller: SSE: plan_approved
    else Plan Rejection
        Agent->>Agent: handlePlanApproval()
        Agent->>Agent: State = Planning
        Agent->>Agent: Add failure logs
        Agent->>Controller: SSE: plan_rejected
    end
    
    Agent->>CheckpointRepo: saveCheckpoint(context)
    CheckpointRepo->>MongoDB: Update checkpoint
    
    Agent->>Agent: executeAgent(context, isResume=true)
    Note over Agent: Continue execution from new state
    
    Agent->>Controller: SSE: Continued execution events
    Controller->>Client: Stream continues
```

### 3.3 Resume from Checkpoint

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant Agent
    participant CheckpointRepo
    participant MongoDB

    Client->>Controller: POST /agents/classification/runs/{runId}/resume
    
    Controller->>Agent: resumeFromCheckpoint(runId)
    Agent->>CheckpointRepo: loadCheckpoint(runId)
    CheckpointRepo->>MongoDB: Query by runId
    
    alt Checkpoint Found
        MongoDB->>CheckpointRepo: AgentContext
        CheckpointRepo->>Agent: context
        Agent->>Agent: executeAgent(context, isResume=true)
        Agent->>Controller: SSE: agent.run.resumed
        Controller->>Client: Resume execution stream
    else No Checkpoint
        CheckpointRepo->>Agent: null
        Agent->>Controller: SSE: agent.run.error
        Controller->>Client: Error event
    end
```

## 4. State Transition Details

### 4.1 Planning State Workflow

```mermaid
flowchart TD
    START_PLANNING[Planning State Entry]
    CHECK_PLANS{Plans < MaxPlans?}
    CREATE_PLAN[Create LLM Plan]
    UPDATE_CONTEXT[Update Context with Plan]
    STREAM_PLAN[Stream Plan to User]
    AWAIT_APPROVAL[AwaitingPlanApproval State]
    MAX_PLANS_ERROR[Error: Max Plans Reached]
    
    START_PLANNING --> CHECK_PLANS
    CHECK_PLANS -->|Yes| CREATE_PLAN
    CHECK_PLANS -->|No| MAX_PLANS_ERROR
    CREATE_PLAN --> UPDATE_CONTEXT
    UPDATE_CONTEXT --> STREAM_PLAN
    STREAM_PLAN --> AWAIT_APPROVAL
    
    style AWAIT_APPROVAL fill:#ffeb3b
    style MAX_PLANS_ERROR fill:#f44336
```

### 4.2 Classification State Workflow

```mermaid
flowchart TD
    START_CLASSIFY[Classifying State Entry]
    CHECK_CALLS{Model Calls < Max?}
    FETCH_CONVS[Fetch Conversations]
    CHECK_DATA{Data Available?}
    CALL_LLM[Call LLM for Classification]
    STORE_PENDING[Store in pendingClassifications]
    AWAIT_BATCH[AwaitingBatchApproval State]
    SUMMARIZE[Summarizing State]
    STOPPED[Stopped State]
    
    START_CLASSIFY --> CHECK_CALLS
    CHECK_CALLS -->|No| STOPPED
    CHECK_CALLS -->|Yes| FETCH_CONVS
    FETCH_CONVS --> CHECK_DATA
    CHECK_DATA -->|No Data| SUMMARIZE
    CHECK_DATA -->|Has Data| CALL_LLM
    CALL_LLM --> STORE_PENDING
    STORE_PENDING --> AWAIT_BATCH
    
    style AWAIT_BATCH fill:#ffeb3b
    style STOPPED fill:#f44336
    style SUMMARIZE fill:#4caf50
```

## 5. Checkpoint Persistence Flow

```mermaid
flowchart LR
    subgraph "Agent Execution"
        STATE_CHANGE[State Transition]
        CONTEXT_UPDATE[Update AgentContext]
    end
    
    subgraph "Persistence Layer"
        SAVE_CHECKPOINT[CheckpointRepository.saveCheckpoint()]
        SERIALIZE[Jackson Serialization]
        MONGO_SAVE[MongoDB Collection: agent_runs]
    end
    
    subgraph "Resume Process"
        LOAD_CHECKPOINT[CheckpointRepository.loadCheckpoint()]
        DESERIALIZE[Jackson Deserialization]
        RESTORE_CONTEXT[Restore AgentContext]
    end
    
    STATE_CHANGE --> CONTEXT_UPDATE
    CONTEXT_UPDATE --> SAVE_CHECKPOINT
    SAVE_CHECKPOINT --> SERIALIZE
    SERIALIZE --> MONGO_SAVE
    
    MONGO_SAVE -.->|Later| LOAD_CHECKPOINT
    LOAD_CHECKPOINT --> DESERIALIZE
    DESERIALIZE --> RESTORE_CONTEXT
    
    style MONGO_SAVE fill:#4caf50
    style RESTORE_CONTEXT fill:#2196f3
```

## 6. Error Handling and Recovery

```mermaid
flowchart TD
    EXECUTION[Agent Execution]
    ERROR_OCCURS{Error Occurs?}
    ERROR_TYPE{Error Type}
    
    FETCH_ERROR[Fetch Error]
    CAN_REPLAN{Can Replan?}
    REPLAN[Trigger Replanning]
    ERROR_STATE[Error State]
    
    LLM_ERROR[LLM Error]
    RETRY_LOGIC[Retry Logic]
    FAIL_STATE[Fail to Error State]
    
    VALIDATION_ERROR[Validation Error]
    COMMAND_ERROR[Invalid Command]
    SSE_ERROR[Send Error Event]
    
    EXECUTION --> ERROR_OCCURS
    ERROR_OCCURS -->|Yes| ERROR_TYPE
    ERROR_OCCURS -->|No| EXECUTION
    
    ERROR_TYPE --> FETCH_ERROR
    ERROR_TYPE --> LLM_ERROR
    ERROR_TYPE --> VALIDATION_ERROR
    
    FETCH_ERROR --> CAN_REPLAN
    CAN_REPLAN -->|Yes| REPLAN
    CAN_REPLAN -->|No| ERROR_STATE
    REPLAN --> EXECUTION
    
    LLM_ERROR --> RETRY_LOGIC
    RETRY_LOGIC --> FAIL_STATE
    
    VALIDATION_ERROR --> COMMAND_ERROR
    COMMAND_ERROR --> SSE_ERROR
    
    style ERROR_STATE fill:#f44336
    style FAIL_STATE fill:#f44336
    style SSE_ERROR fill:#ff9800
    style REPLAN fill:#4caf50
```

## 7. Human-in-the-Loop Decision Points

```mermaid
flowchart TD
    subgraph "Plan Approval Flow"
        PLAN_CREATED[Plan Created by LLM]
        STREAM_PLAN[Stream Plan to User]
        WAIT_PLAN[AwaitingPlanApproval]
        
        PLAN_DECISION{User Decision}
        APPROVE_PLAN[ApprovePlan Command]
        REJECT_PLAN[RejectPlan + Feedback]
        STOP_PLAN[Stop Command]
        
        PLAN_CREATED --> STREAM_PLAN
        STREAM_PLAN --> WAIT_PLAN
        WAIT_PLAN --> PLAN_DECISION
        PLAN_DECISION --> APPROVE_PLAN
        PLAN_DECISION --> REJECT_PLAN
        PLAN_DECISION --> STOP_PLAN
    end
    
    subgraph "Batch Approval Flow"
        BATCH_READY[Classifications Ready]
        SHOW_PREVIEW[Show First 5 for Review]
        WAIT_BATCH[AwaitingBatchApproval]
        
        BATCH_DECISION{User Decision}
        APPROVE_BATCH[ApproveBatch Command]
        REJECT_BATCH[RejectBatch + Feedback]
        STOP_BATCH[Stop Command]
        
        BATCH_READY --> SHOW_PREVIEW
        SHOW_PREVIEW --> WAIT_BATCH
        WAIT_BATCH --> BATCH_DECISION
        BATCH_DECISION --> APPROVE_BATCH
        BATCH_DECISION --> REJECT_BATCH
        BATCH_DECISION --> STOP_BATCH
    end
    
    APPROVE_PLAN --> BATCH_READY
    REJECT_PLAN --> PLAN_CREATED
    APPROVE_BATCH --> PLAN_CREATED
    REJECT_BATCH --> PLAN_CREATED
    
    style WAIT_PLAN fill:#ffeb3b
    style WAIT_BATCH fill:#ffeb3b
    style STOP_PLAN fill:#f44336
    style STOP_BATCH fill:#f44336
```

## 8. Data Flow Architecture

```mermaid
flowchart LR
    subgraph "Input Sources"
        USER_INSTR[User Instructions]
        API_KEY[API Key]
        RUN_ID[Run ID]
    end
    
    subgraph "Agent Context"
        CONTEXT[AgentContext]
        STATE[Current State]
        PROGRESS[Progress Data]
        METADATA[Metadata & Logs]
    end
    
    subgraph "External Data"
        CONVERSATIONS[(Conversations Collection)]
        CHECKPOINTS[(Agent Runs Collection)]
        LLM_RESPONSES[LLM API Responses]
    end
    
    subgraph "Output Streams"
        SSE_EVENTS[SSE Event Stream]
        SAVED_CLASSIFICATIONS[Updated Classifications]
        SUMMARIES[Generated Summaries]
    end
    
    USER_INSTR --> CONTEXT
    API_KEY --> CONTEXT
    RUN_ID --> CONTEXT
    
    CONTEXT --> STATE
    CONTEXT --> PROGRESS
    CONTEXT --> METADATA
    
    CONVERSATIONS --> CONTEXT
    CHECKPOINTS --> CONTEXT
    LLM_RESPONSES --> CONTEXT
    
    CONTEXT --> SSE_EVENTS
    CONTEXT --> SAVED_CLASSIFICATIONS
    CONTEXT --> SUMMARIES
    
    style CONTEXT fill:#2196f3
    style SSE_EVENTS fill:#4caf50
```

This comprehensive workflow documentation provides readers with a clear understanding of:

1. **System Architecture**: How components interact
2. **State Transitions**: The agent's decision-making process  
3. **API Flows**: Request/response patterns for each endpoint
4. **Error Handling**: Recovery mechanisms and failure paths
5. **HITL Integration**: Human decision points and command flows
6. **Data Persistence**: Checkpoint and resume mechanisms
7. **Real-time Communication**: SSE streaming patterns

The diagrams use standard Mermaid syntax for easy integration into documentation systems and provide both high-level overviews and detailed technical flows. 