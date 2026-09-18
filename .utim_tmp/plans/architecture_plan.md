## Strategic Overview
The architecture transforms Intercept AI’s backend from a **stateless state machine** into a **stateful, evolving risk engine** by leveraging a Redis-based context window and a Postgres-based per-number risk profile. The system treats an incoming number as a "persona" whose risk profile is a function of its history. The core principle is **backward-compatible extension**: the existing `/analyze/text` endpoint continues to work for anonymous/single-shot requests, but when session metadata (e.g., a hashed caller ID) is provided, the system enriches the analysis with context. This shifts the intelligence from "this text looks like a scam" to "this text is a scam, given this number’s evolving history."

---

## A) Data Model & Schema
We separate ephemeral state (conversation context) from persistent state (risk profiles).

### 1. Ephemeral State: Redis (Context Window)
Stores the rolling conversation history for a number. This is hot data, accessed during live call screening.

| Key Pattern | Data Type | Structure | TTL |
| :--- | :--- | :--- | :--- |
| `ctx:{hash}` | List (JSON) | `[{ts: int, dir: "in"/"out", text: str, tokens: int, intent: str}]` | 5 mins (idle) |
| `lock:{hash}` | String | `lock_value` | 10 sec (distributed lock) |

> **Design Note:** We store a *summarized* context (intent, sentiment, not full PII text) to keep memory footprint low. The text is scrubbed of PII before storage.

### 2. Persistent State: Postgres (Risk Profiles)
Stores the evolving risk profile per number. This is cold/warm data, accessed during analysis to adjust weights.

**Table: `number_risk_profiles`**
| Column | Type | Constraint | Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PK | Internal ID |
| `number_hash` | VARCHAR(64) | UNIQUE, INDEXED | SHA-256 of E.164 number |
| `risk_score` | FLOAT | DEFAULT 0.0 | Current aggregated risk (0-1) |
| `confidence` | FLOAT | DEFAULT 0.0 | Model confidence in the score |
| `last_interaction` | TIMESTAMP | | Last time this number was seen |
| `traits` | JSONB | | `{urgency: 0.8, deception: 0.2, ...}` |
| `created_at` | TIMESTAMP | | |
| `updated_at` | TIMESTAMP | | |

**Table: `interaction_events`**
| Column | Type | Constraint | Description |
| :--- | :--- | :--- | :--- |
| `id` | BIGSERIAL | PK | |
| `profile_id` | UUID | FK -> profiles | |
| `session_id` | UUID | | Correlates a call/session |
| `event_type` | ENUM | `call_in`, `msg_in`, `analysis_run` | |
| `payload` | JSONB | `{text_hash, intent, result_level}` | No PII |
| `created_at` | TIMESTAMP | | |

---

## B) API Contract Additions/Modifications

### 1. Modified `POST /analyze/text`
We extend the payload to accept optional context metadata. This maintains backward compatibility (fields are optional).

**Request:**
```json
{
  "text": "Urgent: your account is locked. Call 555-1234 now.",
  "channel": "sms",
  "language": "en",
  "context": {
    "number_hash": "a1b2c3...",  // SHA-256 of E.164, optional
    "session_id": "sess-123",     // Correlates consecutive messages
    "direction": "in"             // "in" (scammer) or "out" (user)
  }
}
```

**Response (No Change to Core, Extended Debug):**
```json
{
  "level": "high",
  "risk": 0.92,
  "why": ["Urgency tactic", "Account lock threat", "Known scam pattern"],
  "simple_mode": true,
  "user_message": "High risk call detected.",
  "metadata": {
    "context_used": true,         // New: did we use session history?
    "profile_id": "uuid-456",    // New: which risk profile was referenced
    "context_depth": 3           // New: how many previous messages were considered
  }
}
```

### 2. New `GET /risk/{number_hash}`
Allows the client (e.g., Android app) to fetch a quick risk score for a number without analyzing text. Useful for pre-screening.

**Response:**
```json
{
  "number_hash": "a1b2c3...",
  "risk_score": 0.85,
  "confidence": 0.7,
  "last_seen": "2023-10-27T10:00:00Z",
  "status": "active" // or "archived"
}
```

### 3. New `POST /risk/{number_hash}/reset`
Administrative or user-initiated action to clear a number's profile (e.g., if a number is a friend who made a weird call).

**Auth:** Requires admin token or user-specific key.

---

## C) Caching Strategy for Low-Latency Live Screening

The requirement is **low-latency lookups during live call screening**. We use a **two-tier cache** to avoid blocking the main thread.

### Architecture:
1.  **L1 Cache (In-Memory, Process-Local):**
    *   **Store:** Python `dict` or `lru_cache` inside the FastAPI worker.
    *   **Key:** `number_hash`.
    *   **Data:** `NumberRiskProfile` object (score, traits, last_update).
    *   **TTL:** 30 seconds.
    *   **Purpose:** Eliminates Redis/DB latency for repeated lookups within the same worker.

2.  **L2 Cache (Redis, Cluster-Wide):**
    *   **Store:** Redis Hash `risk:{hash}`.
    *   **Data:** Serialized `NumberRiskProfile`.
    *   **TTL:** 5 minutes.
    *   **Purpose:** Shared state across multiple FastAPI workers/instances.

3.  **Context Cache (Redis List):**
    *   **Store:** Redis List `ctx:{session_id}` (or `ctx:{hash}` if no session ID).
    *   **Data:** Last 5-10 message summaries (JSON).
    *   **TTL:** 10 minutes (sliding window).

