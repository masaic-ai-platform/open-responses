# Classification Agent – Front‑End Specification (Dark/Gold Agentic Theme)

## 0 Design Language

| Theme Layer | Colour / Gradient | Token | Usage |
|-------------|------------------|-------|-------|
| **Primary** | `#F6C140 → #FFB300` | `--cl-primary-gold` | Active progress segment, primary CTAs, bubble outlines |
| **Accent**  | `#7DD3FC → #2563EB` | `--cl-accent-agent` | Links & hover effects |
| **Surface** | Dark `#161618` / Light `#FFFFFF` | `--bg-surface` | Column backgrounds |
| **Error**   | `#EF4444` | `--cl-error` | Error badges, progress segment |
| **Success** | `#22C55E` | `--cl-success` | Completed badge |

*Gradients*: `background-image: linear-gradient(45deg, var(--cl-primary-gold-start), var(--cl-primary-gold-end));`

Font stack: **Inter** (UI) and *JetBrains Mono* (code).

---

## 1 Layout (desktop)

Columns **30 / 40 / 30 %** – each scrolls independently.

```
┌ Header ───────────────────────────────────────────────────────────────────┐
│  logo · “Conversation Classifier” · dark‑mode toggle                     │
├─────────────┬───────────────────────────────┬────────────────────────────┤
│ ① Chat      │ ② Agent Progress              │ ③ Live Event Log           │
│  & CTA      │  – vertical progress bar      │  – streaming console       │
│             │  – stage‑specific card        │                            │
└─────────────┴───────────────────────────────┴────────────────────────────┘
```

---

## 2 API Pattern

* Every endpoint **returns `text/event-stream`** on the same HTTP request.
* First event always contains the **`runId`** – cache it for future `/command` calls.

### 2.1 Start

```bash
POST /agents/classificationAgent/runs
Authorization: Bearer <key>
Content-Type: application/json

{ "instruction": "Use conversations from 5 to 10 May that belong to order" }
```

### 2.2 Command

```bash
POST /agents/classificationAgent/runs/{runId}/command
Content-Type: application/json

{ "type": "ApproveBatch" | "RejectBatch" | "ApproveFetch" | "RejectFetch" | "Stop",
  "feedback": "<text when reject>" }
```

#### Event Envelope

```json
{
  "type": "agent.run.*",
  "logMessage": "string",
  "data": <json|null>,
  "runId": "uuid|null"
}
```

---

## 3 Column Behaviour

### 3.1 Chat / Commands

* Streams any `*.delta` text (`plan_summary`, `batch_summary`, `summary`, `output_text`).
* CTA chips appear on
  * `agent.run.awaiting_fetch_approval`
  * `agent.run.awaiting_batch_approval`
* Chips → POST `/command` and swap EventSource to new response.

### 3.2 Agent Progress (middle)

Vertical bar segments:

| Segment              |
|----------------------|
| Planning             |
| Fetch Approval       |
| Fetching             |
| Classifying          |
| Batch Approval       |
| Summarizing          |
| Completed            |

`StageCard` below shows markdown, tables or summary depending on segment.

### 3.3 Live Event Log (right)

Monospace list `ts · type · logMessage`; colour stripe by family.

---

## 4 Event → UI Mapping

| Event Type | Progress | Chat Bubble | CTA | Notes |
|------------|----------|-------------|-----|-------|
| `agent.run.started` | Planning active | “Run started” | – | capture `runId` |
| `plan_summary.delta` | – | append streamed md | – | |
| `planning.completed` | Planning done | “Plan ready” | Approve/Reject/Stop | |
| `awaiting_fetch_approval` | Fetch Approval amber | system bubble + convIds link | Approve/Reject/Stop | |
| `fetching.started/completed` | Fetching active/done | – | – | |
| `classifying.started/completed` | Classifying active/done | – | – | |
| `awaiting_batch_approval` | Batch Approval amber | list first 5 classifications | Approve/Reject/Stop | |
| `batch_summary.delta` | – | stream md | – | |
| `batch_data` | update % bar | – | – | |
| `summary.delta` | Summarizing active | stream | – | |
| `completed` | bar green | “Completed” + CSV | – | |
| `stopped`/`error` | bar red | red bubble | – | |

---

## 5 Vertical Progress Component

```ts
type Stage =
  | 'Planning'|'Fetch Approval'|'Fetching'
  | 'Classifying'|'Batch Approval'
  | 'Summarizing'|'Completed';

interface Status { state:'waiting'|'active'|'done'|'error' }
<VerticalProgress stages:Record<Stage,Status> percentOverall:number />
```

---

## 6 Colour Tokens (Tailwind extract)

```js
extend: {
  colors:{
    primary:{ DEFAULT:'#F6C140' },
    error:'#EF4444',
    success:'#22C55E'
  },
  backgroundImage:{
    'primary-grad':'linear-gradient(45deg,#F6C140,#FFB300)',
    'accent-grad':'linear-gradient(45deg,#7DD3FC,#2563EB)'
  }
}
```

---

## 7 Developer Checklist

1. `useAgentSSE()` – open EventSource from **fetch response body**, parse per‑line JSON.
2. Reconnect on each `/command` response.
3. Redux/Zustand slice keyed by `runId`.
4. Show CTA chips only when `awaiting_*` events received.
5. Dark + light modes via `prefers-color-scheme`.

---

## 8 Acceptance

* Golden path (approve plan → approve batch) renders all states, CSV downloads IDs.
* Rejecting plan triggers replanning loop; UI shows new plan.
* WCAG AA contrast for gold on dark.