### Data Flow during `/analyze/text`:
1.  Check L1 Cache for `number_hash` -> Risk Profile.
2.  If Miss, check L2 Redis -> Risk Profile.
3.  If Miss, query Postgres -> Update L1/L2 -> Return Profile.
4.  Fetch Context from Redis `ctx:{hash}` (Last N items).
5.  Pass `[Context History] + [Current Text] + [Risk Profile]` to the LLM/Model.
6.  **Concurrency:** Use a Redis Distributed Lock (`lock:{hash}`) with a 10s timeout to serialize *writes* to the context list. Reads are non-blocking.

---

## D) Cleanup & Archival Policy (TTL-based)

We enforce a **30-day rolling window** for active profiles.

### 1. Postgres Archival Job (Cron: Daily)
*   **Trigger:** `last_interaction < NOW() - INTERVAL '30 days'`.
*   **Action:**
    1.  Move row from `number_risk_profiles` to `number_risk_profiles_archive`.
    2.  Delete associated `interaction_events` older than 90 days (longer retention for audit, but lower priority).
    3.  **Key:** Do NOT delete the profile immediately. Mark it as `status: 'archived'`.
    4.  If a new call comes in for an archived number, "re-activate" it by copying the archive row back to main table and resetting confidence to 0.5.

### 2. Redis Cleanup Job (Cron: Hourly)
*   **Trigger:** Redis `EXPIRE` keys are set automatically (TTL).
*   **Action:** No explicit code needed for keys. However, run a `MEMORY USAGE` check weekly to catch any "stuck" locks.

### 3. PII Purge
*   **Strict Rule:** No PII (phone numbers, names) is ever stored in Postgres or Redis.
*   **Hashing:** All numbers are hashed with SHA-256 + Salt (Salt rotated yearly).
*   **Context Text:** Before storing in Redis, text is stripped of PII (regex for emails, addresses, names) and truncated to 500 chars.

---

## E) Integration Points with Existing Text Analysis Flow

We modify the existing `AnalyzeService` class to inject context without breaking the stateless path.

### 1. Service Layer Changes
**File:** `intercept-backend/services/analysis_service.py`

*   **Before:**
    ```python
    def analyze(self, text, channel, language):
        result = llm_client.predict(text)
        return result
    ```
*   **After:**
    ```python
    def analyze(self, text, channel, language, context=None):
        # 1. If context provided, fetch Risk Profile & History
        if context and context.get("number_hash"):
            profile = self.cache.get_risk_profile(context["number_hash"])
            history = self.cache.get_conversation_context(context["number_hash"])
            
            # 2. Build Enhanced Prompt
            # "Given this history: {history} and this profile: {profile}, 
            #  analyze the new message: {text}"
            enhanced_prompt = self.build_enhanced_prompt(profile, history, text)
            result = llm_client.predict(enhanced_prompt)
            
            # 3. Update Risk Profile (Async)
            asyncio.create_task(self.update_risk_profile(
                context["number_hash"], 
                result.risk_score, 
                result.traits
            ))
            return result
        else:
            # Stateless Path (Backward Compatible)
            result = llm_client.predict(text)
            return result
    ```

### 2. Prompt Engineering for Context
The LLM prompt must be structured to handle context gracefully.

**Template:**
```text
You are a scam detection expert.

## Context
- Caller Number Hash: {number_hash}
- Historical Risk Score: {profile.risk_score} (0-1)
- Detected Traits: {profile.traits}
- Recent Conversation Summary:
  {history_snippets}

## Current Message
Text: "{text}"
Channel: "{channel}"

## Task
1. Determine if this message is a scam, given the context.
2. Output JSON: {level, risk, why, simple_mode, user_message}
3. Update the 'traits' based on new evidence.
```

### 3. Error Handling & Graceful Degradation
*   **Redis Down:** If context fetch fails, fall back to stateless analysis. Log a warning. Do NOT fail the request.
*   **Postgres Down:** Use L1/L2 cache for reads. Queue writes to a local disk buffer (SQLite) for offline write resilience.
*   **LLM Timeout:** Return a default "Medium Risk" response with a generic `why` if the LLM times out, rather than blocking the user.

---

## Key Trade-offs & Decisions

| Decision | Rationale | Trade-off |
| :--- | :--- | :--- |
| **Redis for Context** | Fast, atomic list operations, TTL built-in. | Data is ephemeral. If Redis flushes, context is lost. Acceptable for "short-term memory." |
| **Postgres for Profiles** | Durable, relational, supports complex queries. | Higher latency. Mitigated by caching layer. |
| **Hashing Phone Numbers** | Privacy-preserving, GDPR/CCPA compliant. | Cannot re-identify users for support/debugging. Acceptable risk for security product. |
| **Async Profile Update** | Keeps API response time low (<100ms). | Race conditions if two calls finish simultaneously. Mitigated by optimistic locking on `updated_at` in Postgres. |
| **No Webhooks** | Simpler, client pulls `/risk/{hash}`. | Less real-time. Clients must poll or fetch before screen call. |

## Implementation Steps (Dev Team Action Items)

1.  **DB Migration:** Create `number_risk_profiles` and `interaction_events` tables in Postgres.
2.  **Cache Layer:** Implement `RiskCacheService` (L1 LRU + L2 Redis).
3.  **Context Engine:** Implement `ContextManager` to handle Redis list read/write with PII scrubbing.
4.  **API Extension:** Update `POST /analyze/text` schema to accept `context` object.
5.  **LLM Prompt:** Update prompt template to include context placeholders.
6.  **Async Worker:** Create a background task queue (Celery/Arq) to persist profile updates to Postgres.
7.  **Cron Job:** Implement daily archival script for stale profiles.
8.  **Testing:**
    *   Unit: Verify PII scrubbing in context.
    *   Integration: Test concurrent calls to same number (lock behavior).
    *   Backward Compat: Test `/analyze/text` without `context` field.